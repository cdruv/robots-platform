import Foundation
import Network

protocol CalibrationTransport: AnyObject {
    func connect() async throws
    func request(_ command: String, ticket: CalibrationTicket, steps: [Int]?) async throws -> CalibrationReply
    func close()
}

/// Strict request/reply transport. The controller is its sole, sequential caller.
final class CalibrationTCPTransport: CalibrationTransport {
    private var connection: NWConnection?
    private var nextID = 0
    private var buffer = Data()
    private var connectCompletion: ((Result<Void, Error>) -> Void)?

    func connect() async throws {
        precondition(!RobotServices.isTestHost)
        close()
        let connection = NWConnection(host: "192.168.4.1", port: 8765, using: .tcp)
        self.connection = connection
        let timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(8))
            guard !Task.isCancelled else { return }
            self?.completeConnect(.failure(CalibrationFailure("Calibration connection timed out. Check Local Network permission in System Settings and re-arm calibration if the Pico timed out.")))
            connection.cancel()
        }
        defer { timeout.cancel() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connectCompletion = { continuation.resume(with: $0) }
            connection.stateUpdateHandler = { [weak self] state in
                Task { @MainActor [weak self] in
                    guard let self, self.connection === connection else { return }
                    switch state {
                    case .ready: self.completeConnect(.success(()))
                    case .failed(let error): self.completeConnect(.failure(error))
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

    func request(_ command: String, ticket: CalibrationTicket, steps: [Int]?) async throws -> CalibrationReply {
        guard let connection else { throw CalibrationFailure("Calibration is disconnected") }
        nextID += 1
        let id = nextID
        var object: [String: Any] = ["version": 1, "board": ticket.board, "session": ticket.session, "id": id, "command": command]
        if let steps { object["steps"] = steps }
        var data = try JSONSerialization.data(withJSONObject: object)
        data.append(10)
        let deadline = Task {
            try? await Task.sleep(for: .milliseconds(1500))
            if !Task.isCancelled { connection.cancel() }
        }
        defer { deadline.cancel() }
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
        return reply
    }

    func close() {
        completeConnect(.failure(CalibrationFailure("Calibration disconnected")))
        connection?.cancel()
        connection = nil
        buffer.removeAll()
    }
}
