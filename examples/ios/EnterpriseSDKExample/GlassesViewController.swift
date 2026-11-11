import UIKit
import UniformTypeIdentifiers
import EvenDevicesSDK

/// UI observations never grant authorization; Kotlin/Swift selects the exact SN proof.
final class GlassesViewController: UIViewController, UIDocumentPickerDelegate {
    private var sdk: EvenDevicesG2SDK?
    private var importedLicense: Data?
    private var candidates: [String: [G2Side: UUID]] = [:]
    // Epoch is advanced before retiring an owner, whose callbacks can be synchronous.
    private var ownerEpoch: UInt64 = 0
    private var readyOwner: (sn: String, session: UInt64)?
    private var pageActive = false
    private var pcmBytes = 0
    private var lines: [String] = []
    private let serialField = UITextField()
    private let scanDetails = UILabel()
    private let connectionDetails = UILabel()
    private let audioDetails = UILabel()
    private let logs = UITextView()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Enterprise G2 · Native Swift"
        view.backgroundColor = .systemBackground
        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12)
        ])
        stack.addArrangedSubview(button("Import portal license JSON", #selector(importLicense)))
        stack.addArrangedSubview(button("Initialize SDK", #selector(initializeSDK)))
        stack.addArrangedSubview(button("Scan / refresh candidates", #selector(scan)))
        scanDetails.numberOfLines = 0
        scanDetails.text = "Scan: no observations"
        stack.addArrangedSubview(scanDetails)
        serialField.placeholder = "Full 14-byte SN from scan"
        serialField.borderStyle = .roundedRect
        serialField.autocorrectionType = .no
        serialField.autocapitalizationType = .none
        stack.addArrangedSubview(serialField)
        stack.addArrangedSubview(button("Connect selected SN", #selector(connectSelected)))
        connectionDetails.numberOfLines = 0
        connectionDetails.text = "Connection: not initialized"
        stack.addArrangedSubview(connectionDetails)
        let actions = UIStackView(arrangedSubviews: [
            button("Show text", #selector(showText)), button("Close page", #selector(closePage)),
            button("Sync time", #selector(syncTime))
        ])
        actions.distribution = .fillEqually
        stack.addArrangedSubview(actions)
        let audioActions = UIStackView(arrangedSubviews: [
            button("Audio on", #selector(enableAudio)), button("Audio off", #selector(disableAudio)),
            button("Disconnect", #selector(disconnect))
        ])
        audioActions.distribution = .fillEqually
        stack.addArrangedSubview(audioActions)
        audioDetails.text = "PCM: 0 bytes (16 kHz mono PCM16LE)"
        stack.addArrangedSubview(audioDetails)
        logs.isEditable = false
        logs.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        logs.accessibilityLabel = "Native SDK event log"
        stack.addArrangedSubview(logs)
    }

    private func button(_ title: String, _ selector: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.addTarget(self, action: selector, for: .touchUpInside)
        return button
    }

    @objc private func importLicense() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json], asCopy: true)
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            // Validation belongs to the native model; do not transform or select entries here.
            _ = try EnterpriseLicenseBundle(jsonData: data)
            importedLicense = data
            append("License JSON loaded; initialize to apply. Contents are not logged.")
        } catch {
            append("License rejected: \(type(of: error))")
        }
    }

    @objc private func initializeSDK() {
        guard let data = importedLicense else {
            append("Import the portal's license JSON first.")
            return
        }
        do {
            let bundle = try EnterpriseLicenseBundle(jsonData: data)
            ownerEpoch &+= 1
            let epoch = ownerEpoch
            sdk?.stopScan()
            sdk?.disconnect()
            readyOwner = nil
            pageActive = false
            candidates.removeAll()
            pcmBytes = 0
            sdk = EvenDevicesG2SDK(namespace: "enterprise-native-example", licenseBundle: bundle,
                autoReconnect: false,
                onEvent: { [weak self] event in
                    guard let self, self.ownerEpoch == epoch else { return }
                    self.handle(event)
                },
                onEvenHubEvent: { [weak self] event in
                    guard let self, self.ownerEpoch == epoch else { return }
                    if event.phase == "active" { self.pageActive = true }
                    if event.phase == "closed" || event.phase == "failed" { self.pageActive = false }
                    if event.event == "audioEvent", let pcm = event.details["audioPcm"] as? Data {
                        self.pcmBytes += pcm.count
                        self.audioDetails.text = "PCM: \(self.pcmBytes) bytes (16 kHz mono PCM16LE)"
                    } else {
                        self.append("EvenHub \(event.phase), heartbeat=\(event.heartbeatCount), event=\(event.event ?? "-")")
                    }
                })
            connectionDetails.text = "Connection: initialized; waiting for both legs"
            append("Initialized. Accept the system Bluetooth prompt, then scan.")
        } catch {
            append("Initialization rejected: \(type(of: error))")
        }
    }

    private func handle(_ event: G2Event) {
        if event.phase == .discovered, let side = event.side, let id = event.endpointID {
            candidates[event.serialNumber, default: [:]][side] = id
            scanDetails.text = "Scan candidates:\n" + candidates.keys.sorted().map { sn in
                let pair = candidates[sn] ?? [:]
                return "\(sn): L=\(pair[.left] != nil), R=\(pair[.right] != nil)"
            }.joined(separator: "\n")
            if serialField.text?.isEmpty != false { serialField.text = event.serialNumber }
            return
        }
        if event.phase == .ready && event.side == nil {
            readyOwner = (event.serialNumber, event.sessionGeneration)
        } else if event.phase == .connecting ||
                    (event.serialNumber == readyOwner?.sn && event.sessionGeneration == readyOwner?.session &&
                     event.phase != .ready && event.failure != .busy) {
            readyOwner = nil
            pageActive = false
        }
        connectionDetails.text = "Connection: \(event.phase.rawValue)\nSN: \(event.serialNumber)\nBoth legs READY: \(readyOwner != nil)"
        append("G2 \(event.side?.rawValue ?? "pair") \(event.phase.rawValue), failure=\(event.failure?.rawValue ?? "-")")
    }

    @objc private func scan() {
        guard let sdk else { append("Initialize first."); return }
        sdk.scan()
        append("Scan requested; observations are separate from READY.")
    }

    @objc private func connectSelected() {
        guard let sdk, let serial = serialField.text, let pair = candidates[serial],
              let left = pair[.left], let right = pair[.right],
              let endpoints = G2EndpointPair(left: left, right: right) else {
            append("Scan both sides of the exact selected SN first.")
            return
        }
        sdk.stopScan()
        // The native SDK does the exact SN license lookup before a new GATT attempt.
        sdk.connectG2(serialNumber: serial, endpointIDs: endpoints)
    }

    @objc private func showText() {
        guard let sdk, readyOwner != nil else { append("Wait for both legs READY."); return }
        do { try sdk.openEvenHubTextPage(text: "Hello enterprise") }
        catch { append("Page submission rejected: \(error)") }
    }

    @objc private func closePage() {
        guard let sdk else { return }
        do { try sdk.closeEvenHub() }
        catch { append("Page shutdown rejected: \(error)") }
    }

    @objc private func syncTime() {
        guard let sdk, readyOwner != nil, !pageActive else {
            append("Time sync requires READY and a closed EvenHub page.")
            return
        }
        let epoch = ownerEpoch
        let session = readyOwner?.session
        sdk.syncTime { [weak self] result in
            guard let self, self.ownerEpoch == epoch, self.readyOwner?.session == session else { return }
            self.append("Time sync result: \(result)")
        }
    }

    @objc private func enableAudio() { setAudio(true) }
    @objc private func disableAudio() { setAudio(false) }

    private func setAudio(_ enabled: Bool) {
        guard let sdk, readyOwner != nil, pageActive else {
            append("Audio requires an active page and READY.")
            return
        }
        let epoch = ownerEpoch
        let session = readyOwner?.session
        sdk.executeEvenHubCommand(commandName: "audioControl", parameters: ["enabled": enabled]) { [weak self] result in
            guard let self, self.ownerEpoch == epoch, self.readyOwner?.session == session else { return }
            self.append("Audio control result: \(result)")
        }
    }

    @objc private func disconnect() {
        // Clear host state before callbacks from native cancellation can arrive.
        readyOwner = nil
        pageActive = false
        sdk?.stopScan()
        sdk?.disconnect()
        connectionDetails.text = "Connection: disconnected"
    }

    private func append(_ line: String) {
        // Bound the visible log; never dump license JSON, signature or PCM bytes.
        lines.append(line)
        if lines.count > 40 { lines.removeFirst(lines.count - 40) }
        logs.text = lines.joined(separator: "\n")
    }
}
