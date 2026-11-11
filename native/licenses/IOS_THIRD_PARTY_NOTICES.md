# iOS native SDK third-party notices

The SDK code owned by Hong Kong Even Realities Limited is licensed under Apache-2.0.

See `LICENSE` and `NOTICE` at the root of the XCFramework for the SDK's Apache-2.0 text and attribution.

| Shipped component | Origin and modifications | License and attribution |
| --- | --- | --- |
| G2 BLE transport and business authentication | The SDK adapts the G2 behavior of `flutter_ezw_ble` at `c2e101c9bbc18c7372d439fdbb77b33da84df76b` to the standalone Swift CoreBluetooth implementation. | MIT, Copyright (c) 2025 Whiskee. See [MIT text and notice](licenses/flutter_ezw_ble_LICENSE). |
| liblc3 codec and headers | The SDK uses codec objects and headers shipped with `flutter_ezw_lc3` at `c8d02472bca0d5c0e55b4c31a3d8d949c375d9ca` and regenerates target-specific simulator objects from their embedded bitcode. | The liblc3 headers carry Copyright 2022 Google LLC and [Apache-2.0](licenses/liblc3_LICENSE). The vendor package has a placeholder LICENSE. The provenance and redistribution terms for the supplied precompiled objects still require confirmation from the rights holder. |

The SDK links Foundation, CoreBluetooth and UIKit supplied by Apple on the host platform.
