import Foundation
import XCTest
@testable import EvenDevicesSDK

/// All logical commands exercise the production native codec and controller;
/// no Flutter encoder or real BLE device is needed to advance the fake clock.
final class G2EvenHubCommandTests: XCTestCase {
    typealias Wire = G2EvenHubCommands
    private var pageArguments: [String: Any] {
        ["textObjects": [["containerId": 1, "containerName": "text", "width": 200, "height": 80, "content": "Hi", "isEventCapture": true]],
         "imageObjects": [["containerId": 2, "containerName": "image", "width": 80, "height": 40]],
         "listObjects": [["containerId": 3, "containerName": "list", "width": 100, "height": 80,
            "itemContainer": ["itemNames": ["A", "B"]]]],
         "menu": ["items": [["itemId": 4, "itemName": "Action"],
             ["itemId": 5, "itemName": "Parent", "subItems": [["itemId": 6, "itemName": "Child"]]]]]]
    }
    private func definition() throws -> Wire.PageDefinition { try Wire.page(pageArguments, create: true, session: 123) }
    private func requests(_ name: String, _ args: [String: Any] = [:]) throws -> [Wire.Request] {
        try Wire.requests(name, parameters: args, page: definition(), session: 123, count: 7, mapSession: 55)
    }

    func testAllTenCommandsHaveNativeWireMappingAndStrictAck() throws {
        let cases: [(String, [String: Any], UInt64, UInt64)] = [
            ("createPage", pageArguments, 0, 3), ("rebuildPage", pageArguments, 7, 7),
            ("updateText", ["containerId": 1, "containerName": "text", "content": "Updated"], 5, 9),
            ("updateImage", ["containerId": 2, "containerName": "image", "data": Data([1,2,3])], 3, 5),
            ("shutdownPage", [:], 9, 11), ("heartbeat", [:], 12, 14),
            ("audioControl", ["enabled": true], 15, 18), ("imuControl", ["enabled": true], 19, 22),
            ("menuStartupFailed", ["errorCode": 1], 18, 21),
            ("menuSubitemResponse", ["parentItemId": 5, "subItemId": 6], 23, 26)]
        for (name, args, command, branch) in cases {
            let request = try XCTUnwrap(requests(name, args).first)
            let fields = try XCTUnwrap(Wire.fields(Array(request.payload(magic: 42))))
            XCTAssertEqual(fields[1]?.number, command, name)
            XCTAssertEqual(Wire.oneof(fields), branch, name)
            if request.response != nil {
                let ack = acknowledgement(request, magic: 42)
                XCTAssertTrue(request.accepts(ack, magic: 42), name)
                XCTAssertFalse(request.accepts(ack, magic: 43), name)
                XCTAssertFalse(request.accepts(Data([8, UInt8(request.response!), 16, 42]), magic: 42), name)
            }
        }
    }

    func testHeartbeatMatchesDartEmptyBodyAndAcceptsOmittedOrNonEchoedCounter() throws {
        let request = try requests("heartbeat")[0] // Local metric is seven; wire Cnt remains omitted.
        XCTAssertTrue(request.body.isEmpty)
        for count: UInt64? in [nil, 0, 99, UInt64(UInt32.max)] {
            let body = (count.map { Wire.scalar(1, $0, explicit: true) } ?? []) + Wire.scalar(2, 12)
            XCTAssertTrue(request.accepts(envelope(13, magic: 42, branch: 15, body: body), magic: 42))
        }
    }

    /// Enterprise firmware captured on the device uses the request command ID
    /// with the real response branch. Both dialects still require a valid ACK.
    func testObservedEnterpriseHeartbeatAckAcceptsEchoedCommandOnlyForResponseBody() throws {
        let request = try requests("heartbeat")[0]
        // Reconstructed equivalent of the logged IDs, wire types and values;
        // the diagnostic deliberately does not record original payload bytes.
        let observed = Data([0x08, 0x0c, 0x10, 0x02, 0x7a, 0x02, 0x10, 0x0c])
        XCTAssertTrue(request.accepts(observed, magic: 2))
        XCTAssertNil(request.rejectionReason(observed, magic: 2))
        for command: UInt64 in [12, 13] {
            for count: UInt64? in [nil, 0, 999, UInt64(UInt32.max)] {
                let body = (count.map { Wire.scalar(1, $0, explicit: true) } ?? []) + Wire.scalar(2, 12)
                XCTAssertTrue(request.accepts(envelope(command, magic: 42, branch: 15, body: body), magic: 42))
            }
            XCTAssertFalse(request.accepts(envelope(command, magic: 41, branch: 15, body: Wire.scalar(2, 12)), magic: 42))
            for body in [[], Wire.scalar(2, 11), Wire.bytes(2, []),
                Wire.scalar(2, 12) + Wire.scalar(3, 1), Wire.scalar(2, 12) + Wire.scalar(2, 12)] {
                XCTAssertFalse(request.accepts(envelope(command, magic: 42, branch: 15, body: body), magic: 42))
            }
            for branch: UInt64 in [3, 14, 16, 27] {
                XCTAssertFalse(request.accepts(envelope(command, magic: 42, branch: branch, body: Wire.scalar(2, 12)), magic: 42))
            }
        }
        XCTAssertFalse(request.accepts(request.payload(magic: 42), magic: 42))
        XCTAssertFalse(request.accepts(observed + Data(Wire.bytes(14, [])), magic: 2))
        XCTAssertFalse(request.accepts(observed + Data(Wire.scalar(1, 12)), magic: 2))
    }

