import Foundation
import XCTest
@testable import EvenDevicesSDK

/// Wire parity and hostile page timelines are verified without CoreBluetooth.
final class G2EvenHubTests: XCTestCase {
    func testAudioLeaseRequiresAckAndExactOwnerAndStopsBeforeCloseAck() throws {
        let h = Harness(), packet = Data(repeating: 0, count: 200)
        try h.controller.open(text: "Audio", owner: h.owner)
        h.reply(0)
        h.controller.receiveAudio(packet, owner: h.owner)
        XCTAssertFalse(h.events.contains { $0.event == "audioEvent" })
        try h.controller.execute("audioControl", parameters: ["enabled": true], owner: h.owner) { _ in }
        h.controller.receiveAudio(packet, owner: h.owner)
        XCTAssertFalse(h.events.contains { $0.event == "audioEvent" })
        let enable = h.sent[1]
        enable.complete(.success(Data([8, 16, 16, enable.magic, 154, 1, 2, 8, 1])))
        let stale = G2EvenHubSession.Owner(namespace: h.owner.namespace, serialNumber: h.owner.serialNumber,
            endpointID: h.owner.endpointID, sessionGeneration: 1, attemptGeneration: 3)
        h.controller.receiveAudio(packet, owner: stale)
        h.controller.receiveAudio(Data(repeating: 0, count: 199), owner: h.owner)
        XCTAssertFalse(h.events.contains { $0.event == "audioEvent" })
        h.controller.receiveAudio(packet + Data([0, 0, 254, 255, 7]), owner: h.owner)
        let event = try XCTUnwrap(h.events.last)
        XCTAssertEqual(event.event, "audioEvent")
        XCTAssertEqual((event.details["audioPcm"] as? Data)?.count, 1600)
        XCTAssertEqual(event.details["direction"] as? Int, -2)
        let count = h.events.filter { $0.event == "audioEvent" }.count
        try h.controller.close()
        h.controller.receiveAudio(packet, owner: h.owner)
        XCTAssertEqual(h.events.filter { $0.event == "audioEvent" }.count, count)
        h.reply(2)
        h.controller.invalidate()
        h.controller.receiveAudio(packet, owner: h.owner)
        XCTAssertEqual(h.events.filter { $0.event == "audioEvent" }.count, count)
    }

    func testAudioFromLeftUsesIndependentCurrentPairOwner() throws {
        let h = Harness()
        let left = G2EvenHubSession.Owner(namespace: h.owner.namespace, serialNumber: h.owner.serialNumber,
            endpointID: UUID(), sessionGeneration: h.owner.sessionGeneration, attemptGeneration: 4, side: "left")
        try h.controller.open(text: "Audio", owner: h.owner, sources: [h.owner, left]); h.reply(0)
        try h.controller.execute("audioControl", parameters: ["enabled": true], owner: h.owner) { _ in }
        h.sent[1].complete(.success(Data([8, 16, 16, h.sent[1].magic, 154, 1, 2, 8, 1])))
        h.controller.receiveAudio(Data(repeating: 0, count: 200), owner: left)
        XCTAssertEqual(h.events.last?.event, "audioEvent")
        XCTAssertEqual(h.events.last?.endpointID, left.endpointID)
        XCTAssertEqual(h.events.last?.side, "left")
    }

    func testAudioDisableRetiresDecoderBeforeAcknowledgement() throws {
        let h = Harness(), packet = Data(repeating: 0, count: 204)
        try h.controller.open(text: "Audio", owner: h.owner); h.reply(0)
        try h.controller.execute("audioControl", parameters: ["enabled": true], owner: h.owner) { _ in }
        h.sent[1].complete(.success(Data([8, 16, 16, h.sent[1].magic, 154, 1, 2, 8, 1])))
        h.controller.receiveAudio(packet, owner: h.owner)
        XCTAssertEqual(h.events.last?.event, "audioEvent")
        try h.controller.execute("audioControl", parameters: ["enabled": false], owner: h.owner) { _ in }
        let pendingCount = h.events.count
        h.controller.receiveAudio(packet, owner: h.owner)
        XCTAssertEqual(h.events.count, pendingCount)
        h.sent[2].complete(.success(Data([8, 16, 16, h.sent[2].magic, 154, 1, 0])))
        let count = h.events.count
        h.controller.receiveAudio(packet, owner: h.owner)
        XCTAssertEqual(h.events.count, count)
    }

