import Foundation

nonisolated struct CommandResult: Sendable {
    var status: Int32
    var stdout: String
    var stderr: String

    var succeeded: Bool { status == 0 }

    /// First non-empty line of stderr, else of stdout, else the exit status.
    var failureReason: String {
        for text in [stderr, stdout] {
            if let line = text.split(whereSeparator: \.isNewline).first(where: { !$0.allSatisfy(\.isWhitespace) }) {
                return String(line).trimmingCharacters(in: .whitespaces)
            }
        }
        return "exit \(status)"
    }
}

/// Runs command-line tools (`adb`, later `mpremote`) the way a terminal would, and reports
/// each run to the activity footer as the command a person would type.
final class CommandRunner {
    /// Receives a `.running` entry when a command starts and the same entry, finished, when it ends.
    var report: ((LinkActivity) -> Void)?

    private let environment: [String: String]

    init(environment: [String: String] = ProcessInfo.processInfo.environment) {
        self.environment = environment
    }

    /// Runs `tool` with `arguments`. `judge` turns the result into the footer state; by default
    /// exit 0 is ok and anything else fails with the first line of output.
    @discardableResult
    func run(
        _ tool: String,
        _ arguments: [String],
        timeout: Duration = .seconds(15),
        judge: (CommandResult) -> LinkActivity.State = { $0.succeeded ? .ok(nil) : .failed($0.failureReason) }
    ) async -> CommandResult {
        var entry = LinkActivity(text: "$ " + Self.displayCommand(tool, arguments), state: .running)
        report?(entry)
        let result: CommandResult
        if let url = Self.resolve(tool, environment: environment) {
            result = await Self.execute(url, arguments, timeout: timeout)
        } else {
            result = CommandResult(status: 127, stdout: "", stderr: "\(tool) not found (looked in \(Self.searchDirectories(environment: environment).joined(separator: ", ")))")
        }
        entry.state = judge(result)
        entry.date = .now
        report?(entry)
        return result
    }

    /// The argv as typed in a terminal: the bare tool name, arguments quoted only when needed.
    nonisolated static func displayCommand(_ tool: String, _ arguments: [String]) -> String {
        ([tool] + arguments.map(shellQuoted)).joined(separator: " ")
    }

    /// Where GUI apps, which have no shell PATH, look for tools.
    nonisolated static func searchDirectories(environment: [String: String]) -> [String] {
        var directories: [String] = []
        for key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
            if let sdk = environment[key], !sdk.isEmpty { directories.append(sdk + "/platform-tools") }
        }
        let home = environment["HOME"] ?? NSHomeDirectory()
        directories += [home + "/Library/Android/sdk/platform-tools", "/opt/homebrew/bin", "/usr/local/bin"]
        return directories
    }

    nonisolated static func resolve(_ tool: String, environment: [String: String]) -> URL? {
        searchDirectories(environment: environment)
            .map { URL(fileURLWithPath: $0).appendingPathComponent(tool) }
            .first { FileManager.default.isExecutableFile(atPath: $0.path) }
    }

    private nonisolated static func shellQuoted(_ argument: String) -> String {
        let safe = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_./:=@%+,"))
        if !argument.isEmpty, argument.unicodeScalars.allSatisfy(safe.contains) { return argument }
        return "'" + argument.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// Runs the process on a background queue; terminates it after `timeout`. Output goes to
    /// temporary files rather than pipes: `adb` may start its server, which inherits the
    /// output handles and would keep a pipe open long after the command exits.
    private nonisolated static func execute(_ url: URL, _ arguments: [String], timeout: Duration) async -> CommandResult {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: executeBlocking(url, arguments, timeout: timeout))
            }
        }
    }

    private nonisolated static func executeBlocking(_ url: URL, _ arguments: [String], timeout: Duration) -> CommandResult {
        let directory = FileManager.default.temporaryDirectory
        let outURL = directory.appendingPathComponent("relay-\(UUID().uuidString).out")
        let errURL = directory.appendingPathComponent("relay-\(UUID().uuidString).err")
        defer {
            try? FileManager.default.removeItem(at: outURL)
            try? FileManager.default.removeItem(at: errURL)
        }
        FileManager.default.createFile(atPath: outURL.path, contents: nil)
        FileManager.default.createFile(atPath: errURL.path, contents: nil)
        let process = Process()
        process.executableURL = url
        process.arguments = arguments
        process.standardInput = FileHandle.nullDevice
        do {
            let out = try FileHandle(forWritingTo: outURL)
            let err = try FileHandle(forWritingTo: errURL)
            defer {
                try? out.close()
                try? err.close()
            }
            process.standardOutput = out
            process.standardError = err
            try process.run()
            let seconds = Double(timeout.components.seconds) + Double(timeout.components.attoseconds) / 1e18
            let timer = DispatchWorkItem { if process.isRunning { process.terminate() } }
            DispatchQueue.global().asyncAfter(deadline: .now() + seconds, execute: timer)
            process.waitUntilExit()
            timer.cancel()
            var stderr = (try? String(contentsOf: errURL, encoding: .utf8)) ?? ""
            if process.terminationReason == .uncaughtSignal {
                stderr = "timed out after \(Int(seconds)) s\n" + stderr
            }
            return CommandResult(
                status: process.terminationStatus,
                stdout: (try? String(contentsOf: outURL, encoding: .utf8)) ?? "",
                stderr: stderr
            )
        } catch {
            return CommandResult(status: -1, stdout: "", stderr: error.localizedDescription)
        }
    }
}