    /// The firmware dialect is heartbeat-specific, never a general permission
    /// to accept the outgoing command ID as a successful business response.
    func testOtherCommandsAndForgedHeartbeatDescriptorsCannotEchoRequestCommand() throws {
        let cases: [(String, [String: Any])] = [
            ("createPage", pageArguments), ("rebuildPage", pageArguments),
            ("updateText", ["containerId": 1, "containerName": "text", "content": "x"]),
            ("shutdownPage", [:]), ("audioControl", ["enabled": true]),
            ("imuControl", ["enabled": true])]
        for (name, args) in cases {
            let request = try requests(name, args)[0]
            let ack = try XCTUnwrap(Wire.fields(Array(acknowledgement(request, magic: 42))))
            let body = try XCTUnwrap(ack[request.responseBranch!]?.data)
            XCTAssertFalse(request.accepts(envelope(request.command, magic: 42, branch: request.responseBranch!, body: body), magic: 42), name)
        }
        for request in [
            Wire.Request(name: "heartbeat", command: 11, branch: 14, body: [], response: 13, responseBranch: 15, expected: [2: 12]),
            Wire.Request(name: "heartbeat", command: 12, branch: 13, body: [], response: 13, responseBranch: 15, expected: [2: 12]),
            Wire.Request(name: "heartbeat", command: 12, branch: 14, body: [], response: 13, responseBranch: 15, expected: [2: 11])
        ] {
            XCTAssertFalse(request.accepts(envelope(12, magic: 42, branch: 15, body: Wire.scalar(2, 12)), magic: 42))
        }
    }

    func testHeartbeatMetricsRemainLocalAndMonotonicWithNonEchoAndOmittedCount() throws {
        let h = GeneralHarness(); try h.open(pageArguments); h.reply(0)
        h.time = 5; h.timers[0].fire()
        XCTAssertTrue(h.sent[1].request.body.isEmpty)
        // The observed command-echo dialect renews the same native lease and
        // increments the same local metric as the standard command-13 reply.
        h.sent[1].complete(.success(envelope(12, magic: h.sent[1].magic, branch: 15,
            body: Wire.scalar(1, 999) + Wire.scalar(2, 12))))
        XCTAssertEqual(h.events.last?.heartbeatCount, 1)
        h.time = 10; h.timers[1].fire()
        h.sent[2].complete(.success(envelope(13, magic: h.sent[2].magic, branch: 15, body: Wire.scalar(2, 12))))
        XCTAssertEqual(h.events.last?.heartbeatCount, 2)
        XCTAssertTrue(h.controller.hasPage)
    }

    func testHeartbeatStillRequiresExactAckEnvelopeResultAndUint32CounterWire() throws {
        let request = try requests("heartbeat")[0]
        let valid = Wire.scalar(2, 12)
        XCTAssertFalse(request.accepts(envelope(13, magic: 41, branch: 15, body: valid), magic: 42))
        XCTAssertFalse(request.accepts(envelope(11, magic: 42, branch: 15, body: valid), magic: 42))
        XCTAssertFalse(request.accepts(envelope(13, magic: 42, branch: 14, body: valid), magic: 42))
        XCTAssertFalse(request.accepts(envelope(13, magic: nil, branch: 15, body: valid), magic: 42))
        for body in [Wire.scalar(2, 11), [], Wire.bytes(1, []) + valid,
            Wire.scalar(1, UInt64(UInt32.max) + 1) + valid,
            Wire.scalar(1, 1) + Wire.scalar(1, 2) + valid,
            Wire.scalar(1, 1) + valid + Wire.scalar(3, 1)] {
            XCTAssertFalse(request.accepts(envelope(13, magic: 42, branch: 15, body: body), magic: 42))
        }
    }

    func testHeartbeatDiagnosticsNameTheExactFailedValidationCheck() throws {
        let request = try requests("heartbeat")[0], body = Wire.scalar(2, 12)
        let cases: [(Data, String)] = [
            (envelope(13, magic: 42, branch: 15, body: body) + Data(Wire.scalar(1, 13)), "rootMalformed"),
            (envelope(11, magic: 42, branch: 15, body: body), "rootCommandMismatch"),
            (envelope(13, magic: 41, branch: 15, body: body), "rootMagicMismatch"),
            (envelope(13, magic: 42, branch: 15, body: body) + Data(Wire.bytes(3, [])), "rootOneofMismatch"),
            (Data(Wire.scalar(1, 13) + Wire.scalar(2, 42) + Wire.scalar(15, 1)), "responseBranchWireMismatch"),
            (envelope(13, magic: 42, branch: 15, body: body + body), "responseBranchMalformed"),
            (envelope(13, magic: 42, branch: 15, body: body + Wire.scalar(3, 1)), "responseUnknownFields"),
            (envelope(13, magic: 42, branch: 15, body: body + Wire.bytes(1, [])), "heartbeatCounterInvalid"),
            (envelope(13, magic: 42, branch: 15, body: Wire.bytes(2, [])), "responseFieldWireMismatch.2"),
            (envelope(13, magic: 42, branch: 15, body: []), "responseFieldValueMismatch.2")]
        for (data, reason) in cases {
            XCTAssertEqual(request.rejectionReason(data, magic: 42), reason)
            XCTAssertFalse(request.accepts(data, magic: 42))
        }
        XCTAssertNil(request.rejectionReason(envelope(13, magic: 42, branch: 15, body: body), magic: 42))
    }

