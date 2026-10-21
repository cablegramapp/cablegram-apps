// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CablegramCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "CablegramCore", targets: ["CablegramCore"])],
    targets: [
        .target(name: "CablegramCore"),
        .testTarget(name: "CablegramCoreTests", dependencies: ["CablegramCore"]),
    ]
)
