import Foundation
import XCTest
@testable import EvenDevicesSDK

final class G2BusinessTests: XCTestCase {
    /// Exercise more than two firmware-byte cycles, including 255 -> 1. The
    /// encoded request and exact ACK must agree without ever emitting zero.
    func testFirmwareMagicAllocationWrapsWithoutWideningTimeSyncRequests() throws {
        let g2 = try XCTUnwrap(readContract()["g2"] as? [String: Any])
        let contract = try XCTUnwrap(g2["time_sync"] as? [String: Any])
        let maximum = try XCTUnwrap(contract["request_magic_max"] as? Int)
        var magic: UInt8 = 0
        var previous: UInt8?
        for operation in 1...768 {
            magic = G2BusinessFrame.nextFirmwareMagic(after: magic)
            XCTAssertEqual(Int(magic), (operation - 1) % maximum + 1)
            let payload = G2TimeSyncWire.payload(epochSeconds: 1_700_000_000,
                offsetMinutes: 480, magic: UInt16(magic))
            XCTAssertEqual(G2BusinessFrame.magicRandom(in: payload), UInt64(magic))
            let reply = timeSyncReply(magic: UInt64(magic))
            XCTAssertTrue(G2TimeSyncWire.isSuccessfulResponse(reply, magic: UInt16(magic)))
            if let previous {
                XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(reply, magic: UInt16(previous)))
            }
            previous = magic
        }
    }

    /// These ACKs are reconstructed from beta.10's numeric metadata, not raw
    /// packet captures. A low-byte ACK must never match a wider request value.
    func testObservedLowByteRepliesDoNotRelaxExactMagicMatching() {
        for (sent, echoed): (UInt16, UInt16) in [(40085, 149), (45795, 227)] {
            XCTAssertEqual(sent & 0xff, echoed)
            let reply = timeSyncReply(magic: UInt64(echoed))
            XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(reply, magic: sent))
            XCTAssertTrue(G2TimeSyncWire.isSuccessfulResponse(reply, magic: echoed))
        }
    }

    func testTimeSyncMatchesDartContractWithIndependentResponseSync() throws {
        let contract = try readContract()
        let vectors = try XCTUnwrap(contract["vectors"] as? [String: String])
        let requestPayload = G2TimeSyncWire.payload(epochSeconds: 1_700_000_000,
                                                    offsetMinutes: -210, magic: 42)
        XCTAssertEqual(G2TimeSyncWire.timezoneUnits(offsetMinutes: -210), -14)
        XCTAssertEqual(G2TimeSyncWire.timezoneUnits(offsetMinutes: 22), 1)
        XCTAssertEqual(G2BusinessFrame.request(service: .deviceSettings,
                                               syncID: 7, payload: requestPayload)?.hex,
                       vectors["g2_time_sync_request_magic_42_sync_7_hex"])
        let response = Data(hex: try XCTUnwrap(vectors["g2_time_sync_response_magic_42_sync_9_hex"]))
        let frame = try XCTUnwrap(G2BusinessFrame.parse(response))
        XCTAssertEqual(frame.syncID, 9) // The request used 7.
        XCTAssertEqual(frame.service, .deviceSettings)
        XCTAssertFalse(frame.isNotification)
        XCTAssertEqual(G2BusinessFrame.magicRandom(in: frame.payload), 42)
        XCTAssertTrue(G2TimeSyncWire.isSuccessfulResponse(frame.payload, magic: 42))
        XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(frame.payload, magic: 43))
    }

    /// An empty response body is proto3 SUCCESS, but a second real DevCfg
    /// oneof or a known scalar encoded as bytes cannot become a valid ACK.
    func testTimeSyncRequiresUniqueKnownBranchAndKnownScalarWireTypes() {
        typealias Proto = G2EvenHubCommands
        XCTAssertTrue(G2TimeSyncWire.isSuccessfulResponse(timeSyncReply(), magic: 42))
        for branch: UInt64 in Array(3...12) + [14, 129] {
            XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(
                timeSyncReply(rootExtras: Proto.bytes(branch, [])), magic: 42), "branch \(branch)")
        }
        for field: UInt64 in [1, 2, 3] {
            XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(
                timeSyncReply(body: Proto.bytes(field, [])), magic: 42), "field \(field)")
        }
        XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(
            timeSyncReply(body: Proto.scalar(3, 7)), magic: 42))
        XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(
            timeSyncReply(body: Proto.scalar(3, 0, explicit: true) + Proto.scalar(3, 0, explicit: true)), magic: 42))
        // Preserve existing unknown-extension handling without admitting an
        // invalid protobuf field number outside the 29-bit schema limit.
        XCTAssertTrue(G2TimeSyncWire.isSuccessfulResponse(
            timeSyncReply(body: Proto.scalar(99, 1), rootExtras: Proto.scalar(200, 1)), magic: 42))
        XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(
            timeSyncReply(rootExtras: Proto.scalar(1 << 29, 1)), magic: 42))
        XCTAssertTrue(G2TimeSyncWire.isSuccessfulResponse(timeSyncReply(magic: 65535), magic: 65535))
        XCTAssertFalse(G2TimeSyncWire.isSuccessfulResponse(timeSyncReply(magic: 65535), magic: 255))
    }

    /// Diagnostics report only protocol identity and structural metadata. They
    /// cannot claim request correlation without the expected request magic.
    func testTimeSyncDiagnosticsAreBoundedAndNeverExposeBodyValuesOrBytes() {
        typealias Proto = G2EvenHubCommands
        let secret = "PRIVATE-LICENSE-AND-PAGE-CONTENT"
        let body = Proto.scalar(1, 1_700_000_000) + Proto.scalar(2, UInt64(bitPattern: -14)) +
            Proto.scalar(3, 0, explicit: true) + Proto.bytes(99, Array(secret.utf8))
        let summary = G2TimeSyncWire.responseDiagnostics(timeSyncReply(body: body, magic: 65535))
        for expected in ["rootCmd=128", "rootMagic=65535", "rootFields=[1:varint,2:varint,128:bytes]",
                         "timeSyncStatus=parsed", "timeSyncFields=[1:varint,2:varint,3:varint,99:bytes]",
                         "resultPresent=true", "resultCode=0", "semanticReason=none", "magicCorrelation=notEvaluated"] {
            XCTAssertTrue(summary.contains(expected), expected)
        }
        for forbidden in [secret, "1700000000", "-14", "PRIVATE", body.map { String(format: "%02x", $0) }.joined()] {
            XCTAssertFalse(summary.contains(forbidden))
        }
        let many = (UInt64(200)...299).flatMap { Proto.scalar($0, 123456789) }
        let bounded = G2TimeSyncWire.responseDiagnostics(timeSyncReply(rootExtras: many))
        XCTAssertTrue(bounded.contains("moreFields"))
        XCTAssertLessThan(bounded.count, 1000)
        XCTAssertFalse(bounded.contains("123456789"))
    }

    func testTimeSyncDiagnosticsDistinguishMissingMalformedAndWrongWire() {
        typealias Proto = G2EvenHubCommands
        let root = Proto.scalar(1, 128) + Proto.scalar(2, 42)
        XCTAssertTrue(G2TimeSyncWire.responseDiagnostics(Data(root)).contains("timeSyncStatus=absent"))
        XCTAssertTrue(G2TimeSyncWire.responseDiagnostics(Data(root + Proto.scalar(128, 1))).contains("timeSyncStatus=wrongWire"))
        XCTAssertTrue(G2TimeSyncWire.responseDiagnostics(timeSyncReply(body: [0x08])).contains("timeSyncStatus=malformed"))
        XCTAssertTrue(G2TimeSyncWire.responseDiagnostics(Data([0x82, 0x08, 0x02, 0x00])).contains("rootFields=[malformed]"))
        XCTAssertTrue(G2TimeSyncWire.responseDiagnostics(timeSyncReply(body: Proto.bytes(2, []))).contains("semanticReason=timeSyncFieldWireMismatch.2"))
        XCTAssertTrue(G2TimeSyncWire.responseDiagnostics(timeSyncReply(rootExtras: Proto.bytes(3, []))).contains("semanticReason=rootOneofMismatch"))
        XCTAssertTrue(G2TimeSyncWire.responseDiagnostics(timeSyncReply(body: Proto.scalar(3, 7))).contains("semanticReason=resultRejected"))
    }

    func testBusinessWireFailsClosedForExcludedServicesAndDamagedFrames() {
        XCTAssertNil(G2BusinessService(rawValue: 0x91)) // R1 relay.
        XCTAssertNil(G2BusinessService(rawValue: 0x00)) // Consumer dashboard.
        XCTAssertNil(G2BusinessFrame.request(service: .evenHub, syncID: 1, payload: Data()))
        let payload = Data([0x08, 0x80, 0x01, 0x10, 0x2a])
        XCTAssertEqual(G2BusinessFrame.magicRandom(in: payload), 42)
        XCTAssertNil(G2BusinessFrame.magicRandom(in: Data([0x10, 0x2a, 0x10, 0x2b])))
        let request = G2BusinessFrame.request(service: .deviceSettings, syncID: 7, payload: payload)!
        XCTAssertEqual(request[7], 0x20) // Dart syncBoth default.
        var reply = request
        reply[1] = 0x12
        reply[7] = 0
        XCTAssertNotNil(G2BusinessFrame.parse(reply))
        reply[6] = 0x91
        XCTAssertNil(G2BusinessFrame.parse(reply))
        reply[6] = 0x80
        reply[reply.count - 1] ^= 1
        XCTAssertNil(G2BusinessFrame.parse(reply))
    }

    func testInvalidParametersBackoffUsesExactCoreBluetoothDomain() {
        let other = NSError(domain: "Other", code: 1)
        XCTAssertFalse(G2ReconnectBackoff.isInvalidParameters(other))
        let failure = NSError(domain: "CBErrorDomain", code: 1)
        XCTAssertTrue(G2ReconnectBackoff.isInvalidParameters(failure))
        var backoff = G2ReconnectBackoff()
        XCTAssertEqual((0..<7).map { _ in backoff.registerInvalidParameters() },
                       [1, 2, 4, 8, 16, 30, 30])
        backoff.businessConnected()
        XCTAssertEqual(backoff.registerInvalidParameters(), 1)
    }

    private func readContract() throws -> [String: Any] {
        let file = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol/contract.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
    }

    /// This builder uses the independently exercised EvenHub protobuf helpers
    /// solely to construct device-settings test input, never production paths.
    private func timeSyncReply(body: [UInt8] = [], magic: UInt64 = 42, rootExtras: [UInt8] = []) -> Data {
        typealias Proto = G2EvenHubCommands
        return Data(Proto.scalar(1, 128) + Proto.scalar(2, magic) + Proto.bytes(128, body) + rootExtras)
    }
}

private extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }

    init(hex: String) {
        self = Data(stride(from: 0, to: hex.count, by: 2).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            return UInt8(hex[start..<hex.index(start, offsetBy: 2)], radix: 16)!
        })
    }
}
