# Native iOS example

A UIKit app using only `EvenDevicesSDK`; Flutter and CocoaPods are not required.
The checked-in Xcode project consumes the repository root binary Swift package
through the relative path `../..`. It exercises the same immutable beta.14
XCFramework distributed to remote SPM users.

## Build

Open `EnterpriseSDKExample.xcodeproj` in Xcode 15+ with the iOS 15+ deployment
support installed, select the `EnterpriseSDKExample` scheme, and choose an iPhone
simulator to compile. No development team is required for simulator builds:

```bash
xcodebuild -project examples/ios/EnterpriseSDKExample.xcodeproj \
  -scheme EnterpriseSDKExample -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios-example CODE_SIGNING_ALLOWED=NO build
```

For a physical iPhone, set your own signing team in Xcode. The simulator build
checks native imports/linkage only; actual Bluetooth needs an iPhone and licensed
enterprise-firmware G2 pair.

## Use

1. Transfer the signing portal's full JSON export to the iPhone Files app. Do not
   add real proofs to this repository.
2. Tap **Import portal license JSON**, choose the file, then **Initialize SDK**.
   JSON remains in memory. The native SDK validates every entry and owns exact-SN
   proof lookup; the app never selects the first license as a fallback.
3. Accept the system Bluetooth prompt and tap **Scan**. Scan observations display
   separately from connection status. Wait for both sides of the intended full SN.
4. Select/type that exact SN and tap **Connect selected SN**. Wait until the pair's
   `ready` event has no side; a single leg or GATT connection is insufficient.
5. **Show text** opens a native EvenHub page. Heartbeat is native-owned. Audio on
   requires active page plus firmware STREAM support; the PCM label counts native
   decoded/processed bytes. Close the page before **Sync time**.
6. **Disconnect** explicitly retires the connection; no background auto-connect
   policy is enabled by this example. Re-import/reinitialize replaces the owner
   only after validated JSON is available.

The log is bounded and excludes proof/PCM contents. This small example does not
provide OTA UI; see the SDK integration guide for version-verified OTA APIs.

## Use the remote release instead

In Xcode, remove the local package reference, add
`https://github.com/even-realities/enterprise_sdk.git` with exact version
`0.1.0-beta.14`, and keep product `EvenDevicesSDK` linked to the app target.
Do not add both local and remote packages defining the same product.
