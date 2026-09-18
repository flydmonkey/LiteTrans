// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LiteTrans",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "LiteTransDomain", targets: ["LiteTransDomain"]),
    ],
    targets: [
        .target(
            name: "LiteTransDomain",
            path: "LiteTrans/Domain",
            linkerSettings: [
                .linkedLibrary("z"),
            ]
        ),
        .testTarget(
            name: "LiteTransDomainTests",
            dependencies: ["LiteTransDomain"],
            path: "LiteTransTests/Domain",
            linkerSettings: [
                .linkedLibrary("z"),
            ]
        ),
    ]
)
