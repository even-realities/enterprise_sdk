import Foundation

/// Safe public failures contain neither user content nor signature material.
public enum G2EvenHubError: String, Error {
    case notReady, busy, invalidText, invalidArguments, deviceRejected, transportFailed, disconnected, cancelled
}

/// Page and input events retain the real BLE source plus the native page token.
public struct G2EvenHubEvent {
    public let namespace: String
    public let serialNumber: String
    public let endpointID: UUID
    public let side: String
    public let sessionGeneration: UInt64
    public let attemptGeneration: UInt64
    public let operation: UInt64
    public let phase: String
    public let event: String?
    public let failure: String?
    public let heartbeatCount: UInt32
    public let details: [String: Any]
}

/// The sole native page lease serializes CREATE, updates, image chunks,
/// heartbeat and shutdown. Injected transport/clock make hostile timelines testable.
final class G2EvenHubSession {
    struct Owner: Equatable {
        let namespace: String
        let serialNumber: String
        let endpointID: UUID
        let sessionGeneration: UInt64
        let attemptGeneration: UInt64
        var side: String = "right"
    }
    typealias Completion = (Result<[String: Any], G2EvenHubError>) -> Void
    typealias Send = (Owner, Data, UInt8, @escaping (Result<Data, G2BusinessCommandError>) -> Void) -> Void
    typealias Schedule = (TimeInterval, @escaping () -> Void) -> (() -> Void)

    private final class Page {
        let owner: Owner
        let sources: [Owner]
        let operation: UInt64
        let sessionID: UInt32
        var definition: G2EvenHubCommands.PageDefinition
        var heartbeatCount: UInt32 = 0
        var confirmedHeartbeatCount: UInt32 = 0
        var leaseDueAt: TimeInterval = 0
        // A UUID distinguishes repeated heartbeats and duplicated write callbacks.
        var pendingID: UUID?
        var pendingName: String?
        var completion: Completion?
        var closing = false
        var closeRequests: [G2EvenHubCommands.Request]?
        var closeCompletion: Completion?
        // Only an acknowledged audio-enable owns predictive decoder state.
        var audioEnabled = false
        var audioDecoders: [UUID: G2AudioDecoder] = [:]
        var active = false
        var cancelTimer: (() -> Void)?
        var timerID: UUID?
        init(owner: Owner, sources: [Owner], operation: UInt64, definition: G2EvenHubCommands.PageDefinition) {
            self.owner = owner; self.sources = sources; self.operation = operation
            sessionID = UInt32(truncatingIfNeeded: operation); self.definition = definition
        }
    }
    private let send: Send
    private let sendOneWay: Send
    /// One bounded whole-image retry gets a fresh native map ID. Retaining
    /// logical parameters permits regeneration without exposing fragments.
    private final class ImageJob {
        let parameters: [String: Any]
        var retries = 0
        init(_ parameters: [String: Any]) { self.parameters = parameters }
    }
    private let now: () -> TimeInterval
    private let schedule: Schedule
    private let onEvent: (G2EvenHubEvent) -> Void
    private var current: Page?
    // Unpaged menu startup belongs to a READY physical pair, not a synthetic
    // page. The independent token fences its one-way write across reset.
    private struct UnpagedCommand {
        let token: UUID
        let owner: Owner
        let operation: UInt64
        let completion: Completion
    }
    private var readyOwners: [Owner] = []
    private var unpaged: UnpagedCommand?

