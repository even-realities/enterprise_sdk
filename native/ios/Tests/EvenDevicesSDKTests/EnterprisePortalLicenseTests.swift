import Foundation
import XCTest
@testable import EvenDevicesSDK

/// A portal export is a serial-indexed allowlist, never one default signature.
final class EnterprisePortalLicenseTests: XCTestCase {
    private let firstSN = "S200TEST000001"
    private let secondSN = "S200TEST000002"
    private let appID = " Demo App "
    private var firstSignature: String { Data(repeating: 0x11, count: 64).base64EncodedString() }
    private var secondSignature: String { Data(repeating: 0x22, count: 64).base64EncodedString() }

    func testSecondSerialSelectsItsOwn8403Proof() throws {
        let bundle = try EnterpriseLicenseBundle(jsonData: export())
        let proof = try XCTUnwrap(bundle.proof(for: secondSN))
        XCTAssertEqual(proof.appID, appID)
        XCTAssertEqual(proof.serialNumber, secondSN)
        XCTAssertEqual(proof.signature, Data(repeating: 0x22, count: 64))
        XCTAssertNotEqual(proof.signature, try XCTUnwrap(bundle.proof(for: firstSN)).signature)
        let payload = proof.authorizationPayloadV1()
        let serialStart = 3 + appID.utf8.count
        XCTAssertEqual(payload.subdata(in: serialStart..<(serialStart + 14)), Data(secondSN.utf8))
        XCTAssertEqual(Data(payload.suffix(64)), Data(repeating: 0x22, count: 64))
    }

    func testUnknownSerialNeverFallsBackOrNormalizes() throws {
        let bundle = try EnterpriseLicenseBundle(jsonData: export())
        for serial in ["S200TEST000003", secondSN.lowercased(), " " + secondSN,
                       secondSN + " ", "", "short"] {
            XCTAssertNil(bundle.proof(for: serial), serial)
        }
        let empty = try EnterpriseLicenseBundle(jsonData: export(licenses: []))
        XCTAssertNil(empty.proof(for: firstSN))
    }

    func testDuplicateArraySerialsAreRejectedEvenWithIdenticalSignatures() throws {
        for signature in [firstSignature, secondSignature] {
            let data = try export(licenses: [
                ["sn": firstSN, "signature": firstSignature],
                ["sn": firstSN, "signature": signature]
            ])
            XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: data)) {
                XCTAssertEqual($0 as? EnterpriseLicenseError, .duplicateSerialNumber)
            }
        }
    }

    func testEveryEntryMustBeWellFormedBeforeAnyProofIsAvailable() throws {
        let malformed: [Any] = [
            NSNull(), "not an object", 1,
            ["sn": secondSN], ["signature": secondSignature],
            ["sn": secondSN, "signature": 42],
            ["sn": 42, "signature": secondSignature],
            ["sn": secondSN, "signature": secondSignature, "extra": true],
            ["sn": "short", "signature": secondSignature],
            ["sn": secondSN, "signature": "bad Base64"],
            ["sn": secondSN, "signature": Data(repeating: 0x22, count: 63).base64EncodedString()]
        ]
        for entry in malformed {
            let data = try export(licenses: [["sn": firstSN, "signature": firstSignature], entry])
            XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: data), String(describing: entry))
        }
    }

    func testUnknownOrMixedFormatsAndInvalidRootFieldsAreRejected() throws {
        let malformed: [[String: Any]] = [
            ["format": "enterprise-sdk-license-v2", "app_id": appID, "licenses": []],
            ["format": "enterprise-sdk-license-v1", "app_id": appID, "licenses": [:]],
            ["format": "enterprise-sdk-license-v1", "app_id": appID, "licenses": [], "proofs": [:]],
            ["format": "enterprise-sdk-license-v1", "app_id": appID, "licenses": [], "schema_version": 1],
            ["format": "enterprise-sdk-license-v1", "app_id": "", "licenses": []],
            ["format": "enterprise-sdk-license-v1", "app_id": String(repeating: "界", count: 22), "licenses": []],
            ["format": "enterprise-sdk-license-v1", "app_id": appID],
            ["app_id": appID, "licenses": []]
        ]
        for root in malformed {
            let data = try JSONSerialization.data(withJSONObject: root)
            XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: data), String(describing: root))
        }
    }

    func testDuplicatePortalEntryKeysIncludingEscapedAliasesAreRejected() {
        let data = Data("""
        {"format":"enterprise-sdk-license-v1","app_id":"app","licenses":[
          {"sn":"\(firstSN)","\\u0073n":"\(secondSN)","signature":"\(firstSignature)"}
        ]}
        """.utf8)
        XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: data)) {
            XCTAssertEqual($0 as? EnterpriseLicenseError, .duplicateJSONKey)
        }
    }

    /// Use synthetic signatures only; real customer license exports stay outside the repository.
    private func export(licenses: [Any]? = nil) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "format": "enterprise-sdk-license-v1", "app_id": appID,
            "licenses": licenses ?? [
                ["sn": firstSN, "signature": firstSignature],
                ["sn": secondSN, "signature": secondSignature]
            ]
        ])
    }
}
