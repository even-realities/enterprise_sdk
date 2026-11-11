# Native repository migration

## Plan and invariants

1. Import the already published beta.14 native source snapshot (f931d52d4716fd150f885f5168b64faabf20783a) without changing runtime behavior; preserve its license/provenance records.
2. Keep Android/iOS sources and shared protocol vectors together. Make build entry points reproducible without the parent Flutter workspace and add native consumer examples.
3. Retain Maven coordinate com.evenrealities.devices:even-devices-native-android and SPM product EvenDevicesSDK. Import the original beta.14 XCFramework ZIP byte-for-byte; do not republish an existing Maven version.
4. Publish this repository's main and first 0.1.0-beta.14 SPM tag after local checks. Existing tags/assets in flutter_enterprise_sdk remain untouched.
5. Change the Flutter adapter's SPM URL/product package identity to this repository; keep Android resolving beta.14 from Maven Central. Update documentation/verification and validate both platform consumers before publishing the adapter change.

No parent-workspace uncommitted changes are imported. Hardware behavior is unchanged; new physical-device acceptance is outside this migration's build evidence.