    func testProtocolFieldDiagnosticsExposeOnlyBoundedIdsAndWireTypes() {
        let secret = "DO-NOT-LOG-CONTENT"
        let fields = Wire.scalar(1, 13) + Wire.scalar(2, 42) + Wire.bytes(15, Array(secret.utf8))
        XCTAssertEqual(Wire.fieldMetadata(fields), "1:varint,2:varint,15:bytes")
        XCTAssertFalse(Wire.fieldMetadata(fields).contains(secret))
        XCTAssertEqual(Wire.fieldMetadata(Wire.scalar(2, 1) + Wire.scalar(2, 2)), "2:varint,2:varint")
        XCTAssertEqual(Wire.fieldMetadata([0x7a, 0x02, 0x01]), "15:bytes,truncated")
        XCTAssertEqual(Wire.fieldMetadata([]), "empty")
        let bounded = Wire.fieldMetadata(Array(repeating: Wire.scalar(1, 1), count: 33).flatMap { $0 })
        XCTAssertTrue(bounded.hasSuffix("moreFields"))
        XCTAssertEqual(bounded.components(separatedBy: ",").count, 33)
    }

    func testPaddedSinglePacketUsesDeclaredPayloadAndCrcBoundary() throws {
        let payload = envelope(13, magic: 42, branch: 15, body: Wire.scalar(2, 12))
        var packet = try XCTUnwrap(G2BusinessFrame.request(service: .evenHub, syncID: 7, payload: payload))
        packet[1] = 0x12; packet[7] = 0
        let padded = packet + Data([0, 0, 255, 0xaa, 0x12])
        XCTAssertEqual(G2BusinessFrame.parse(padded)?.payload, payload)
        XCTAssertEqual(G2BusinessAssembler().append(padded, now: 0)?.payload, payload)
        XCTAssertNil(G2BusinessFrame.parse(packet.dropLast()))
        var badCrc = packet; badCrc[badCrc.count - 1] ^= 1
        XCTAssertNil(G2BusinessFrame.parse(badCrc + packet.suffix(2)))
        var shortDeclared = packet; shortDeclared[3] = 1
        XCTAssertNil(G2BusinessFrame.parse(shortDeclared))
    }

    func testPaddedMultiPacketAssemblyUsesEachDeclaredLength() throws {
        let payload = Data((0..<8200).map { UInt8($0 & 255) })
        let outbound = try XCTUnwrap(G2BusinessFrame.requests(service: .evenHub, syncID: 4,
            payload: payload, maximumWriteLength: 147))
        let packets = outbound.map { original -> Data in
            var packet = original; packet[1] = 0x12; packet[7] = 1
            return packet
        }
        let assembler = G2BusinessAssembler()
        for p in packets.dropLast() { XCTAssertNil(assembler.append(p + Data(repeating: 0xff, count: 11), now: 0)) }
        XCTAssertEqual(assembler.append(packets.last! + Data(repeating: 0, count: 13), now: 1)?.payload, payload)
        let truncated = G2BusinessAssembler()
        for p in packets.dropLast() { _ = truncated.append(p, now: 0) }
        XCTAssertNil(truncated.append(packets.last!.dropLast(), now: 0))
        let corrupted = G2BusinessAssembler()
        for p in packets.dropLast() { _ = corrupted.append(p + Data([0]), now: 0) }
        var bad = packets.last!; bad[bad.count - 1] ^= 1
        XCTAssertNil(corrupted.append(bad + packets.last!.suffix(2), now: 0))
    }

    func testProductionDemoCreateMatchesCanonicalDartVector() throws {
        let p: [String: Any] = ["textObjects": [["containerId": 1, "containerName": "native-text", "xPosition": 24,
            "yPosition": 24, "width": 528, "height": 240, "paddingLength": 8, "isEventCapture": true, "content": "Hello EvenHub"]]]
        let request = try Wire.requests("createPage", parameters: p, page: nil, session: 123, count: 0, mapSession: 0)[0]
        XCTAssertEqual(request.payload(magic: 42), Data([0x08, 0x00, 0x10, 0x2a, 0x1a, 0x35, 0x08, 0x01, 0x1a, 0x2c,
            0x08, 0x18, 0x10, 0x18, 0x18, 0x90, 0x04, 0x20, 0xf0, 0x01, 0x40, 0x08, 0x48, 0x01, 0x52, 0x0b,
            0x6e, 0x61, 0x74, 0x69, 0x76, 0x65, 0x2d, 0x74, 0x65, 0x78, 0x74, 0x58, 0x01, 0x62, 0x0d,
            0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0x45, 0x76, 0x65, 0x6e, 0x48, 0x75, 0x62, 0x28, 0x99, 0x59, 0x38, 0x7b]))
    }

