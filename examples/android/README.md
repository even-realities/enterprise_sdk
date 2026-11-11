# Pure Android example

A Kotlin/Android Activity consumes the source SDK directly; Flutter is not installed or loaded. It separates scan observations from whole-pair connection status and provides Even Hub text, audio enable/disable, confirmed-heartbeat logs and time sync.

## Build

From the repository root, set `JAVA_HOME` to JDK 17 or 21 and `ANDROID_HOME` to your Android SDK, then run:

```bash
native/android/gradlew -p examples/android :app:assembleDebug
native/android/gradlew -p examples/android :app:installDebug
```

The pinned build uses Gradle 8.12, AGP 8.7.3, Kotlin 2.1.10, compileSdk 36 and NDK 27.1.12297006 (the SDK module). AGP emits a known warning because its tested compileSdk is 35. SDK tests/lint and a real device remain separate checks. `local.properties` is not required when `ANDROID_HOME` is set.

## Run with enterprise glasses

1. Export your exact App-ID/SN licenses from the signing portal. Save the **whole** export as `app/src/main/assets/enterprise-licenses.json`. That filename is ignored by Git. Never commit actual proofs or signing credentials.
2. Without this file the app uses `enterprise-licenses.example.json`, which has an empty list: scanning works, but native authorization admission denies every SN.
3. Tap **Initialize and scan**, grant runtime Bluetooth permissions and enable Bluetooth. On Android 6–11, location permission and system location state may also affect scanning.
4. Enter the complete scanned 14-byte SN only after left and right addresses appear, then tap **Connect selected SN**. Native selects the exact proof. Only **READY (both legs)** enables valid business operations; a scan/transport/enterprise-only result is insufficient.
5. Display text. Heartbeat is owned by native. Enable microphone only after page activation; the log reports decoded PCM byte counts, not raw audio content.
6. Close the page and wait for `closed` before time sync. Disconnect before another host app takes over the glasses.

This example uses `implementation project(':sdk')` to validate the source under review. To consume the public release, remove `:sdk` from `settings.gradle` and replace the dependency with:

```groovy
implementation 'com.evenrealities.devices:even-devices-native-android:0.1.0-beta.14'
```

Keep `google()` and `mavenCentral()` available. A build or emulator launch does not validate Bluetooth authorization, PCM or an OTA installation on physical hardware. For all ten Even Hub commands and native OTA APIs, use the repository's Android SDK integration guide.