    /// Unpaged events require the complete authenticated pair. Replacing its
    /// physical identity cancels a previous one-way operation before callbacks.
    func setReadyOwners(_ owners: [Owner]) {
        let first = owners.first
        let valid = owners.count == 2 && Set(owners.map(\.side)) == ["left", "right"]
            && Set(owners.map(\.endpointID)).count == 2
            && owners.allSatisfy { $0.namespace == first?.namespace && $0.serialNumber == first?.serialNumber
                && $0.sessionGeneration == first?.sessionGeneration && $0.sessionGeneration > 0 && $0.attemptGeneration > 0 }
        readyOwners = valid ? owners : []
        if let command = unpaged, !readyOwners.contains(command.owner) {
            unpaged = nil
            command.completion(.failure(.disconnected))
        }
    }
    private var nextMagic: UInt8 = 0
    // Process-wide, since reset creates a replacement controller while delayed
    // firmware events from its predecessor may still be in the BLE queue.
    private static let sequenceLock = NSLock()
    private static var nextOperation: UInt64 = 0
    private static func allocateOperation() -> UInt64 {
        sequenceLock.lock(); defer { sequenceLock.unlock() }
        repeat { nextOperation &+= 1 } while UInt32(truncatingIfNeeded: nextOperation) == 0
        return nextOperation
    }
    init(send: @escaping Send, oneWay: Send? = nil, now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }, schedule: @escaping Schedule,
         onEvent: @escaping (G2EvenHubEvent) -> Void) {
        self.send = send; sendOneWay = oneWay ?? send; self.now = now; self.schedule = schedule; self.onEvent = onEvent
    }
    deinit { current?.cancelTimer?() }

    /// Other SDK commands must not occupy the page heartbeat transport.
    var hasPage: Bool { current != nil }

    /// The demo is an ordinary native CREATE; host widgets own no protocol work.
    func open(text: String, owner: Owner, sources: [Owner] = []) throws {
        guard current == nil else { throw G2EvenHubError.busy }
        guard !text.utf8.isEmpty, text.utf8.count <= 64 else { throw G2EvenHubError.invalidText }
        try execute("createPage", parameters: ["textObjects": [["containerId": 1, "containerName": "native-text",
            "xPosition": 24, "yPosition": 24, "width": 528, "height": 240, "paddingLength": 8,
            "isEventCapture": true, "content": text]]], owner: owner, sources: sources) { _ in }
    }
    func close() throws {
        guard let page = current else { throw G2EvenHubError.notReady }
        try execute("shutdownPage", parameters: [:], owner: page.owner) { _ in }
    }

    /// Argument and busy rejection leave the current lease untouched. A valid
    /// admitted command completes only after its ACK (or one-way ATT write).
    func execute(_ name: String, parameters: [String: Any], owner: Owner, sources: [Owner] = [], completion: @escaping Completion) throws {
        if current == nil, name == "menuStartupFailed" {
            guard unpaged == nil else { throw G2EvenHubError.busy }
            guard readyOwners.contains(owner) else { throw G2EvenHubError.notReady }
            let request = try G2EvenHubCommands.requests(name, parameters: parameters, page: nil,
                session: 0, count: 0, mapSession: 0)[0]
            let token = UUID()
            unpaged = UnpagedCommand(token: token, owner: owner, operation: Self.allocateOperation(), completion: completion)
            nextMagic = nextMagic == UInt8.max ? 1 : nextMagic + 1; let magic = nextMagic
            sendOneWay(owner, request.payload(magic: magic), magic) { [weak self] response in
                guard let self, let command = self.unpaged, command.token == token,
                      command.owner == owner, self.readyOwners.contains(owner) else { return }
                self.unpaged = nil
                switch response {
                case .success:
                    command.completion(.success(["command": name, "operation": command.operation,
                        "acknowledged": false, "submitted": true, "fragments": 1, "imageFragments": 0, "retries": 0]))
                case .failure: command.completion(.failure(.transportFailed))
                }
            }
            return
        }
        if name == "createPage" {
            guard current == nil, unpaged == nil else { throw G2EvenHubError.busy }
            let operation = Self.allocateOperation()
            let requests = try G2EvenHubCommands.requests(name, parameters: parameters, page: nil,
                session: UInt32(truncatingIfNeeded: operation), count: 0, mapSession: 0)
            guard let definition = requests.first?.definition else { throw G2EvenHubError.invalidArguments }
            let page = Page(owner: owner, sources: sources.isEmpty ? [owner] : sources, operation: operation, definition: definition)
            current = page
            submit(requests, page: page, completion: completion, beforeSend: { self.emit("opening", page) })
            return
        }
        guard let page = current, page.owner == owner, page.active || page.pendingName == "createPage" else { throw G2EvenHubError.notReady }
        guard !page.closing else { throw G2EvenHubError.busy }
        let count = name == "heartbeat" ? page.heartbeatCount &+ 1 : page.heartbeatCount
        let requests = try G2EvenHubCommands.requests(name, parameters: parameters, page: page.definition,
            session: page.sessionID, count: count, mapSession: UInt32(truncatingIfNeeded: Self.allocateOperation()))
        if name == "shutdownPage" {
            page.audioEnabled = false; page.audioDecoders.removeAll()
            page.closing = true; page.cancelTimer?(); page.cancelTimer = nil; page.timerID = nil
            page.closeRequests = requests; page.closeCompletion = completion
            emit("closing", page)
            guard current === page, page.pendingID == nil else { return }
            drainClose(page); return
        }
        guard page.active else { throw G2EvenHubError.notReady }
        guard page.pendingID == nil else { throw G2EvenHubError.busy }
        if name == "audioControl", parameters["enabled"] as? Bool == false {
            // Host disable intent revokes delivery before any write callback;
            // a delayed ACK must never extend the old microphone lease.
            page.audioEnabled = false; page.audioDecoders.removeAll()
        }
        if name == "heartbeat" { page.heartbeatCount = count }
        submit(requests, page: page, completion: completion, image: name == "updateImage" ? ImageJob(parameters) : nil)
    }

    /// Retire before callbacks, so a synchronous observer can safely open a new
    /// page without an old timer/completion removing its ownership.
    func invalidate(failure: String = "disconnected") {
        let command = unpaged
        unpaged = nil; readyOwners = []
        if let page = current { fail(page, failure: failure, error: .disconnected) }
        command?.completion(.failure(.disconnected))
    }
    func receive(_ payload: Data, owner: Owner) {
        if current == nil {
            guard readyOwners.contains(owner), let input = G2EvenHubEvents.unpagedConnectionEvent(payload) else { return }
            onEvent(G2EvenHubEvent(namespace: owner.namespace, serialNumber: owner.serialNumber,
                endpointID: owner.endpointID, side: owner.side, sessionGeneration: owner.sessionGeneration,
                attemptGeneration: owner.attemptGeneration, operation: Self.allocateOperation(), phase: "input", event: input.name,
                failure: nil, heartbeatCount: 0, details: input.details))
            return
        }
        guard let page = current, page.sources.contains(owner), page.active,
              let input = G2EvenHubEvents.decode(payload, definition: page.definition, session: page.sessionID) else { return }
        if input.terminal {
            let complete = page.completion, close = page.closeCompletion
            retire(page); emit("closed", page, event: input.name, source: owner, details: input.details)
            complete?(.failure(.disconnected)); close?(.failure(.disconnected))
        } else if !page.closing { emit("input", page, event: input.name, source: owner, details: input.details) }
    }

    /// STREAM is raw LC3 on 6450/6402, never a business protobuf. Require
    /// an authenticated page endpoint and positive captured generations on every frame.
    func receiveAudio(_ packet: Data, owner: Owner) {
        guard let page = current, page.active, !page.closing, page.sources.contains(owner), page.audioEnabled,
              owner.sessionGeneration > 0, owner.attemptGeneration > 0,
              G2AudioDecoder.encodedCount(packet.count) != nil else { return }
        if page.audioDecoders[owner.endpointID] == nil {
            page.audioDecoders[owner.endpointID] = G2AudioDecoder()
        }
        guard let decoder = page.audioDecoders[owner.endpointID],
              let frame = decoder.decode(packet), !frame.audioPcm.isEmpty else { return }
        var details: [String: Any] = [
            "audioPcm": frame.audioPcm, "source": "glasses", "speakerRole": frame.speakerRole,
            "sampleRate": 16000, "channels": 1, "bitsPerSample": 16]
        if let direction = frame.direction { details["direction"] = direction }
        emit("input", page, event: "audioEvent", source: owner, details: details)
    }

    private func submit(_ requests: [G2EvenHubCommands.Request], page: Page, completion: @escaping Completion,
                        beforeSend: () -> Void = {}, image: ImageJob? = nil) {
        guard current === page, !requests.isEmpty else { return }
        page.cancelTimer?(); page.cancelTimer = nil; page.timerID = nil
        page.completion = completion
        let id = UUID(); page.pendingID = id; page.pendingName = requests[0].name
        beforeSend()
        guard current === page, page.pendingID == id else { return }
        sendFragment(requests, index: 0, page: page, id: id, image: image)
    }
    private func sendFragment(_ requests: [G2EvenHubCommands.Request], index: Int, page: Page, id: UUID, image: ImageJob? = nil) {
        guard current === page, page.pendingID == id else { return }
        let request = requests[index]
        nextMagic = nextMagic == UInt8.max ? 1 : nextMagic + 1; let magic = nextMagic
        let transport = request.response == nil ? sendOneWay : send
        if request.name == "heartbeat" { logHeartbeat(page, stage: "send", magic: magic) }
        transport(page.owner, request.payload(magic: magic), magic) { [weak self, weak page] result in
            guard let self, let page, self.current === page, page.pendingID == id else { return }
            switch result {
            case .failure(let error):
                if request.name == "heartbeat" {
                    self.logHeartbeat(page, stage: "transportFailure", magic: magic, accepted: false,
                        reason: "transport.\(String(describing: error))")
                }
                if let image, [.timedOut, .transportRejected, .deviceRejected].contains(error),
                   self.retryImage(image, page: page) { return }
                self.fail(page, failure: String(describing: error), error: .transportFailed)
            case .success(let response):
                let accepted = request.response == nil || request.accepts(response, magic: magic)
                if request.name == "heartbeat" {
                    self.logHeartbeat(page, stage: "ack", magic: magic, response: response, accepted: accepted,
                        reason: accepted ? "accepted" : (request.rejectionReason(response, magic: magic) ?? "ackValidation"))
                }
                guard accepted else {
                    if let image, self.retryImage(image, page: page) { return }
                    self.fail(page, failure: "deviceRejected", error: .deviceRejected); return
                }
                if index + 1 < requests.count {
                    // Each image chunk has its own callback token. A duplicate
                    // previous ACK cannot advance the next native fragment.
                    self.continueImage(requests, index: index + 1, page: page, image: image); return
                }
                let completion = page.completion; page.completion = nil
                page.pendingID = nil; page.pendingName = nil
                if let definition = request.definition { page.definition = definition }
                let awaitsExit = request.name == "shutdownPage"
                    && G2EvenHubCommands.fields(request.body)?[1]?.number == 1
                var result: [String: Any] = ["command": request.name, "operation": page.operation,
                    "pageSessionId": page.sessionID, "heartbeatCount": request.name == "heartbeat" ? page.heartbeatCount : page.confirmedHeartbeatCount,
                    "acknowledged": request.response != nil, "fragments": requests.count,
                    "imageFragments": image == nil ? 0 : requests.count, "retries": image?.retries ?? 0]
                if request.name == "shutdownPage" {
                    result["awaitingExit"] = awaitsExit
                    if awaitsExit {
                        // exit_mode=1 only opens firmware confirmation. Its ACK
                        // does not prove exit; retain/renew until scoped EXIT.
                        page.closing = false
                        self.emit("active", page, details: ["awaitingExit": true])
                        completion?(.success(result))
                        self.armHeartbeat(page)
                    } else {
                        self.retire(page); self.emit("closed", page); completion?(.success(result))
                    }
                    return
                }
                if request.name == "audioControl" {
                    page.audioEnabled = false; page.audioDecoders.removeAll()
                    if G2EvenHubCommands.fields(request.body)?[1]?.number == 1, !page.closing {
                        guard let decoder = G2AudioDecoder() else {
                            self.fail(page, failure: "audioDecoderUnavailable", error: .transportFailed)
                            completion?(.failure(.transportFailed)); return
                        }
                        page.audioDecoders[page.owner.endpointID] = decoder
                        page.audioEnabled = true
                    }
                }
                if request.name == "createPage" { page.active = true }
                if request.name == "createPage" || request.name == "heartbeat" { page.leaseDueAt = self.now() + 5 }
                if request.name == "heartbeat" { page.confirmedHeartbeatCount = page.heartbeatCount }
                if !page.closing {
                    if request.name == "createPage" { self.emit("active", page) }
                    else if request.name == "heartbeat" { self.emit("heartbeat", page) }
                }
                completion?(.success(result))
                guard self.current === page else { return }
                if page.closing { self.drainClose(page) } else { self.armHeartbeat(page) }
            }
        }
    }
    /// A long image operation cannot suppress the firmware's 15-second page
    /// lease. Renew between ACKed chunks at five seconds, never mid-fragment.
    private func continueImage(_ requests: [G2EvenHubCommands.Request], index: Int, page: Page, image: ImageJob?) {
        guard current === page else { return }
        if page.closing {
            let completion = page.completion; page.completion = nil
            page.pendingID = nil; page.pendingName = nil
            completion?(.failure(.cancelled)); drainClose(page); return
        }
        let token = UUID(); page.pendingID = token
        if now() < page.leaseDueAt {
            sendFragment(requests, index: index, page: page, id: token, image: image); return
        }
        page.heartbeatCount &+= 1
        let count = page.heartbeatCount
        guard let renewal = try? G2EvenHubCommands.requests("heartbeat", parameters: [:], page: page.definition,
            session: page.sessionID, count: count, mapSession: 0).first else {
            fail(page, failure: "heartbeatRejected", error: .transportFailed); return
        }
        nextMagic = nextMagic == UInt8.max ? 1 : nextMagic + 1; let magic = nextMagic
        logHeartbeat(page, stage: "send", magic: magic)
        send(page.owner, renewal.payload(magic: magic), magic) { [weak self, weak page] response in
            guard let self, let page, self.current === page, page.pendingID == token else { return }
            let accepted: Bool
            switch response {
            case .success(let payload):
                accepted = renewal.accepts(payload, magic: magic)
                self.logHeartbeat(page, stage: "ack", magic: magic, response: payload, accepted: accepted,
                    reason: accepted ? "accepted" : (renewal.rejectionReason(payload, magic: magic) ?? "ackValidation"))
            case .failure(let error):
                accepted = false
                self.logHeartbeat(page, stage: "transportFailure", magic: magic, accepted: false,
                    reason: "transport.\(String(describing: error))")
            }
            guard accepted else {
                self.fail(page, failure: "heartbeatRejected", error: .transportFailed); return
            }
            page.leaseDueAt = self.now() + 5
            page.confirmedHeartbeatCount = count
            if !page.closing { self.emit("heartbeat", page) }
            // Allocate another token before continuing, so duplicate renewal
            // ACKs cannot accidentally advance a subsequent image fragment.
            self.continueImage(requests, index: index, page: page, image: image)
        }
    }
    /// Native diagnostics distinguish transport failure from semantic ACK
    /// rejection without logging protobuf, page text, licenses or signatures.
    private func logHeartbeat(_ page: Page, stage: String, magic: UInt8, response: Data? = nil,
                              accepted: Bool? = nil, reason: String = "pending") {
        let root = response.flatMap { G2EvenHubCommands.fields(Array($0)) }
        let values = root?[15]?.data.flatMap { G2EvenHubCommands.fields($0) }
        let counterPresent = values?[1] != nil
        let counter = values?[1]?.number.map(String.init) ?? "absentOrInvalid"
        let result = values?[2]?.number.map(String.init) ?? "absentOrInvalid"
        let acceptedText = accepted.map(String.init) ?? "pending"
        let rootCommand = root?[1]?.number.map(String.init) ?? "absentOrInvalid"
        let rootMagic = root?[2]?.number.map(String.init) ?? "absentOrInvalid"
        let rootFields = response.map { G2EvenHubCommands.fieldMetadata(Array($0)) } ?? "absent"
        let branchFields = root?[15]?.data.map { G2EvenHubCommands.fieldMetadata($0) } ?? "absentOrInvalid"
        NSLog("%@", "[EvenDevicesSDK][EvenHubHeartbeat] command=heartbeat stage=\(stage) operation=\(page.operation) localCount=\(page.heartbeatCount) confirmedCount=\(page.confirmedHeartbeatCount) magic=\(magic) ackCountPresent=\(counterPresent) ackCount=\(counter) resultCode=\(result) rootCmd=\(rootCommand) rootMagic=\(rootMagic) rootFields=[\(rootFields)] branch15Fields=[\(branchFields)] accepted=\(acceptedText) reason=\(reason)")
    }
    private func retryImage(_ job: ImageJob, page: Page) -> Bool {
        guard !page.closing, job.retries == 0,
              let requests = try? G2EvenHubCommands.requests("updateImage", parameters: job.parameters,
                page: page.definition, session: page.sessionID, count: page.heartbeatCount,
                mapSession: UInt32(truncatingIfNeeded: Self.allocateOperation())) else { return false }
        job.retries += 1
        continueImage(requests, index: 0, page: page, image: job)
        return true
    }
    private func drainClose(_ page: Page) {
        guard current === page, page.pendingID == nil, let requests = page.closeRequests else { return }
        let completion = page.closeCompletion ?? { _ in }
        page.closeRequests = nil; page.closeCompletion = nil
        submit(requests, page: page, completion: completion)
    }
    private func armHeartbeat(_ page: Page) {
        guard current === page, page.active, !page.closing, page.pendingID == nil else { return }
        page.cancelTimer?()
        let token = UUID(); page.timerID = token
        page.cancelTimer = schedule(max(0, page.leaseDueAt - now())) { [weak self, weak page] in
            guard let self, let page, self.current === page, page.active, !page.closing, page.pendingID == nil, page.timerID == token else { return }
            page.cancelTimer = nil; page.timerID = nil
            do { try self.execute("heartbeat", parameters: [:], owner: page.owner) { _ in } }
            catch { self.fail(page, failure: "heartbeatRejected", error: .transportFailed) }
        }
    }
    private func fail(_ page: Page, failure: String, error: G2EvenHubError) {
        let completion = page.completion, close = page.closeCompletion
        retire(page); emit("failed", page, failure: failure)
        completion?(.failure(error)); close?(.failure(error))
    }
    private func retire(_ page: Page) {
        guard current === page else { return }
        page.audioEnabled = false; page.audioDecoders.removeAll()
        current = nil; page.cancelTimer?(); page.cancelTimer = nil; page.timerID = nil
        page.completion = nil; page.closeCompletion = nil; page.pendingID = nil
    }
    private func emit(_ phase: String, _ page: Page, event: String? = nil, failure: String? = nil,
                      source: Owner? = nil, details: [String: Any] = [:]) {
        let owner = source ?? page.owner
        onEvent(G2EvenHubEvent(namespace: owner.namespace, serialNumber: owner.serialNumber,
            endpointID: owner.endpointID, side: owner.side, sessionGeneration: owner.sessionGeneration,
            attemptGeneration: owner.attemptGeneration, operation: page.operation,
            phase: phase, event: event, failure: failure, heartbeatCount: page.confirmedHeartbeatCount,
            details: details.merging(["pageSessionId": page.sessionID]) { current, _ in current }))
    }
}
