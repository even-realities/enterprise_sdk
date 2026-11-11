import Foundation

/// INFORMATION carries the header. RAW windows contain only the component body.
struct G2OtaComponent {
    let header: Data
    let body: Data
}

struct G2OtaImage {
    let version: String
    let components: [G2OtaComponent]
}

/// Both enterprise endpoints use C0 control and C1 RAW, each with its own window receipt.
enum G2OtaWire {
    private enum WireError: Error { case invalidFirmware, invalidWindow }
    private static let controlSID: UInt8 = 0xc0
    private static let rawSID: UInt8 = 0xc1

    /// Check all offsets, inner headers and body checksums before either endpoint can enter START.
    static func parse(_ bytes: Data) throws -> G2OtaImage {
        // Normalize Data slice indices for offsets measured from the beginning of the file.
        let data = Data(bytes)
        func matches(_ at: Int, _ magic: String) -> Bool {
            data[at..<(at + magic.utf8.count)].elementsEqual(magic.utf8)
        }
        func u32(_ at: Int) -> UInt32 {
            (0..<4).reduce(UInt32.zero) { $0 | UInt32(data[at + $1]) << ($1 * 8) }
        }
        guard data.count >= 80, matches(0, "EVENOTA\0") else { throw WireError.invalidFirmware }
        let count = Int(u32(8))
        guard count > 0, count <= (data.count - 80) / (16 + 128) else { throw WireError.invalidFirmware }
        let footer = 64 + count * 16
        let endOfVersion: Int = data[48..<64].firstIndex(of: 0) ?? 64
        guard matches(footer, "evenota\0"), endOfVersion > 48,
              data[48..<endOfVersion].allSatisfy({ (32...126).contains($0) }),
              data[endOfVersion..<64].allSatisfy({ $0 == 0 }) else { throw WireError.invalidFirmware }
        let version = String(decoding: data[48..<endOfVersion], as: UTF8.self)
        var components: [G2OtaComponent] = []
        var start = footer + 16
        for i in 0..<count {
            let entry = 64 + i * 16
            let length = Int(u32(entry + 8))
            let crc = u32(entry + 12)
            guard Int(u32(entry + 4)) == start, length >= 128, length <= data.count - start,
                  matches(start + 20, "EVEN"), Int(u32(start + 8)) == length - 128,
                  u32(start + 12) == crc else { throw WireError.invalidFirmware }
            let end = start + length
            let body = data.subdata(in: start + 128..<end)
            guard imageCrc(body) == crc else { throw WireError.invalidFirmware }
            components.append(G2OtaComponent(header: data.subdata(in: start..<start + 128), body: body))
            start = end
        }
        guard start == data.count else { throw WireError.invalidFirmware }
        return G2OtaImage(version: version, components: components)
    }

    /// EVEN's Castagnoli CRC consumes the body MSB-first, initial state zero, without a final XOR.
    private static let imageTable: [UInt32] = (0..<256).map { i in
        var value = UInt32(i) << 24
        for _ in 0..<8 {
            value = (value << 1) ^ ((value >> 31 == 0) ? 0 : 0x1edc6f41)
        }
        return value
    }

    private static func imageCrc(_ body: Data) -> UInt32 {
        var crc: UInt32 = 0
        for byte in body {
            crc = (crc << 8) ^ imageTable[Int(crc >> 24) ^ Int(byte)]
        }
        return crc
    }

    /// Control commands fit one frame even for the full 128-byte INFORMATION record.
    static func control(cid: Int, sync: Int, payload: Data = Data()) -> Data {
        precondition((0...3).contains(cid) && (0...255).contains(sync) && payload.count == (cid == 1 ? 128 : 0))
        let body = Data([UInt8(cid)]) + payload
        return frame(service: controlSID, sync: sync, total: 1, serial: 1, part: body,
                     crc: G2AuthenticationWire.crc16(Array(body)))
    }

    /// FILE shares its sync with all following C1 fragments. Reserve a CRC-only final fragment when needed.
    static func window(_ data: Data, sync: Int, maxWrite: Int) throws -> [Data] {
        guard (1...4096).contains(data.count), (0...255).contains(sync), maxWrite >= 11 else {
            throw WireError.invalidWindow
        }
        let capacity = min(255, maxWrite - 8)
        let partCount = (data.count + capacity - 1) / capacity
        let remaining = data.count - (partCount - 1) * capacity
        let total = partCount + (remaining + 2 > capacity ? 1 : 0)
        guard total <= 255 else { throw WireError.invalidWindow }
        var frames = [control(cid: 2, sync: sync)]
        let crc = G2AuthenticationWire.crc16(Array(data))
        for i in 0..<total {
            let start = data.startIndex + min(i * capacity, data.count)
            let part = data[start..<min(start + capacity, data.endIndex)]
            frames.append(frame(service: rawSID, sync: sync, total: total, serial: i + 1,
                                part: part, crc: i + 1 == total ? crc : nil))
        }
        return frames
    }

    private static func frame(service: UInt8, sync: Int, total: Int, serial: Int, part: Data, crc: UInt16?) -> Data {
        var frame = Data([0xaa, 0x21, UInt8(sync), UInt8(part.count + (crc == nil ? 0 : 2)),
                          UInt8(total), UInt8(serial), service, 0])
        frame.append(part)
        if let crc { frame.append(contentsOf: [UInt8(crc & 0xff), UInt8(crc >> 8)]) }
        return frame
    }

    /// nil means unrelated; -1 means a matched reply was malformed or rejected at the transport layer.
    static func reply(_ frame: Data, expectedCid: Int, expectedSync: Int) -> Int? {
        let bytes = [UInt8](frame)
        let service = expectedCid == 2 ? rawSID : controlSID
        guard isSingleInbound(bytes, service), Int(bytes[2]) == expectedSync, bytes[7] & 1 == 0 else { return nil }
        guard bytes[7] == 0, validResponsePayload(bytes) else { return -1 }
        if bytes[8] == 4 && service == controlSID { return nil }
        return Int(bytes[8]) == expectedCid ? Int(bytes[9]) : -1
    }

    /// Firmware assigns NOTIFY its own sync. The owner must check endpoint and final RESULT stage before acceptance.
    static func notify(_ frame: Data) -> Int? {
        let bytes = [UInt8](frame)
        guard isSingleInbound(bytes, controlSID), bytes[7] <= 1,
              validResponsePayload(bytes), bytes[8] == 4 else { return nil }
        return Int(bytes[9])
    }

    private static func isSingleInbound(_ b: [UInt8], _ service: UInt8) -> Bool {
        b.count >= 8 && b[0] == 0xaa && b[1] == 0x12 && b[4] == 1 && b[5] == 1 && b[6] == service
    }

    // Len includes the little-endian CRC; padding after this boundary is not firmware payload.
    private static func validResponsePayload(_ b: [UInt8]) -> Bool {
        guard b.count >= 12 && b[3] == 4 else { return false }
        let crc = UInt16(b[10]) | UInt16(b[11]) << 8
        return G2AuthenticationWire.crc16(Array(b[8..<10])) == crc
    }
}