    func testRawAudioFrameBoundsAndSlicedPackets() throws {
        let decoder = try XCTUnwrap(G2AudioDecoder())
        for count in [40, 80, 480] {
            let backing = Data(repeating: 0, count: count + 7)
            let slice = backing.dropFirst(7)
            XCTAssertEqual(try XCTUnwrap(decoder.decode(slice)).audioPcm.count, count / 40 * 320)
        }
        for count in [0, 39, 41, 479, 481, 512] {
            XCTAssertNil(decoder.decode(Data(repeating: 0, count: count)))
        }
        let tagged = Data(repeating: 0, count: 211).dropFirst(7)
        XCTAssertEqual(try XCTUnwrap(decoder.decode(tagged)).audioPcm.count, 1600)
        XCTAssertEqual(try XCTUnwrap(decoder.decode(Data(repeating: 0, count: 40))).audioPcm.count, 320)
    }

    func testRequestsAndResponsesMatchCanonicalDartVectors() throws {
        let vectors = try contractVectors()
        let create = try XCTUnwrap(EvenHubTestWire.create(text: "Hello EvenHub", magic: 42, page: 123))
        XCTAssertEqual(G2BusinessFrame.request(service: .evenHub, syncID: 7, payload: create)?.hex,
                       vectors["g2_evenhub_create_hex"])
        XCTAssertEqual(G2BusinessFrame.request(service: .evenHub, syncID: 7,
            payload: EvenHubTestWire.heartbeat(count: 7, magic: 42))?.hex,
            vectors["g2_evenhub_heartbeat_hex"])
        XCTAssertEqual(G2BusinessFrame.request(service: .evenHub, syncID: 7,
            payload: EvenHubTestWire.shutdown(magic: 42))?.hex, vectors["g2_evenhub_close_hex"])
        for (command, name) in [(EvenHubTestWire.Command.create, "create"), (.heartbeat, "heartbeat"), (.shutdown, "close")] {
            let frame = try XCTUnwrap(G2BusinessFrame.parse(Data(hex: try XCTUnwrap(vectors["g2_evenhub_\(name)_ack_hex"]))))
            XCTAssertTrue(EvenHubTestWire.accepts(frame.payload, command: command, magic: 42, count: 7))
        }
        let click = try XCTUnwrap(G2BusinessFrame.parse(Data(hex: try XCTUnwrap(vectors["g2_evenhub_click_hex"]))))
        XCTAssertEqual(EvenHubTestWire.input(click.payload, page: 123)?.name, "click")
    }

    func testMalformedResponsesCannotAliasSuccessfulDefaults() {
        XCTAssertFalse(EvenHubTestWire.accepts(Data([8, 1, 16, 42]), command: .create, magic: 42))
        XCTAssertFalse(EvenHubTestWire.accepts(Data([8, 1, 16, 42, 34, 0, 98, 0]), command: .create, magic: 42))
        XCTAssertFalse(EvenHubTestWire.accepts(Data([8, 1, 16, 42, 34, 2, 10, 0]), command: .create, magic: 42))
        XCTAssertFalse(EvenHubTestWire.accepts(Data([8, 1, 16, 42, 34, 0]), command: .create, magic: 43))
        XCTAssertFalse(EvenHubTestWire.accepts(Data([8, 13, 16, 42, 122, 2, 8, 1]), command: .heartbeat, magic: 42, count: 1))
        XCTAssertTrue(EvenHubTestWire.accepts(Data([8, 13, 16, 42, 122, 4, 8, 2, 16, 12]), command: .heartbeat, magic: 42, count: 1))
        let h = Harness()
        XCTAssertThrowsError(try h.controller.open(text: "", owner: h.owner))
        XCTAssertThrowsError(try h.controller.open(text: String(repeating: "中", count: 22), owner: h.owner))
        XCTAssertNoThrow(try h.controller.open(text: String(repeating: "A", count: 64), owner: h.owner))
    }

