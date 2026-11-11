# Enterprise Android SDK

The Even Devices Android SDK connects an Android App directly to enterprise-enabled Even G2 glasses. It runs in Kotlin/Java without Flutter. It owns Bluetooth connections, license selection and authorization, dual-leg business authentication, Even Hub pages and heartbeat, glasses microphone decoding, time synchronization, and G2 firmware upgrades.

This guide targets Maven artifact **0.1.0-beta.14**. Native source reference: `f931d52d4716fd150f885f5168b64faabf20783a`. Companion Flutter plugin reference: `ea53058edac83a6d7ecc6406593ab383c1f41e48`.

For Even Hub concepts and development guidance, continue with [Even Hub: Get Started / Overview](https://hub.evenrealities.com/docs/get-started/overview). The Kotlin interfaces and limits below describe this native release; JavaScript Web SDK names are not interchangeable with them.

## Scope

| Included in beta.14 | Outside this release's public host API |
| --- | --- |
| Enterprise G2 scanning and two-leg connections | R1 rings, G1, consumer G2 connection entry points |
| Native license validation, exact SN lookup, enterprise authorization | Signing-platform access, private-key management, asynchronous license-provider callback |
| Time sync; all ten logical Even Hub commands | General device/settings command facade or phone-notification forwarding |
| Native page heartbeat, page input, IMU and processed microphone audio | Public standalone arbitrary-LC3 decoding or configurable PCM-processing API |
| G2 OTA, cancellation and version verification | R1 DFU, persistent/background automatic reconnection |

Keep application UI, runtime permissions, license delivery, content generation, persistence of the last selected device, and explicit foreground reconnect policy in your App. Firmware must support the enterprise service and the capabilities you use. Audio additionally requires the firmware STREAM service.

## Install from Maven Central

Add `mavenCentral()` to dependency resolution and pin the artifact in the App module:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.evenrealities.devices:even-devices-native-android:0.1.0-beta.14")
}
```

Use a toolchain that can consume Java 17 bytecode and Kotlin 2.1 metadata; Kotlin 2.1.10 is the release baseline. Configure your Kotlin JVM target to 17 as well. The SDK was built with compileSdk 36, AGP 8.7.3 and JDK 21; Android 5.0/API 21 is its runtime minimum. The AAR includes `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` libraries. Bluetooth behavior needs a real device even when an emulator can load the artifact. Maven resolves the declared Gson, Kotlin standard library and Android coroutines dependencies; do not copy the AAR alone and omit its dependencies.

### Permissions and Bluetooth state

Declare the permissions in your host manifest:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

On Android 12/API 31 and later, request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` at runtime before using the SDK. On API 23–30, request fine location before scanning and check that the system location service is enabled. Bluetooth must be enabled. The SDK checks permissions and throws `SecurityException`; it does not display permission or Bluetooth-enable prompts. Receiving the glasses microphone stream does not use the phone microphone and does not require `RECORD_AUDIO` for this feature alone.

## Supply licenses to the native SDK

Load the complete, unmodified platform export and pass it to `EnterpriseLicenseBundle.fromJsonString`. Do not select a proof in Flutter or rebuild a bundle for each target. A platform export has this structure; placeholders below are deliberately not a usable license:

```json
{
  "format": "enterprise-sdk-license-v1",
  "app_id": "your-exact-app-id",
  "licenses": [
    { "sn": "<device-SN>", "signature": "<platform-issued-Base64-signature>" }
  ]
}
```

The native parser validates the entire bundle before connection:

- `app_id`: 1–64 exact UTF-8 bytes. Preserve case and whitespace; it must equal the value signed by the platform.
- SN: exactly 14 ASCII bytes. Selection is exact and case-sensitive; there is no normalization or fallback to the first proof.
- Signature: canonical standard padded Base64, 88 characters ending in `==`, decoding to 64 bytes.
- Duplicate JSON keys, duplicate SNs, invalid entries, unknown fields and mixed schemas are rejected.

The original `{"schema_version":1,"app_id":"…","proofs":{"<SN>":"<signature>"}}` interchange format is also supported. Choose one schema for the whole file. An empty valid bundle grants no device access.

