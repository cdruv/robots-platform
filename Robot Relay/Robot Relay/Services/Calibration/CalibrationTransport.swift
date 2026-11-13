import Foundation
import Network
import os

protocol CalibrationTransport: AnyObject {
    func connect() async throws
    func request(_ command: String, ticket: CalibrationTicket, steps: [Int]?, side: Int?) async throws -> CalibrationReply
    func close()
}

extension CalibrationTransport {
    func request(_ command: String, ticket: CalibrationTicket, steps: [Int]?) async throws -> CalibrationReply {
        try await request(command, ticket: ticket, steps: steps, side: nil)
    }
}

/// Strict request/reply transport. The controller is its sole, sequential caller.
final class CalibrationTCPTransport: CalibrationTransport {
    private var connection: NWConnection?
    private var nextID = 0
    private var buffer = Data()
    private var connectCompletion: ((Result<Void, Error>) -> Void)?
    private var lastWait: String?

    nonisolated static func describe(_ path: NWPath) -> String {
        var parts = ["\(path.status)"]
        if path.status != .satisfied { parts.append("reason \(path.unsatisfiedReason)") }
        parts.append("interfaces [" + path.availableInterfaces.map(\.name).joined(separator: ", ") + "]")
        if let local = path.localEndpoint { parts.append("local \(local)") }
        return parts.joined(separator: ", ")
    }

    func connect() async throws {
        precondition(!RobotServices.isTestHost)
        close()
        let connection = NWConnection(host: "192.168.4.1", port: 8765, using: .tcp)
        self.connection = connection
        lastWait = nil
        let timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(8))
            guard !Task.isCancelled else { return }
            let reason = self?.lastWait ?? "no wait reason reported, path: " + (connection.currentPath.map(Self.describe) ?? "none")
            Logger.calibration.error("calibration TCP 192.168.4.1:8765 connect timed out after 8 s: \(reason, privacy: .public)")
            self?.completeConnect(.failure(CalibrationFailure("Calibration connection timed out (\(reason)). Re-arm calibration if the Pico timed out.")))
            connection.cancel()
        }
        defer { timeout.cancel() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connectCompletion = { continuation.resume(with: $0) }
            connection.stateUpdateHandler = { [weak self] state in
                let path = connection.currentPath.map(Self.describe) ?? "no path"
                Task { @MainActor [weak self] in
                    guard let self, self.connection === connection else { return }
                    Logger.calibration.info("calibration TCP state \(String(describing: state), privacy: .public), path: \(path, privacy: .public)")
                    switch state {
                    case .ready: self.completeConnect(.success(()))
                    case .waiting(let error):
                        self.lastWait = String(describing: error) + ", path: " + path
                    case .failed(let error): self.completeConnect(.failure(CalibrationFailure("Calibration connection failed: \(String(describing: error)), path: \(path)")))
                    case .cancelled: self.completeConnect(.failure(CalibrationFailure("Calibration disconnected")))
                    default: break
                    }
                }
            }
            connection.start(queue: .global(qos: .userInitiated))
        }
    }

    private func completeConnect(_ result: Result<Void, Error>) {
        let completion = connectCompletion
        connectCompletion = nil
        completion?(result)
    }

    func request(_ command: String, ticket: CalibrationTicket, steps: [Int]?, side: Int?) async throws -> CalibrationReply {
        guard let connection else { throw CalibrationFailure("Calibration is disconnected") }
        nextID += 1
        let id = nextID
        let started = ContinuousClock.now
        var object: [String: Any] = ["version": 1, "board": ticket.board, "session": ticket.session, "id": id, "command": command]
        if let steps { object["steps"] = steps }
        if let side { object["side"] = side }
        var data = try JSONSerialization.data(withJSONObject: object)
        data.append(10)
        var deadlineFired = false
        let deadline = Task {
            try? await Task.sleep(for: .milliseconds(3000))
            guard !Task.isCancelled else { return }
            deadlineFired = true
            Logger.calibration.error("request \(id) \(command, privacy: .public) exceeded 3000 ms reply deadline after \(started.duration(to: .now).description, privacy: .public)")
            connection.cancel()
        }
        defer { deadline.cancel() }
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                connection.send(content: data, completion: .contentProcessed { error in
                    if let error { continuation.resume(throwing: error) }
                    else { continuation.resume() }
                })
            }
            while !buffer.contains(10) {
                let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, complete, error in
                        if let error { continuation.resume(throwing: error) }
                        else if let data, !data.isEmpty { continuation.resume(returning: data) }
                        else { continuation.resume(throwing: CalibrationFailure(complete ? "Calibration disconnected" : "Empty calibration response")) }
                    }
                }
                buffer.append(chunk)
                guard buffer.count <= 2048 else { throw CalibrationFailure("Calibration response too large") }
            }
            let newline = buffer.firstIndex(of: 10)!
            let line = buffer[..<newline]
            buffer.removeSubrange(...newline)
            let reply = try JSONDecoder().decode(CalibrationReply.self, from: line)
            try reply.validate(ticket: ticket, id: id)
            let elapsed = started.duration(to: .now)
            if command != "heartbeat" || elapsed > .milliseconds(500) {
                Logger.calibration.info("request \(id) \(command, privacy: .public) replied after \(elapsed.description, privacy: .public)")
            }
            return reply
        } catch {
            if deadlineFired {
                throw CalibrationFailure("Timed out waiting for \(command) #\(id) after 3000 ms")
            }
            Logger.calibration.error("request \(id) \(command, privacy: .public) failed after \(started.duration(to: .now).description, privacy: .public): \(error.localizedDescription, privacy: .public)")
            throw error
        }
    }

    func close() {
        completeConnect(.failure(CalibrationFailure("Calibration disconnected")))
        connection?.cancel()
        connection = nil
        buffer.removeAll()
    }
}
