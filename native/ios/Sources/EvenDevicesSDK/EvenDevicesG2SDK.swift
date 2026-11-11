import CoreBluetooth
import Foundation
import OSLog
#if canImport(UIKit)
import UIKit
#endif

public enum G2Side: String, Hashable {
    case left, right
}

public enum G2Phase: String {
    case discovered, waitingForForeground, connecting, enterpriseAuthorizing, businessAuthenticating
    case retrying, ready, disconnected, failed
}

public enum G2Failure: String {
    case bluetoothUnavailable, deviceNotSeen, proofNotFound, busy
    case connectionFailed, enterpriseServiceMissing, characteristicMissing
    case mtuTooSmall, authorizationNotificationFailed, authorizationWriteFailed
    case authorizationMalformedResult, authorizationSerialMismatch
    case authorizationSignatureRejected, authorizationDeviceError
    case authorizationConnectionState, authorizationTimedOut
    case businessNotificationFailed, businessWriteFailed
    case businessAuthorizationViolation, businessAuthTimedOut
}

/// Events always name their logical session. Endpoint events also name the exact
/// physical attempt, so a host can discard anything from a retired connection.
public struct G2Event {
    public let namespace: String
    public let serialNumber: String
    public let side: G2Side?
    public let endpointID: UUID?
    public let sessionGeneration: UInt64
    public let attemptGeneration: UInt64?
    public let phase: G2Phase
    public let failure: G2Failure?
}

/// A validated, reassembled business event from the active 8402 endpoint.
public struct G2BusinessEvent {
    public let serialNumber: String
    public let side: G2Side
    public let endpointID: UUID
    public let sessionGeneration: UInt64
    public let attemptGeneration: UInt64
    public let service: G2BusinessService
    public let payload: Data
}

public enum G2BusinessCommandError: Error, Equatable {
    case notReady, busy, malformedPayload, packetTooLarge
    case writeFailed, timedOut, transportRejected, deviceRejected, disconnected
}

/// CoreBluetooth owns the complete enterprise gate and the first G2 business AUTH.
/// Public methods and callbacks run on the main queue. This source module has no
/// Flutter runtime, EventChannel, or legacy 5450/540x service dependency.
public final class EvenDevicesG2SDK: NSObject {
    public typealias EventHandler = (G2Event) -> Void
    public typealias BusinessEventHandler = (G2BusinessEvent) -> Void
    public typealias EvenHubEventHandler = (G2EvenHubEvent) -> Void

    private static let service = uuid("8450")
    private static let rx = uuid("8401")
    private static let tx = uuid("8402")
    private static let authorization = uuid("8403")
    // Raw microphone notifications are isolated from COMMON and OTA parsing.
    private static let streamService = uuid("6450")
    private static let streamNotify = uuid("6402")
    private static let otaService = uuid("1001")
    private static let otaWrite = uuid("0001")
    private static let otaNotify = uuid("0002")
    private let otaEventHandler: ((G2OtaEvent) -> Void)?
    private var ota: OtaOperation?
    // Main-thread allocation keeps handles unique across plugin reset/reinitialize.
    private static var nextOtaID: Int64 = 0
    private var otaSync: UInt8 = 0
    // Keep safe command metadata in unified logging as well as the attached
    // console: losing the developer console must not erase the first breakpoint.
    private static let businessLog = Logger(subsystem: "com.evenrealities.devices", category: "G2Business")

    private let namespace: String
    private let autoReconnect: Bool
    private let eventHandler: EventHandler
    private let businessEventHandler: BusinessEventHandler?
    private let evenHubEventHandler: EvenHubEventHandler?
    // The page lease shares this SDK's authenticated owner and existing command
    // transport. Flutter observes events but cannot drive heartbeat timers.
    private lazy var evenHub = G2EvenHubSession(
        send: { [weak self] captured, payload, magic, completion in
            self?.sendEvenHub(captured, payload: payload, magic: magic, responseRequired: true, completion: completion)
                ?? completion(.failure(.disconnected))
        },
        oneWay: { [weak self] captured, payload, magic, completion in
            self?.sendEvenHub(captured, payload: payload, magic: magic, responseRequired: false, completion: completion)
                ?? completion(.failure(.disconnected))
        },
        schedule: { delay, fire in
            let timer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { _ in fire() }
            return { timer.invalidate() }
        },
        onEvent: { [weak self] event in self?.evenHubEventHandler?(event) }
    )
    private var licenses: EnterpriseLicenseBundle
    private var central: CBCentralManager!
    private var scanRequested = false
    private var candidates: [String: [G2Side: CBPeripheral]] = [:]
    private var session: Session?
    private var nextSessionGeneration: UInt64 = 0
    private var nextAttemptGeneration: UInt64 = 0
    private var nextBusinessSyncID: UInt8 = 0
    // AUTH and TimeSync share the firmware-byte sequence so the next settings
    // command does not immediately reuse the last AUTH's correlation value.
    private var nextBusinessMagic: UInt8 = 0
    private var nextCommandSyncID: UInt8 = 0
    // The CoreBluetooth UUID survives Bluetooth reset. Barrier ownership must
    // instead follow the exact peripheral object from the retired attempt.
    private var canceling = Set<ObjectIdentifier>()
    private var reconnectSerial: String?
    // Keep UUID values, never stale CoreBluetooth objects, across transport reset.
    private var reconnectEndpoints: G2EndpointPair?
    // Synchronous CoreBluetooth lookup is deferred until the host is active.
    private var pendingConnection: (serial: String, retry: Int, endpoints: G2EndpointPair)?
    private var foregroundObserver: NSObjectProtocol?
    private var reconnectRetryCount = 0
    private var scheduledReconnectID: UUID?
    private var reconnectBackoff: [String: G2ReconnectBackoff] = [:]
    private var reconnectDelay: TimeInterval = 1

