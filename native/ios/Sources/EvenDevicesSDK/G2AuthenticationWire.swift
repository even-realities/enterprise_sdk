import Foundation

/// The first business command after enterprise authorization uses the existing EUS
/// protobuf envelope, but travels exclusively over enterprise RX/TX 8401/8402.
enum G2AuthenticationWire {
    static let deviceSettingsSID: UInt8 = 0x80
    static let authenticationCommand: UInt64 = 4

    static func request(syncID: UInt8, magic: UInt16) -> Data {
        // DevCfgDataPackage { commandId: AUTHENTICATION, magicRandom: magic,
        // authMgr: { secAuth: true, phoneType: PHONE_IOS } }.
        var body: [UInt8] = [0x08, 0x04, 0x10]
        body += varint(UInt64(magic))
        body += [0x1A, 0x04, 0x08, 0x01, 0x10, 0x03]
        let crc = crc16(body)
        return Data([0xAA, 0x21, syncID, UInt8(body.count + 2), 1, 1,
                     deviceSettingsSID, 0] + body + [UInt8(crc & 0xff), UInt8(crc >> 8)])
    }

    enum Signal: Equatable {
        case commandAccepted, authenticated
    }

    /// Firmware ACKs the request using its protobuf magic, then independently
    /// pushes AuthMgr success with device-owned sync and magic values. Validate
    /// both frames strictly; the connection owner must collect both signals.
    static func classify(_ data: Data, requestMagic: UInt16) -> Signal? {
        guard let frame = G2BusinessFrame.parse(data), frame.service == .deviceSettings,
              frame.resultCode == 0 else { return nil }
        var cursor = ProtoCursor(Array(frame.payload))
        var command: UInt64?
        var replyMagic: UInt64?
        var auth: [UInt8]?
        while let field = cursor.next() {
            switch field.number {
            case 1:
                guard command == nil, let value = field.varint else { return nil }
                command = value
            case 2:
                guard replyMagic == nil, let value = field.varint else { return nil }
                replyMagic = value
            case 3:
                guard auth == nil, let value = field.bytes else { return nil }
                auth = value
            default: break
            }
        }
        guard cursor.valid, command == authenticationCommand,
              let auth else { return nil }
        var authCursor = ProtoCursor(auth)
        var secAuth: UInt64?
        var result: UInt64?
        while let field = authCursor.next() {
            if field.number == 1 {
                guard secAuth == nil, let value = field.varint else { return nil }
                secAuth = value
            }
            if field.number == 3 {
                guard result == nil, let value = field.varint else { return nil }
                result = value
            }
        }
        guard authCursor.valid, result ?? 0 == 0 else { return nil }
        if frame.isNotification {
            // Device-owned magic may wrap to proto3's omitted default zero.
            // It never correlates a push to the host's request.
            return secAuth == 1 ? .authenticated : nil
        }
        // A command ACK can contain an empty AuthMgr. It is not proof that
        // the endpoint is connected; the separate success push is mandatory.
        return replyMagic == UInt64(requestMagic) ? .commandAccepted : nil
    }

    /// Matches the existing Dart uint8list_ext.toCrc16 implementation.
    static func crc16(_ bytes: [UInt8]) -> UInt16 {
        var crc: UInt16 = 0xffff
        for byte in bytes {
            crc = (crc >> 8) | (crc << 8)
            crc ^= UInt16(byte)
            crc ^= (crc & 0xff) >> 4
            crc ^= crc << 12
            crc ^= (crc & 0xff) << 5
        }
        return crc
    }

    private static func varint(_ value: UInt64) -> [UInt8] {
        var value = value
        var output: [UInt8] = []
        repeat {
            var byte = UInt8(value & 0x7f)
            value >>= 7
            if value != 0 { byte |= 0x80 }
            output.append(byte)
        } while value != 0
        return output
    }
}

/// Bounded protobuf reader for the two fields needed to prove business AUTH.
private struct ProtoCursor {
    struct Field {
        let number: UInt64
        let varint: UInt64?
        let bytes: [UInt8]?
    }

    private let data: [UInt8]
    private var offset = 0
    private(set) var valid = true

    init(_ data: [UInt8]) { self.data = data }

    mutating func next() -> Field? {
        guard valid, offset < data.count else { return nil }
        guard let key = readVarint(), key >> 3 != 0 else { valid = false; return nil }
        let number = key >> 3
        switch key & 7 {
        case 0:
            guard let value = readVarint() else { valid = false; return nil }
            return Field(number: number, varint: value, bytes: nil)
        case 1:
            guard skip(8) else { valid = false; return nil }
        case 2:
            guard let length = readVarint(), length <= UInt64(data.count - offset) else {
                valid = false; return nil
            }
            let end = offset + Int(length)
            let value = Array(data[offset..<end])
            offset = end
            return Field(number: number, varint: nil, bytes: value)
        case 5:
            guard skip(4) else { valid = false; return nil }
        default:
            valid = false
        }
        return valid ? Field(number: number, varint: nil, bytes: nil) : nil
    }

    private mutating func readVarint() -> UInt64? {
        var value: UInt64 = 0
        for shift in stride(from: 0, through: 63, by: 7) {
            guard offset < data.count else { return nil }
            let byte = data[offset]
            offset += 1
            if shift == 63 && byte > 1 { return nil }
            value |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 { return value }
        }
        return nil
    }

    private mutating func skip(_ count: Int) -> Bool {
        guard count <= data.count - offset else { return false }
        offset += count
        return true
    }
}
