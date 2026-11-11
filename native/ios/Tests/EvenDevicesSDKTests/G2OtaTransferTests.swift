import XCTest
@testable import EvenDevicesSDK

final class G2OtaTransferTests: XCTestCase {
    private func component(_ marker: UInt8, _ length: Int) -> G2OtaComponent {
        G2OtaComponent(header: Data(repeating: marker, count: 128), body: Data(repeating: marker, count: length))
    }

    private func reply(_ cid: UInt8, _ code: UInt8, sync: UInt8) -> Data {
        let crc = G2AuthenticationWire.crc16([cid, code])
        return Data([0xaa, 0x12, sync, 4, 1, 1, cid == 2 ? 0xc1 : 0xc0, 0, cid, code,
                     UInt8(crc & 0xff), UInt8(crc >> 8)])
    }

    /// Each new request records the exact sync expected for its next receipt.
    @discardableResult
    private func accept(_ leg: G2OtaTransfer, _ cid: UInt8, _ code: UInt8 = 0,
                        sync: UInt8) throws -> [Data] {
        let packets = try leg.packets(sync: Int(sync), maxWrite: 244)
        XCTAssertTrue(try leg.receive(reply(cid, code, sync: sync)))
        return packets
    }

    func testScopedWindowCreditsAndEarlyFinalNotify() throws {
        let leg = G2OtaTransfer(image: .init(version: "", components: [component(1, 4097)]))
        _ = try leg.packets(sync: 4, maxWrite: 244)
        XCTAssertFalse(try leg.receive(reply(0, 0, sync: 3)))
        XCTAssertFalse(try leg.receive(reply(4, 0, sync: 4)))
        try accept(leg, 0, sync: 4)
        try accept(leg, 1, sync: 5)
        let frames = try accept(leg, 2, sync: 6)
        XCTAssertGreaterThan(frames.count, 2)
        XCTAssertEqual(leg.confirmedBytes, 4096)
        _ = try leg.packets(sync: 7, maxWrite: 244)
        XCTAssertFalse(try leg.receive(reply(2, 0, sync: 6)))
        XCTAssertThrowsError(try leg.receive(reply(2, 5, sync: 7)))
        XCTAssertEqual(leg.confirmedBytes, 4096)
        try accept(leg, 2, sync: 8)
        XCTAssertEqual(leg.confirmedBytes, 4097)
        _ = try leg.packets(sync: 9, maxWrite: 244)
        XCTAssertTrue(try leg.receive(reply(4, 9, sync: 42)))
        XCTAssertEqual(leg.step, .finished)
        XCTAssertFalse(try leg.receive(reply(3, 8, sync: 9)))
    }

    func testRecoveryReplaysOnlyCurrentComponent() throws {
        let leg = G2OtaTransfer(image: .init(version: "", components: [component(1, 1), component(2, 4100)]))
        try accept(leg, 0, sync: 1)
        try accept(leg, 1, sync: 2)
        try accept(leg, 2, sync: 3)
        try accept(leg, 3, 8, sync: 4)
        try accept(leg, 1, sync: 5)
        try accept(leg, 2, sync: 6)
        XCTAssertEqual(leg.confirmedBytes, 4097)
        leg.restartCurrentComponent()
        XCTAssertEqual(leg.confirmedBytes, 1)
        XCTAssertEqual(leg.step, .start)
        try accept(leg, 0, sync: 7)
        let header = try accept(leg, 1, sync: 8)
        XCTAssertEqual(header[0][9], 2)
        try accept(leg, 2, sync: 9)
        try accept(leg, 2, sync: 10)
        try accept(leg, 3, 8, sync: 11)
        XCTAssertEqual(leg.step, .waitingNotify)
        XCTAssertFalse(try leg.receive(reply(2, 0, sync: 11)))
        XCTAssertTrue(try leg.receive(reply(4, 0, sync: 8)))
        XCTAssertEqual(leg.step, .finished)
    }