`connectG2(sn, left, right)` selects the proof **inside the native SDK**, before creating a new GATT attempt. An absent SN throws `IllegalArgumentException("No enterprise proof for G2 SN")`. Use the SN and both endpoint identities from the same native discovery result. Do not invent an SN from the advertised device name or substitute a different device's licensed SN.

`updateLicenses(newBundle)` replaces the native snapshot for future connections; it does not authorize an existing session retroactively. OTA reconnect also checks the current bundle. Keep licenses for an upgrading device available through its final version verification. The App stores signature proofs only; neither signing keys nor platform login credentials belong in the App.

## Connection and READY

```mermaid
sequenceDiagram
    participant App
    participant SDK as Kotlin SDK
    participant Legs as G2 left + right
    App->>SDK: initialize(complete License Bundle)
    App->>SDK: scan()
    SDK-->>App: native discovery: SN + endpoints
    App->>SDK: connectG2(SN, left, right)
    SDK->>SDK: exact SN lookup; reject if missing
    SDK->>Legs: encrypted GATT; enterprise service 8450
    SDK->>Legs: subscribe 8403; write signed proof to 8403
    Legs-->>SDK: enterprise authorization result
    SDK->>Legs: subscribe 8402; business AuthMgr via 8401
    Legs-->>SDK: business AUTH responses from each leg
    SDK-->>App: G2AuthEvent(ready=true), only after BOTH legs
```

The enterprise route uses service `00002760-08C2-11E1-9073-0E8AC72E8450`, business RX `…8401`, business TX `…8402` and AUTH `…8403`. The SDK owns the payload encoder, subscription order, write-with-response, bounded authorization retries and connection generation checks. It does not use the old `5450/5401/5402/5403` entry. Business AUTH is the AuthMgr protocol on `8401/8402`, not the old shared-key write to `5403`.

A physical connection, successful `8403`, or raw `connectFinish` alone does not mean the pair is ready. Enable business actions only after `G2AuthEvent.ready == true` for your current pair generation. `onG2ConnectionEvent` reports individual endpoint phases such as `connecting`, `enterpriseAuthorized`, `businessAuthenticating`, `ready` and `disconnected`.

Enterprise `8403` result meanings in this release are:

| Firmware result | Meaning and native policy |
| --- | --- |
| `0` | Enterprise proof accepted for this endpoint; continue business AUTH. |
| `1` | Invalid proof format; terminal rejection. |
| `2` | Device/SN mismatch or not bound; terminal rejection. |
| `3` | Invalid signature; terminal rejection. |
| `4` | Firmware internal error; one reconnect/retry budget per endpoint. |
| `6` | Invalid connection state; one reconnect/retry budget per endpoint. |
| Native result timeout | One reconnect/retry budget per endpoint; repeat timeout is terminal. |
| Other result | Terminal rejection; never treated as authorization. |

The raw `ENTERPRISE_AUTHORIZATION` event carries endpoint, SN, result/reason and generation identities. The high-level `G2AuthEvent` reports whole-pair availability rather than a complete structured enterprise failure enum. Do not substitute a format-valid proof or a retry attempt for a firmware success result.

## Minimal Kotlin integration

The following controller uses the public native API. Save your real export as the App asset `enterprise-licenses.json`. Call `initialize()` once, then `scan()` after the host has granted permissions. Present candidates to the user and call `connect(candidate)` for the chosen pair. The controller does not automatically connect to every scan result.

