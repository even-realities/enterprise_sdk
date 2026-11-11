# Enterprise iOS SDK

Connect an enterprise G2 glasses pair from a native Swift app, authorize it with
an SN-bound license, and use Even Hub pages, microphone audio, time synchronization
and firmware updates. `EvenDevicesSDK` owns CoreBluetooth, enterprise and business
authentication, page heartbeats, LC3 decoding and the OTA transaction. It does not
require Flutter.

This guide describes **0.1.0-beta.14**, iOS **15.0+**. It covers G2 only. R1,
consumer services, a notification composition/file-transfer API and background
state restoration are outside this release's high-level API. A low-level
`notification` business SID exists; it is not a complete notification feature.

For Even Hub concepts and application design, see the
[official Get Started / Overview](https://hub.evenrealities.com/docs/get-started/overview).
That site also documents the web SDK. The Swift signatures and native command
parameters in this guide are the contract for this SDK; web/npm method signatures
are not interchangeable with them.

## Install with Swift Package Manager

In Xcode, choose **File → Add Package Dependencies**, enter
`https://github.com/even-realities/enterprise_sdk.git`, select the exact
version **0.1.0-beta.14**, and add **EvenDevicesSDK** to your app target.

For a Swift package consumer:

```swift
// Package.swift dependency
.package(
    url: "https://github.com/even-realities/enterprise_sdk.git",
    exact: "0.1.0-beta.14"
)

// Consumer target dependency
.product(name: "EvenDevicesSDK", package: "enterprise_sdk")
```

The version tag contains a binary XCFramework for arm64 devices and arm64/x86_64
simulators. It does not need CocoaPods, Flutter or a private artifact token.
Bluetooth hardware testing requires a physical iPhone.

Add a meaningful Bluetooth explanation to the app's `Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Connect to your enterprise glasses.</string>
```

CoreBluetooth requests Bluetooth authorization. The glasses microphone is received
over Bluetooth; it does not require permission to record the iPhone microphone.
If your app also records the iPhone microphone, request that separate permission.

## License and trust boundary

Bundle the signing platform's JSON export unchanged, for example as
`enterprise-licenses.json` in the app's resources. Parse it with
`EnterpriseLicenseBundle(jsonData:)`; the native SDK owns validation and exact SN
selection. Do not select the first license in Flutter or app code and send it to
every device.

The portal export shape is:

```json
{
  "format": "enterprise-sdk-license-v1",
  "app_id": "your-enterprise-app-id",
  "licenses": [
    { "sn": "<14 ASCII bytes>", "signature": "<portal Base64 signature>" }
  ]
}
```

The placeholders above are explanatory and are not a valid license. The alternate
SDK bundle shape `{ "schema_version": 1, "app_id": "…", "proofs": { "SN": "…" } }`
is also supported. Do not combine the two formats or add unknown fields.

The native parser validates every entry before publishing the bundle:

- `app_id`: 1–64 UTF-8 bytes; preserve case and spaces exactly.
- SN: exactly 14 ASCII bytes; lookup is exact and case-sensitive.
- Signature: canonical standard Base64, including padding, decoding to 64 bytes.
- Duplicate JSON keys, duplicate SN entries, mixed formats and invalid entries
  reject the entire bundle.

At connection admission, a missing proof produces `proofNotFound` before any BLE
connect. The matched proof is sent independently to both temples through AUTH
characteristic **8403** in enterprise service **8450**. The glasses validate it;
the SDK holds neither a signing private key nor signing-platform credentials.
Only after success does it subscribe to **8402** and perform the business
`AUTHENTICATION/AuthMgr` exchange over **8401**. It does not write legacy **5403**
or fall back to **5450/5401/5402**.

```mermaid
sequenceDiagram
    participant App as Swift App
    participant SDK as EvenDevicesSDK
    participant L as G2 Left
    participant R as G2 Right
    App->>SDK: Parse full license JSON, scan()
    SDK-->>App: discovered(SN, side, endpointID)
    App->>SDK: connectG2(serialNumber: SN)
    SDK->>SDK: Exact SN lookup; reject if absent
    par Left temple
        SDK->>L: Encrypted connection, 8450 discovery
        SDK->>L: Subscribe/write 8403 proof
        L-->>SDK: Enterprise verification success
        SDK->>L: Subscribe 8402, business AUTH via 8401
        L-->>SDK: Business AUTH success
    and Right temple
        SDK->>R: Encrypted connection, 8450 discovery
        SDK->>R: Subscribe/write 8403 proof
        R-->>SDK: Enterprise verification success
        SDK->>R: Subscribe 8402, business AUTH via 8401
        R-->>SDK: Business AUTH success
    end
    SDK-->>App: ready with side == nil
    App->>SDK: createPage / audioControl / startOta
```

## Minimal Swift integration

Retain one SDK owner for the active session. Create it and call its APIs on the
**main queue**; callbacks also run there. This controller accumulates both
discovery records under their exact SN and keeps them separate from READY state.

```swift
import Foundation
import EvenDevicesSDK

final class EnterpriseGlassesController {
    private var sdk: EvenDevicesG2SDK?
    private var discovered: [String: [G2Side: UUID]] = [:]
    private(set) var readySerial: String?
    private var readySession: UInt64?

    func initialize(licenseJSON: Data) throws {
        precondition(Thread.isMainThread)
        let licenses = try EnterpriseLicenseBundle(jsonData: licenseJSON)
        sdk?.stopScan()
        sdk?.disconnect()
        discovered.removeAll()
        readySerial = nil
        readySession = nil
        sdk = EvenDevicesG2SDK(
            namespace: "your-enterprise-app",
            licenseBundle: licenses,
            autoReconnect: false,
            onEvent: { [weak self] event in
                guard let self = self else { return }
                if event.phase == .discovered,
                   let side = event.side, let id = event.endpointID {
                    self.discovered[event.serialNumber, default: [:]][side] = id
                }
                // A temple's READY is insufficient; nil side is whole-pair READY.
                if event.phase == .ready && event.side == nil {
                    self.readySerial = event.serialNumber
                    self.readySession = event.sessionGeneration
                } else if event.phase == .connecting ||
                    (event.phase != .discovered && event.phase != .ready &&
                     event.failure != .busy && event.serialNumber == self.readySerial &&
                     event.sessionGeneration == self.readySession) {
                    // Retire this owner on loss/failure; a rejected competing
                    // request must not erase an already authenticated pair.
                    self.readySerial = nil
                    self.readySession = nil
                }
                if let failure = event.failure {
                    print("G2 phase=\(event.phase.rawValue) failure=\(failure.rawValue)")
                }
            },
            onEvenHubEvent: { event in
                if event.event == "audioEvent",
                   let pcm = event.details["audioPcm"] as? Data {
                    // Copy/enqueue for recording or processing off the main queue.
                    print("PCM bytes=\(pcm.count)")
                }
                print("Page phase=\(event.phase) heartbeats=\(event.heartbeatCount)")
            },
            onOtaEvent: { event in
                print("OTA operation=\(event.operation) phase=\(event.phase) progress=\(event.progress)")
            }
        )
    }

    func scan() {
        sdk?.scan()
    }

    func connectDiscovered(serial: String) {
        guard let endpoints = discovered[serial],
              endpoints[.left] != nil, endpoints[.right] != nil else { return }
        sdk?.stopScan()
        sdk?.connectG2(serialNumber: serial)
    }

    func storedPair(for serial: String) -> G2EndpointPair? {
        guard let endpoints = discovered[serial],
              let left = endpoints[.left], let right = endpoints[.right] else { return nil }
        return G2EndpointPair(left: left, right: right)
    }

    func reconnect(serial: String, pair: G2EndpointPair) {
        sdk?.connectG2(serialNumber: serial, endpointIDs: pair)
    }

    func showText() throws {
        guard let sdk = sdk, readySerial != nil else { return }
        try sdk.openEvenHubTextPage(text: "Hello Even Hub")
    }

    func setGlassesMicrophone(enabled: Bool) {
        sdk?.executeEvenHubCommand(
            commandName: "audioControl", parameters: ["enabled": enabled]
        ) { result in
            print(result) // A success here is the matching device acknowledgement.
        }
    }

    func syncTime() {
        sdk?.syncTime { result in print(result) }
    }

    func closePage() throws {
        try sdk?.closeEvenHub()
    }

    func disconnect() {
        sdk?.stopScan()
        sdk?.disconnect()
        readySerial = nil
        readySession = nil
    }
}
```

Load the resource and start discovery from the main queue:

```swift
let controller = EnterpriseGlassesController() // Retain this in your app owner.
if let url = Bundle.main.url(forResource: "enterprise-licenses", withExtension: "json") {
    try controller.initialize(licenseJSON: Data(contentsOf: url))
    controller.scan()
}
```

`connectG2(serialNumber:)` needs **both temples in this SDK instance's discovery
cache**. Persisting only an SN does not repopulate that cache. After a successful
binding, persist the exact SN plus `G2EndpointPair.left/right` UUID strings under
your account/namespace and pass the reconstructed pair on reconnect. A pair must
contain two different UUIDs. These are CoreBluetooth identifiers, not MAC
addresses, and do not grant authorization or establish device ownership.

The explicit pair uses CoreBluetooth retrieval when a system-connected temple
is no longer advertising. Observed SN/side conflicts are rejected. If retrieval
cannot find both endpoints, `deviceNotSeen` remains possible; foreground the app,
scan again, ensure both temples are powered, and check whether another phone/app
holds them. `deviceNotSeen` occurs before enterprise authorization and does not
mean the signature was rejected. `waitingForForeground` can occur while retrieving
an explicit pair; foreground the app to continue.

## Public API reference

| API | Input and result |
| --- | --- |
| `EnterpriseLicenseBundle(jsonData:) throws` | Parse and validate an entire portal export or SDK bundle. |
| `EnterpriseLicenseBundle(appID:signaturesBySerial:) throws` | Build a validated bundle from an exact SN → Base64 map. |
| `bundle.proof(for:)` | Return an `EnterpriseBindingProof?` for an exact SN; absent means no authorization. |
| `EnterpriseBindingProof(appID:serialNumber:signatureBase64:) throws` | Validate one proof; exposes `appID`, `serialNumber`, decoded `signature`. |
| `proof.authorizationPayloadV1()` | Versioned raw proof payload; normal hosts let the SDK perform the write. |
| `G2EndpointPair(left:right:)` | Failable constructor for a distinct CoreBluetooth UUID pair. |
| `EvenDevicesG2SDK(namespace:licenseBundle:autoReconnect:onEvent:onBusinessEvent:onEvenHubEvent:onOtaEvent:)` | Nonempty namespace; retain the instance. Optional handlers default to nil; reconnect defaults to false. |
| `updateLicenses(_:)` | Atomically replace a fully validated bundle for subsequent connection attempts. Does not retroactively authorize an active session. |
| `scan()` / `stopScan()` | Request/cancel discovery; observe `.discovered` events. |
| `connectG2(serialNumber:endpointIDs:)` | Start one logical two-temple session; optional saved pair defaults to nil. Completion is asynchronous through `onEvent`. |
| `disconnect()` | Cancel recovery/connection intent, retire current page/audio/OTA ownership and disconnect the active pair. |
| `syncTime(completion:)` | Use the phone clock and current time-zone offset; callback `Result<Void, G2BusinessCommandError>`. |
| `syncTime(epochSeconds:utcOffsetMinutes:completion:)` | Explicit UInt32 Unix seconds and integer UTC offset in minutes; same completion. |
| `openEvenHubTextPage(text:) throws` | Convenience single-text page; admission can throw. Observe page `active`/`failed` events for device outcome. |
| `closeEvenHub() throws` | Request native page shutdown; observe `closed` or failure. |
| `executeEvenHubCommand(commandName:parameters:completion:)` | `[String: Any]` arguments; callback `Result<[String: Any], G2EvenHubError>`. Ten commands below. |
| `sendBusinessCommand(side:service:payload:magicRandom:responseRequired:completion:)` | Advanced protobuf transport, not JSON: `Data` payload and matching UInt64 protobuf magic; response defaults true; callback `Result<Data, G2BusinessCommandError>`. |
| `startOta(firmware:targetVersion:) throws -> Int64` | Validated G2 firmware bytes plus expected installed version; returns cancellation handle, not success. |
| `cancelOta(operationId:)` | Cancel only the matching active OTA handle; a stale handle has no effect. |

Advanced business commands accept only `G2BusinessService.notification` (`0x04`),
`.deviceSettings` (`0x80`) and `.evenHub` (`0xE0`). Unknown services are excluded.
The caller must already implement the approved protobuf protocol and correlation
magic; an arbitrary payload is not a supported device command. Use typed/high-level
methods where available. There is one in-flight business command per endpoint;
conflicts return `busy`. Non-EvenHub business writes are blocked while a page owns
the connection. This release has no public native `reset()` method: explicitly
disconnect before replacing the SDK owner.

## Even Hub command contract

Use `executeEvenHubCommand` after **whole-pair READY**. Unless noted otherwise,
commands also require an active page. Do not run a host heartbeat timer. The SDK
owns the page session, message encoding, fragmentation, ACK validation, bounded
retry and lease renewal.

All dictionary keys are case-sensitive; unknown keys are rejected. Integer values
must be finite integral numbers within the documented range; booleans are not
integers. Strings use UTF-8 byte limits, reject NUL and are not normalized.

| Command | Parameters | Completion semantics |
| --- | --- | --- |
| `createPage` | `widgetId`: nonzero UInt32, default `11417`; inline `listObjects`, `textObjects`, `imageObjects`, optional `menu`. | Own one page; success requires create result `0`; renewal starts after ACK. |
| `updateImage` | Existing image `containerId`, matching `containerName`; `data: Data` of 1–1,048,576 bytes; `compressMode`: `0...2`, default `0`. | Native splits into 4,096-byte image fragments; validates matching fragment ACK/result `4`; at most one whole-image retry. |
| `updateText` | Existing text `containerId`, matching `containerName`; `content`: 0–1,999 bytes; `contentOffset`: UInt32 default `0`; `contentLength`: UInt32 default content UTF-8 bytes; optional `textColor: 0...4`. | Success requires result `8`; offsets/lengths are protocol values, not Swift character indices. |
| `rebuildPage` | Inline page fields, without `widgetId`. | Success result `6` replaces container/menu identities while retaining the page session. |
| `shutdownPage` | `exitMode`: `0...1`, default `0`. | Result `10`: mode `0` closes; mode `1` only prompts for exit, returning `awaitingExit: true` and retaining renewal until the exact scoped exit event. |
| `heartbeat` | Empty dictionary. | Matching success result `12`; native accepts the supported firmware response variants. Normal apps let the SDK schedule it. |
| `audioControl` | Required `enabled: Bool`. | Device response must echo the requested state. PCM delivery starts only after enable ACK. |
| `menuStartupFailed` | Required `errorCode`: UInt32; `errorString`: 0–199 bytes, default empty. | One-way transport submission, not a device ACK. Allowed with whole-pair READY before create or after close; creates no page/heartbeat. |
| `imuControl` | Required `enabled: Bool`; `reportFrequency`: `100...1000`, default `100`. | Device status must match the requested state. |
| `menuSubitemResponse` | Required current `parentItemId`, current `subItemId`; `status`: UInt32, default `0`. | One-way transport submission. Respond to a real subitem-switch event within firmware's 10-second deadline. |

For page commands, successful results contain `command`, native `operation`,
`pageSessionId`, `heartbeatCount`, `acknowledged`, `fragments`, `imageFragments`
and `retries`; shutdown also contains `awaitingExit`. One-way commands have
`acknowledged: false`; this proves submission, not firmware acceptance. An
unpaged `menuStartupFailed` result also has `submitted: true` and omits
`pageSessionId`. Do not interpret any one-way result as proof of display.

### Containers and menus

Page fields default to empty arrays; their combined container count must be
**1–12**, IDs must be unique across arrays, and at most one container can capture
input. `listObjects` permits 4, `textObjects` 8 and `imageObjects` 4 containers.

| Field group | Rules |
| --- | --- |
| All containers | Required `containerId`: nonzero UInt32; `containerName`: 1–15 UTF-8 bytes; `width`: `1...576`; `height`: `1...288`. `xPosition: 0...576` and `yPosition: 0...288` default `0`; optional `zOrderIndex`: UInt32. |
| Text/list border and input | `borderWidth: 0...5`, `borderColor: 0...15`, `borderRadius: 0...10`, `paddingLength: 0...32` default `0`; `isEventCapture: Bool` defaults false. These are not image fields. |
| Text | `content`: 0–999 UTF-8 bytes on create/rebuild, default empty; optional `textColor: 0...4`. |
| List | Required `itemContainer` with `itemNames`: 1–20 strings, each 0–63 bytes; `itemWidth: 0...576` default `0`; `isItemSelectBorderEnabled: Bool` default false. |
| Image | Overrides dimensions to `width: 20...288`, `height: 20...144`; send content separately with `updateImage`. |
| Menu | `menu: ["items": [...]]`; 1–10 items with unique nonzero UInt32 `itemId`, `itemName`: 1–31 bytes; `position: 0...10` default `0`; `checkedIndex: 0...subItems.count` default `0`; `subItems` defaults empty, maximum 5, with unique nonzero `itemId` and 1–31-byte `itemName`. |

Every container must fit the display: `xPosition + width <= 576` and
`yPosition + height <= 288`.

For example, create a page, then enable audio only after its successful callback:

```swift
func createAudioPage(on sdk: EvenDevicesG2SDK) {
    let text: [String: Any] = [
        "containerId": 1, "containerName": "message",
        "xPosition": 0, "yPosition": 0, "width": 576, "height": 120,
        "content": "Speak to your glasses", "isEventCapture": true
    ]
    sdk.executeEvenHubCommand(commandName: "createPage", parameters: [
        "widgetId": 11417, "textObjects": [text]
    ]) { result in
        guard case .success = result else { print(result); return }
        sdk.executeEvenHubCommand(commandName: "audioControl", parameters: [
            "enabled": true
        ]) { print($0) }
    }
}
```

`updateImage.data` must already be **firmware-format image bytes encoded for the
chosen `compressMode`**. This SDK does not accept a PNG/JPEG/BMP file as an image
conversion API and does not encode LZ4 for you. The 1 MiB bound is the whole-image
input limit; the logical business payload limit is 32,768 bytes and native image
fragmentation keeps individual requests bounded. Never set native-owned fields
such as `sessionId`, `containerTotalNum`, `mapSessionId`, fragment indexes/sizes,
`count`, `magic` or `syncId` in host parameters.

## Page events, microphone and PCM

`onEvenHubEvent` reports `phase` values `opening`, `active`, `heartbeat`, `closing`,
`closed`, `failed` and `input`. `heartbeatCount` counts confirmed native renewal
ACKs. A successful method return/admission does not prove page creation.

Each event includes namespace, SN, endpoint UUID, source side, connection/attempt
generations, operation, optional event/failure and details. Page events include
`details["pageSessionId"]`; connection-scoped `menuStartup` and timeout diagnostics
can arrive without a page. Do not assume every input has a page ID.

Input event names include `click`, `scrollUp`, `scrollDown`, `doubleClick`,
`foregroundEnter`, `foregroundExit`, `abnormalExit`, `exit`, `longPress`,
`longPressRelease`, `imuData`, `privateEvent`, `menuStartup`, `menuItemClick`,
`menuSubitemSwitch`, `timeoutPageEnter`, `timeoutPageExit` and `audioEvent`.
Container events identify `containerId`, `containerName`, and `kind`; list events
also identify `selectedItemName`/`selectedItemIndex`. Menu switches identify
`parentItemId`, `subItemId`, `subItemIndex`. IMU details have finite `x/y/z`
numbers. System input can identify `source`, `exitReasonCode`, widget and page.
Only exact widget/session-scoped terminal exits retire a page; malformed or unknown
events are ignored rather than treated as a click/success.

After `audioControl(enabled: true)` is acknowledged, the native SDK receives the
glasses STREAM notifications, decodes LC3 and applies native PCM enhancement and
speaker-role smoothing. An `audioEvent` contains:

| Detail | Meaning |
| --- | --- |
| `audioPcm: Data` | Signed **PCM16 little-endian**, mono, **16,000 Hz**; raw samples without a WAV header. |
| `sampleRate`, `channels`, `bitsPerSample` | `16000`, `1`, `16`. |
| `source` | `"glasses"`. |
| `speakerRole` | `"self"`, `"other"` or `"unknown"`; algorithmic classification, not a verified speaker identity. |
| `direction` | Optional signed firmware direction value; do not infer undocumented physical units. |

No extra LC3 decoder or Flutter layer is required. The native decoder/processor
are internal implementation types, not a public API for arbitrary host LC3
buffers. Tagged packets use a one-frame PCM smoothing delay; the first delayed
output is a generated initialization block, not evidence of captured speech.
Disable, page close, disconnect and ownership replacement revoke delivery and
reset decoder state. Disabling revokes delivery immediately while the command
ACK is still pending. Keep callback work small; copy/enqueue PCM for storage,
speech recognition or other processing off the main queue. This is capture from
the glasses, not a glasses speaker-playback API.

## Time synchronization and OTA

Close the active Even Hub page and wait for `closed` before calling `syncTime` or
starting OTA. Time synchronization is sent through the authenticated right
temple. The native method constructs the firmware-compatible clock payload,
time-zone representation and correlation magic; do not prepend BLE frame bytes.
Its completion distinguishes a matched successful device result from timeout,
busy admission and device rejection.

```swift
func startFirmwareUpdate(on sdk: EvenDevicesG2SDK,
                         firmware: Data, targetVersion: String) throws -> Int64 {
    // Both temples must be READY and no page/business command may own transport.
    return try sdk.startOta(firmware: firmware, targetVersion: targetVersion)
}

func cancelFirmwareUpdate(on sdk: EvenDevicesG2SDK, operation: Int64) {
    sdk.cancelOta(operationId: operation)
}
```

Supply a supported G2 firmware package and its expected version label. The SDK
validates package structure and its `s200_` image version against `targetVersion`
before acquiring transport; this is not a generic arbitrary-file uploader.
Observe `onOtaEvent` for phases `started`, `transferring`, `recovering`,
`transportCompleted`, `verifying`, `succeeded`, `failed` or `cancelled`.
`progress` is a `0...1` fraction of firmware-acknowledged bytes across both
temples; it does not prove installation.

**`transportCompleted` is not final success.** After transfer/reboot the SDK
reconnects, repeats authorization and reads the installed versions. Only the
target-matched dual-temple readback produces `succeeded`; missing or mismatched
readback produces `versionUnverified` or `versionMismatch`. Events expose
`leftVersion` and `rightVersion` when available, plus `sn`, `generation`,
`operation`, optional `endpoint`, `session`, `attempt` and `failure`.
Cancellation is scoped to the returned operation ID and retires physical
attempts, preventing old ACKs from completing a new operation. OTA readback and
recovery are native-owned; do not implement a competing reconnect loop.

## Errors and lifecycle

`onEvent` exposes connection phases `discovered`, `waitingForForeground`,
`connecting`, `enterpriseAuthorizing`, `businessAuthenticating`, `retrying`,
`ready`, `disconnected` and `failed`. Endpoint events include `side` and
`attemptGeneration`; whole-device events use a nil side. Only whole-pair READY
enables business actions. Save an authenticated binding only after this point.

| Failure area | Public values and host action |
| --- | --- |
| License parsing | `malformedJSON`, `duplicateJSONKey`, `duplicateSerialNumber`, `unsupportedSchema`, `invalidAppID`, `invalidSerialNumber`, `invalidSignature`, `missingProof` in `EnterpriseLicenseError`. Fix the full bundle before initializing/updating. |
| Connection admission | `bluetoothUnavailable`, `deviceNotSeen`, `proofNotFound`, `busy`. Check permission/power, exact pair discovery/retrieval, exact licensed SN, or existing operation ownership. |
| Enterprise transport | `connectionFailed`, `enterpriseServiceMissing`, `characteristicMissing`, `mtuTooSmall`, `authorizationNotificationFailed`, `authorizationWriteFailed`, `authorizationTimedOut`. Check compatible enterprise firmware and transport diagnostics. No old-service fallback. |
| Firmware authorization | `authorizationMalformedResult`, `authorizationSerialMismatch`, `authorizationSignatureRejected`, `authorizationDeviceError`, `authorizationConnectionState`. Correct the matching proof/firmware issue; a transport connection is not authorization. |
| Business connection | `businessNotificationFailed`, `businessWriteFailed`, `businessAuthorizationViolation`, `businessAuthTimedOut`. Enterprise verification alone is insufficient for READY. |
| Business completion | `notReady`, `busy`, `malformedPayload`, `packetTooLarge`, `writeFailed`, `timedOut`, `transportRejected`, `deviceRejected`, `disconnected` in `G2BusinessCommandError`. Respect native ownership and distinguish ATT/transport failure from firmware rejection. |
| Even Hub completion/admission | `notReady`, `busy`, `invalidText`, `invalidArguments`, `deviceRejected`, `transportFailed`, `disconnected`, `cancelled` in `G2EvenHubError`. Fix arguments, wait for READY/ownership, or inspect the matching page failure. |
| OTA admission | `notReady`, `busy`, `invalidFirmware`, `invalidTargetVersion` in `G2OtaError`. Later transaction failures arrive in `G2OtaEvent.failure`. |

All SDK methods and callbacks use the main queue. Do file loading, heavy image
preparation and PCM consumers outside that queue, then submit results on it.
Capture host owners weakly in retained callbacks to avoid retain cycles.

`autoReconnect: true` enables native recovery of the current logical pair; it
does not persist a last-used SN for your UI and does not provide iOS background
state restoration. Explicit disconnect cancels recovery intent. Stop scanning
when no longer needed. On account/device-owner replacement, disconnect the old
SDK before releasing it and creating the next one; keep UI state and saved
bindings scoped to the account/namespace.

Native code rejects stale session/attempt/operation callbacks. Hosts should also
key delayed UI work by these identities and avoid showing an old operation's
result as a new session's state. Do not log licenses, signature bytes or PCM
content in production diagnostics.

`onBusinessEvent` is the advanced validated/reassembled 8402 event callback:
`G2BusinessEvent` includes `serialNumber`, `side`, `endpointID`,
`sessionGeneration`, `attemptGeneration`, `service` and protobuf `payload: Data`.
It is not a generic unfiltered BLE notification stream. Normal page/audio
consumers should use `onEvenHubEvent` instead.

## Version and verification

This document was checked against the published beta.14 XCFramework interface,
the corresponding native source commit
`f931d52d4716fd150f885f5168b64faabf20783a` and its shared Even Hub command contract.
The Swift examples were typechecked against the shipped framework and iPhoneOS
SDK without Flutter. This documentation work adds **no new physical-device
acceptance**; Bluetooth authorization, both-temple READY, page input/renewal,
microphone audio and OTA installation/readback must be validated on your target
iPhone and enterprise firmware.

Continue with the [Even Hub official overview](https://hub.evenrealities.com/docs/get-started/overview)
for page concepts and interaction design, using the native API contract above
for Swift integration.

## Companion guides

Documentation date: 2026-11-11. Native source and SPM releases are maintained in [enterprise_sdk](https://github.com/even-realities/enterprise_sdk). Existing beta.14 tags in the Flutter repository remain available.

- [Enterprise Flutter SDK](https://github.com/even-realities/flutter_enterprise_sdk/blob/main/docs/enterprise/enterprise-flutter-sdk.md)
- [Android Kotlin](enterprise-android-sdk.md)
- [Shared logical command contract](https://github.com/even-realities/enterprise_sdk/blob/0.1.0-beta.14/native/protocol/evenhub_commands.json)