    func testInputRequiresRecognizedBranchAndExactTerminalPageIdentity() {
        XCTAssertNil(EvenHubTestWire.input(Data([8, 2, 106, 0]), page: 1))
        XCTAssertNil(EvenHubTestWire.input(Data([8, 2, 106, 6, 18, 4, 8, 1, 24, 99]), page: 1))
        XCTAssertNil(EvenHubTestWire.input(Data([8, 2, 106, 4, 26, 2, 8, 7]), page: 1))
        let valid = sysExit(page: 1)
        XCTAssertEqual(EvenHubTestWire.input(valid, page: 1)?.name, "exit")
        XCTAssertTrue(EvenHubTestWire.input(valid, page: 1)?.terminal == true)
        XCTAssertNil(EvenHubTestWire.input(valid, page: 2))
    }

    func testCreateSuccessStartsLeaseOnlyAfterAckAndHeartbeatValidatesCount() throws {
        let h = Harness()
        try h.controller.open(text: "Hello", owner: h.owner)
        XCTAssertEqual(h.events.map(\.phase), ["opening"])
        XCTAssertTrue(h.timers.isEmpty)
        h.reply(0)
        XCTAssertEqual(h.events.last?.phase, "active")
        XCTAssertEqual(h.timers.first?.delay, 5)
        h.timers[0].fire()
        XCTAssertEqual(h.sent.count, 2)
        XCTAssertEqual(h.sent[1].payload[1], 12)
        h.reply(1, count: 1)
        XCTAssertEqual(h.events.last?.phase, "heartbeat")
        XCTAssertEqual(h.events.last?.heartbeatCount, 1)
        XCTAssertEqual(h.timers.count, 2)
    }

    func testCloseDuringCreateQueuesShutdownAndNeverSchedulesLease() throws {
        let h = Harness()
        try h.controller.open(text: "Hello", owner: h.owner)
        try h.controller.close()
        XCTAssertEqual(h.events.map(\.phase), ["opening", "closing"])
        XCTAssertEqual(h.sent.count, 1)
        h.reply(0)
        XCTAssertEqual(h.sent.count, 2)
        XCTAssertEqual(h.sent[1].payload[1], 9)
        XCTAssertTrue(h.timers.isEmpty)
        h.reply(1)
        XCTAssertEqual(h.events.last?.phase, "closed")
    }

    func testCloseDuringHeartbeatWaitsForItAndStopsNewHeartbeats() throws {
        let h = Harness()
        try h.controller.open(text: "Hello", owner: h.owner)
        h.reply(0)
        h.timers[0].fire()
        try h.controller.close()
        h.reply(1, count: 1)
        XCTAssertEqual(h.sent.count, 3)
        XCTAssertEqual(h.sent[2].payload[1], 9)
        XCTAssertEqual(h.events.filter { $0.phase == "heartbeat" }.count, 0)
        h.reply(2)
        XCTAssertEqual(h.events.last?.phase, "closed")
        h.timers[0].fire() // Even a timer delivered after cancellation is inert.
        XCTAssertEqual(h.sent.count, 3)
    }

    func testRetiredOwnerAndDuplicateCommandCompletionCannotAffectReplacement() throws {
        let h = Harness()
        try h.controller.open(text: "A", owner: h.owner)
        h.controller.invalidate()
        try h.controller.open(text: "B", owner: h.owner)
        let newOperation = h.events.last?.operation
        h.reply(0)
        XCTAssertEqual(h.events.last?.phase, "opening")
        XCTAssertEqual(h.events.last?.operation, newOperation)
        h.reply(1)
        h.timers[0].fire()
        h.reply(2, count: 1)
        h.timers[1].fire()
        let before = h.events.count
        h.reply(2, count: 1) // Duplicate old heartbeat during a new heartbeat.
        XCTAssertEqual(h.events.count, before)
        h.reply(3, count: 2)
        XCTAssertEqual(h.events.last?.heartbeatCount, 2)
    }

