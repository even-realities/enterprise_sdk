// swift-tools-version: 5.9
import PackageDescription

// Imported beta.14 is byte-identical to the prior Flutter repository release.
// Future versions are published here; native/ios is the separate source package.
let package = Package(
    name: "EvenDevicesSDK",
    platforms: [.iOS(.v15)],
    products: [.library(name: "EvenDevicesSDK", targets: ["EvenDevicesSDK"])],
    targets: [.binaryTarget(name: "EvenDevicesSDK",
                           path: "spm/EvenDevicesSDK-0.1.0-beta.14.xcframework.zip")]
)