    func testLogicalValidationRejectsNativeMetadataWrongTypesAndCrossContainerUpdates() throws {
        for p: [String: Any] in [["sessionId": 1], ["containerTotalNum": 1], ["widgetId": true], ["widgetId": 1.0]] {
            XCTAssertThrowsError(try requests("createPage", p))
        }
        XCTAssertThrowsError(try requests("unknown", [:]))
        XCTAssertThrowsError(try requests("heartbeat", ["count": 9]))
        XCTAssertThrowsError(try requests("audioControl", ["enabled": 1]))
        XCTAssertThrowsError(try requests("imuControl", ["enabled": true, "reportFrequency": 99]))
        XCTAssertThrowsError(try requests("updateText", ["containerId": 2, "containerName": "image", "content": "x"]))
        XCTAssertThrowsError(try requests("updateText", ["containerId": 1, "containerName": "wrong", "content": "x"]))
        XCTAssertThrowsError(try requests("updateText", ["containerId": 1, "containerName": "text", "content": String(repeating: "中", count: 667)]))
        XCTAssertThrowsError(try requests("updateImage", ["containerId": 2, "containerName": "image", "data": Data(), "mapSessionId": 1]))
        XCTAssertThrowsError(try requests("menuSubitemResponse", ["parentItemId": 5, "subItemId": 99]))
        var p = pageArguments
        p["imageObjects"] = [["containerId": 1, "containerName": "duplicate", "width": 80, "height": 40]]
        XCTAssertThrowsError(try requests("createPage", p))
        p = pageArguments
        p["textObjects"] = [["containerId": 1, "containerName": "text", "xPosition": 500, "width": 200, "height": 80]]
        XCTAssertThrowsError(try requests("createPage", p))
    }

    func testAudioAndImuZeroNeedAnActualPresentResponseBranch() throws {
        for (name, command, branch) in [("audioControl", UInt64(16), UInt64(19)), ("imuControl", 20, 23)] {
            let r = try requests(name, ["enabled": false])[0]
            XCTAssertTrue(r.accepts(envelope(command, magic: 4, branch: branch, body: []), magic: 4))
            XCTAssertFalse(r.accepts(envelope(command, magic: 4, branch: branch, body: Wire.scalar(1, 1)), magic: 4))
            XCTAssertFalse(r.accepts(envelope(command, magic: 4, branch: branch, body: Wire.bytes(1, [])), magic: 4))
            XCTAssertFalse(r.accepts(envelope(command, magic: 4, branch: branch, body: Wire.bytes(2, [])), magic: 4))
        }
    }

    func testWholeImagesHaveNative4096ChunksAndEveryEchoedMetadataMustMatch() throws {
        let list = try requests("updateImage", ["containerId": 2, "containerName": "image", "data": Data(repeating: 7, count: 8200), "compressMode": 2])
        XCTAssertEqual(list.count, 3)
        for (index, r) in list.enumerated() {
            let fields = try XCTUnwrap(Wire.fields(r.body))
            XCTAssertEqual(fields[6]?.number ?? 0, UInt64(index))
            XCTAssertEqual(fields[8]?.data?.count, index == 2 ? 8 : 4096)
            XCTAssertTrue(r.accepts(acknowledgement(r, magic: 42), magic: 42))
            for field in [UInt64(1), 3, 4, 5, 6, 7, 8] {
                var expected = r.expected; expected[field] = (expected[field] ?? 0) + 1
                XCTAssertFalse(r.accepts(acknowledgement(r, magic: 42, overrides: expected), magic: 42))
            }
            XCTAssertFalse(r.accepts(acknowledgement(r, magic: 42, name: "old"), magic: 42))
        }
        XCTAssertEqual(try requests("updateImage", ["containerId": 2, "containerName": "image", "data": Data(repeating: 1, count: 1048576)]).count, 256)
        XCTAssertThrowsError(try requests("updateImage", ["containerId": 2, "containerName": "image", "data": Data(repeating: 1, count: 1048577)]))
    }