    public init(namespace: String, licenseBundle: EnterpriseLicenseBundle,
                autoReconnect: Bool = false, onEvent: @escaping EventHandler,
                onBusinessEvent: BusinessEventHandler? = nil,
                onEvenHubEvent: EvenHubEventHandler? = nil,
                onOtaEvent: ((G2OtaEvent) -> Void)? = nil) {
        precondition(!namespace.isEmpty)
        self.namespace = namespace
        licenses = licenseBundle
        self.autoReconnect = autoReconnect
        eventHandler = onEvent
        businessEventHandler = onBusinessEvent
        evenHubEventHandler = onEvenHubEvent
        otaEventHandler = onOtaEvent
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
#if canImport(UIKit)
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, let request = self.pendingConnection else { return }
            self.pendingConnection = nil
            self.beginConnection(serialNumber: request.serial, authRetryCount: request.retry,
                                 endpointIDs: request.endpoints)
        }
#endif
    }

    deinit {
        if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver) }
    }

    /// Replaces the whole validated bundle atomically for subsequent attempts.
    public func updateLicenses(_ bundle: EnterpriseLicenseBundle) {
        precondition(Thread.isMainThread)
        licenses = bundle
    }

    public func scan() {
        precondition(Thread.isMainThread)
        scanRequested = true
        if central.state == .poweredOn {
            central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        } else if central.state != .unknown && central.state != .resetting {
            emit(.failed, serial: "", failure: .bluetoothUnavailable)
        }
    }

    public func stopScan() {
        precondition(Thread.isMainThread)
        scanRequested = false
        central.stopScan()
    }

    /// New devices use the two advertised endpoints. A previously bound pair
    /// can instead be retrieved by UUID when a system-connected temple is silent.
    /// Every new session still performs enterprise verification and business AUTH.
    public func connectG2(serialNumber: String, endpointIDs: G2EndpointPair? = nil) {
        precondition(Thread.isMainThread)
        // A rejected concurrent request must not erase the active pair's UUIDs
        // or recovery intent. Internal retries repeat the same admission check.
        guard session == nil, canceling.isEmpty, ota == nil else {
            emit(.failed, serial: serialNumber, failure: .busy)
            return
        }
        pendingConnection = nil
        scheduledReconnectID = nil
        // A manual request replaces any queued recovery intent, even if its
        // own proof or endpoint lookup subsequently fails admission.
        reconnectSerial = nil
        reconnectEndpoints = nil
        reconnectDelay = 1
        beginConnection(serialNumber: serialNumber, authRetryCount: 0, endpointIDs: endpointIDs)
    }

    /// Sends one retained G2 protobuf command after this leg and its peer are
    /// READY. A response is matched by endpoint, SID and protobuf magic, since
    /// device sync IDs are independent. A second in-flight command fails busy.
    public func sendBusinessCommand(side: G2Side, service: G2BusinessService,
                                    payload: Data, magicRandom: UInt64,
                                    responseRequired: Bool = true,
                                    completion: @escaping (Result<Data, G2BusinessCommandError>) -> Void) {
        precondition(Thread.isMainThread)
        // READY belongs to an authenticated attempt. CoreBluetooth may already
        // have lost the link or its subscription before a delegate callback.
        // Admit the write only against that attempt's live 8450/8401/8402 pair.
        guard let owner = session, owner.endpoints.values.allSatisfy({ $0.phase == .ready }),
              let endpoint = owner.endpoints[side], endpoint.peripheral.state == .connected,
              let rx = endpoint.rx, rx.uuid == Self.rx,
              let tx = endpoint.tx, tx.uuid == Self.tx, tx.isNotifying,
              let commonService = rx.service, commonService === tx.service,
              commonService.uuid == Self.service, commonService.peripheral === endpoint.peripheral else {
            if service == .deviceSettings {
                logBusiness("admissionRejected", details: "reason=notReady requestMagic=\(magicRandom)")
            }
            completion(.failure(.notReady)); return
        }
        // A timed-out ATT write still owns its eventual delegate callback.
        // Preserve that debt until drained; it cannot acknowledge a successor.
        guard ota == nil, endpoint.command == nil, !endpoint.writeLedger.hasPending,
              service == .evenHub || !evenHub.hasPage else {
            if service == .deviceSettings {
                logBusiness("admissionRejected", endpoint: endpoint,
                    details: "reason=busy requestMagic=\(magicRandom) pageActive=\(evenHub.hasPage) attPending=\(endpoint.writeLedger.hasPending)")
            }
            completion(.failure(.busy)); return
        }
        guard G2BusinessFrame.magicRandom(in: payload) == magicRandom else {
            completion(.failure(.malformedPayload)); return
        }
        nextCommandSyncID = nextCommandSyncID == UInt8.max ? 1 : nextCommandSyncID + 1
        guard let packets = G2BusinessFrame.requests(service: service,
            syncID: nextCommandSyncID, payload: payload,
            maximumWriteLength: endpoint.peripheral.maximumWriteValueLength(for: .withResponse)) else {
            completion(.failure(.packetTooLarge)); return
        }
        let command = BusinessCommand(service: service, magicRandom: magicRandom,
            syncID: nextCommandSyncID, packets: packets, responseRequired: responseRequired,
            completion: completion)
        endpoint.command = command
        endpoint.writeLedger.begin(command)
        if service == .deviceSettings {
            logBusiness("send", endpoint: endpoint, command: command,
                details: businessWireSummary(packets[0]))
        }
        endpoint.peripheral.writeValue(packets[0], for: rx, type: .withResponse)
        // Keep the owner and exact command object in the timeout closure. A
        // callback from a retired session cannot complete its replacement.
        command.timeout = Timer.scheduledTimer(withTimeInterval: 3, repeats: false) { [weak self, weak owner, weak endpoint, weak command] _ in
            guard let self, let owner, let endpoint, let command,
                  self.session === owner, endpoint.command === command else { return }
            self.finish(command, on: endpoint, result: .failure(.timedOut))
        }
    }

    /// Matches Dart's right-endpoint TIME_SYNC after both temples are READY.
    /// The iOS timezone offset is converted to 15-minute G2 units; an explicit
    /// offset keeps tests and host-initiated time-zone changes deterministic.
    public func syncTime(epochSeconds: UInt32, utcOffsetMinutes: Int,
                         completion: @escaping (Result<Void, G2BusinessCommandError>) -> Void) {
        precondition(Thread.isMainThread)
        // The current firmware truncates wider magic values in its reply.
        // Choose a compatible value before encoding; never truncate an ACK.
        nextBusinessMagic = G2BusinessFrame.nextFirmwareMagic(after: nextBusinessMagic)
        let magic = UInt16(nextBusinessMagic)
        let payload = G2TimeSyncWire.payload(epochSeconds: epochSeconds,
                                             offsetMinutes: utcOffsetMinutes, magic: magic)
        sendBusinessCommand(side: .right, service: .deviceSettings,
                            payload: payload, magicRandom: UInt64(magic)) { result in
            switch result {
            case .success(let response):
                completion(G2TimeSyncWire.isSuccessfulResponse(response, magic: magic)
                           ? .success(()) : .failure(.deviceRejected))
            case .failure(let error): completion(.failure(error))
            }
        }
    }

    /// Uses the host clock and its current timezone, as Dart does when the
    /// caller does not provide an explicit clock for testing or correction.
    public func syncTime(completion: @escaping (Result<Void, G2BusinessCommandError>) -> Void) {
        precondition(Thread.isMainThread)
        let now = Date()
        let seconds = now.timeIntervalSince1970
        guard seconds >= 0, seconds <= Double(UInt32.max) else {
            completion(.failure(.malformedPayload)); return
        }
        syncTime(epochSeconds: UInt32(seconds),
                 utcOffsetMinutes: TimeZone.current.secondsFromGMT(for: now) / 60,
                 completion: completion)
    }

    /// Presents one bounded text container through the enterprise business
    /// channel. Return acknowledges admission; native page events report ACKs.
    public func openEvenHubTextPage(text: String) throws {
        precondition(Thread.isMainThread)
        guard let owner = session, owner.endpoints.count == 2,
              owner.endpoints.values.allSatisfy({ $0.phase == .ready }),
              let endpoint = owner.endpoints[.right] else { throw G2EvenHubError.notReady }
        guard ota == nil else { throw G2EvenHubError.busy }
        try evenHub.open(text: text, owner: G2EvenHubSession.Owner(
            namespace: namespace, serialNumber: owner.serial,
            endpointID: endpoint.peripheral.identifier, sessionGeneration: owner.generation,
            attemptGeneration: endpoint.attemptGeneration), sources: evenHubSources(owner))
    }

    /// Stops future lease writes immediately and orders shutdown after any
    /// already-submitted CREATE/heartbeat; only its valid device ACK closes it.
    public func closeEvenHub() throws {
        precondition(Thread.isMainThread)
        try evenHub.close()
    }

    /// General EvenHub business arguments are validated and encoded entirely
    /// by native code; completion reports ACK/write completion, not admission.
    public func executeEvenHubCommand(commandName: String, parameters: [String: Any],
        completion: @escaping (Result<[String: Any], G2EvenHubError>) -> Void) {
        precondition(Thread.isMainThread)
        guard let owner = session, owner.endpoints.count == 2,
              owner.endpoints.values.allSatisfy({ $0.phase == .ready }),
              let endpoint = owner.endpoints[.right] else { completion(.failure(.notReady)); return }
        if commandName == "audioControl", parameters["enabled"] as? Bool == true,
           !owner.endpoints.values.contains(where: { $0.streamTX?.isNotifying == true }) { completion(.failure(.notReady)); return }
        let captured = G2EvenHubSession.Owner(namespace: namespace, serialNumber: owner.serial,
            endpointID: endpoint.peripheral.identifier, sessionGeneration: owner.generation,
            attemptGeneration: endpoint.attemptGeneration)
        do { try evenHub.execute(commandName, parameters: parameters, owner: captured,
            sources: evenHubSources(owner), completion: completion) }
        catch let error as G2EvenHubError { completion(.failure(error)) }
        catch { completion(.failure(.invalidArguments)) }
    }

    /// Capture both physical owners once per page. A touch from the left leg
    /// is accepted only with its exact live session/attempt identity.
    private func evenHubSources(_ owner: Session) -> [G2EvenHubSession.Owner] {
        owner.endpoints.values.map { endpoint in
            G2EvenHubSession.Owner(namespace: namespace, serialNumber: owner.serial,
                endpointID: endpoint.peripheral.identifier, sessionGeneration: owner.generation,
                attemptGeneration: endpoint.attemptGeneration, side: endpoint.side.rawValue)
        }
    }

    /// Revalidate the captured right-leg transport on every native command,
    /// including internally scheduled heartbeats and one-way menu responses.
    private func sendEvenHub(_ captured: G2EvenHubSession.Owner, payload: Data, magic: UInt8,
        responseRequired: Bool, completion: @escaping (Result<Data, G2BusinessCommandError>) -> Void) {
        guard let owner = session, owner.serial == captured.serialNumber,
              owner.generation == captured.sessionGeneration,
              let endpoint = owner.endpoints[.right],
              endpoint.peripheral.identifier == captured.endpointID,
              endpoint.attemptGeneration == captured.attemptGeneration else {
            completion(.failure(.disconnected)); return
        }
        sendBusinessCommand(side: .right, service: .evenHub, payload: payload,
            magicRandom: UInt64(magic), responseRequired: responseRequired, completion: completion)
    }

    private func finish(_ command: BusinessCommand, on endpoint: Endpoint,
                        result: Result<Data, G2BusinessCommandError>) {
        guard endpoint.command === command else { return }
        if command.service == .deviceSettings {
            let outcome: String
            switch result {
            case .success: outcome = "responseReceived"
            case .failure(let error): outcome = String(describing: error)
            }
            logBusiness("complete", endpoint: endpoint, command: command, details: "outcome=\(outcome)")
        }
        endpoint.command = nil
        command.timeout?.invalidate()
        command.completion(result)
    }

    /// These diagnostics contain transport identity and numeric protocol
    /// metadata only, never payload bytes, host text, SN or license material.
    /// Restrict routine output to device-settings work to avoid heartbeat spam.
    private func logBusiness(_ stage: String, endpoint: Endpoint? = nil,
                             command: BusinessCommand? = nil, details: String) {
        let generation: UInt64
        if let endpoint, let owner = session, owner.endpoints[endpoint.side] === endpoint {
            generation = owner.generation
        } else { generation = 0 }
        let message = "[EvenDevicesSDK][G2Business] stage=\(stage) side=\(endpoint?.side.rawValue ?? "none") session=\(generation) attempt=\(endpoint?.attemptGeneration ?? 0) service=\(command.map { String($0.service.rawValue) } ?? "none") requestSync=\(command.map { String($0.syncID) } ?? "none") requestMagic=\(command.map { String($0.magicRandom) } ?? "none") writtenPackets=\(command?.packetIndex ?? 0) writeAck=\(command?.writeAcknowledged ?? false) responseCached=\(command?.response != nil) \(details)"
        NSLog("%@", message)
        Self.businessLog.notice("\(message, privacy: .public)")
    }

    /// Header-declared metadata is sufficient to distinguish routing, flags,
    /// truncation and partial-frame admission. Never print an arbitrary prefix
    /// when the incoming data does not have a recognizable transport header.
    private func businessWireSummary(_ data: Data) -> String {
        let bytes = Array(data.prefix(8))
        guard bytes.count == 8, bytes[0] == 0xaa else {
            return "wireBytes=\(data.count) header=unrecognized"
        }
        return "wireBytes=\(data.count) declaredEnd=\(8 + Int(bytes[3])) source=\(bytes[1] & 15) destination=\(bytes[1] >> 4) wireSync=\(bytes[2]) total=\(bytes[4]) index=\(bytes[5]) wireService=\(bytes[6]) flags=\(bytes[7])"
    }

    private func beginConnection(serialNumber: String, authRetryCount: Int,
                                 endpointIDs: G2EndpointPair? = nil) {
        precondition(Thread.isMainThread)
        guard session == nil, canceling.isEmpty else {
            emit(.failed, serial: serialNumber, failure: .busy)
            return
        }
        guard central.state == .poweredOn else {
            emit(.failed, serial: serialNumber, failure: .bluetoothUnavailable)
            return
        }
        // The requested serial must be licensed before retrieving endpoints or
        // issuing any BLE connect. A bundle's first entry is never a fallback.
        guard let proof = licenses.proof(for: serialNumber) else {
            if let op = ota, op.stage == .reconnecting {
                finishOta(op, phase: "failed", failure: "proofNotFound", disconnectLink: false)
            }
            emit(.failed, serial: serialNumber, failure: .proofNotFound)
            return
        }
        let identities = candidates.mapValues { $0.mapValues(\.identifier) }
        guard let ids = G2EndpointPair.select(serialNumber: serialNumber,
                                              advertised: identities, preferred: endpointIDs) else {
            emit(.failed, serial: serialNumber, failure: .deviceNotSeen)
            return
        }
        let pair: [G2Side: CBPeripheral]
        if endpointIDs != nil {
#if canImport(UIKit)
            guard UIApplication.shared.applicationState == .active else {
                pendingConnection = (serialNumber, authRetryCount, ids)
                emit(.waitingForForeground, serial: serialNumber)
                return
            }
#endif
            // Retrieve only this exact host binding. Never associate every
            // system-connected G2 with one SN by name or service membership.
            let retrieved = central.retrievePeripherals(withIdentifiers: [ids.left, ids.right])
            pair = [.left: retrieved.first { $0.identifier == ids.left },
                    .right: retrieved.first { $0.identifier == ids.right }].compactMapValues { $0 }
        } else {
            pair = candidates[serialNumber] ?? [:]
        }
        guard let left = pair[.left], let right = pair[.right] else {
            if let op = ota, op.stage == .reconnecting, ids == op.pair, serialNumber == op.serial {
                // CoreBluetooth may briefly forget one UUID during reboot. Keep
                // the frozen pair and retry discovery until the operation's deadline.
                reconnectSerial = serialNumber
                reconnectEndpoints = ids
                if !central.isScanning {
                    central.scanForPeripherals(withServices: nil,
                        options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
                }
                maybeReconnect()
            } else { emit(.failed, serial: serialNumber, failure: .deviceNotSeen) }
            return
        }
        nextSessionGeneration &+= 1
        nextAttemptGeneration &+= 1
        let leftAttempt = Endpoint(side: .left, peripheral: left,
                                   attemptGeneration: nextAttemptGeneration)
        nextAttemptGeneration &+= 1
        let rightAttempt = Endpoint(side: .right, peripheral: right,
                                    attemptGeneration: nextAttemptGeneration)
        let owner = Session(serial: serialNumber, proof: proof,
                            generation: nextSessionGeneration, authRetryCount: authRetryCount,
                            endpoints: [.left: leftAttempt, .right: rightAttempt])
        session = owner
        reconnectSerial = nil
        reconnectEndpoints = ids
        for endpoint in [leftAttempt, rightAttempt] {
            emit(.connecting, owner: owner, endpoint: endpoint)
            central.connect(endpoint.peripheral)
            armTimeout(endpoint, owner: owner, phase: .connecting,
                       failure: .connectionFailed, seconds: 15)
        }
    }

    /// Cancellation retires the owner before asking CoreBluetooth to disconnect.
    /// Late callbacks therefore cannot promote a new session or issue 8401 writes.
    public func disconnect() {
        precondition(Thread.isMainThread)
        pendingConnection = nil
        scheduledReconnectID = nil
        reconnectSerial = nil
        reconnectEndpoints = nil
        reconnectBackoff.removeAll()
        if let op = ota { finishOta(op, phase: "cancelled", disconnectLink: false) }
        retireSession(reconnect: false)
    }

    private func retireSession(reconnect: Bool, authRetryCount: Int = 0) {
        guard let owner = session else { return }
        session = nil
        reconnectSerial = reconnect ? owner.serial : nil
        reconnectRetryCount = authRetryCount
        var interrupted: [(Endpoint, BusinessCommand)] = []
        for endpoint in owner.endpoints.values {
            endpoint.timeout?.invalidate()
            if let command = endpoint.command {
                interrupted.append((endpoint, command))
            }
            if endpoint.peripheral.state != .disconnected {
                canceling.insert(ObjectIdentifier(endpoint.peripheral))
                central.cancelPeripheralConnection(endpoint.peripheral)
            }
        }
        // Physical cancellation barriers are installed before the page event
        // can synchronously call back into connectG2 from a pure-native host.
        if let op = ota, op.stage != .reconnecting {
            finishOta(op, phase: "failed", failure: "disconnected", disconnectLink: false)
        }
        evenHub.invalidate()
        emit(.disconnected, owner: owner)
        for (endpoint, command) in interrupted {
            finish(command, on: endpoint, result: .failure(.disconnected))
        }
    }

    private func fail(_ failure: G2Failure, owner: Session, endpoint: Endpoint) {
        guard session === owner else { return }
        let retryable: Set<G2Failure> = [.authorizationDeviceError,
                                         .authorizationConnectionState,
                                         .authorizationTimedOut]
        let shouldRetry = retryable.contains(failure) && owner.authRetryCount == 0
        emit(shouldRetry ? .retrying : .failed, owner: owner,
             endpoint: endpoint, failure: failure)
        let retryVerification = ota?.stage == .reconnecting && failure == .connectionFailed
        retireSession(reconnect: shouldRetry || retryVerification, authRetryCount: shouldRetry ? 1 : 0)
        if !shouldRetry && !retryVerification, let op = ota, op.stage == .reconnecting {
            finishOta(op, phase: "failed", failure: "reauthorizationFailed")
        }
        maybeReconnect()
    }

    private func armTimeout(_ endpoint: Endpoint, owner: Session, phase: Endpoint.Phase,
                            failure: G2Failure, seconds: TimeInterval = 3) {
        endpoint.timeout?.invalidate()
        endpoint.timeout = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self, weak owner, weak endpoint] _ in
            guard let self, let owner, let endpoint,
                  self.session === owner, endpoint.phase == phase else { return }
            self.fail(failure, owner: owner, endpoint: endpoint)
        }
    }

    private func completeEnterpriseGate(_ endpoint: Endpoint, owner: Session) {
        guard endpoint.phase == .authorizationWriting,
              endpoint.authWriteAcknowledged, endpoint.authResultAccepted,
              let tx = endpoint.tx else { return }
        endpoint.timeout?.invalidate()
        endpoint.phase = .businessSubscribing
        endpoint.peripheral.setNotifyValue(true, for: tx)
        armTimeout(endpoint, owner: owner, phase: .businessSubscribing,
                   failure: .businessNotificationFailed)
    }

    private func completeBusinessAuth(_ endpoint: Endpoint, owner: Session) {
        guard session === owner, endpoint.phase == .businessWriting,
              endpoint.businessWriteAcknowledged, endpoint.businessCommandAccepted,
              endpoint.businessReplyAccepted else { return }
        endpoint.timeout?.invalidate()
        endpoint.phase = .ready
        if let stream = endpoint.streamTX { endpoint.peripheral.setNotifyValue(true, for: stream) }
        if owner.endpoints.values.allSatisfy({ $0.phase == .ready }) {
            // Menu launch events can arrive before any page is created; bind
            // their admissible sources only after whole-pair business AUTH.
            evenHub.setReadyOwners(evenHubSources(owner))
            reconnectBackoff[owner.serial]?.businessConnected()
            if let op = ota, op.stage == .reconnecting { otaReconnected(op, owner: owner) }
            guard session === owner else { return }
        }
        Self.emitReadyEvents(
            endpoint: { self.emit(.ready, owner: owner, endpoint: endpoint) },
            isCurrent: { self.session === owner && endpoint.phase == .ready },
            bothReady: { owner.endpoints.values.allSatisfy({ $0.phase == .ready }) },
            whole: { self.emit(.ready, owner: owner) }
        )
    }

    /// The host callback is synchronous and may disconnect or reset the SDK.
    /// Recheck the owner after the endpoint event before publishing whole READY.
    static func emitReadyEvents(endpoint: () -> Void, isCurrent: () -> Bool,
                                bothReady: () -> Bool, whole: () -> Void) {
        endpoint()
        guard isCurrent(), bothReady() else { return }
        whole()
    }

    private func emit(_ phase: G2Phase, serial: String, failure: G2Failure? = nil,
                      side: G2Side? = nil, endpointID: UUID? = nil) {
        eventHandler(G2Event(namespace: namespace, serialNumber: serial, side: side, endpointID: endpointID,
                             sessionGeneration: nextSessionGeneration,
                             attemptGeneration: nil, phase: phase, failure: failure))
    }

    private func emit(_ phase: G2Phase, owner: Session, endpoint: Endpoint? = nil,
                      failure: G2Failure? = nil) {
        eventHandler(G2Event(namespace: namespace, serialNumber: owner.serial, side: endpoint?.side,
                             endpointID: endpoint?.peripheral.identifier,
                             sessionGeneration: owner.generation,
                             attemptGeneration: endpoint?.attemptGeneration,
                             phase: phase, failure: failure))
    }

    private static func uuid(_ suffix: String) -> CBUUID {
        CBUUID(string: "00002760-08C2-11E1-9073-0E8AC72E\(suffix)")
    }

    private final class Session {
        let serial: String
        let proof: EnterpriseBindingProof
        let generation: UInt64
        let authRetryCount: Int
        let endpoints: [G2Side: Endpoint]

        init(serial: String, proof: EnterpriseBindingProof, generation: UInt64,
             authRetryCount: Int,
             endpoints: [G2Side: Endpoint]) {
            self.serial = serial
            self.proof = proof
            self.generation = generation
            self.authRetryCount = authRetryCount
            self.endpoints = endpoints
        }

        func endpoint(for peripheral: CBPeripheral) -> Endpoint? {
            endpoints.values.first { $0.peripheral === peripheral }
        }
    }

    private final class Endpoint {
        enum Phase { case connecting, discovering, authorizationSubscribing,
                    authorizationWriting, businessSubscribing, businessWriting, ready, otaRebooted }
        let side: G2Side
        let peripheral: CBPeripheral
        let attemptGeneration: UInt64
        var phase: Phase = .connecting
        var authorization: CBCharacteristic?
        var rx: CBCharacteristic?
        var tx: CBCharacteristic?
        var streamTX: CBCharacteristic?
        // OTA characteristics belong to this physical attempt and are discovered on demand.
        var otaRX: CBCharacteristic?
        var otaTX: CBCharacteristic?
        var timeout: Timer?
        var authWriteAcknowledged = false
        var authResultAccepted = false
        var businessCommandAccepted = false
        var businessWriteAcknowledged = false
        var businessReplyAccepted = false
        var businessMagic: UInt16 = 0
        var businessSyncID: UInt8 = 0
        var command: BusinessCommand?
        let assembler = G2BusinessAssembler()
        let writeLedger = G2BusinessWriteLedger<BusinessCommand>()

        init(side: G2Side, peripheral: CBPeripheral, attemptGeneration: UInt64) {
            self.side = side
            self.peripheral = peripheral
            self.attemptGeneration = attemptGeneration
        }
    }

    /// All ATT fragments and their matching logical response share a lifetime;
    /// replacing the BLE owner cancels this exact native command.
    private final class BusinessCommand {
        let service: G2BusinessService
        let magicRandom: UInt64
        let syncID: UInt8
        let packets: [Data]
        let responseRequired: Bool
        var packetIndex = 0
        let completion: (Result<Data, G2BusinessCommandError>) -> Void
        var timeout: Timer?
        var writeAcknowledged = false
        var response: Data?

        init(service: G2BusinessService, magicRandom: UInt64, syncID: UInt8,
             packets: [Data], responseRequired: Bool,
             completion: @escaping (Result<Data, G2BusinessCommandError>) -> Void) {
            self.service = service
            self.magicRandom = magicRandom
            self.syncID = syncID
            self.packets = packets
            self.responseRequired = responseRequired
            self.completion = completion
        }
    }
}

