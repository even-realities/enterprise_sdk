import Foundation

/// The enterprise G2 business allowlist. No consumer or ring SID is admitted.
public enum G2BusinessService: UInt8 {
    case notification = 0x04
    case deviceSettings = 0x80
    case evenHub = 0xE0
}

/// One complete G2 logical transport frame, after bounded native reassembly.
struct G2BusinessFrame {
    let service: G2BusinessService
    let syncID: UInt8
    let resultCode: UInt8
    let isNotification: Bool
    let payload: Data

    /// Observed enterprise firmware echoes only the low byte even though the
    /// protobuf field permits 16 bits. Allocate nonzero byte values up front;
    /// response correlation still compares the full value without masking.
    static func nextFirmwareMagic(after current: UInt8) -> UInt8 {
        current == UInt8.max ? 1 : current + 1
    }

    /// Dart EvenBleTransport uses a CRC over the protobuf payload, not the header.
    static func request(service: G2BusinessService, syncID: UInt8, payload: Data) -> Data? {
        guard !payload.isEmpty, payload.count <= 253 else { return nil }
        let body = Array(payload)
        let crc = G2AuthenticationWire.crc16(body)
        return Data([0xAA, 0x21, syncID, UInt8(body.count + 2), 1, 1,
                     service.rawValue, 0x20] + body + [UInt8(crc & 0xff), UInt8(crc >> 8)])
    }

    /// Fragment the complete protobuf; only the last packet carries its CRC.
    /// Packet size follows the actual ATT write limit and the one-byte wire length.
    static func requests(service: G2BusinessService, syncID: UInt8, payload: Data,
                         maximumWriteLength: Int) -> [Data]? {
        guard !payload.isEmpty, payload.count <= 32768, maximumWriteLength >= 11 else { return nil }
        let capacity = min(255, maximumWriteLength - 8)
        let lastCapacity = capacity - 2
        var slices: [Data] = []
        var offset = 0
        while offset < payload.count {
            let remaining = payload.count - offset
            let size = remaining <= lastCapacity ? remaining : min(capacity, remaining - 1)
            slices.append(payload.subdata(in: offset..<(offset + size)))
            offset += size
        }
        guard slices.count <= 255 else { return nil }
        let crc = G2AuthenticationWire.crc16(Array(payload))
        return slices.enumerated().map { index, body in
            let last = index == slices.count - 1
            return Data([0xAA, 0x21, syncID, UInt8(body.count + (last ? 2 : 0)),
                         UInt8(slices.count), UInt8(index + 1), service.rawValue, 0x20])
                + body + (last ? Data([UInt8(crc & 0xff), UInt8(crc >> 8)]) : Data())
        }
    }

    /// A successful response has a device-owned sync counter. Correlate its
    /// protobuf magic instead; only transport failures echo the request sync.
    static func parse(_ data: Data) -> G2BusinessFrame? {
        guard let bytes = declaredBytes(data) else { return nil }
        guard bytes[0] == 0xAA,
              bytes[1] >> 4 == 1, [2, 4, 5].contains(bytes[1] & 0x0f),
              bytes[4] == 1, bytes[5] == 1,
              let service = G2BusinessService(rawValue: bytes[6]),
              bytes[7] & 0xc0 == 0 else { return nil }
        let resultCode = (bytes[7] >> 1) & 0x0f
        if resultCode != 0 {
            // Firmware transport failures can have no protobuf payload or
            // CRC; matching by request sync happens at the command owner.
            return G2BusinessFrame(service: service, syncID: bytes[2],
                                   resultCode: resultCode,
                                   isNotification: bytes[7] & 1 != 0,
                                   payload: Data())
        }
        guard bytes.count >= 10, bytes[3] >= 2 else { return nil }
        let body = Array(bytes[8..<(bytes.count - 2)])
        let crc = UInt16(bytes[bytes.count - 2]) | UInt16(bytes[bytes.count - 1]) << 8
        guard G2AuthenticationWire.crc16(body) == crc else { return nil }
        return G2BusinessFrame(service: service, syncID: bytes[2],
                               resultCode: resultCode,
                               isNotification: bytes[7] & 1 != 0,
                               payload: Data(body))
    }

    /// Notifications may carry transport padding after the header-declared
    /// frame, as in production Dart. Never include that tail in protobuf/CRC;
    /// truncated declared frames still fail before parsing or assembly.
    static func declaredBytes(_ data: Data) -> [UInt8]? {
        let bytes = Array(data)
        guard bytes.count >= 8 else { return nil }
        let end = 8 + Int(bytes[3])
        guard bytes.count >= end else { return nil }
        return Array(bytes[..<end])
    }

