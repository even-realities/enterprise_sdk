# Source and build inputs for the standalone G2 SDK

The Android and iOS build records identify the commit used to compile the release. In the beta.14 distribution, `source/native-sdk-0.1.0-beta.14.tar.gz` contains a self-contained Git bundle and checkout instructions. Clone the bundle to inspect the exact Kotlin, Swift, C/JNI, tests, Gradle wrapper, Swift package and platform packaging scripts. These build inputs are available to recipients with the SDK itself.

| Input | Repository and revision | Use and modifications |
| --- | --- | --- |
| G2 BLE | [Company GitLab, dev/v2.4.1](http://192.168.2.101/even/applications/app/ezw/flutter_ezw_ble/-/commit/c2e101c9bbc18c7372d439fdbb77b33da84df76b) | Android incorporates selected BLE files, with enterprise authorization, GATT ownership, reconnection, command admission and OTA changes. iOS uses the SDK's Swift CoreBluetooth implementation. Access to the original fork requires company GitLab access. |
| Android utilities | [Whiskee/flutter_ezw_utils](https://github.com/Whiskee/flutter_ezw_utils/commit/53ef9f052653b8b7c9ffacce20ee2ad88e5ef7ed) | Kotlin extensions and Gson helpers are compiled into the AAR. The upstream MIT copyright and permission text are retained. |
| LC3 inputs | [Whiskee/flutter_ezw_lc3](https://github.com/Whiskee/flutter_ezw_lc3/commit/c8d02472bca0d5c0e55b4c31a3d8d949c375d9ca) | Includes the original ABI libraries, Apple archive and headers used by these builds. Android changes the internal SONAME. The Apple preparation tool retains original objects and compiles embedded LLVM for alternate targets. The SDK's C/JNI and Swift/Kotlin integration sources are included. The vendor provides the codec implementation as binaries. See `native/codec/README.md` and `native/ios/Codec/PROVENANCE.json` for transformations and hashes. |

In the source bundle, `UPSTREAM_FILES.tsv` lists each retained upstream Kotlin file or SDK addition in the BLE and utilities namespaces, with upstream and shipped Git blob IDs. `git diff fa66b7d HEAD -- native/android/src/main/kotlin/com/fzfstudio` shows the source changes against the exported baseline. The baseline is a projection of the named upstream revisions onto the included files. The tip contains the full standalone SDK build inputs. The original repositories retain their own commit identities.

The SDK attributes its original work to Hong Kong Even Realities Limited. The original BLE and utility licenses identify Whiskee. The LC3 headers carry their own Google LLC notices.
