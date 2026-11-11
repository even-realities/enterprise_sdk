import Foundation

/// Thrown before an operation acquires either temple's transport.
public enum G2OtaError: String, Error {
    case notReady, busy, invalidFirmware, invalidTargetVersion
}

/// Progress counts bytes acknowledged by firmware across both temples.
public struct G2OtaEvent {
    public let sn: String
    public let generation: UInt64
    public let operation: Int64
    public let endpoint: String?
    public let session: UInt64?
    public let attempt: UInt64?
    public let leftVersion: String?
    public let rightVersion: String?
    public let phase: String
    public let progress: Double
    public let failure: String?
}

/// One temple's credit-driven transfer. Only its owner can supply an ACK or
/// advance the cursor; CoreBluetooth backpressure cannot optimistically credit bytes.
final class G2OtaTransfer {
    enum Step: Equatable { case start, information, window, result, waitingNotify, finished }
    let image: G2OtaImage
    let totalBytes: Int
    private(set) var step: Step = .start
    private(set) var confirmedBytes = 0
    private(set) var sync = 0
    private var componentIndex = 0
    private var offset = 0
    private var windowLength = 0
    var isFinalComponent: Bool { componentIndex == image.components.count - 1 }

    init(image: G2OtaImage) {
        self.image = image
        totalBytes = image.components.reduce(0) { $0 + $1.body.count }
    }

    /// Build a single outstanding request/window using the negotiated ATT value length.
    func packets(sync: Int, maxWrite: Int) throws -> [Data] {
        self.sync = sync
        let packet: Data
        switch step {
        case .start: packet = G2OtaWire.control(cid: 0, sync: sync)
        case .information:
            packet = G2OtaWire.control(cid: 1, sync: sync, payload: image.components[componentIndex].header)
        case .window:
            let data = image.components[componentIndex].body
            windowLength = min(4096, data.count - offset)
            return try G2OtaWire.window(data.subdata(in: offset..<(offset + windowLength)),
                                        sync: sync, maxWrite: maxWrite)
        case .result: packet = G2OtaWire.control(cid: 3, sync: sync)
        case .waitingNotify, .finished: return []
        }
        guard packet.count <= maxWrite else { throw TransferError.mtu }
        return [packet]
    }

    /// Components whose RESULT was accepted stay committed. A new secure link
    /// must repeat START and INFORMATION and replay only the interrupted body.
    func restartCurrentComponent() {
        confirmedBytes -= offset
        offset = 0
        windowLength = 0
        step = .start
    }

    /// Intermediate components have no install notification; the production
    /// protocol permits the next INFORMATION after a missing RESULT response.
    func allowMissingIntermediateResult() -> Bool {
        guard step == .result, !isFinalComponent else { return false }
        componentIndex += 1
        offset = 0
        step = .information
        return true
    }

    enum TransferError: Error { case mtu, device(Int) }

    /// Notifications are uncorrelated by sync on firmware. Admit them only
    /// after the final RESULT was written; it may overtake the RESULT ACK.
    func receive(_ data: Data) throws -> Bool {
        if (step == .result || step == .waitingNotify), isFinalComponent,
           let status = G2OtaWire.notify(data) {
            guard status == 0 || status == 9 else { throw TransferError.device(status) }
            step = .finished
            return true
        }
        let cid: Int
        switch step {
        case .start: cid = 0
        case .information: cid = 1
        case .window: cid = 2
        case .result: cid = 3
        case .waitingNotify, .finished: return false
        }
        guard let status = G2OtaWire.reply(data, expectedCid: cid, expectedSync: sync) else { return false }
        if step == .result, status == 4 {
            // Firmware reports an uncertain RESULT. The final component still
            // requires its autonomous NOTIFY, while intermediate components do not send one.
            if !allowMissingIntermediateResult() { step = .waitingNotify }
            return true
        }
        guard status == (step == .result ? 8 : 0) else { throw TransferError.device(status) }
        switch step {
        case .start: step = .information
        case .information: step = image.components[componentIndex].body.isEmpty ? .result : .window
        case .window:
            offset += windowLength
            confirmedBytes += windowLength
            step = offset == image.components[componentIndex].body.count ? .result : .window
        case .result:
            if isFinalComponent { step = .waitingNotify }
            else {
                componentIndex += 1
                offset = 0
                step = .information
            }
        case .waitingNotify, .finished: break
        }
        return true
    }
}

/// A read can return stale firmware immediately after READY. Poll within one
/// monotonic budget, keeping each dual-temple reply tied to its own ATT receipt.
final class G2OtaReadback {
    private let target: String
    let deadline: TimeInterval
    private(set) var writeOutstanding = false
    private var candidate: G2OtaVersions?
    private(set) var lastVersions: G2OtaVersions?

    init(target: String, now: TimeInterval) {
        self.target = target
        deadline = now + 60
    }

    func begin(now: TimeInterval) -> Bool {
        guard now < deadline, !writeOutstanding else { return false }
        candidate = nil
        writeOutstanding = true
        return true
    }

    func acknowledged(now: TimeInterval) -> Bool {
        writeOutstanding = false
        return check(now: now)
    }

    func received(_ versions: G2OtaVersions, now: TimeInterval) -> Bool {
        candidate = versions
        return check(now: now)
    }

    private func check(now: TimeInterval) -> Bool {
        guard now < deadline, !writeOutstanding, let candidate else { return false }
        lastVersions = candidate
        return candidate.matches(target: target)
    }
}

/// Same-link START recovery and physical reconnection spend the same budget.
/// Only time in recovery counts; a long, healthy transfer must remain eligible.
struct G2OtaRecoveryBudget {
    private var rounds = 0
    private var elapsed: TimeInterval = 0
    private var started: TimeInterval?

    func remaining(at now: TimeInterval) -> TimeInterval {
        max(0, 90 - elapsed - (started.map { now - $0 } ?? 0))
    }

    var isRecovering: Bool { started != nil }

    mutating func reserve(at now: TimeInterval) -> Bool {
        guard rounds < 3, remaining(at: now) > 0 else { return false }
        rounds += 1
        if started == nil { started = now }
        return true
    }

    mutating func informationAccepted(at now: TimeInterval) {
        if let started { elapsed += now - started }
        started = nil
        rounds = 0
    }
}

/// A peripheral UUID can survive reconnection and a session owns two attempts.
/// An OTA reply must match all three identifiers of the currently selected leg.
struct G2OtaLegOwner: Equatable {
    let generation: UInt64
    let attempt: UInt64
    let peripheralID: UUID

    func accepts(generation: UInt64, attempt: UInt64, peripheralID: UUID) -> Bool {
        generation > 0 && attempt > 0 && self.generation == generation &&
            self.attempt == attempt && self.peripheralID == peripheralID
    }
}