```kotlin
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.evenrealities.devices.EnterpriseLicenseBundle
import com.evenrealities.devices.EvenDevicesAndroidBle
import com.evenrealities.devices.G2Endpoint
import com.evenrealities.devices.NativeBleEvent

class EnterpriseGlasses(
    context: Context,
    private val onCandidate: (Candidate) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onPcm: (ByteArray) -> Unit,
) {
    data class Candidate(val sn: String, val left: G2Endpoint, val right: G2Endpoint)

    private val appContext = context.applicationContext
    private val sdk = EvenDevicesAndroidBle.instance
    private val main = Handler(Looper.getMainLooper())
    private val discovered = mutableMapOf<String, MutableMap<String, G2Endpoint>>()
    private var selected: Candidate? = null
    private var ready = false

    fun initialize() {
        val json = appContext.assets.open("enterprise-licenses.json")
            .bufferedReader().use { it.readText() }
        val licenses = EnterpriseLicenseBundle.fromJsonString(json)
        sdk.initialize(
            context = appContext,
            licenses = licenses,
            onEvent = { event -> main.post { acceptDiscovery(event) } },
            onG2AuthEvent = { event ->
                if (event.sn == selected?.sn) {
                    ready = event.ready
                    onStatus(if (event.ready) "READY" else "Pair unavailable")
                }
            },
            onG2TimeSyncEvent = { event ->
                onStatus("Time sync ${event.operation}: ${event.success}")
            },
            onG2ConnectionEvent = { event ->
                if (event.phase == "disconnected") ready = false
                onStatus("${event.endpoint}: ${event.phase}")
            },
            onEvenHubEvent = { event ->
                if (event.event == "audioEvent") {
                    (event.details["audioPcm"] as? ByteArray)?.let(onPcm)
                } else {
                    onStatus("EvenHub ${event.phase}; heartbeat=${event.heartbeatCount}")
                }
            },
            onOtaEvent = { event ->
                onStatus("OTA ${event.phase}: ${(event.progress * 100).toInt()}%")
            },
        )
    }

    fun scan() { sdk.scan() }
    fun stopScan() { sdk.stopScan() }

    private fun acceptDiscovery(event: NativeBleEvent) {
        if (event.type.name != "SCAN_RESULT") return
        val raw = event.value as? Map<*, *> ?: return
        if (raw["isPureScanResult"] == true) return
        val sn = raw["sn"] as? String ?: return
        val devices = raw["devices"] as? List<*> ?: return
        val legs = discovered.getOrPut(sn) { mutableMapOf() }
        for (item in devices) {
            val device = item as? Map<*, *> ?: continue
            if (device["belongConfig"] != EvenDevicesAndroidBle.G2_CONFIG || device["sn"] != sn) continue
            val address = device["uuid"] as? String ?: continue
            val name = device["name"] as? String ?: continue
            val side = when {
                name.contains("_L_", ignoreCase = true) -> "left"
                name.contains("_R_", ignoreCase = true) -> "right"
                else -> continue
            }
            legs[side] = G2Endpoint(address, name)
        }
        val left = legs["left"] ?: return
        val right = legs["right"] ?: return
        if (!left.address.equals(right.address, ignoreCase = true)) {
            onCandidate(Candidate(sn, left, right))
        }
    }

    fun connect(candidate: Candidate) {
        sdk.stopScan()
        ready = false
        selected = candidate
        sdk.connectG2(candidate.sn, candidate.left, candidate.right)
    }

    fun showText(text: String): Long {
        check(ready) { "Wait for both legs to be READY" }
        return sdk.openEvenHubTextPage(text)
    }

    fun microphone(enabled: Boolean): Long = sdk.executeEvenHubCommand(
        "audioControl", mapOf("enabled" to enabled),
    ) { result ->
        result.onSuccess { onStatus("Microphone request acknowledged") }
            .onFailure { onStatus(it.message ?: "Microphone request failed") }
    }

    fun closePage() { sdk.closeEvenHub() }

    fun syncTime(): Long {
        val candidate = checkNotNull(selected)
        // Call after the EvenHub closed event, not immediately after closePage().
        return sdk.syncG2Time(candidate.right.address)
    }

    fun startUpgrade(firmware: ByteArray, targetVersion: String): Long {
        // Load bytes off the main thread; wait until page/time-sync work has finished.
        return sdk.startOta(firmware, targetVersion)
    }

    fun cancelUpgrade(operation: Long) { sdk.cancelOta(operation) }

    fun disconnect() {
        selected?.let { sdk.disconnectG2(it.left, it.right) }
        ready = false
        selected = null
    }

    fun release() {
        sdk.reset()
        selected = null
        ready = false
        discovered.clear()
    }
}
```

Catch synchronous permission, validation, lifecycle and busy exceptions at your UI action boundary. Handle asynchronous command failures separately in their `Result` callback. Keep the controller process-scoped or lifecycle-owned so activity recreation does not call `initialize()` twice. When using cached device identities, rescan before a new foreground connection and show the current discovery state separately from the connected state.

