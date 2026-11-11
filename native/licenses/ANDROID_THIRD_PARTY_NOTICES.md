# Android native SDK third-party notices

The SDK code owned by Hong Kong Even Realities Limited is licensed under Apache-2.0.

In the AAR, open `classes.jar` and read this file in `META-INF/`. The SDK's Apache-2.0 text is `even-devices-LICENSE.txt` and its attribution is in `even-devices-NOTICE.txt` in the same directory.

| Shipped component | Origin and modifications | License and attribution |
| --- | --- | --- |
| G2 BLE engine from `flutter_ezw_ble` | BLE revision `c2e101c9bbc18c7372d439fdbb77b33da84df76b`; the SDK adapts the Kotlin transport for enterprise G2 authentication and connection management. | MIT, Copyright (c) 2025 Whiskee. See [MIT text and notice](licenses/flutter_ezw_ble_LICENSE). |
| Utility classes from `flutter_ezw_utils` | Kotlin classes under `com.fzfstudio.ezw_utils`, utility revision `53ef9f052653b8b7c9ffacce20ee2ad88e5ef7ed`. | MIT, Copyright (c) 2025 Whiskee. Both components carry the same [MIT text and notice](licenses/flutter_ezw_ble_LICENSE). |
| liblc3 codec and headers | The SDK uses the four Android ABI libraries shipped with `flutter_ezw_lc3` at `c8d02472bca0d5c0e55b4c31a3d8d949c375d9ca`, changes the ELF SONAME to `libeg3.so`, and calls the decoder through its JNI wrapper. | The liblc3 headers carry Copyright 2022 Google LLC and [Apache-2.0](licenses/liblc3_LICENSE). The vendor package has a placeholder LICENSE. The provenance and redistribution terms for the supplied precompiled objects still require confirmation from the rights holder. |

Gradle resolves Gson 2.11.0, Kotlin standard library 2.1.10 and kotlinx-coroutines-android 1.10.2 as separate Maven artifacts. Each uses Apache-2.0 and carries its own upstream notices.