extension EvenDevicesG2SDK: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            // The latest BLE source invalidates CoreBluetooth object caches
            // across transport reset. Rebind using fresh advertisements or a
            // fresh UUID lookup; authorization facts never survive this boundary.
            if central.state != .unknown { candidates.removeAll() }
            if let owner = session {
                if let op = ota, op.generation == owner.generation { _ = recoverOta(op) }
                retireSession(reconnect: autoReconnect || ota?.stage == .reconnecting)
            }
            // CoreBluetooth invalidated the held objects; their terminal
            // callbacks are no longer required to release this barrier.
            canceling.removeAll()
            return
        }
        if scanRequested || reconnectSerial != nil {
            central.scanForPeripherals(withServices: nil,
                                       options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        }
        maybeReconnect()
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                               advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard let manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              manufacturer.count >= 16, manufacturer[0] == 0x45, manufacturer[1] == 0x52,
              let sn = String(bytes: manufacturer[2..<16], encoding: .ascii),
              let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name else { return }
        let side: G2Side
        if name.contains("_L_") { side = .left }
        else if name.contains("_R_") { side = .right }
        else { return }
        candidates[sn, default: [:]][side] = peripheral
        // Discovery identifies the observed side without pretending a physical
        // attempt exists. Hosts can wait for both identities before first bind.
        emit(.discovered, serial: sn, side: side, endpointID: peripheral.identifier)
        maybeReconnect()
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let owner = session, let endpoint = owner.endpoint(for: peripheral),
              endpoint.phase == .connecting else {
            // A stale didConnect never becomes a new owner. It also must not
            // add a barrier that a reset peripheral can never discharge.
            if central.state == .poweredOn && peripheral.state == .connected {
                central.cancelPeripheralConnection(peripheral)
            }
            return
        }
        peripheral.delegate = self
        endpoint.phase = .discovering
        peripheral.discoverServices([Self.service, Self.streamService])
        armTimeout(endpoint, owner: owner, phase: .discovering, failure: .connectionFailed)
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                               error: Error?) {
        canceling.remove(ObjectIdentifier(peripheral))
        if let owner = session, let endpoint = owner.endpoint(for: peripheral) {
            if autoReconnect && G2ReconnectBackoff.isInvalidParameters(error) {
                var backoff = reconnectBackoff[owner.serial] ?? G2ReconnectBackoff()
                reconnectDelay = backoff.registerInvalidParameters()
                reconnectBackoff[owner.serial] = backoff
                emit(.retrying, owner: owner, endpoint: endpoint, failure: .connectionFailed)
                retireSession(reconnect: true)
            } else {
                fail(.connectionFailed, owner: owner, endpoint: endpoint)
            }
        }
        maybeReconnect()
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                               error: Error?) {
        canceling.remove(ObjectIdentifier(peripheral))
        if let owner = session, let lost = owner.endpoint(for: peripheral) {
            if let op = ota, op.stage != .reconnecting, op.generation == owner.generation,
               op.leftFinished, !op.preflighting, lost.side == .left {
                // A successfully updated left temple may reboot during right transfer.
                lost.phase = .otaRebooted
                emit(.disconnected, owner: owner, endpoint: lost)
                return
            }
            if autoReconnect && G2ReconnectBackoff.isInvalidParameters(error) {
                var backoff = reconnectBackoff[owner.serial] ?? G2ReconnectBackoff()
                reconnectDelay = backoff.registerInvalidParameters()
                reconnectBackoff[owner.serial] = backoff
            }
            if let op = ota, op.generation == owner.generation { _ = recoverOta(op) }
            retireSession(reconnect: autoReconnect || ota?.stage == .reconnecting)
        }
        maybeReconnect()
    }

    private func maybeReconnect() {
        guard canceling.isEmpty, session == nil, let serial = reconnectSerial,
              central.state == .poweredOn, scheduledReconnectID == nil else { return }
        let identities = candidates.mapValues { $0.mapValues(\.identifier) }
        guard G2EndpointPair.select(serialNumber: serial, advertised: identities,
                                    preferred: reconnectEndpoints) != nil else {
            // After a Bluetooth reset CoreBluetooth objects are invalid. Wait
            // for two fresh advertisements before claiming this serial again.
            if !central.isScanning {
                central.scanForPeripherals(withServices: nil,
                                           options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            }
            return
        }
        let authRetryCount = reconnectRetryCount
        let endpointIDs = reconnectEndpoints
        let delay = reconnectDelay
        reconnectDelay = 1
        let scheduleID = UUID()
        scheduledReconnectID = scheduleID
        // The disconnection barrier has completed for both endpoints. A fresh
        // session performs 8403 and business AUTH again; no old READY is reused.
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.scheduledReconnectID == scheduleID else { return }
            self.scheduledReconnectID = nil
            self.reconnectSerial = nil
            self.beginConnection(serialNumber: serial, authRetryCount: authRetryCount,
                                 endpointIDs: endpointIDs)
        }
    }
}

