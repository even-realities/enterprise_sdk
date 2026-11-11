import Foundation
import XCTest
@testable import EvenDevicesSDK

final class EnterpriseProtocolTests: XCTestCase {
    private let sn = "S211GABE130301"
    private var signature: String { Data(0..<64).base64EncodedString() }

    func testLicenseAnd8403MatchSharedContractVector() throws {
        let proof = try EnterpriseBindingProof(appID: " Demo ", serialNumber: sn,
                                               signatureBase64: signature)
        let contract = try readContract()
        let vectors = try XCTUnwrap(contract["vectors"] as? [String: String])
        XCTAssertEqual(proof.authorizationPayloadV1().hex,
                       vectors["enterprise_auth_v1_app_demo_sn_130301_signature_0_to_63_hex"])
        XCTAssertEqual(proof.authorizationPayloadV1().count, 1 + 1 + 6 + 1 + 14 + 64)
    }

    func testBundleRejectsDuplicateAndEscapedDuplicateKeys() throws {
        let json = """
        {"schema_version":1,"app_id":"app","proofs":{"\(sn)":"\(signature)","\(sn)":"\(signature)"}}
        """
        XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: Data(json.utf8))) {
            XCTAssertEqual($0 as? EnterpriseLicenseError, .duplicateJSONKey)
        }
        let alias = """
        {"schema_version":1,"app_id":"app","proofs":{"\(sn)":"\(signature)","\\u0053211GABE130301":"\(signature)"}}
        """
        XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: Data(alias.utf8))) {
            XCTAssertEqual($0 as? EnterpriseLicenseError, .duplicateJSONKey)
        }
    }

    func testLicenseRejectsNonCanonicalSignatureAndInvalidIdentity() throws {
        XCTAssertThrowsError(try EnterpriseBindingProof(appID: "app", serialNumber: sn,
                                                         signatureBase64: signature.replacingOccurrences(of: "=", with: "")))
        XCTAssertThrowsError(try EnterpriseBindingProof(appID: "app", serialNumber: "短SN",
                                                         signatureBase64: signature))
        XCTAssertThrowsError(try EnterpriseBindingProof(appID: String(repeating: "界", count: 22),
                                                         serialNumber: sn, signatureBase64: signature))
        let valid = try EnterpriseLicenseBundle(jsonData: Data("""
            {"schema_version":1,"app_id":"app","proofs":{"\(sn)":"\(signature)"}}
            """.utf8))
        XCTAssertNotNil(valid.proof(for: sn))
        XCTAssertNil(valid.proof(for: "S211GABE130302"))
        let unsupported = Data("{\"schema_version\":2,\"app_id\":\"app\",\"proofs\":{}}".utf8)
        XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: unsupported)) {
            XCTAssertEqual($0 as? EnterpriseLicenseError, .unsupportedSchema)
        }
        let boolean = Data("{\"schema_version\":true,\"app_id\":\"app\",\"proofs\":{}}".utf8)
        XCTAssertThrowsError(try EnterpriseLicenseBundle(jsonData: boolean)) {
            XCTAssertEqual($0 as? EnterpriseLicenseError, .malformedJSON)
        }
    }

    func testBusinessAuthRequiresExactMagicCommandAndAuthMgr() throws {
        let contract = try readContract()
        let vectors = try XCTUnwrap(contract["vectors"] as? [String: String])
        XCTAssertEqual(vectors["g2_business_auth_success_protobuf_hex"], "08041a020801")
        let request = G2AuthenticationWire.request(syncID: 3, magic: 7)
        XCTAssertEqual(request[0], 0xaa)
        XCTAssertEqual(request[6], 0x80)
        XCTAssertEqual(request[7], 0) // Single endpoint only.
        XCTAssertEqual(Array(request[8..<(request.count - 2)]).hex, "080410071a0408011003")
        XCTAssertEqual(G2AuthenticationWire.request(syncID: 7, magic: 42).hex,
                       vectors["g2_business_auth_ios_request_magic_42_sync_7_hex"])
        let push = Data([UInt8](hex: try XCTUnwrap(vectors["g2_business_auth_response_magic_42_sync_7_hex"])))
        XCTAssertEqual(G2AuthenticationWire.classify(push, requestMagic: 42), .authenticated)
        // A push is device-owned; request identity belongs to the command ACK.
        XCTAssertEqual(G2AuthenticationWire.classify(push, requestMagic: 43), .authenticated)
        var independentPush = push
        independentPush[2] = 9
        XCTAssertEqual(G2AuthenticationWire.classify(independentPush, requestMagic: 42), .authenticated)

        XCTAssertEqual(G2AuthenticationWire.classify(
            packet(hexPayload: "08041a020801"), requestMagic: 42), .authenticated)
        let reply = packet(hexPayload: "080410071a020801")
        XCTAssertEqual(G2AuthenticationWire.classify(reply, requestMagic: 7), .authenticated)
        XCTAssertNil(G2AuthenticationWire.classify(packet(hexPayload: "080510071a020801"), requestMagic: 7))
        XCTAssertNil(G2AuthenticationWire.classify(packet(hexPayload: "080410071a020800"), requestMagic: 7))
        XCTAssertNil(G2AuthenticationWire.classify(packet(hexPayload: "080410071a0418010801"), requestMagic: 7))
        var damaged = reply
        damaged[damaged.count - 1] ^= 1
        XCTAssertNil(G2AuthenticationWire.classify(damaged, requestMagic: 7))
        damaged = reply
        damaged[7] = 0
        XCTAssertEqual(G2AuthenticationWire.classify(damaged, requestMagic: 7), .commandAccepted)
        XCTAssertNil(G2AuthenticationWire.classify(damaged, requestMagic: 8))
    }

    /// Real firmware separates the correlated command ACK from its unsolicited
    /// AUTH push. Neither signal alone may promote the business connection.
    func testCapturedAuthAckAndIndependentPushAreSeparateSignals() throws {
        let vectors = try XCTUnwrap(try readContract()["vectors"] as? [String: String])
        let ack = Data([UInt8](hex: try XCTUnwrap(vectors["g2_business_auth_ack_magic_195_sync_223_hex"])))
        let push = Data([UInt8](hex: try XCTUnwrap(vectors["g2_business_auth_push_magic_67_sync_67_hex"])))
        XCTAssertEqual(G2AuthenticationWire.classify(ack, requestMagic: 195), .commandAccepted)
        XCTAssertNil(G2AuthenticationWire.classify(ack, requestMagic: 196))
        XCTAssertNil(G2AuthenticationWire.classify(ack, requestMagic: 59331))
        XCTAssertEqual(G2AuthenticationWire.classify(push, requestMagic: 195), .authenticated)
        XCTAssertNil(G2AuthenticationWire.classify(
            packet(hexPayload: "080510c3011a020801"), requestMagic: 195))
        var damaged = push
        damaged[damaged.count - 1] ^= 1
        XCTAssertNil(G2AuthenticationWire.classify(damaged, requestMagic: 195))
    }

    func testSynchronousEndpointReadyCallbackCannotPublishRetiredPairReady() {
        var current = true
        var events: [String] = []
        EvenDevicesG2SDK.emitReadyEvents(
            endpoint: { events.append("endpoint"); current = false },
            isCurrent: { current }, bothReady: { true },
            whole: { events.append("pair") }
        )
        XCTAssertEqual(events, ["endpoint"])

        current = true
        EvenDevicesG2SDK.emitReadyEvents(
            endpoint: { events.append("endpoint") },
            isCurrent: { current }, bothReady: { true },
            whole: { events.append("pair") }
        )
        XCTAssertEqual(events, ["endpoint", "endpoint", "pair"])
    }

    private func readContract() throws -> [String: Any] {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol/contract.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
    }

    private func packet(hexPayload: String) -> Data {
        let payload = [UInt8](hex: hexPayload)
        let crc = G2AuthenticationWire.crc16(payload)
        return Data([0xaa, 0x12, 3, UInt8(payload.count + 2), 1, 1, 0x80, 1]
                    + payload + [UInt8(crc & 0xff), UInt8(crc >> 8)])
    }
}

private extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

private extension Array where Element == UInt8 {
    var hex: String { map { String(format: "%02x", $0) }.joined() }

    init(hex: String) {
        self = stride(from: 0, to: hex.count, by: 2).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            return UInt8(hex[start..<hex.index(start, offsetBy: 2)], radix: 16)!
        }
    }
}