## Public host API

All methods below belong to `EvenDevicesAndroidBle.instance`. Kotlin defaults are shown in prose; `Long` tokens identify operations, not success.

| Method | Behavior/result |
| --- | --- |
| `initialize(context, licenses, onEvent, onG2AuthEvent = {}, onG2TimeSyncEvent = {}, onG2ConnectionEvent = {}, onEvenHubEvent = {})` | Registers callbacks and initializes the single native BLE owner; returns `Unit`. A second initialization before reset fails. |
| `initialize(..., onOtaEvent)` | Same initialization with an additional `(G2OtaEvent) -> Unit` callback. Supply it to observe the OTA result. |
| `updateLicenses(EnterpriseLicenseBundle)` | Replaces validated native licenses for future attempts. |
| `scan(): Map<String, Any>` | Starts scanning; immediate status is separate from later raw `SCAN_RESULT` events. |
| `stopScan()` | Stops scanning; requires scan permission as well. |
| `connectG2(sn: String, left: G2Endpoint, right: G2Endpoint)` | Selects the native proof, replaces any previous pair and starts dual-leg authorization. Distinct endpoint addresses are required. |
| `disconnectG2(left, right)` | Cancels pair/page/OTA work and disconnects both supplied endpoints. Pass the selected pair, not unrelated identities. |
| `syncG2Time(endpointAddress, epochSeconds = currentSeconds, offsetMinutes = localUtcOffset): Long` | Sends native time sync through the authenticated **right** endpoint with firmware peer-sync enabled. Completion is `G2TimeSyncEvent`. |
| `openEvenHubTextPage(text: String): Long` | Convenience create for nonempty text up to 64 UTF-8 bytes; uses container ID `1`, name `native-text`, and event capture. |
| `executeEvenHubCommand(command: String, parameters: Map<String, Any?>, completion: (Result<Map<String, Any>>) -> Unit): Long` | Validates and submits one of the ten logical commands below; reports confirmed or explicitly one-way results through completion. |
| `closeEvenHub()` | Requests native shutdown, stops scheduling new heartbeat work and serializes behind an in-flight page command. Wait for `closed` or failure. |
| `startOta(firmware: ByteArray, targetVersion: String): Long` | Starts native two-leg upgrade with a single complete G2 firmware image and required target version. |
| `cancelOta(operationId: Long)` | Cancels only the matching current OTA operation. |
| `reset()` | Cancels operations, disconnects/releases BLE ownership, clears licenses/listeners and allows a later initialization. |

`G2Endpoint(address, name)` contains an Android BLE address and exact advertised name. `EnterpriseBindingProof(appId, sn, signature)` and `EnterpriseLicenseBundle.fromProofs(appId, proofs)` support hosts that already hold individual platform proofs. `findProof(sn)` is exact; `signatureBytes` returns a defensive copy. Prefer loading the exported JSON to preserve validation and avoid accidental license transformation.

## Even Hub commands

The native API accepts logical maps, not Web SDK objects or prebuilt protobuf. `uint32` means a Kotlin integral value in `0..4294967295` (use `Long` for values beyond `Int.MAX_VALUE`). Boolean or floating-point values are not accepted as integers. Unknown keys, invalid UTF-8, embedded NUL and oversized content are rejected as `invalidArguments`. Do not supply native-owned `sessionId`, `containerTotalNum`, `mapSessionId`, fragment metadata, `count`, `magic` or `syncId`. Logical protobuf payloads are limited to 32,768 bytes; image transfers use the separate whole-image limit below.