    func testResultTimeoutAppliesOnlyToSubmittedComponent() throws {
        let leg = G2OtaTransfer(image: .init(version: "", components: [component(1, 2), component(2, 1)]))
        try accept(leg, 0, sync: 1)
        try accept(leg, 1, sync: 2)
        XCTAssertFalse(leg.allowMissingIntermediateResult())
        try accept(leg, 2, sync: 3)
        _ = try leg.packets(sync: 4, maxWrite: 244)
        XCTAssertFalse(try leg.receive(reply(4, 9, sync: 0)))
        XCTAssertThrowsError(try leg.receive(reply(3, 7, sync: 4)))
        XCTAssertTrue(leg.allowMissingIntermediateResult())
        try accept(leg, 1, sync: 5)
        try accept(leg, 2, sync: 6)
        try accept(leg, 3, 4, sync: 7)
        XCTAssertFalse(leg.allowMissingIntermediateResult())
        XCTAssertEqual(leg.step, .waitingNotify)
        XCTAssertThrowsError(try leg.receive(reply(4, 10, sync: 0)))
        XCTAssertTrue(try leg.receive(reply(4, 0, sync: 0)))
    }

    func testReadbackPollRetainsWholeSnapshotsUnderOneDeadline() {
        let poll = G2OtaReadback(target: "2.0.1", now: 1)
        XCTAssertTrue(poll.begin(now: 1))
        XCTAssertFalse(poll.begin(now: 3)) // ATT callback still belongs to the first write.
        XCTAssertFalse(poll.received(.init(left: "2.0.1", right: "2.0.0"), now: 4))
        XCTAssertFalse(poll.acknowledged(now: 5))
        XCTAssertTrue(poll.begin(now: 7))
        XCTAssertFalse(poll.acknowledged(now: 8))
        XCTAssertFalse(poll.received(.init(left: "2.0.0", right: "2.0.1"), now: 9))
        XCTAssertEqual(poll.lastVersions?.left, "2.0.0")
        XCTAssertTrue(poll.begin(now: 59))
        XCTAssertFalse(poll.received(.init(left: "2.0.1", right: "2.0.1"), now: 60))
        XCTAssertFalse(poll.acknowledged(now: 61)) // A late ACK cannot turn timeout into success.
        XCTAssertFalse(poll.begin(now: 62))
        let timely = G2OtaReadback(target: "2.0.1", now: 1)
        XCTAssertTrue(timely.begin(now: 1))
        XCTAssertFalse(timely.acknowledged(now: 2))
        XCTAssertTrue(timely.received(.init(left: "2.0.1", right: "2.0.1"), now: 3))
    }

    func testRecoveryBudgetCountsConsecutiveRoundsAndCumulativeElapsed() {
        var budget = G2OtaRecoveryBudget()
        XCTAssertTrue(budget.reserve(at: 0))
        XCTAssertTrue(budget.reserve(at: 1))
        XCTAssertTrue(budget.reserve(at: 2))
        XCTAssertFalse(budget.reserve(at: 3))
        budget.informationAccepted(at: 40)
        XCTAssertEqual(budget.remaining(at: 1800), 50)
        XCTAssertTrue(budget.reserve(at: 1800))
        XCTAssertEqual(budget.remaining(at: 1850), 0)
        XCTAssertFalse(budget.reserve(at: 1850))
    }

    func testOwnerRejectsOtherLegAndRetiredAttemptWithSamePeripheralID() {
        let rightID = UUID()
        let owner = G2OtaLegOwner(generation: 3, attempt: 8, peripheralID: rightID)
        XCTAssertTrue(owner.accepts(generation: 3, attempt: 8, peripheralID: rightID))
        XCTAssertFalse(owner.accepts(generation: 2, attempt: 8, peripheralID: rightID))
        XCTAssertFalse(owner.accepts(generation: 3, attempt: 7, peripheralID: rightID))
        XCTAssertFalse(owner.accepts(generation: 3, attempt: 8, peripheralID: UUID()))
        XCTAssertFalse(owner.accepts(generation: 0, attempt: 0, peripheralID: rightID))
    }
}