    func testAllEventBranchesReturnSemanticFieldsAndRejectMalformedDefaults() throws {
        let page = try definition()
        func decode(_ cmd: UInt64, _ branch: UInt64, _ body: [UInt8]) -> G2EvenHubEvents.Input? {
            G2EvenHubEvents.decode(envelope(cmd, magic: nil, branch: branch, body: body), definition: page, session: 123)
        }
        let click = Wire.bytes(2, Wire.scalar(1, 1))
        XCTAssertEqual(decode(2, 13, click)?.name, "click")
        XCTAssertNil(decode(2, 13, []))
        XCTAssertNil(decode(2, 13, Wire.bytes(2, Wire.scalar(1, 1) + Wire.scalar(3, 99))))
        XCTAssertNil(decode(2, 13, Wire.bytes(2, Wire.scalar(1, 2))))
        XCTAssertNil(decode(2, 13, Wire.bytes(2, Wire.scalar(1, 1) + Wire.bytes(2, [255]))))
        XCTAssertEqual(decode(2, 13, Wire.bytes(1, Wire.scalar(1, 3) + Wire.bytes(3, [65]) + Wire.scalar(4, 1)))?.details["selectedItemName"] as? String, "A")
        let sys = Wire.scalar(1, 7) + Wire.scalar(5, 11417) + Wire.scalar(6, 123)
        XCTAssertTrue(decode(2, 13, Wire.bytes(3, sys))?.terminal == true)
        XCTAssertNil(decode(2, 13, Wire.bytes(3, Wire.scalar(1, 7))))
        XCTAssertEqual(decode(11, 16, Wire.scalar(1, 1) + Wire.scalar(3, 8) + Wire.scalar(4, 9))?.name, "privateEvent")
        XCTAssertEqual(decode(14, 17, [])?.name, "timeoutPageEnter")
        XCTAssertEqual(decode(14, 17, Wire.scalar(1, 1))?.name, "timeoutPageExit")
        XCTAssertNil(decode(14, 17, Wire.scalar(1, 2)))
        XCTAssertEqual(decode(17, 20, Wire.scalar(1, 11417))?.name, "menuStartup")
        XCTAssertEqual(decode(21, 24, Wire.scalar(1, 4))?.name, "menuItemClick")
        XCTAssertNil(decode(21, 24, Wire.scalar(1, 5)))
        XCTAssertEqual(decode(22, 25, Wire.scalar(1, 5) + Wire.scalar(2, 6) + Wire.scalar(3, 1))?.name, "menuSubitemSwitch")
        XCTAssertNil(decode(22, 25, Wire.scalar(1, 5) + Wire.scalar(2, 6) + Wire.scalar(3, 2)))
        XCTAssertNil(decode(99, 13, click))
        XCTAssertNil(decode(2, 13, click + click))
        XCTAssertNil(decode(2, 13, Wire.bytes(2, Wire.scalar(1, 1) + Wire.bytes(2, []))))
        XCTAssertNil(decode(2, 13, Wire.bytes(2, Wire.scalar(1, 1) + Wire.scalar(9, 1))))
        XCTAssertNil(decode(14, 17, Wire.bytes(3, [])))
        let imu = [UInt8(13), 0, 0, 128, 63] // x float32=1
        XCTAssertEqual(decode(2, 13, Wire.bytes(3, Wire.scalar(1, 8) + Wire.bytes(3, imu)))?.details["x"] as? Double, 1)
        XCTAssertNil(decode(2, 13, Wire.bytes(3, Wire.scalar(1, 8) + Wire.bytes(3, [13,0,0,128,127]))))
    }

    func testLateAttWriteDebtCannotAdvanceSuccessorAfterLogicalTimeout() {
        final class Command {}
        let a = Command(), b = Command(), ledger = G2BusinessWriteLedger<Command>()
        var active: Command? = a
        ledger.begin(a)
        active = nil // Application timeout releases only the logical command.
        XCTAssertTrue(ledger.hasPending) // B admission must remain busy.
        let late = ledger.consume()
        XCTAssertTrue(late === a)
        XCTAssertFalse(late === active)
        XCTAssertFalse(ledger.hasPending)
        active = b
        ledger.begin(b)
        XCTAssertTrue(ledger.consume() === active)
        XCTAssertNil(ledger.consume()) // A duplicate cannot consume B later.
    }

    func testNativeFragmentAssemblyBoundsCrcOrderingExpiryAndStreamCount() throws {
        let payload = Data((0..<8200).map { UInt8($0 & 255) })
        let requests = try XCTUnwrap(G2BusinessFrame.requests(service: .evenHub, syncID: 4, payload: payload, maximumWriteLength: 147))
        XCTAssertGreaterThan(requests.count, 1)
        XCTAssertTrue(requests.allSatisfy { $0.count <= 147 })
        let packets = requests.map { data -> Data in var p = data; p[1] = 0x12; p[7] = 0; return p }
        let assembler = G2BusinessAssembler()
        for p in packets.dropLast() { XCTAssertNil(assembler.append(p, now: 0)) }
        XCTAssertEqual(assembler.append(packets.last!, now: 1)?.payload, payload)
        let gap = G2BusinessAssembler()
        XCTAssertNil(gap.append(packets[0], now: 0)); XCTAssertNil(gap.append(packets[2], now: 0))
        for p in packets.dropFirst(3) { XCTAssertNil(gap.append(p, now: 0)) }
        let expired = G2BusinessAssembler()
        _ = expired.append(packets[0], now: 0)
        for p in packets.dropFirst() { XCTAssertNil(expired.append(p, now: 3.1)) }
        let corrupted = G2BusinessAssembler()
        for p in packets.dropLast() { _ = corrupted.append(p, now: 0) }
        var bad = packets.last!; bad[bad.count - 1] ^= 1
        XCTAssertNil(corrupted.append(bad, now: 0))
        XCTAssertNil(G2BusinessFrame.requests(service: .evenHub, syncID: 4, payload: Data(repeating: 1, count: 32769), maximumWriteLength: 147))
        XCTAssertNil(G2BusinessFrame.requests(service: .evenHub, syncID: 4, payload: payload, maximumWriteLength: 11))
        let full = G2BusinessAssembler()
        for id in 0..<5 { var p = packets[0]; p[2] = UInt8(id); XCTAssertNil(full.append(p, now: 0)) }
        for p in packets.dropFirst() { var p = p; p[2] = 4; XCTAssertNil(full.append(p, now: 0)) }
    }