| Command string | Parameters | Successful completion |
| --- | --- | --- |
| `createPage` | Optional `widgetId` (nonzero uint32, default `11417`); inline `listObjects`, `textObjects`, `imageObjects`, optional `menu` as below. | Device ACK `resultCode=0`; acquires the sole page session and starts native heartbeat. |
| `updateImage` | Existing image `containerId`, matching `containerName`, whole `data: ByteArray` (1–1,048,576 bytes), optional `compressMode` 0–2 (default 0). | Matching ACK `resultCode=4` for every native fragment; includes `imageFragments` and `retries`. |
| `updateText` | Existing text `containerId`, matching `containerName`, `content` (0–1,999 UTF-8 bytes); optional `contentOffset` uint32 (default 0), `contentLength` uint32 (default content byte count), `textColor` 0–4. | ACK `resultCode=8`. Offsets and lengths are byte-oriented logical fields, not Dart string indices. |
| `rebuildPage` | Inline page fields as for create, **without** `widgetId`. | ACK `resultCode=6`; replaces container/menu identities only after acceptance, retaining the native page session. |
| `shutdownPage` | Optional `exitMode` 0 or 1 (default 0). | ACK `resultCode=10`. Mode 0 closes. Mode 1 confirms the exit prompt and returns `awaitingExit=true`; heartbeat continues until the exact page exit event. |
| `heartbeat` | Empty map. Native owns scheduling and counters. | ACK `resultCode=12`; increments locally confirmed heartbeat count. Normally let the native scheduler send this. |
| `audioControl` | Required `enabled: Boolean`. | ACK matches the requested 0/1 target; decoding starts only after enable ACK. |
| `menuStartupFailed` | Required `errorCode` uint32; optional `errorString` (0–199 UTF-8 bytes, default empty). | One-way transport admission: `submitted=true`, `acknowledged=false`. Allowed under pair READY even without a page; creates no page or heartbeat. |
| `imuControl` | Required `enabled: Boolean`; optional `reportFrequency` 100–1,000 (default 100; firmware protocol value). | ACK matches the requested 0/1 target. |
| `menuSubitemResponse` | Existing `parentItemId`, existing `subItemId`; optional `status` uint32 (default 0). | One-way transport admission: `submitted=true`, `acknowledged=false`. Respond to an actual switch event within the firmware's 10-second window. |

Normal successful result maps include `command`, `operation`, `pageSessionId`, `heartbeatCount`, and, for acknowledged commands, `resultCode`/`acknowledged=true`. A page-free `menuStartupFailed` result omits `pageSessionId`. The operation for a page command identifies its owning page; do not assume it is a separate monotonically increasing request ID for every command. A submission return value by itself does not prove device acceptance.

### Page/container schema

Pass `textObjects`, `listObjects` and `imageObjects` directly in the command map, **not** inside a `page` key. Maximum counts are 8 text, 4 list and 4 image containers; the combined count must be 1–12. Container IDs must be unique across all arrays. At most one list/text container may capture events.

| Fields | Rules |
| --- | --- |
| All containers | Required `width`, `height`, `containerId` (nonzero uint32), `containerName` (1–15 UTF-8 bytes). Optional `xPosition`, `yPosition` (default 0), `zOrderIndex` uint32. Bounds must fit the 576×288 canvas. |
| Text/list geometry | Width 1–576; height 1–288. |
| Text/list decorations | Optional `borderWidth` 0–5, `borderColor` 0–15, `borderRadius` 0–10, `paddingLength` 0–32, `isEventCapture` Boolean; defaults 0/false. |
| Text | Optional `content` (0–999 UTF-8 bytes, default empty), `textColor` 0–4. The larger 1,999-byte limit belongs specifically to `updateText`. |
| List | Required `itemContainer` map: `itemNames` list of 1–20 strings, each 0–63 UTF-8 bytes. Optional `itemWidth` 0–576 (default 0), `isItemSelectBorderEnabled` Boolean (default false). |
| Image | Width 20–288; height 20–144. Decoration/event-capture fields are not accepted. Image data is sent afterward with `updateImage`. |
| Menu | `menu: {"items": [...]}` with 1–10 items. Each requires unique nonzero uint32 `itemId` and `itemName` (1–31 UTF-8 bytes). Optional `position` 0–10, `checkedIndex` 0–subitem-count, `subItems` list of at most 5 `{itemId, itemName}` maps with unique IDs within that item. Defaults are 0 and empty list. |

Image `data` must already be encoded in the **firmware image format appropriate to `compressMode`**. Passing PNG/BMP/JPEG file bytes or Android `Bitmap` data does not invoke an image decoder, rasterizer or LZ4 compressor. Native code generates map IDs, divides the whole image into 4,096-byte fragments, correlates ACK metadata and handles bounded image retries. Host code must not create its own image-fragment protocol.

