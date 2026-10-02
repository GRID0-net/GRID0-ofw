// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "GRID0-ofw",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "GRID0-ofw", targets: ["GRID0-ofw"])
    ],
    targets: [
        .executableTarget(
            name: "GRID0-ofw",
            path: "Sources/GRID0-ofw"
        )
    ],
    swiftLanguageModes: [.v5]
)
