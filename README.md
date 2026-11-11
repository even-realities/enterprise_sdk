# Even Enterprise SDK

Native Android and iOS SDKs for connecting enterprise-authorized Even G2 glasses. No Flutter runtime is required. [中文](README.zh-CN.md).

## Install

Current native release: **0.1.0-beta.14**. Firmware must expose the enterprise G2 service; a signed license for the exact glasses SN is required.

Android, from Maven Central:

```kotlin
repositories { google(); mavenCentral() }
dependencies {
    implementation("com.evenrealities.devices:even-devices-native-android:0.1.0-beta.14")
}
```

In Xcode, add the package below with **Exact Version 0.1.0-beta.14** and select product **EvenDevicesSDK**:

```text
https://github.com/even-realities/enterprise_sdk.git
```

Follow the [Android guide](docs/enterprise/enterprise-android-sdk.md) or [iOS guide](docs/enterprise/enterprise-ios-sdk.md) for permissions, raw license loading, scanning, connection and API examples. Use [flutter_enterprise_sdk](https://github.com/even-realities/flutter_enterprise_sdk) for Flutter applications; its thin adapter consumes these native SDKs.

## Capabilities

- G2 scan and dual-temple connection, exact-SN license matching and enterprise verification.
- Business authentication and whole-device READY, session/attempt generation fencing, time synchronization.
- Ten Even Hub commands, text/list/image containers, native page heartbeat, input/menu and IMU events.
- Glasses microphone control, built-in LC3 decoding and processed 16 kHz mono PCM16 audio callbacks.
- Dual-temple G2 OTA, cancellation, reconnect and installed-version readback.

Signing private keys and portal credentials do not belong in the App. The native SDK selects the proof for the connected SN; missing proof denies a new connection. Only both temples completing enterprise and business AUTH establishes READY. OTA transfer completion is not installation success.

This release is G2-only. It does not expose R1/DFU, phone notification composition, a standalone configurable LC3 decoder, or automatic image conversion. See [scope and architecture](docs/ARCHITECTURE.md).

## Native examples

- [Android Kotlin App](examples/android/README.md): build and run without Flutter.
- [iOS Swift App](examples/ios/README.md): open its Xcode project; the example consumes this repository's local binary SPM package.

Examples require your own signed license file and physical enterprise glasses. No valid license or private key is included.

## Build from source

Prerequisites: Android SDK/NDK, JDK 17+ (release baseline JDK 21), and Android Gradle wrapper; or macOS with Xcode and its command-line tools. Exact build configuration is recorded under `native/` and in the [publishing guide](docs/PUBLISHING.md).

```bash
# Android source checks; the wrapper is checked in.
cd native/android
./gradlew testDebugUnitTest lintRelease assembleRelease
```

From the repository root:

```bash
python3 tool/prepare_apple_lc3.py --verify
swift test --package-path native/ios
bash tool/test_native_lc3.sh
python3 tool/verify_repository.py
```

`native/ios/Package.swift` is the source-build package; the root `Package.swift` is the binary package for App consumers. Follow [publishing](docs/PUBLISHING.md) for clean-commit local packaging and future Maven/SPM releases. Existing Central versions must not be republished.

## Repository and release history

Android/iOS implementations, shared [protocol contracts](native/protocol/README.md), source tests and publication tooling are maintained together here. Android keeps the existing Maven coordinate and iOS keeps the `EvenDevicesSDK` product name.

The first tag in this repository imports the **original beta.14 XCFramework ZIP byte-for-byte**. It does not claim a newly rebuilt beta.14 release. Older Flutter-repository tags/assets remain available for existing consumers. See [provenance](PROVENANCE.md) and [migration notes](docs/REPOSITORY_MIGRATION.md).

For Even Hub concepts and application design, read [Even Hub: Get Started / Overview](https://hub.evenrealities.com/docs/get-started/overview). That site's web/npm SDK installation is distinct from native SDK installation above.

Apache 2.0: [LICENSE](LICENSE). Vendor components retain their [third-party notices](THIRD_PARTY_NOTICES.md). Automated builds do not replace physical-device acceptance.