    func testFrequentUpdatesKeepOriginalLeaseDeadlineAndOldTimerCannotFire() throws {
        let h = GeneralHarness(); try h.open(pageArguments); h.reply(0)
        XCTAssertEqual(h.timers[0].delay, 5)
        h.time = 4
        try h.execute("updateText", ["containerId": 1, "containerName": "text", "content": "one"]); h.reply(1)
        XCTAssertEqual(h.timers[1].delay, 1)
        let before = h.sent.count; h.timers[0].fire(); XCTAssertEqual(h.sent.count, before)
        h.time = 5; h.timers[1].fire(); XCTAssertEqual(h.sent.last?.request.name, "heartbeat")
        XCTAssertEqual(h.events.last?.heartbeatCount, 0)
        h.reply(2); XCTAssertEqual(h.events.last?.heartbeatCount, 1)
    }

    func testImageYieldsToHeartbeatThenResumesAndQueuedCloseStopsRemainingChunks() throws {
        let h = GeneralHarness(); try h.open(pageArguments); h.reply(0)
        try h.execute("updateImage", ["containerId": 2, "containerName": "image", "data": Data(repeating: 3, count: 12288)])
        h.time = 5; h.reply(1)
        XCTAssertEqual(h.sent[2].request.name, "heartbeat")
        h.reply(2)
        XCTAssertEqual(h.sent[3].request.name, "updateImage")
        let before = h.sent.count; h.reply(1); XCTAssertEqual(h.sent.count, before) // stale image ACK
        try h.execute("shutdownPage")
        h.reply(3)
        XCTAssertEqual(h.sent.last?.request.name, "shutdownPage")
        XCTAssertEqual(h.completions.last?.0, "updateImage")
        if case .failure(.cancelled)? = h.completions.last?.1 {} else { XCTFail("Image should cancel behind current ACK") }
        h.reply(4); XCTAssertEqual(h.events.last?.phase, "closed")
    }

    func testImageRetriesOnceWithFreshNativeMapAndRejectsLateAck() throws {
        let h = GeneralHarness(); try h.open(pageArguments); h.reply(0)
        try h.execute("updateImage", ["containerId": 2, "containerName": "image", "data": Data(repeating: 3, count: 5000)])
        let old = h.sent[1].request.expected[3]
        h.sent[1].complete(.failure(.timedOut))
        XCTAssertNotEqual(h.sent[2].request.expected[3], old)
        h.reply(1); XCTAssertEqual(h.sent.count, 3)
        h.reply(2); h.reply(3)
        let result = try XCTUnwrap(h.completions.last).1.get()
        XCTAssertEqual(result["retries"] as? Int, 1)
        XCTAssertEqual(result["imageFragments"] as? Int, 2)
    }

    func testConfirmationShutdownRetainsLeaseUntilScopedExit() throws {
        let h = GeneralHarness(); try h.open(pageArguments); h.reply(0)
        try h.execute("shutdownPage", ["exitMode": 1]); h.reply(1)
        XCTAssertEqual(h.events.last?.phase, "active")
        XCTAssertEqual(h.events.last?.details["awaitingExit"] as? Bool, true)
        XCTAssertTrue(h.controller.hasPage)
        XCTAssertEqual(try h.completions.last?.1.get()["awaitingExit"] as? Bool, true)
        XCTAssertEqual(h.timers.count, 2)
        let session = try XCTUnwrap(h.events.first?.details["pageSessionId"] as? UInt32)
        let exit = envelope(2, magic: nil, branch: 13, body: Wire.bytes(3,
            Wire.scalar(1, 7) + Wire.scalar(5, 11417) + Wire.scalar(6, UInt64(session))))
        h.controller.receive(exit, owner: h.owner)
        XCTAssertEqual(h.events.last?.phase, "closed")
        XCTAssertFalse(h.controller.hasPage)
    }

    func testPendingHeartbeatClosingAndFailureExposeOnlyConfirmedCount() throws {
        let h = GeneralHarness(); try h.open(pageArguments); h.reply(0)
        h.time = 5; h.timers[0].fire()
        try h.execute("shutdownPage")
        XCTAssertEqual(h.events.last?.phase, "closing")
        XCTAssertEqual(h.events.last?.heartbeatCount, 0)
        h.sent[1].complete(.failure(.timedOut))
        XCTAssertEqual(h.events.last?.phase, "failed")
        XCTAssertEqual(h.events.last?.heartbeatCount, 0)
        XCTAssertFalse(h.controller.hasPage)
        let before = h.events.count
        h.reply(1); XCTAssertEqual(h.events.count, before)
    }