extension EvenDevicesG2SDK: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let owner = session, let endpoint = owner.endpoint(for: peripheral) else { return }
        if let op = transferOwner(owner, endpoint), op.stage == .services {
            guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.otaService }) else {
                finishOta(op, phase: "failed", failure: "otaServiceMissing"); return
            }
            op.stage = .characteristics
            peripheral.discoverCharacteristics([Self.otaWrite, Self.otaNotify], for: service)
            return
        }
        guard endpoint.phase == .discovering else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else {
            fail(.enterpriseServiceMissing, owner: owner, endpoint: endpoint)
            return
        }
        peripheral.discoverCharacteristics([Self.authorization, Self.rx, Self.tx], for: service)
        if let stream = peripheral.services?.first(where: { $0.uuid == Self.streamService }) {
            peripheral.discoverCharacteristics([Self.streamNotify], for: stream)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                           error: Error?) {
        guard let owner = session, let endpoint = owner.endpoint(for: peripheral) else { return }
        if service.uuid == Self.otaService, let op = transferOwner(owner, endpoint), op.stage == .characteristics {
            guard error == nil, let chars = service.characteristics,
                  let rx = chars.first(where: { $0.uuid == Self.otaWrite && $0.properties.contains(.writeWithoutResponse) }),
                  let tx = chars.first(where: { $0.uuid == Self.otaNotify && $0.properties.contains(.notify) })
            else { finishOta(op, phase: "failed", failure: "otaCharacteristicMissing"); return }
            endpoint.otaRX = rx
            endpoint.otaTX = tx
            op.stage = .subscribing
            guard peripheral.maximumWriteValueLength(for: .withoutResponse) - 4 >= 139 else {
                finishOta(op, phase: "failed", failure: "otaPacketTooLarge"); return
            }
            if tx.isNotifying { otaSubscribed(op, owner: owner) }
            else { peripheral.setNotifyValue(true, for: tx) }
            return
        }
        if service.uuid == Self.streamService {
            // Optional capability: missing STREAM does not block ordinary G2
            // AUTH, but audio enable remains closed until notify is confirmed.
            guard error == nil, let stream = service.characteristics?.first(where: {
                $0.uuid == Self.streamNotify && $0.properties.contains(.notify)
            }) else { return }
            endpoint.streamTX = stream
            if endpoint.phase == .ready { peripheral.setNotifyValue(true, for: stream) }
            return
        }
        guard endpoint.phase == .discovering, service.uuid == Self.service else { return }
        let chars = service.characteristics ?? []
        guard error == nil,
              let auth = chars.first(where: { $0.uuid == Self.authorization }),
              let rx = chars.first(where: { $0.uuid == Self.rx }),
              let tx = chars.first(where: { $0.uuid == Self.tx }),
              auth.properties.contains(.notify), auth.properties.contains(.write),
              rx.properties.contains(.write), tx.properties.contains(.notify) else {
            fail(.characteristicMissing, owner: owner, endpoint: endpoint)
            return
        }
        endpoint.authorization = auth
        endpoint.rx = rx
        endpoint.tx = tx
        endpoint.phase = .authorizationSubscribing
        peripheral.setNotifyValue(true, for: auth)
        armTimeout(endpoint, owner: owner, phase: .authorizationSubscribing,
                   failure: .authorizationNotificationFailed)
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didUpdateNotificationStateFor characteristic: CBCharacteristic,
                           error: Error?) {
        guard let owner = session, let endpoint = owner.endpoint(for: peripheral) else { return }
        if endpoint.otaTX === characteristic, let op = transferOwner(owner, endpoint) {
            guard error == nil, characteristic.isNotifying else {
                finishOta(op, phase: "failed", failure: "otaNotifyFailed"); return
            }
            if op.stage == .subscribing { otaSubscribed(op, owner: owner) }
            return
        }
        if endpoint.authorization === characteristic, endpoint.phase == .authorizationSubscribing {
            guard error == nil, characteristic.isNotifying else {
                fail(.authorizationNotificationFailed, owner: owner, endpoint: endpoint); return
            }
            let payload = owner.proof.authorizationPayloadV1()
            // With-response may allow a long characteristic write. Check the
            // negotiated no-response ATT payload as an MTU proxy first, then
            // perform exactly one acknowledged write that fits that payload.
            let attPayload = peripheral.maximumWriteValueLength(for: .withoutResponse)
            let acknowledgedLimit = peripheral.maximumWriteValueLength(for: .withResponse)
            guard attPayload >= 147, attPayload >= payload.count,
                  acknowledgedLimit >= payload.count else {
                fail(.mtuTooSmall, owner: owner, endpoint: endpoint); return
            }
            endpoint.phase = .authorizationWriting
            emit(.enterpriseAuthorizing, owner: owner, endpoint: endpoint)
            peripheral.writeValue(payload, for: characteristic, type: .withResponse)
            armTimeout(endpoint, owner: owner, phase: .authorizationWriting,
                       failure: .authorizationTimedOut)
        } else if endpoint.tx === characteristic, endpoint.phase == .businessSubscribing {
            guard error == nil, characteristic.isNotifying, let rx = endpoint.rx else {
                fail(.businessNotificationFailed, owner: owner, endpoint: endpoint); return
            }
            // Keep the Dart 8-bit magic range and advance across attempts so
            // the correlated command ACK cannot match the previous request.
            nextBusinessMagic = G2BusinessFrame.nextFirmwareMagic(after: nextBusinessMagic)
            endpoint.businessMagic = UInt16(nextBusinessMagic)
            nextBusinessSyncID = nextBusinessSyncID == UInt8.max ? 1 : nextBusinessSyncID + 1
            endpoint.businessSyncID = nextBusinessSyncID
            let packet = G2AuthenticationWire.request(
                syncID: endpoint.businessSyncID, magic: endpoint.businessMagic
            )
            guard peripheral.maximumWriteValueLength(for: .withResponse) >= packet.count else {
                fail(.mtuTooSmall, owner: owner, endpoint: endpoint); return
            }
            endpoint.phase = .businessWriting
            emit(.businessAuthenticating, owner: owner, endpoint: endpoint)
            peripheral.writeValue(packet, for: rx, type: .withResponse)
            armTimeout(endpoint, owner: owner, phase: .businessWriting,
                       failure: .businessAuthTimedOut)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                           error: Error?) {
        guard let owner = session, let endpoint = owner.endpoint(for: peripheral) else { return }
        if let op = ota, op.stage == .readback, op.generation == owner.generation,
           endpoint.side == .right, characteristic === endpoint.rx {
            guard error == nil else { finishOta(op, phase: "failed", failure: "versionReadFailed"); return }
            if op.readback?.acknowledged(now: ProcessInfo.processInfo.systemUptime) == true {
                completeVerifiedOta(op, owner: owner)
            }
            return
        }
        if endpoint.authorization === characteristic, endpoint.phase == .authorizationWriting {
            guard error == nil else {
                fail(.authorizationWriteFailed, owner: owner, endpoint: endpoint); return
            }
            endpoint.authWriteAcknowledged = true
            completeEnterpriseGate(endpoint, owner: owner)
        } else if endpoint.rx === characteristic, endpoint.phase == .businessWriting {
            guard error == nil else {
                let nsError = error as NSError?
                let failure: G2Failure = nsError?.domain == CBATTErrorDomain && nsError?.code == 8
                    ? .businessAuthorizationViolation : .businessWriteFailed
                fail(failure, owner: owner, endpoint: endpoint)
                return
            }
            endpoint.businessWriteAcknowledged = true
            completeBusinessAuth(endpoint, owner: owner)
        } else if endpoint.rx === characteristic, endpoint.phase == .ready {
            // Drain the ATT owner even after its application timeout. A stale
            // callback cannot acknowledge a later command on this endpoint.
            let written = endpoint.writeLedger.consume()
            guard let command = endpoint.command, written === command else {
                if written?.service == .deviceSettings || endpoint.command?.service == .deviceSettings {
                    logBusiness("writeCallbackUnowned", endpoint: endpoint, command: endpoint.command,
                        details: "errorPresent=\(error != nil)")
                }
                return
            }
            if command.service == .deviceSettings {
                logBusiness("writeCallback", endpoint: endpoint, command: command,
                    details: "errorPresent=\(error != nil)")
            }
            guard error == nil else {
                finish(command, on: endpoint, result: .failure(.writeFailed)); return
            }
            command.packetIndex += 1
            if command.packetIndex < command.packets.count {
                endpoint.writeLedger.begin(command)
                endpoint.peripheral.writeValue(command.packets[command.packetIndex], for: characteristic, type: .withResponse)
                return
            }
            command.writeAcknowledged = true
            if !command.responseRequired {
                finish(command, on: endpoint, result: .success(Data()))
                return
            }
            if let response = command.response {
                finish(command, on: endpoint, result: .success(response))
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                           error: Error?) {
        guard let owner = session, let endpoint = owner.endpoint(for: peripheral) else { return }
        if endpoint.streamTX === characteristic {
            guard error == nil, characteristic.isNotifying, endpoint.phase == .ready, ota == nil,
                  owner.endpoints.values.allSatisfy({ $0.phase == .ready }),
                  let packet = characteristic.value else { return }
            evenHub.receiveAudio(packet, owner: G2EvenHubSession.Owner(
                namespace: namespace, serialNumber: owner.serial, endpointID: peripheral.identifier,
                sessionGeneration: owner.generation, attemptGeneration: endpoint.attemptGeneration,
                side: endpoint.side.rawValue))
            return
        }
        if endpoint.otaTX === characteristic, let op = transferOwner(owner, endpoint) {
            guard error == nil, let data = characteristic.value else {
                finishOta(op, phase: "failed", failure: "otaNotifyFailed"); return
            }
            receiveOta(op, endpoint: endpoint, data: data)
            return
        }
        if let op = ota, op.stage == .readback, op.generation == owner.generation,
           endpoint.side == .right, characteristic === endpoint.tx, let data = characteristic.value,
           error == nil, let result = G2OtaVersionWire.response(data, magic: op.versionMagic) {
            if op.readback?.received(result, now: ProcessInfo.processInfo.systemUptime) == true {
                completeVerifiedOta(op, owner: owner)
            }
            return
        }
        if endpoint.authorization === characteristic, endpoint.phase == .authorizationWriting {
            guard error == nil, let bytes = characteristic.value, bytes.count == 2,
                  bytes[0] == 1 else {
                fail(.authorizationMalformedResult, owner: owner, endpoint: endpoint); return
            }
            let failure: G2Failure?
            switch bytes[1] {
            case 0: failure = nil
            case 1: failure = .authorizationMalformedResult
            case 2: failure = .authorizationSerialMismatch
            case 3: failure = .authorizationSignatureRejected
            case 4: failure = .authorizationDeviceError
            case 6: failure = .authorizationConnectionState
            default: failure = .authorizationMalformedResult
            }
            if let failure { fail(failure, owner: owner, endpoint: endpoint); return }
            endpoint.authResultAccepted = true
            completeEnterpriseGate(endpoint, owner: owner)
        } else if endpoint.tx === characteristic, endpoint.phase == .businessWriting {
            guard error == nil, let data = characteristic.value,
                  let signal = G2AuthenticationWire.classify(
                    data, requestMagic: endpoint.businessMagic
                  ) else { return }
            // The firmware push uses independent IDs. Retain it only on this
            // owner's exact endpoint/characteristic after the enterprise gate.
            switch signal {
            case .commandAccepted: endpoint.businessCommandAccepted = true
            case .authenticated: endpoint.businessReplyAccepted = true
            }
            completeBusinessAuth(endpoint, owner: owner)
        } else if endpoint.tx === characteristic, endpoint.phase == .ready {
            guard error == nil, let data = characteristic.value else {
                if endpoint.command?.service == .deviceSettings {
                    logBusiness("receiveError", endpoint: endpoint, command: endpoint.command,
                        details: "errorPresent=\(error != nil) valuePresent=\(characteristic.value != nil)")
                }
                return
            }
            // Observe every incoming candidate while TimeSync is pending,
            // including wrong SID/magic and malformed/partial frames. Diagnosis
            // does not change their existing admission or notification routing.
            let trace = endpoint.command?.service == .deviceSettings ||
                (data.count >= 8 && data[data.startIndex] == 0xaa && data[data.startIndex + 6] == 0x80)
            if trace { logBusiness("receive", endpoint: endpoint, command: endpoint.command, details: businessWireSummary(data)) }
            guard let frame = endpoint.assembler.append(data) else {
                if trace { logBusiness("receiveRejectedOrPartial", endpoint: endpoint, command: endpoint.command, details: "reason=frameNotCompleteOrInvalid") }
                return
            }
            if trace {
                let metadata = frame.service == .deviceSettings ? G2TimeSyncWire.responseDiagnostics(frame.payload) : "payloadMetadata=notDeviceSettings"
                logBusiness("parsed", endpoint: endpoint, command: endpoint.command,
                    details: "responseService=\(frame.service.rawValue) responseSync=\(frame.syncID) notify=\(frame.isNotification) transportResult=\(frame.resultCode) \(metadata)")
            }
            if frame.isNotification {
                guard frame.resultCode == 0 else { return }
                if frame.service == .evenHub {
                    evenHub.receive(frame.payload, owner: G2EvenHubSession.Owner(
                        namespace: namespace, serialNumber: owner.serial,
                        endpointID: peripheral.identifier, sessionGeneration: owner.generation,
                        attemptGeneration: endpoint.attemptGeneration, side: endpoint.side.rawValue))
                    guard session === owner, endpoint.phase == .ready else { return }
                }
                // Freeze source identity before calling the host. Its callback
                // may disconnect or replace the active session synchronously.
                businessEventHandler?(G2BusinessEvent(
                    serialNumber: owner.serial, side: endpoint.side,
                    endpointID: peripheral.identifier, sessionGeneration: owner.generation,
                    attemptGeneration: endpoint.attemptGeneration,
                    service: frame.service, payload: frame.payload
                ))
                return
            }
            guard let command = endpoint.command, command.service == frame.service else {
                if trace { logBusiness("responseIgnored", endpoint: endpoint, command: endpoint.command, details: "reason=noPendingCommandOrServiceMismatch") }
                return
            }
            if frame.resultCode != 0 {
                // Transport errors have no trustworthy protobuf magic and
                // must match the original sync ID instead.
                if frame.syncID == command.syncID {
                    finish(command, on: endpoint, result: .failure(.transportRejected))
                }
                return
            }
            guard G2BusinessFrame.magicRandom(in: frame.payload) == command.magicRandom else {
                if trace { logBusiness("responseIgnored", endpoint: endpoint, command: command, details: "reason=magicMismatchOrMalformed") }
                return
            }
            command.response = frame.payload
            if trace { logBusiness("responseMatched", endpoint: endpoint, command: command, details: "waitingForWriteAck=\(!command.writeAcknowledged)") }
            if command.writeAcknowledged {
                finish(command, on: endpoint, result: .success(frame.payload))
            }
        }
    }
}

// MARK: - G2 firmware update

extension EvenDevicesG2SDK {
    /// Starts an exclusive dual-temple transfer on an authenticated pair. The
    /// returned id scopes cancellation even if the host retains an older handle.
    @discardableResult
    public func startOta(firmware: Data, targetVersion: String) throws -> Int64 {
        precondition(Thread.isMainThread)
        guard ota == nil, !evenHub.hasPage else { throw G2OtaError.busy }
        guard let owner = session, let left = owner.endpoints[.left], let right = owner.endpoints[.right],
              owner.endpoints.values.allSatisfy({ $0.phase == .ready && $0.peripheral.state == .connected }),
              let pair = G2EndpointPair(left: left.peripheral.identifier, right: right.peripheral.identifier)
        else { throw G2OtaError.notReady }
        guard owner.endpoints.values.allSatisfy({ $0.command == nil && !$0.writeLedger.hasPending })
        else { throw G2OtaError.busy }
        guard G2OtaVersions.isValidTarget(targetVersion) else { throw G2OtaError.invalidTargetVersion }
        guard let image = try? G2OtaWire.parse(firmware) else { throw G2OtaError.invalidFirmware }
        guard image.version.hasPrefix("s200_") else { throw G2OtaError.invalidFirmware }
        let label = String(image.version.dropFirst("s200_".count))
        guard G2OtaVersions(left: label, right: label).matches(target: targetVersion)
        else { throw G2OtaError.invalidTargetVersion }
        Self.nextOtaID += 1
        let op = OtaOperation(id: Self.nextOtaID, serial: owner.serial, pair: pair,
                              generation: owner.generation, image: image, target: targetVersion)
        op.captureOwners(owner)
        ota = op
        publishOta(op, phase: "started")
        if ota === op { discoverOta(op, owner: owner) }
        return op.id
    }

    /// Retire the physical attempts after cancellation: protocol replies carry
    /// only an 8-bit sync and firmware NOTIFY has no operation id at all.
    public func cancelOta(operationId: Int64) {
        precondition(Thread.isMainThread)
        guard let op = ota, op.id == operationId else { return }
        finishOta(op, phase: "cancelled")
    }

    private final class OtaOperation {
        enum Stage { case services, characteristics, subscribing, transferring, backoff, relayDelay, reconnecting, readback }
        let id: Int64
        let serial: String
        let pair: G2EndpointPair
        let target: String
        let initialGeneration: UInt64
        var generation: UInt64
        var stage: Stage = .services
        var side: G2Side = .left
        var leftFinished = false
        var transfer: G2OtaTransfer
        var packets: ArraySlice<Data> = []
        var timer: Timer?
        var poll: Timer?
        var progress: Double = 0
        var versionMagic: UInt8 = 0
        var readback: G2OtaReadback?
        var startSawInbound = false
        var windowRetries = 0
        var windowStarted: TimeInterval?
        var recovery = G2OtaRecoveryBudget()
        var replaying = false
        var submitted = false
        var plannedSide: G2Side = .left
        var preflighting = true
        var owners: [G2Side: G2OtaLegOwner] = [:]

        init(id: Int64, serial: String, pair: G2EndpointPair, generation: UInt64,
             image: G2OtaImage, target: String) {
            self.id = id
            self.serial = serial
            self.pair = pair
            initialGeneration = generation
            self.generation = generation
            self.target = target
            transfer = G2OtaTransfer(image: image)
        }

        func captureOwners(_ owner: Session) {
            owners = owner.endpoints.mapValues { G2OtaLegOwner(generation: owner.generation,
                attempt: $0.attemptGeneration, peripheralID: $0.peripheral.identifier) }
        }
    }

    /// All callbacks, timers and queued writes recheck the exact operation,
    /// connection generation and peripheral retained by the current endpoint.
    private func transferOwner(_ owner: Session, _ endpoint: Endpoint) -> OtaOperation? {
        guard let op = ota, session === owner, owner.generation == op.generation,
              endpoint.phase == .ready, endpoint.side == op.side,
              op.owners[op.side]?.accepts(generation: owner.generation, attempt: endpoint.attemptGeneration,
                                          peripheralID: endpoint.peripheral.identifier) == true else { return nil }
        return op
    }

    private func discoverOta(_ op: OtaOperation, owner: Session) {
        guard let endpoint = owner.endpoints[op.side], transferOwner(owner, endpoint) === op,
              endpoint.peripheral.state == .connected else {
            finishOta(op, phase: "failed", failure: "disconnected"); return
        }
        op.stage = .services
        otaDeadline(op, seconds: 10, reason: "otaDiscoveryTimeout")
        endpoint.peripheral.discoverServices([Self.otaService])
    }

    /// Discover and subscribe both temples before writing the first START. This
    /// prevents a missing right-side service or short write limit from producing
    /// a partially flashed pair. A parked temple is excluded from replay.
    private func otaSubscribed(_ op: OtaOperation, owner: Session) {
        if op.preflighting && op.side == .left {
            op.side = .right
            discoverOta(op, owner: owner)
            return
        }
        op.preflighting = false
        op.side = op.plannedSide
        guard let next = owner.endpoints[op.side], transferOwner(owner, next) === op,
              next.otaTX?.isNotifying == true, next.peripheral.state == .connected else {
            finishOta(op, phase: "failed", failure: "disconnected"); return
        }
        sendOtaStep(op, endpoint: next)
    }

    private func otaDeadline(_ op: OtaOperation, seconds: TimeInterval, reason: String) {
        op.timer?.invalidate()
        let now = ProcessInfo.processInfo.systemUptime
        let timeout = op.recovery.isRecovering ? min(seconds, max(0.001, op.recovery.remaining(at: now))) : seconds
        op.timer = Timer.scheduledTimer(withTimeInterval: timeout, repeats: false) { [weak self, weak op] timer in
            guard let self, let op, self.ota === op, op.timer === timer else { return }
            if op.recovery.isRecovering, op.recovery.remaining(at: ProcessInfo.processInfo.systemUptime) <= 0 {
                self.finishOta(op, phase: "failed", failure: "otaRecoveryTimeout"); return
            }
            // A missing START ACK can be recovered safely with a fresh sync.
            // Never retransmit an uncertain flash window or RESULT as a START retry.
            if op.stage == .transferring, op.submitted, op.transfer.step == .start, !op.startSawInbound,
               let owner = self.session, let endpoint = owner.endpoints[op.side],
               self.transferOwner(owner, endpoint) === op,
               op.recovery.reserve(at: ProcessInfo.processInfo.systemUptime) {
                self.publishOta(op, phase: "recovering", endpoint: endpoint)
                guard self.ota === op, self.session === owner else { return }
                self.sendOtaStep(op, endpoint: endpoint)
                return
            }
            if op.stage == .transferring, let owner = self.session, let endpoint = owner.endpoints[op.side],
               self.transferOwner(owner, endpoint) === op {
                if !op.submitted {
                    if self.recoverOta(op) {
                        self.retireSession(reconnect: true)
                        self.maybeReconnect()
                    } else { self.finishOta(op, phase: "failed", failure: "otaWriteStalled") }
                    return
                }
                if op.transfer.step == .window, self.retryWindow(op, endpoint: endpoint, code: 4) { return }
                if op.submitted && op.transfer.allowMissingIntermediateResult() {
                    self.sendOtaStep(op, endpoint: endpoint)
                    return
                }
            }
            self.finishOta(op, phase: "failed", failure: reason)
        }
    }

    private func sendOtaStep(_ op: OtaOperation, endpoint: Endpoint) {
        op.stage = .transferring
        op.submitted = false
        op.startSawInbound = false
        if op.transfer.step == .window, op.windowStarted == nil {
            op.windowStarted = ProcessInfo.processInfo.systemUptime
        }
        otaSync = otaSync == 255 ? 1 : otaSync + 1
        let maximum = endpoint.peripheral.maximumWriteValueLength(for: .withoutResponse)
        do {
            op.packets = try op.transfer.packets(sync: Int(otaSync), maxWrite: maximum - 4)[...]
        } catch {
            finishOta(op, phase: "failed", failure: "otaPacketTooLarge"); return
        }
        let finalResult = op.transfer.step == .result && op.transfer.isFinalComponent
        let seconds = op.windowStarted.map { min(15, max(0.001, 30 - (ProcessInfo.processInfo.systemUptime - $0))) }
        let controlTimeout: TimeInterval = finalResult ? 90 : (op.transfer.step == .start ? 5 : 15)
        otaDeadline(op, seconds: seconds ?? controlTimeout, reason: "otaResponseTimeout")
        pumpOta(op, endpoint: endpoint)
    }

    /// CoreBluetooth's ready callback may be coalesced. Poll the same owner
    /// while blocked and preserve the firmware ACK deadline across poll ticks.
    private func pumpOta(_ op: OtaOperation, endpoint: Endpoint) {
        guard let owner = session, transferOwner(owner, endpoint) === op, op.stage == .transferring,
              let rx = endpoint.otaRX, endpoint.peripheral.state == .connected else { return }
        op.poll?.invalidate()
        op.poll = nil
        var submitted = 0
        while !op.packets.isEmpty, endpoint.peripheral.canSendWriteWithoutResponse, submitted < 64 {
            endpoint.peripheral.writeValue(op.packets.removeFirst(), for: rx, type: .withoutResponse)
            submitted += 1
        }
        op.submitted = op.packets.isEmpty
        if !op.packets.isEmpty {
            op.poll = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: false) { [weak self, weak op, weak endpoint] timer in
                guard let self, let op, let endpoint, self.ota === op, op.poll === timer else { return }
                self.pumpOta(op, endpoint: endpoint)
            }
        }
    }

    public func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard let owner = session, let endpoint = owner.endpoint(for: peripheral),
              let op = transferOwner(owner, endpoint), !op.packets.isEmpty else { return }
        pumpOta(op, endpoint: endpoint)
    }

    private func receiveOta(_ op: OtaOperation, endpoint: Endpoint, data: Data) {
        guard op.stage == .transferring, op.submitted else { return }
        let previousStep = op.transfer.step
        if previousStep == .start { op.startSawInbound = true }
        do {
            guard try op.transfer.receive(data) else { return }
        } catch G2OtaTransfer.TransferError.device(let code) {
            if retryWindow(op, endpoint: endpoint, code: code) { return }
            finishOta(op, phase: "failed", failure: "deviceRejected_\(code)"); return
        } catch { finishOta(op, phase: "failed", failure: "malformedResponse"); return }
        if previousStep == .information {
            op.recovery.informationAccepted(at: ProcessInfo.processInfo.systemUptime)
        }
        // Invalidate the previous ACK before calling out to a reentrant observer.
        // waitingNotify alone inherits the receipt of the submitted final RESULT.
        op.submitted = op.transfer.step == .waitingNotify
        op.windowRetries = 0
        op.windowStarted = nil
        let legProgress = Double(op.transfer.confirmedBytes) / Double(max(1, op.transfer.totalBytes))
        let progress = ((op.leftFinished ? 1 : 0) + legProgress) / 2
        if Int(progress * 100) > Int(op.progress * 100) {
            op.progress = progress
            publishOta(op, phase: "transferring", endpoint: endpoint)
            guard ota === op else { return }
        }
        switch op.transfer.step {
        case .waitingNotify: otaDeadline(op, seconds: 90, reason: "otaNotifyTimeout")
        case .finished:
            if op.side == .left {
                op.leftFinished = true
                op.side = .right
                op.plannedSide = .right
                op.stage = .relayDelay
                op.transfer = G2OtaTransfer(image: op.transfer.image)
                op.timer?.invalidate()
                op.timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: false) { [weak self, weak op] _ in
                    guard let self, let op, self.ota === op, let owner = self.session else { return }
                    guard let next = owner.endpoints[.right], self.transferOwner(owner, next) === op,
                          next.peripheral.state == .connected, next.otaTX?.isNotifying == true else {
                        self.finishOta(op, phase: "failed", failure: "disconnected"); return
                    }
                    self.sendOtaStep(op, endpoint: next)
                }
            } else {
                op.progress = 1
                op.stage = .reconnecting
                otaDeadline(op, seconds: 99, reason: "versionUnverified")
                publishOta(op, phase: "transportCompleted")
                guard ota === op else { return }
                // Match the production reboot teardown. Fresh READY must pass both
                // enterprise gates again before the private version request is sent.
                retireSession(reconnect: true)
                maybeReconnect()
            }
        default: sendOtaStep(op, endpoint: endpoint)
        }
    }

    /// Firmware permits retry of this exact RAW window only for CRC, timeout
    /// and resource pressure. A later successful window starts a new budget.
    private func retryWindow(_ op: OtaOperation, endpoint: Endpoint, code: Int) -> Bool {
        guard op.transfer.step == .window, [3, 4, 5].contains(code), op.windowRetries < 10,
              let started = op.windowStarted else { return false }
        let remaining = 30 - (ProcessInfo.processInfo.systemUptime - started)
        guard remaining > 0 else { return false }
        op.windowRetries += 1
        op.submitted = false
        op.poll?.invalidate()
        op.timer?.invalidate()
        op.stage = .backoff
        let delay = min(code == 5 ? 5 : 0.001, remaining)
        op.timer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self, weak op, weak endpoint] timer in
            guard let self, let op, let endpoint, self.ota === op, op.timer === timer else { return }
            if ProcessInfo.processInfo.systemUptime - started >= 30 {
                self.finishOta(op, phase: "failed", failure: "otaResponseTimeout"); return
            }
            self.sendOtaStep(op, endpoint: endpoint)
        }
        return true
    }

    /// Fail closed once RESULT may have committed. Earlier loss replays only
    /// the current component after both old attempts retire and a fresh READY.
    private func recoverOta(_ op: OtaOperation) -> Bool {
        guard op.stage != .readback, op.stage != .reconnecting,
              op.transfer.step != .waitingNotify, !(op.transfer.step == .result && op.submitted),
              op.recovery.reserve(at: ProcessInfo.processInfo.systemUptime) else { return false }
        op.replaying = true
        op.stage = .reconnecting
        op.transfer.restartCurrentComponent()
        op.packets = []
        op.submitted = false
        op.windowRetries = 0
        op.windowStarted = nil
        op.poll?.invalidate()
        otaDeadline(op, seconds: 99, reason: "otaRecoveryTimeout")
        return true
    }

    private func otaReconnected(_ op: OtaOperation, owner: Session) {
        guard owner.generation > op.generation, owner.serial == op.serial,
              owner.endpoints[.left]?.peripheral.identifier == op.pair.left,
              let right = owner.endpoints[.right], right.peripheral.identifier == op.pair.right,
              let rx = right.rx else { finishOta(op, phase: "failed", failure: "versionUnverified"); return }
        op.generation = owner.generation
        op.captureOwners(owner)
        if op.replaying {
            op.replaying = false
            op.side = op.leftFinished ? .right : .left
            op.preflighting = !op.leftFinished
            publishOta(op, phase: "recovering", endpoint: owner.endpoints[op.plannedSide])
            guard ota === op, session === owner else { return }
            discoverOta(op, owner: owner)
            return
        }
        op.leftFinished = false
        op.stage = .readback
        op.readback = G2OtaReadback(target: op.target, now: ProcessInfo.processInfo.systemUptime)
        op.timer?.invalidate()
        publishOta(op, phase: "verifying", endpoint: right)
        guard ota === op, session === owner else { return }
        pollReadback(op, right: right, rx: rx)
    }

    private func completeVerifiedOta(_ op: OtaOperation, owner: Session) {
        guard session === owner, owner.generation == op.generation, owner.endpoints.count == 2,
              owner.endpoints.values.allSatisfy({ $0.phase == .ready && $0.peripheral.state == .connected })
        else { finishOta(op, phase: "failed", failure: "disconnected"); return }
        finishOta(op, phase: "succeeded", disconnectLink: false)
    }

    private func pollReadback(_ op: OtaOperation, right: Endpoint, rx: CBCharacteristic) {
        guard ota === op, let owner = session, owner.generation == op.generation,
              owner.endpoints[.right] === right, right.rx === rx, let readback = op.readback else { return }
        let now = ProcessInfo.processInfo.systemUptime
        guard now < readback.deadline else {
            finishOta(op, phase: "failed", failure: readback.lastVersions == nil ? "versionUnverified" : "versionMismatch",
                      disconnectLink: readback.writeOutstanding)
            return
        }
        // Only a consumed ATT write callback permits succession on the same
        // characteristic. A missed notification can be retried with a new magic.
        if readback.begin(now: now) {
            nextBusinessMagic = G2BusinessFrame.nextFirmwareMagic(after: nextBusinessMagic)
            op.versionMagic = nextBusinessMagic
            nextCommandSyncID = nextCommandSyncID == 255 ? 1 : nextCommandSyncID + 1
            let request = G2OtaVersionWire.request(syncID: nextCommandSyncID, magic: op.versionMagic)
            right.peripheral.writeValue(request, for: rx, type: .withResponse)
        }
        op.timer = Timer.scheduledTimer(withTimeInterval: min(2, readback.deadline - now), repeats: false) { [weak self, weak op, weak right] timer in
            guard let self, let op, let right, op.timer === timer else { return }
            self.pollReadback(op, right: right, rx: rx)
        }
    }

    private func publishOta(_ op: OtaOperation, phase: String, endpoint: Endpoint? = nil, failure: String? = nil) {
        otaEventHandler?(G2OtaEvent(sn: op.serial, generation: op.initialGeneration, operation: op.id,
                                    endpoint: endpoint?.peripheral.identifier.uuidString,
                                    session: op.generation, attempt: endpoint?.attemptGeneration,
                                    leftVersion: op.readback?.lastVersions?.left, rightVersion: op.readback?.lastVersions?.right, phase: phase,
                                    progress: op.progress, failure: failure))
    }

    /// Once we have handed a write to CoreBluetooth it cannot be revoked.
    /// A failed/cancelled transfer must cross a disconnection barrier before retry.
    private func finishOta(_ op: OtaOperation, phase: String, failure: String? = nil, disconnectLink: Bool = true) {
        guard ota === op else { return }
        let endpoint = session?.generation == op.generation ? session?.endpoints[op.side] : nil
        op.timer?.invalidate()
        op.poll?.invalidate()
        ota = nil
        if disconnectLink {
            pendingConnection = nil
            scheduledReconnectID = nil
            reconnectSerial = nil
            retireSession(reconnect: autoReconnect)
            maybeReconnect()
        }
        publishOta(op, phase: phase, endpoint: endpoint, failure: failure)
    }
}
