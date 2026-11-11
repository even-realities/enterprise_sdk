// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "EvenDevicesSDK",
    platforms: [.iOS(.v15), .macOS(.v13)],
    products: [.library(name: "EvenDevicesSDK", targets: ["EvenDevicesSDK"])],
    targets: [
        // The production archive owns decoder behavior; embedded LLVM supplies
        // missing platform slices through the compiler backend, not relabeling.
        .binaryTarget(name: "EvenLC3Core", path: "Codec/EvenLC3Core.xcframework"),
        .target(name: "EvenLC3", dependencies: ["EvenLC3Core"],
                path: "Sources/EvenLC3", publicHeadersPath: "include"),
        .target(name: "EvenDevicesSDK", dependencies: ["EvenLC3"]),
        .testTarget(name: "EvenDevicesSDKTests", dependencies: ["EvenDevicesSDK"]),
    ]
)