    func testBusyAndInvalidReplacementLeaveTheSuccessfulPageUntouched() throws {
        let h = Harness()
        XCTAssertThrowsError(try h.controller.open(text: "", owner: h.owner)) {
            XCTAssertEqual($0 as? G2EvenHubError, .invalidText)
        }
        try h.controller.open(text: "A", owner: h.owner)
        h.reply(0)
        XCTAssertThrowsError(try h.controller.open(text: "B", owner: h.owner)) {
            XCTAssertEqual($0 as? G2EvenHubError, .busy)
        }
        XCTAssertEqual(h.events.last?.phase, "active")
        XCTAssertFalse(h.timers[0].cancelled)
    }

    func testPageIDsRemainDistinctAcrossSDKControllerReplacement() throws {
        let first = Harness()
        try first.controller.open(text: "A", owner: first.owner)
        let old = try XCTUnwrap(first.events.first?.operation)
        first.controller.invalidate()
        let second = Harness()
        try second.controller.open(text: "B", owner: second.owner)
        let current = try XCTUnwrap(second.events.first?.operation)
        XCTAssertNotEqual(UInt32(truncatingIfNeeded: old), UInt32(truncatingIfNeeded: current))
    }

    func testFailureTimeoutAndExitRetireLeaseBeforePublishing() throws {
        let h = Harness()
        try h.controller.open(text: "A", owner: h.owner)
        try h.controller.close()
        h.sent[0].complete(.failure(.timedOut))
        XCTAssertEqual(h.events.last?.phase, "failed")
        XCTAssertEqual(h.events.last?.failure, "timedOut")
        XCTAssertEqual(h.sent.count, 1)
        try h.controller.open(text: "B", owner: h.owner)
        h.reply(1)
        let active = try XCTUnwrap(h.events.last)
        var wrong = h.owner
        wrong = .init(namespace: wrong.namespace, serialNumber: wrong.serialNumber,
            endpointID: wrong.endpointID, sessionGeneration: wrong.sessionGeneration + 1,
            attemptGeneration: wrong.attemptGeneration)
        h.controller.receive(sysExit(page: UInt8(active.operation)), owner: wrong)
        XCTAssertEqual(h.events.last?.phase, "active")
        h.controller.receive(sysExit(page: UInt8(active.operation)), owner: h.owner)
        XCTAssertEqual(h.events.last?.phase, "closed")
        XCTAssertTrue(h.timers[0].cancelled)
        h.timers[0].fire()
        XCTAssertEqual(h.sent.count, 2)
    }

    func testSynchronousCloseFromOpeningOrdersCreateBeforeShutdown() throws {
        let h = Harness()
        h.eventHook = { event in
            if event.phase == "opening" { try? h.controller.close() }
        }
        try h.controller.open(text: "A", owner: h.owner)
        XCTAssertEqual(h.sent.count, 1)
        XCTAssertEqual(h.sent[0].payload[1], 0)
        h.reply(0)
        XCTAssertEqual(h.sent[1].payload[1], 9)
    }

    private func sysExit(page: UInt8) -> Data {
        // DevEvent.SysEvent {type7, widget11417, session=page}.
        Data([8, 2, 106, 9, 26, 7, 8, 7, 40, 153, 89, 48, page])
    }

    private func contractVectors() throws -> [String: String] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("protocol/contract.json")
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try XCTUnwrap(json["vectors"] as? [String: String])
    }
}