    /// All retained top-level protobuf messages put magicRandom at field 2.
    /// Reject malformed or duplicate values instead of matching a default zero.
    static func magicRandom(in payload: Data) -> UInt64? {
        let bytes = Array(payload)
        var offset = 0
        var magic: UInt64?
        while offset < bytes.count {
            guard let key = readVarint(bytes, at: &offset), key >> 3 != 0 else { return nil }
            switch key & 7 {
            case 0:
                guard let value = readVarint(bytes, at: &offset) else { return nil }
                if key >> 3 == 2 {
                    guard magic == nil else { return nil }
                    magic = value
                }
            case 1:
                guard bytes.count - offset >= 8 else { return nil }
                offset += 8
            case 2:
                guard let length = readVarint(bytes, at: &offset),
                      length <= UInt64(bytes.count - offset) else { return nil }
                offset += Int(length)
            case 5:
                guard bytes.count - offset >= 4 else { return nil }
                offset += 4
            default: return nil
            }
        }
        return magic
    }

    private static func readVarint(_ bytes: [UInt8], at offset: inout Int) -> UInt64? {
        var result: UInt64 = 0
        for shift in stride(from: 0, through: 63, by: 7) {
            guard offset < bytes.count else { return nil }
            let byte = bytes[offset]
            offset += 1
            if shift == 63 && byte > 1 { return nil }
            result |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 { return result }
        }
        return nil
    }
}

/// Each endpoint owns its assembler, so fragments cannot cross physical
/// session/attempt boundaries. Reject gaps, reordered packets and stale chunks.
final class G2BusinessAssembler {
    private struct Partial {
        let started: TimeInterval
        let total: UInt8
        var next: UInt8
        var bytes: Data
    }
    private var partials: [Data: Partial] = [:]

    func append(_ data: Data, now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> G2BusinessFrame? {
        partials = partials.filter { now - $0.value.started <= 3 }
        guard let b = G2BusinessFrame.declaredBytes(data) else { return nil }
        guard b[0] == 0xaa, b[1] >> 4 == 1,
              [2, 4, 5].contains(b[1] & 0x0f),
              b[4] > 0, b[5] > 0, b[5] <= b[4],
              let service = G2BusinessService(rawValue: b[6]), b[7] & 0xc0 == 0 else { return nil }
        let key = Data([b[1], b[2], b[6], b[7]])
        let result = (b[7] >> 1) & 15
        if result != 0 {
            partials.removeValue(forKey: key)
            return G2BusinessFrame(service: service, syncID: b[2], resultCode: result,
                                   isNotification: b[7] & 1 != 0, payload: Data())
        }
        if b[4] == 1 {
            partials.removeValue(forKey: key)
            return G2BusinessFrame.parse(data)
        }
        let last = b[4] == b[5]
        guard !last || b.count >= 10 else { partials.removeValue(forKey: key); return nil }
        let chunk = Data(b[8..<(b.count - (last ? 2 : 0))])
        if b[5] == 1 {
            guard partials.count < 4 || partials[key] != nil else { return nil }
            partials[key] = Partial(started: now, total: b[4], next: 2, bytes: chunk)
            return nil
        }
        guard var pending = partials[key], pending.total == b[4], pending.next == b[5],
              pending.bytes.count + chunk.count <= 32768 else {
            partials.removeValue(forKey: key); return nil
        }
        pending.bytes.append(chunk)
        if !last {
            pending.next += 1
            partials[key] = pending
            return nil
        }
        partials.removeValue(forKey: key)
        let crc = UInt16(b[b.count - 2]) | UInt16(b[b.count - 1]) << 8
        guard G2AuthenticationWire.crc16(Array(pending.bytes)) == crc else { return nil }
        return G2BusinessFrame(service: service, syncID: b[2], resultCode: 0,
                               isNotification: b[7] & 1 != 0, payload: pending.bytes)
    }
}

/// CoreBluetooth's write delegate has no request ID. Keep the outstanding ATT
/// owner even after logical timeout; admission stays busy until its callback
/// drains, so a late write cannot advance or complete a replacement command.
final class G2BusinessWriteLedger<Owner: AnyObject> {
    private var outstanding: Owner?
    var hasPending: Bool { outstanding != nil }
    func begin(_ owner: Owner) {
        precondition(outstanding == nil)
        outstanding = owner
    }
    func consume() -> Owner? {
        defer { outstanding = nil }
        return outstanding
    }
}
