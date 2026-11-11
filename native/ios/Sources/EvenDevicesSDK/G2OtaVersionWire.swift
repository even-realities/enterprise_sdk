import Foundation

/// Pair snapshot from one fresh, authenticated BASIC_SETTING response.
struct G2OtaVersions {
    let left: String
    let right: String

    func matches(target: String) -> Bool {
        guard let expected = Self.components(target) else { return false }
        return Self.components(left) == expected && Self.components(right) == expected
    }

    static func isValidTarget(_ target: String) -> Bool { components(target) != nil }

    /// Compare numeric components with a V prefix and implicit trailing zeros. A
    /// malformed component cannot be coerced into zero and verify an installation.
    private static func components(_ version: String) -> [UInt64]? {
        var digits = version.trimmingCharacters(in: .whitespacesAndNewlines)[...]
        if digits.first == "V" || digits.first == "v" { digits = digits.dropFirst() }
        guard (1...11).contains(digits.utf8.count) else { return nil }
        var result: [UInt64] = []
        for part in digits.split(separator: ".", omittingEmptySubsequences: false) {
            guard !part.isEmpty, part.utf8.allSatisfy({ (48...57).contains($0) }),
                  let value = UInt64(part) else { return nil }
            result.append(value)
        }
        while result.last == 0 { result.removeLast() }
        // Firmware reports 0.0.0 when its version is not yet available.
        return result.isEmpty ? nil : result
    }
}

/// This private OTA readback admits only DeviceReceiveRequest / BASIC_SETTING on SID 9.
enum G2OtaVersionWire {
    private static let sid: UInt8 = 9

    static func request(syncID: UInt8, magic: UInt8) -> Data {
        precondition(magic != 0)
        // G2SettingPackage { DeviceReceiveRequest, magic, field 4 { BASIC_SETTING=1 } }.
        let payload: [UInt8] = [8, 2, 16] + G2EvenHubCommands.varint(UInt64(magic)) + [34, 2, 8, 1]
        let crc = G2AuthenticationWire.crc16(payload)
        return Data([0xaa, 0x21, syncID, UInt8(payload.count + 2), 1, 1, sid, 0x20]
                    + payload + [UInt8(crc & 255), UInt8(crc >> 8)])
    }

    /// The link owner checks fresh SN and generations. Firmware owns response syncID;
    /// match magic, service, command, response flag, CRC and the unique oneof instead.
    static func response(_ frame: Data, magic: UInt8) -> G2OtaVersions? {
        // The current 19-field nanopb schema caps this complete frame at 146 bytes;
        // enterprise ATT admits at least 147. Partial frames never prove installation.
        guard magic != 0, let bytes = G2BusinessFrame.declaredBytes(frame), bytes.count >= 10,
              bytes[0] == 0xaa, [0x12, 0x14, 0x15].contains(bytes[1]),
              bytes[4] == 1, bytes[5] == 1, bytes[6] == sid, bytes[7] & 0xdf == 0 else { return nil }
        let end = bytes.count
        let payload = Array(bytes[8..<(end - 2)])
        let crc = UInt16(bytes[end - 2]) | UInt16(bytes[end - 1]) << 8
        guard G2AuthenticationWire.crc16(payload) == crc,
              let root = G2TimeSyncWire.fields(in: payload), root[1]?.scalar == 2,
              root[2]?.scalar == UInt64(magic), root.keys.filter({ (3...7).contains($0) }) == [4],
              let branch = root[4]?.bytes, let settings = G2TimeSyncWire.fields(in: branch),
              settings[1]?.scalar == 1, let left = version(settings[5]?.bytes),
              let right = version(settings[6]?.bytes) else { return nil }
        return G2OtaVersions(left: left, right: right)
    }

    /// nanopb max_size=12 reserves one byte for the C terminator, absent on the wire.
    private static func version(_ bytes: [UInt8]?) -> String? {
        guard let bytes, (1...11).contains(bytes.count), let text = String(bytes: bytes, encoding: .ascii),
              G2OtaVersions.isValidTarget(text) else { return nil }
        return text
    }
}