    func testUnpagedStartupAndFailureReplyUseReadyOwnersWithoutPageOrHeartbeat() throws {
        let h = GeneralHarness()
        h.controller.setReadyOwners([h.owner, h.left])
        let startup = envelope(17, magic: nil, branch: 20, body: Wire.scalar(1, 11417))
        h.controller.receive(startup, owner: h.left)
        XCTAssertEqual(h.events.last?.event, "menuStartup")
        XCTAssertEqual(h.events.last?.side, "left")
        let startupOperation = try XCTUnwrap(h.events.last?.operation)
        XCTAssertGreaterThan(startupOperation, 0)
        XCTAssertNil(h.events.last?.details["pageSessionId"])
        XCTAssertFalse(h.controller.hasPage)
        try h.execute("menuStartupFailed", ["errorCode": 1, "errorString": "Unavailable"])
        XCTAssertTrue(h.sent[0].oneWay)
        XCTAssertTrue(h.completions.isEmpty)
        XCTAssertThrowsError(try h.execute("createPage", pageArguments))
        h.sent[0].complete(.success(Data()))
        let result = try XCTUnwrap(h.completions.last).1.get()
        XCTAssertEqual(result["acknowledged"] as? Bool, false)
        XCTAssertEqual(result["submitted"] as? Bool, true)
        XCTAssertNil(result["pageSessionId"])
        XCTAssertTrue(h.timers.isEmpty)
        XCTAssertFalse(h.controller.hasPage)
        try h.open(pageArguments)
        XCTAssertGreaterThan(try XCTUnwrap(h.events.last?.operation), startupOperation)
    }

    func testUnpagedTimeoutDiagnosticsAreStrictAndNeverCreateLease() throws {
        let h = GeneralHarness()
        h.controller.setReadyOwners([h.owner, h.left])
        h.controller.receive(envelope(14, magic: nil, branch: 17, body: []), owner: h.owner)
        XCTAssertEqual(h.events.last?.event, "timeoutPageEnter")
        let first = try XCTUnwrap(h.events.last?.operation)
        h.controller.receive(envelope(14, magic: nil, branch: 17, body: Wire.scalar(1, 1) + Wire.scalar(2, 7)), owner: h.owner)
        XCTAssertEqual(h.events.last?.event, "timeoutPageExit")
        XCTAssertGreaterThan(try XCTUnwrap(h.events.last?.operation), first)
        let count = h.events.count
        for (command, branch, body) in [(UInt64(14), UInt64(17), Wire.scalar(1, 2)),
            (14, 17, Wire.bytes(2, [])), (17, 20, []), (17, 20, Wire.scalar(1, 11417) + Wire.scalar(3, 1))] {
            h.controller.receive(envelope(command, magic: nil, branch: branch, body: body), owner: h.owner)
        }
        XCTAssertEqual(h.events.count, count)
        XCTAssertTrue(h.timers.isEmpty)
        XCTAssertFalse(h.controller.hasPage)
    }

    func testUnpagedDisconnectCancelsWriteAndRejectsRetiredPhysicalOwner() throws {
        let h = GeneralHarness()
        h.controller.setReadyOwners([h.owner, h.left])
        try h.execute("menuStartupFailed", ["errorCode": 1])
        h.controller.invalidate()
        if case .failure(.disconnected)? = h.completions.last?.1 {} else { XCTFail("Disconnect must cancel unpaged completion") }
        let completions = h.completions.count
        h.sent[0].complete(.success(Data()))
        XCTAssertEqual(h.completions.count, completions)
        let startup = envelope(17, magic: nil, branch: 20, body: Wire.scalar(1, 11417))
        h.controller.receive(startup, owner: h.owner)
        XCTAssertTrue(h.events.isEmpty)
        XCTAssertThrowsError(try h.execute("menuStartupFailed", ["errorCode": 1]))
        let fresh = G2EvenHubSession.Owner(namespace: h.owner.namespace, serialNumber: h.owner.serialNumber,
            endpointID: h.owner.endpointID, sessionGeneration: 2, attemptGeneration: 4)
        let freshLeft = G2EvenHubSession.Owner(namespace: h.left.namespace, serialNumber: h.left.serialNumber,
            endpointID: h.left.endpointID, sessionGeneration: 2, attemptGeneration: 5, side: "left")
        h.controller.setReadyOwners([fresh, freshLeft])
        h.controller.receive(startup, owner: h.owner)
        XCTAssertTrue(h.events.isEmpty)
        h.controller.receive(startup, owner: fresh)
        XCTAssertEqual(h.events.last?.sessionGeneration, 2)
        XCTAssertTrue(h.timers.isEmpty)
    }