### Heartbeat and interaction ownership

After create ACK, native code sends heartbeat approximately 5 seconds after the preceding acknowledged work and applies a 3-second response timeout. Heartbeat success requires the current native owner and correlated response; a write receipt is insufficient. `G2EvenHubEvent.heartbeatCount` is the count of **confirmed** heartbeats. The host should not run an additional timer or send heartbeat from Dart/UI lifecycle callbacks.

Subscribe to `onEvenHubEvent` before opening a page. `phase` includes `opening`, `active`, `heartbeat`, `input`, `closing`, `closed` and `failed`. `owner` carries `sn`, endpoint, pair `generation`, physical `session` and `attempt`; `operation` and `details.pageSessionId` scope the page. Replacement, disconnect and reset revoke that owner and its pending callbacks.

Interaction details include:

- Device input uses event names `click`, `scrollUp`, `scrollDown`, `doubleClick`, `foregroundEnter`, `foregroundExit`, `abnormalExit`, `exit`, `imuData`, `longPress`, or `longPressRelease`. Details include `kind=list/text/system`; list/text include container identity; lists include selected item name/index. System input includes source (`unspecified`, `rightGlasses`, `ring`, `leftGlasses`) and exit metadata where supplied. A source enum mentioning `ring` does not expose an R1 connection API.
- `imuData`: finite `x`, `y`, `z` values; `privateEvent`: container identity plus `eventId`/`eventData`.
- `timeoutPageEnter` / `timeoutPageExit`: `value`; `menuStartup`: `widgetId`; `menuItemClick`: `itemId`; `menuSubitemSwitch`: `parentItemId`, `subItemId`, `subItemIndex`.

Only an exact widget/session-scoped terminal exit retires the page. Startup/timeout events can also arrive under whole-pair READY without an active page; they do not create one. Do not translate every input event into page close or display readiness.

## Glasses microphone, LC3 and PCM

Open a page and wait for its `active` event before `audioControl(enabled=true)`. The SDK requires a current STREAM `6450/6402` notification capability; ordinary page display can work even when audio is unavailable. A missing capability throws `G2EvenHubException("audioUnavailable")`.

After enable ACK, the native page owner admits STREAM packets, decodes 40-byte LC3 frames with a stateful native decoder, applies the native PCM processing/AGC and speaker-role smoothing, and emits `event == "audioEvent"` with:

| Detail | Meaning |
| --- | --- |
| `audioPcm: ByteArray` | Signed PCM16 little-endian mono samples; not Base64, a WAV file, or raw LC3. |
| `sampleRate`, `channels`, `bitsPerSample` | `16000`, `1`, `16`. One decoded LC3 frame contains 160 samples/320 PCM bytes; a five-frame batch produces 1,600 bytes. |
| `source` | `glasses`. `owner.endpoint` identifies the producing leg. |
| `speakerRole` | `self`, `other` or `unknown`; native inference, not a stable speaker identity. |
| `direction` | Optional signed direction metadata when supplied by the tagged firmware packet. Do not assume it exists for untagged frames. |

Each endpoint keeps independent decoding and processing history. Disable revokes audio admission immediately; page close, reconnect and reset also retire decoders. Re-enable only on a current active page. Callbacks deliver processed PCM; beta.14 does not expose a standalone `decodeLc3()` API, raw microphone callback or configurable processor through its high-level host interface. If you save a WAV file, add a WAV header yourself. Move recording, transcription, network upload and heavy PCM processing to your own bounded worker queue and apply your App's consent UX.

## Time synchronization

Call `syncG2Time` with the current **right** endpoint. Its default epoch is Unix seconds and its UTC offset is in minutes. Native code sets peer-sync so firmware can mirror time to the other leg. Use `G2TimeSyncEvent.operation` and `generation` to identify completion. `success=true` requires the correlated response; a returned token is only admission. The native response timeout is 1 second.

An active Even Hub page or OTA transaction rejects time sync with `busy`. Close the page and wait for terminal page completion before synchronizing time. Do not issue competing raw business writes or reimplement the time-sync protobuf in the host.

