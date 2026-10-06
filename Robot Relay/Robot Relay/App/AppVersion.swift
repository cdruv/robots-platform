import Foundation

/// The app's version as built into its Info.plist. Major.minor is `MARKETING_VERSION` in the
/// Xcode project (bumped by hand); the build number is the `install_local.sh` build time
/// (YYYYMMDD.HHMM), or 1 for Xcode builds.
nonisolated enum AppVersion {
    static let marketing = info("CFBundleShortVersionString")
    static let build = info("CFBundleVersion")

    /// `v1.0 · build 20261006.1009`.
    static let display = "v\(marketing) · build \(build)"

    private static func info(_ key: String) -> String {
        Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "?"
    }
}