    func testOneWayCompletesOnNativeWriteAndCurrentLeftSourceIsPreserved() throws {
        let h = GeneralHarness(); try h.open(pageArguments); h.reply(0)
        try h.execute("menuStartupFailed", ["errorCode": 1])
        XCTAssertTrue(h.sent[1].oneWay)
        XCTAssertEqual(h.completions.count, 1) // CREATE only, no synthetic one-way ACK
        h.sent[1].complete(.success(Data()))
        XCTAssertEqual(try h.completions.last?.1.get()["acknowledged"] as? Bool, false)
        let click = envelope(2, magic: nil, branch: 13, body: Wire.bytes(2, Wire.scalar(1, 1)))
        h.controller.receive(click, owner: h.left)
        XCTAssertEqual(h.events.last?.side, "left")
        XCTAssertEqual(h.events.last?.endpointID, h.left.endpointID)
        var stale = h.left; stale = .init(namespace: stale.namespace, serialNumber: stale.serialNumber, endpointID: stale.endpointID,
            sessionGeneration: stale.sessionGeneration, attemptGeneration: stale.attemptGeneration + 1, side: "left")
        let count = h.events.count; h.controller.receive(click, owner: stale); XCTAssertEqual(h.events.count, count)
    }
}

private func envelope(_ command: UInt64, magic: UInt8?, branch: UInt64, body: [UInt8]) -> Data {
    Data(G2EvenHubCommands.scalar(1, command, explicit: true) + (magic.map { G2EvenHubCommands.scalar(2, UInt64($0)) } ?? []) + G2EvenHubCommands.bytes(branch, body))
}
private func acknowledgement(_ request: G2EvenHubCommands.Request, magic: UInt8,
                             overrides: [UInt64: UInt64]? = nil, name: String? = nil) -> Data {
    var fields: [UInt8] = []
    for (key, value) in (overrides ?? request.expected).sorted(by: { $0.key < $1.key }) { fields += G2EvenHubCommands.scalar(key, value) }
    if let name { fields += G2EvenHubCommands.bytes(2, Array(name.utf8)) }
    return envelope(request.response!, magic: magic, branch: request.responseBranch!, body: fields)
}

private final class GeneralHarness {
    struct Sent {
        let request: G2EvenHubCommands.Request
        let magic: UInt8
        let oneWay: Bool
        let complete: (Result<Data, G2BusinessCommandError>) -> Void
    }
    struct Scheduled { let delay: TimeInterval; let fire: () -> Void }
    let owner = G2EvenHubSession.Owner(namespace: "test", serialNumber: "S200LABJ270010", endpointID: UUID(), sessionGeneration: 1, attemptGeneration: 2)
    lazy var left = G2EvenHubSession.Owner(namespace: owner.namespace, serialNumber: owner.serialNumber, endpointID: UUID(), sessionGeneration: 1, attemptGeneration: 3, side: "left")
    var time: TimeInterval = 0
    var definition: G2EvenHubCommands.PageDefinition?
    var sent: [Sent] = []
    var timers: [Scheduled] = []
    var completions: [(String, Result<[String: Any], G2EvenHubError>)] = []
    var events: [G2EvenHubEvent] = []
    lazy var controller = G2EvenHubSession(send: { [unowned self] _, data, magic, complete in self.record(data, magic, false, complete) },
        oneWay: { [unowned self] _, data, magic, complete in self.record(data, magic, true, complete) }, now: { [unowned self] in self.time },
        schedule: { [unowned self] delay, fire in self.timers.append(Scheduled(delay: delay, fire: fire)); return {} },
        onEvent: { [unowned self] in self.events.append($0) })
    func open(_ args: [String: Any]) throws {
        definition = try G2EvenHubCommands.page(args, create: true, session: 1)
        try execute("createPage", args)
    }
    func execute(_ name: String, _ args: [String: Any] = [:]) throws {
        try controller.execute(name, parameters: args, owner: owner, sources: [owner, left]) { [unowned self] in self.completions.append((name, $0)) }
    }
    func record(_ data: Data, _ magic: UInt8, _ oneWay: Bool, _ complete: @escaping (Result<Data, G2BusinessCommandError>) -> Void) {
        let root = G2EvenHubCommands.fields(Array(data))!, cmd = root[1]!.number!, branch = G2EvenHubCommands.oneof(root)!, body = root[branch]!.data!
        let fields = G2EvenHubCommands.fields(body) ?? [:]
        let descriptor: (String, UInt64?, UInt64?, [UInt64: UInt64])
        switch cmd {
        case 0: descriptor = ("createPage", 1, 4, [1: 0])
        case 3:
            var expected = fields.compactMapValues(\.number); expected[8] = 4
            descriptor = ("updateImage", 4, 6, expected)
        case 5: descriptor = ("updateText", 6, 10, [1: 8])
        case 9: descriptor = ("shutdownPage", 10, 12, [1: 10])
        case 12: descriptor = ("heartbeat", 13, 15, [1: fields[1]?.number ?? 0, 2: 12])
        default: descriptor = ("menuStartupFailed", nil, nil, [:])
        }
        let r = G2EvenHubCommands.Request(name: descriptor.0, command: cmd, branch: branch, body: body, response: descriptor.1,
            responseBranch: descriptor.2, expected: descriptor.3)
        sent.append(Sent(request: r, magic: magic, oneWay: oneWay, complete: complete))
    }
    func reply(_ index: Int) { let r = sent[index]; r.complete(.success(acknowledgement(r.request, magic: r.magic))) }
}