## G2 OTA

Read the complete firmware image into a `ByteArray` off the UI thread, and supply its intended firmware version to `startOta`. This is a G2 firmware package, not an APK, ZIP download wrapper or arbitrary binary. Native parsing requires its `s200_` image version to match the supplied target. Version comparison accepts a `V` prefix and equivalent trailing zero components; malformed or unknown zero versions are rejected.

The pair must be READY, with no current OTA, Even Hub work or pending time-sync operation. Keep the license bundle and the SDK instance alive until the transaction ends. Native code owns both legs, chunk/ACK flow, reconnect authorization, cancellation and verification.

`G2OtaEvent` contains `sn`, `generation`, `operation`, optional `endpoint`, physical `session`/`attempt`, `phase`, `progress` (0–1), optional `failure`, `leftVersion`, and `rightVersion`.

| Phase | Meaning |
| --- | --- |
| `started` / `transferring` | Native transaction active; progress counts acknowledged transfer bytes across both legs. |
| `recovering` | Bounded native recovery of an interrupted transport. |
| `transportCompleted` | Transfer finished. Installation/version success is still pending, even at progress 1.0. |
| `verifying` | Reconnect, repeat enterprise/business authentication, then obtain fresh firmware-version evidence. |
| `succeeded` | Both read-back versions match the requested target. This is the final success signal. |
| `cancelled` / `failed` | Terminal result; inspect `failure`, keep the operation identity and offer an explicit next action. |

Call `cancelOta(operation)` with the returned token. A stale token cannot cancel a newer OTA. Cancellation retires SDK transport ownership; it does not roll back bytes already accepted by firmware. Never report installed success from `transportCompleted`, percentage alone or a BLE reconnect. Failures such as `versionUnverified` and `versionMismatch` mean transfer completion did not establish the required final version.

## Events, threading and lifecycle

| Callback | Contract |
| --- | --- |
| `onEvent(NativeBleEvent)` | Low-level `BleEC` event and native `value` (`Any?`); includes scan/native status. Treat its thread as unspecified and marshal UI work to main. Raw microphone `RECEIVE_DATA` is withheld; use processed audio events. |
| `onG2AuthEvent(G2AuthEvent)` | Whole-pair result: `sn`, `generation`, `ready`, optional `failedEndpoint`. |
| `onG2ConnectionEvent(G2ConnectionEvent)` | Endpoint progress with generation and applicable session/attempt/failure. |
| `onG2TimeSyncEvent(G2TimeSyncEvent)` | Endpoint, generation, operation, success. |
| `onEvenHubEvent(G2EvenHubEvent)` | Scoped page/connection/input/audio events. |
| `onOtaEvent(G2OtaEvent)` | Scoped transaction progress and final result. |

High-level callbacks and Even Hub completion callbacks are dispatched to the Android main looper. Keep them short. Do not invoke UI from raw callbacks without marshaling, block a callback waiting for another callback, or hold App locks while calling into BLE. Invoke host actions serially, typically from main; read firmware/content off-thread. Synchronous validation exceptions and asynchronous callback failures are separate channels.

Only one native SDK owner may exist in the process. Do not open a second BLE implementation for the same glasses through Flutter, a background service or direct `BluetoothGatt`. `connectG2` replaces the previous pair and invalidates page/time-sync/OTA work. Physical session/attempt generations change on reconnect; recreate pages and re-enable audio after a fresh READY. Native code fences old callbacks, and the App should also correlate displayed results with its current selection and operation.

A normal user disconnect calls `disconnectG2`; final owner disposal calls `reset`. Activity destruction alone is not necessarily process-owner disposal. The SDK does not persist licenses, last-device selection or active page state across process death, and it does not supply general background recovery. Its bounded authorization retries and OTA recovery are scoped operations, not a host-wide automatic reconnect policy.

## Failures and troubleshooting

