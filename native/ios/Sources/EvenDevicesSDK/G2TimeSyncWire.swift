import Foundation

/// Dart createTimeSyncCommand's retained DevCfgDataPackage encoding. Values
/// are injected here so the native request can be checked against shared vectors.
enum G2TimeSyncWire {
    static func timezoneUnits(offsetMinutes: Int) -> Int {
        min(127, max(-128, offsetMinutes / 15))
    }

    static func payload(epochSeconds: UInt32, offsetMinutes: Int, magic: UInt16) -> Data {
        let timezone = timezoneUnits(offsetMinutes: offsetMinutes)
        let nested = [UInt8(0x08)] + varint(UInt64(epochSeconds))
            + [0x10] + varint(UInt64(bitPattern: Int64(timezone)))
        // commandId=TIME_SYNC(128), magicRandom, timeSync=field 128.
        return Data([0x08] + varint(128) + [0x10] + varint(UInt64(magic))
                    + varint((128 << 3) | 2) + varint(UInt64(nested.count)) + nested)
    }

    static func isSuccessfulResponse(_ payload: Data, magic: UInt16) -> Bool {
        rejectionReason(fields(in: Array(payload)), expectedMagic: magic) == nil
    }

    /// Safe structural diagnostics never include timestamp, timezone or byte
    /// contents. This entry has no expected magic, so correlation is explicitly
    /// left to the command owner rather than implied by a semantic SUCCESS.
    static func responseDiagnostics(_ payload: Data) -> String {
        let outer = fields(in: Array(payload))
        let nested = outer?[128]?.bytes
        let reply = nested.flatMap { fields(in: $0) }
        let status: String
        if outer == nil { status = "unavailable" }
        else if outer?[128] == nil { status = "absent" }
        else if nested == nil { status = "wrongWire" }
        else if reply == nil { status = "malformed" }
        else { status = "parsed" }
        let rootSummary = outer.map(fieldSummary) ?? "malformed"
        let bodySummary = reply.map(fieldSummary) ?? status
        let resultPresent = reply.map { String($0[3] != nil) } ?? "unknown"
        let reason = rejectionReason(outer, expectedMagic: nil) ?? "none"
        return "rootCmd=\(scalarSummary(outer, number: 1)) rootMagic=\(scalarSummary(outer, number: 2)) " +
            "rootFields=[\(rootSummary)] timeSyncStatus=\(status) timeSyncFields=[\(bodySummary)] " +
            "resultPresent=\(resultPresent) resultCode=\(scalarSummary(reply, number: 3)) " +
            "semanticReason=\(reason) magicCorrelation=notEvaluated"
    }

    /// Only the schema's real DevCfg oneof tags count as competing branches.
    /// Preserve existing unknown-extension handling, but never allow a second
    /// known command to coexist with TimeSync, even if its result is SUCCESS.
    private static let commandBranches: Set<UInt64> = Set(3...12).union([14, 128, 129])

    /// Admission and diagnostics share semantic checks. Only admission has an
    /// expected magic and can prove exact correlation with the pending request.
    private static func rejectionReason(_ outer: [UInt64: Field]?, expectedMagic: UInt16?) -> String? {
        guard let outer else { return "rootMalformed" }
        guard outer[1]?.scalar == 128 else { return "rootCommandMismatch" }
        guard let magic = outer[2]?.scalar else { return "rootMagicMissingOrWrongWire" }
        guard magic <= UInt64(UInt16.max) else { return "rootMagicOutOfRange" }
        if let expectedMagic, magic != UInt64(expectedMagic) { return "rootMagicMismatch" }
        guard outer[128] != nil else { return "timeSyncMissing" }
        guard outer.keys.filter({ commandBranches.contains($0) }) == [128] else { return "rootOneofMismatch" }
        guard let nested = outer[128]?.bytes else { return "timeSyncWireMismatch" }
        guard let reply = fields(in: nested) else { return "timeSyncMalformed" }
        // Timestamp, timezone and result are scalar fields. Missing result is
        // proto3 SUCCESS; a present field with the wrong wire type is not.
        for number: UInt64 in [1, 2, 3] {
            if let field = reply[number], field.scalar == nil { return "timeSyncFieldWireMismatch.\(number)" }
        }
        return (reply[3]?.scalar ?? 0) == 0 ? nil : "resultRejected"
    }

    /// Bound every field inventory independently of the incoming message size.
    /// Sorting is diagnostic-only and does not change protobuf admission.
    private static func fieldSummary(_ fields: [UInt64: Field]) -> String {
        var tags = fields.sorted { $0.key < $1.key }.prefix(32).map { key, field in
            "\(key):\(field.scalar == nil ? "bytes" : "varint")"
        }
        if fields.count > 32 { tags.append("moreFields") }
        return tags.isEmpty ? "empty" : tags.joined(separator: ",")
    }

    /// Only the command, magic and result scalar values may reach diagnostics.
    private static func scalarSummary(_ fields: [UInt64: Field]?, number: UInt64) -> String {
        guard let fields else { return "unavailable" }
        guard let field = fields[number] else { return "absent" }
        return field.scalar.map(String.init) ?? "wrongWire"
    }

    struct Field {
        let scalar: UInt64?
        let bytes: [UInt8]?
    }

    /// Duplicate fields and unsupported wire types have no safe matching
    /// meaning for an AUTH-adjacent business command, so reject them.
    static func fields(in bytes: [UInt8]) -> [UInt64: Field]? {
        var values: [UInt64: Field] = [:]
        var offset = 0
        while offset < bytes.count {
            guard let tag = readVarint(bytes, at: &offset), tag >> 3 != 0, tag >> 3 <= 0x1fffffff,
                  values[tag >> 3] == nil else { return nil }
            let field: Field
            switch tag & 7 {
            case 0:
                guard let scalar = readVarint(bytes, at: &offset) else { return nil }
                field = Field(scalar: scalar, bytes: nil)
            case 2:
                guard let size = readVarint(bytes, at: &offset),
                      size <= UInt64(bytes.count - offset) else { return nil }
                let end = offset + Int(size)
                field = Field(scalar: nil, bytes: Array(bytes[offset..<end]))
                offset = end
            default: return nil
            }
            values[tag >> 3] = field
        }
        return values
    }

    private static func varint(_ value: UInt64) -> [UInt8] {
        var remaining = value
        var output: [UInt8] = []
        repeat {
            var byte = UInt8(remaining & 0x7f)
            remaining >>= 7
            if remaining != 0 { byte |= 0x80 }
            output.append(byte)
        } while remaining != 0
        return output
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
