import Foundation
import CoreFoundation

/// One portal-issued proof. The original app ID and SN bytes are preserved for the firmware.
public struct EnterpriseBindingProof: Equatable {
    public let appID: String
    public let serialNumber: String
    public let signature: Data

    public init(appID: String, serialNumber: String, signatureBase64: String) throws {
        let appBytes = Array(appID.utf8)
        guard (1...64).contains(appBytes.count) else { throw EnterpriseLicenseError.invalidAppID }
        guard serialNumber.utf8.count == 14,
              serialNumber.utf8.allSatisfy({ $0 < 0x80 }) else {
            throw EnterpriseLicenseError.invalidSerialNumber
        }
        guard let decoded = Data(base64Encoded: signatureBase64),
              decoded.count == 64,
              decoded.base64EncodedString() == signatureBase64 else {
            throw EnterpriseLicenseError.invalidSignature
        }
        self.appID = appID
        self.serialNumber = serialNumber
        signature = decoded
    }

    /// The versioned draft wire format is kept separate from the public license model.
    public func authorizationPayloadV1() -> Data {
        let appBytes = Array(appID.utf8)
        let snBytes = Array(serialNumber.utf8)
        return Data([1, UInt8(appBytes.count)] + appBytes + [UInt8(snBytes.count)] + snBytes) + signature
    }
}

public enum EnterpriseLicenseError: Error, Equatable {
    case malformedJSON
    case duplicateJSONKey
    case duplicateSerialNumber
    case unsupportedSchema
    case invalidAppID
    case invalidSerialNumber
    case invalidSignature
    case missingProof
}

/// A complete bundle is validated before any BLE connection may use it.
public struct EnterpriseLicenseBundle {
    public let appID: String
    private let proofs: [String: EnterpriseBindingProof]

    public init(appID: String, signaturesBySerial: [String: String]) throws {
        guard (1...64).contains(appID.utf8.count) else { throw EnterpriseLicenseError.invalidAppID }
        var validated: [String: EnterpriseBindingProof] = [:]
        for (sn, signature) in signaturesBySerial {
            validated[sn] = try EnterpriseBindingProof(
                appID: appID, serialNumber: sn, signatureBase64: signature
            )
        }
        self.appID = appID
        proofs = validated
    }

    public init(jsonData: Data) throws {
        // JSONSerialization overwrites duplicate keys. Reject them lexically first, including
        // escaped aliases, so a license cannot depend on parser-specific overwrite order.
        try StrictJSONKeys.rejectDuplicates(in: jsonData)
        guard let root = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            throw EnterpriseLicenseError.malformedJSON
        }
        // Portal exports carry an array of SN-bound signatures. Select one format
        // explicitly so mixed documents cannot hide a competing proof collection.
        if root["format"] != nil || root["licenses"] != nil {
            guard Set(root.keys) == Set(["format", "app_id", "licenses"]),
                  let format = root["format"] as? String,
                  let appID = root["app_id"] as? String,
                  let licenses = root["licenses"] as? [[String: Any]] else {
                throw EnterpriseLicenseError.malformedJSON
            }
            guard format == "enterprise-sdk-license-v1" else {
                throw EnterpriseLicenseError.unsupportedSchema
            }
            var signatures: [String: String] = [:]
            for license in licenses {
                guard Set(license.keys) == Set(["sn", "signature"]),
                      let sn = license["sn"] as? String,
                      let signature = license["signature"] as? String else {
                    throw EnterpriseLicenseError.malformedJSON
                }
                guard signatures[sn] == nil else { throw EnterpriseLicenseError.duplicateSerialNumber }
                signatures[sn] = signature
            }
            // Validate every entry before exposing the snapshot, even if the
            // caller will connect only to the first serial in the export.
            try self.init(appID: appID, signaturesBySerial: signatures)
            return
        }
        guard Set(root.keys) == Set(["schema_version", "app_id", "proofs"]),
              let schema = root["schema_version"] as? NSNumber,
              CFGetTypeID(schema) != CFBooleanGetTypeID() else {
            throw EnterpriseLicenseError.malformedJSON
        }
        guard schema.stringValue == "1" else { throw EnterpriseLicenseError.unsupportedSchema }
        guard let appID = root["app_id"] as? String,
              let signatures = root["proofs"] as? [String: String] else {
            throw EnterpriseLicenseError.malformedJSON
        }
        try self.init(appID: appID, signaturesBySerial: signatures)
    }

    /// Lookup is exact: another serial's proof never authorizes a connection.
    public func proof(for serialNumber: String) -> EnterpriseBindingProof? {
        proofs[serialNumber]
    }
}

/// Walks JSON syntax rather than scanning quotes: strings in values cannot impersonate keys.
private struct StrictJSONKeys {
    private let bytes: [UInt8]
    private var index = 0

    static func rejectDuplicates(in data: Data) throws {
        var parser = StrictJSONKeys(bytes: Array(data))
        try parser.value()
        parser.space()
        guard parser.index == parser.bytes.count else { throw EnterpriseLicenseError.malformedJSON }
    }

    private mutating func space() {
        while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
    }

    private mutating func value() throws {
        space()
        guard index < bytes.count else { throw EnterpriseLicenseError.malformedJSON }
        switch bytes[index] {
        case 123: try object()
        case 91: try array()
        case 34: _ = try string()
        default:
            let start = index
            while index < bytes.count && ![44, 93, 125, 9, 10, 13, 32].contains(bytes[index]) {
                index += 1
            }
            guard start < index else { throw EnterpriseLicenseError.malformedJSON }
        }
    }

    private mutating func object() throws {
        index += 1
        space()
        var seen = Set<String>()
        if consume(125) { return }
        while true {
            space()
            let key = try string()
            guard seen.insert(key).inserted else { throw EnterpriseLicenseError.duplicateJSONKey }
            space()
            guard consume(58) else { throw EnterpriseLicenseError.malformedJSON }
            try value()
            space()
            if consume(125) { return }
            guard consume(44) else { throw EnterpriseLicenseError.malformedJSON }
        }
    }

    private mutating func array() throws {
        index += 1
        space()
        if consume(93) { return }
        while true {
            try value()
            space()
            if consume(93) { return }
            guard consume(44) else { throw EnterpriseLicenseError.malformedJSON }
        }
    }

    private mutating func string() throws -> String {
        guard consume(34) else { throw EnterpriseLicenseError.malformedJSON }
        let start = index - 1
        var escaped = false
        while index < bytes.count {
            let byte = bytes[index]
            index += 1
            if byte == 34 && !escaped {
                let token = Data(bytes[start..<index])
                guard let decoded = try? JSONSerialization.jsonObject(
                    with: Data("[".utf8) + token + Data("]".utf8)
                ) as? [String], let value = decoded.first else {
                    throw EnterpriseLicenseError.malformedJSON
                }
                return value
            }
            if byte == 92 && !escaped { escaped = true } else { escaped = false }
        }
        throw EnterpriseLicenseError.malformedJSON
    }

    private mutating func consume(_ byte: UInt8) -> Bool {
        guard index < bytes.count && bytes[index] == byte else { return false }
        index += 1
        return true
    }
}
