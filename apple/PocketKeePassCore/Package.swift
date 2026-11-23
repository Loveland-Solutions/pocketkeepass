// swift-tools-version: 6.2
// The Rust engine (../../core) for the iOS and macOS apps: the static libraries and the Swift
// side of the bridge, both made by ../build-engine.sh. Run that before building or testing.
import PackageDescription

let package = Package(
    name: "PocketKeePassCore",
    platforms: [.iOS(.v26), .macOS(.v15)],
    products: [
        .library(name: "PocketKeePassCore", targets: ["PocketKeePassCore"]),
    ],
    targets: [
        .binaryTarget(name: "PocketKeePassCoreFFI", path: "PocketKeePassCoreFFI.xcframework"),
        .target(
            name: "PocketKeePassCore",
            dependencies: ["PocketKeePassCoreFFI"],
            // UniFFI's generated code predates strict concurrency checking.
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(name: "PocketKeePassCoreTests", dependencies: ["PocketKeePassCore"]),
    ]
)
