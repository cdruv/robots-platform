import Foundation

/// The app's version as built into its Info.plist. Major.minor is `MARKETING_VERSION` (Xcode:
/// target › General › Identity › Version). The build number is the build time (YYYYMMDD.HHMM),
/// stamped into `CFBundleVersion` by the target's "Stamp Build Number" phase on every build.
nonisolated enum AppVersion {
    static let marketing = info("CFBundleShortVersionString")
    static let build = info("CFBundleVersion")

    #if DEBUG
    static let isDebug = true
    #else
    static let isDebug = false
    #endif

    /// `v1.0 · build 20261006.1009`, with ` · debug` appended in Debug builds.
    static let display = "v\(marketing) · build \(build)" + (isDebug ? " · debug" : "")

    private static func info(_ key: String) -> String {
        Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "?"
    }
}