private final class Harness {
    final class Scheduled {
        let delay: TimeInterval
        let callback: () -> Void
        var cancelled = false
        init(delay: TimeInterval, callback: @escaping () -> Void) { self.delay = delay; self.callback = callback }
        // Tests deliberately deliver cancelled timers to prove token fencing.
        func fire() { callback() }
    }
    struct Sent {
        let payload: Data
        let magic: UInt8
        let complete: (Result<Data, G2BusinessCommandError>) -> Void
    }
    let owner = G2EvenHubSession.Owner(namespace: "test", serialNumber: "S200LABJ270010",
        endpointID: UUID(), sessionGeneration: 1, attemptGeneration: 2)
    var time: TimeInterval = 0
    var events: [G2EvenHubEvent] = []
    var sent: [Sent] = []
    var timers: [Scheduled] = []
    var eventHook: ((G2EvenHubEvent) -> Void)?
    lazy var controller = G2EvenHubSession(send: { [unowned self] _, payload, magic, complete in
        self.sent.append(Sent(payload: payload, magic: magic, complete: complete))
    }, now: { [unowned self] in self.time }, schedule: { [unowned self] delay, callback in
        let timer = Scheduled(delay: delay, callback: callback)
        self.timers.append(timer)
        return { timer.cancelled = true }
    }, onEvent: { [unowned self] event in
        self.events.append(event)
        self.eventHook?(event)
    })

    func reply(_ index: Int, count: UInt8 = 0) {
        let request = sent[index]
        let reply: [UInt8]
        switch request.payload[1] {
        case 0: reply = [8, 1, 16, request.magic, 34, 0]
        case 9: reply = [8, 10, 16, request.magic, 98, 2, 8, 10]
        default: reply = [8, 13, 16, request.magic, 122, 4, 8, count, 16, 12]
        }
        request.complete(.success(Data(reply)))
    }
}

private extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
    init(hex: String) {
        self = Data(stride(from: 0, to: hex.count, by: 2).map { index in
            let begin = hex.index(hex.startIndex, offsetBy: index)
            return UInt8(hex[begin..<hex.index(begin, offsetBy: 2)], radix: 16)!
        })
    }
}

/// Compact test conveniences call the production encoder/decoder; golden
/// vectors cannot pass through an obsolete demonstration-only implementation.
private enum EvenHubTestWire {
    enum Command { case create, heartbeat, shutdown }
    private static func pageArguments(_ text: String) -> [String: Any] {
        ["textObjects": [["containerId": 1, "containerName": "native-text", "xPosition": 24, "yPosition": 24,
            "width": 528, "height": 240, "paddingLength": 8, "isEventCapture": true, "content": text]]]
    }
    static func create(text: String, magic: UInt8, page: UInt32) -> Data? {
        try? G2EvenHubCommands.requests("createPage", parameters: pageArguments(text), page: nil,
            session: page, count: 0, mapSession: 0).first?.payload(magic: magic)
    }
    private static func request(_ name: String, page: UInt32 = 1, count: UInt32 = 0) -> G2EvenHubCommands.Request {
        let definition = try! G2EvenHubCommands.page(pageArguments("x"), create: true, session: page)
        return try! G2EvenHubCommands.requests(name, parameters: name == "createPage" ? pageArguments("x") : [:],
            page: definition, session: page, count: count, mapSession: 0)[0]
    }
    static func heartbeat(count: UInt32, magic: UInt8) -> Data { request("heartbeat", count: count).payload(magic: magic) }
    static func shutdown(magic: UInt8) -> Data { request("shutdownPage").payload(magic: magic) }
    static func accepts(_ data: Data, command: Command, magic: UInt8, count: UInt32 = 0) -> Bool {
        let name = command == .create ? "createPage" : command == .heartbeat ? "heartbeat" : "shutdownPage"
        return request(name, count: count).accepts(data, magic: magic)
    }
    static func input(_ data: Data, page: UInt32) -> G2EvenHubEvents.Input? {
        guard let definition = try? G2EvenHubCommands.page(pageArguments("x"), create: true, session: page) else { return nil }
        return G2EvenHubEvents.decode(data, definition: definition, session: page)
    }
}