| Symptom/error | Check/action |
| --- | --- |
| `SecurityException` or scan has no candidates | Runtime permissions, Bluetooth state, API 23–30 location state, enterprise firmware/advertisement, proximity, and whether another phone owns the glasses. Scanning is separate from connection. |
| License parser `IllegalArgumentException` | Validate the entire original export, exact app ID, SN length, canonical signature encoding and duplicates. Do not trim or fix signed values silently. |
| `No enterprise proof for G2 SN` | Confirm the selected discovery SN occurs in the native bundle. Generate the correct proof; do not reuse another SN's entry. |
| Native enterprise authorization rejection | Inspect `ENTERPRISE_AUTHORIZATION`/endpoint logs and matching SN/generation. Firmware authorization is authoritative; format validation alone never proves it. |
| One leg ready, pair not ready | Check both endpoint identities, `8403` results and subsequent two-stage AuthMgr responses. Do not replace pair READY with the first endpoint's GATT status. |
| `G2EvenHubException.code == "notReady"` | Wait for whole-pair READY and, for page-dependent commands, an active current page. |
| `busy` | Complete/close page work, pending time sync or OTA; wait for its callback before starting another owner. |
| `invalidArguments` / `invalidText` | Check exact command name, allowed keys, integer types, UTF-8 byte counts and current container/menu identity. |
| `timedOut`, `writeFailed`, `deviceRejected` | Observe the scoped native failure event; inspect endpoint/generation and ACK stage. A transport write receipt does not prove command acceptance. |
| `audioUnavailable` / `audioDecoderUnavailable` | Check firmware STREAM capability and current notifications; verify packaged native ABI libraries. Page rendering alone does not prove audio support. |
| OTA `invalidFirmware` / `invalidTargetVersion` | Supply the full supported G2 image and matching version. |
| OTA `versionUnverified` / `versionMismatch` | Keep glasses reachable, preserve licenses through reconnect and inspect both version-read results. Do not label transport completion as installed success. |

For SDK diagnostics, correlate `EvenG2Auth` native milestones (`8403 result`, `8401 writeResponse`, per-endpoint READY and pair READY) with the same SN, pair generation and physical session/attempt. Redact signatures, license JSON, App content and microphone bytes from shared logs. The public raw native event payload is an advanced diagnostic surface; it is not a stable replacement for high-level completion callbacks.

## Advanced protocol helpers

The AAR contains low-level public Kotlin classes used by its native coordinators, including `G2EvenHubCodec`, `G2EvenHubTransport`, business/time-sync codecs and coordinator types, `G2EnterpriseRetryBudget`, `EnterpriseG2Gate`, `EnterpriseEndpointPort`, and `EnterpriseAuthV1Encoder`. `EnterpriseG2Gate` has its own `EndpointAttempt`, `EnterpriseAuthEvent`, `EnterpriseAuthPhase` and `EnterpriseAuthFailure` contract; those helper events are not the singleton's `G2AuthEvent` callback. They expose transport or wire-level building blocks, not additional high-level device features. Do not use them to bypass READY, synthesize owners/generations, send a second heartbeat, or write to the old EUS service. The native LC3 decoder is internal. Use the singleton methods and typed events above as the integration boundary.

## Verification and next steps

This document was checked against the published beta.14 native source and the matching shared `native/protocol/evenhub_commands.json` contract. The Kotlin controller was compile-checked against the beta.14 AAR and Android API classes; this documentation task adds no real-device acceptance evidence. Firmware capability, permission UX, microphone behavior and OTA must still be accepted on your target Android versions and enterprise firmware.

Start with [Even Hub: Get Started / Overview](https://hub.evenrealities.com/docs/get-started/overview) for application concepts, then use this guide for the Kotlin-specific installation, ownership model and command contract. The SDK is distributed under Apache License 2.0; retain required third-party notices when redistributing binaries.

## Companion guides

Documentation date: 2026-11-11. Native source and releases are maintained in [enterprise_sdk](https://github.com/even-realities/enterprise_sdk); the Maven coordinate is unchanged.

- [Enterprise Flutter SDK](https://github.com/even-realities/flutter_enterprise_sdk/blob/main/docs/enterprise/enterprise-flutter-sdk.md)
- [iOS Swift](enterprise-ios-sdk.md)
- [Shared logical command contract](https://github.com/even-realities/enterprise_sdk/blob/0.1.0-beta.14/native/protocol/evenhub_commands.json)
