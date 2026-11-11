# Swift source module

The source package builds `EvenDevicesSDK` for iOS 15+ and macOS 13+ tests without
Flutter. Normal iOS consumers use the repository-root binary SPM package and
product `EvenDevicesSDK`; the source package is for native SDK development.

## Verify and test

Run from the repository root on macOS with Xcode command-line tools selected:

```bash
python3 tool/prepare_apple_lc3.py --verify
swift test --package-path native/ios
```

The audited `Codec/EvenLC3Core.xcframework`, its provenance and source checksum
are build inputs, not the public `EvenDevicesSDK` distribution. Verification
checks the original pinned `third_party/flutter_ezw_lc3` archive and every codec
slice. Never substitute another LC3 implementation merely to satisfy a missing
slice.

If intentionally reproducing those codec inputs on a new Xcode backend:

```bash
python3 tool/prepare_apple_lc3.py
python3 tool/prepare_apple_lc3.py --verify
bash tool/test_native_lc3.sh
```

Preparation compiles embedded original LLVM for the missing Apple targets;
it does not relabel Mach-O platform metadata. Review changed codec bytes,
`Codec/PROVENANCE.json`, toolchain identity and parity results before including
new generated inputs in a future release. Do not overwrite a published version's
ZIP/tag. Temporary compiler directories are removed by the preparation tool;
`.build`, generated framework outputs and local Xcode data are ignored.

## Build the distributable framework

```bash
SDK_BUNDLE_VERSION=0.1.0 SDK_BUILD_NUMBER=14 \
  bash native/ios/build_xcframework.sh build/EvenDevicesSDK.xcframework
```

This verifies the codec first and builds arm64 iOS, arm64 simulator and x86_64
simulator slices. It does not upload, sign a release or mutate the repository's
SPM beta.14 ZIP. A successful build is not Bluetooth/device acceptance.

For a clean-commit release package with checksums, legal payload, consumer-link
checks and provenance, use `tool/package_ios_sdk.sh VERSION`. The packager rejects
uncommitted source changes and an existing output directory. See the root
publishing guide for Maven/SPM release coordination.

A complete native UIKit consumer lives in [`examples/ios`](../../examples/ios).
