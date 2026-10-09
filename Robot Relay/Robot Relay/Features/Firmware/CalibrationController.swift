import Foundation
import Observation

@Observable
final class CalibrationController {
    enum Phase: String {
        case idle, preparing, awaitingBoot = "awaiting boot", connecting, adjusting, saving, finishing, finished, error
    }
    private(set) var phase: Phase = .idle
    private(set) var message = ""
    private(set) var recovery: String?
    private(set) var ticket: CalibrationTicket?
    private(set) var saved: LegOffsets?
    private(set) var applied: LegOffsets?
    private(set) var pulses: [Int] = []
    private(set) var isStored = false
    var offsets = LegOffsets(left: 0, right: 0)
    var log: ((String) -> Void)?

    private let board: any CalibrationBoard
    private let wifi: any CalibrationWiFi
    private let transport: any CalibrationTransport
    private let storage: any CalibrationTicketStorage
    private let permission: () async throws -> Void
    private var work: Task<Void, Never>?
    private var wantsSave = false
    private var wantsCancel = false

    var blocksUSB: Bool { [.preparing, .awaitingBoot, .connecting, .adjusting, .saving, .finishing].contains(phase) }
    var canConnect: Bool { phase == .awaitingBoot && !board.isUSBConnected }

    init(board: any CalibrationBoard, wifi: any CalibrationWiFi, transport: any CalibrationTransport,
         storage: any CalibrationTicketStorage, permission: @escaping () async throws -> Void = {}) {
        self.board = board
        self.wifi = wifi
        self.transport = transport
        self.storage = storage
        self.permission = permission
        board.report = { [weak self] entry in
            switch entry.state {
            case .running: self?.log?(entry.text)
            case .ok(let detail): self?.log?(detail ?? "USB calibration command completed")
            case .failed(let reason): self?.log?("USB calibration command failed: " + reason)
            }
        }
        if let pending = storage.load() {
            ticket = pending
            phase = .awaitingBoot
            message = "Recovered a prepared calibration. Disconnect USB before connecting, or cancel with the original board on USB."
            work = Task { await board.reserve() }
        }
    }

    func prepare(snapshot: CalibrationSnapshot?) {
        guard !blocksUSB, let snapshot, snapshot.supported else { return }
        let ticket = CalibrationTicket(board: snapshot.board)
        self.ticket = ticket
        saved = snapshot.offsets
        offsets = snapshot.offsets ?? LegOffsets(left: 0, right: 0)
        applied = nil
        pulses = []
        isStored = snapshot.stored == true
        wantsCancel = false
        wantsSave = false
        recovery = nil
        set(.preparing, "Preparing one calibration boot over USB…")
        work = Task {
            var armingAttempted = false
            do {
                try storage.save(ticket)
                armingAttempted = true
                try await board.prepare(ticket)
                if wantsCancel {
                    await cancelPending(ticket)
                } else {
                    set(.awaitingBoot, "Disconnect USB. Place Walky on its back, legs clear, and turn battery power on. After the five-second countdown, connect within 60 seconds.")
                }
            } catch {
                // Arming may have succeeded despite a lost USB reply. Attempt to disarm.
                let removed = armingAttempted ? await board.cancelPrepared(ticket) : true
                await finish(error: error.localizedDescription + (removed ? "" : " The one-time request may remain armed; reconnect the original board over USB before powering it."))
            }
        }
    }

    func connect() {
        guard canConnect, let ticket else { return }
        set(.connecting, "Switching Mac Wi-Fi to \(ticket.ssid)…")
        work = Task {
            var handshakeReceived = false
            do {
                try await permission()
                try Task.checkCancellation()
                try await wifi.join(ticket)
                try Task.checkCancellation()
                try await transport.connect()
                let hello = try await transport.request("hello", ticket: ticket, steps: nil)
                guard !hello.finished else { throw CalibrationFailure("Calibration already finished") }
                handshakeReceived = true
                apply(hello)
                offsets = applied!
                set(.adjusting, "Connected over calibration Wi-Fi. Adjust both legs to match, perpendicular to the body’s bottom. Positive trim increases pulse width; physical direction depends on the servo.")
                while true {
                    if wantsCancel || Task.isCancelled {
                        _ = try await transport.request("cancel", ticket: ticket, steps: nil)
                        await finish()
                        return
                    }
                    guard let steps = offsets.steps else { throw CalibrationFailure("Offsets must be ±9° in 0.5° steps") }
                    if offsets != applied {
                        let reply = try await transport.request("preview", ticket: ticket, steps: steps)
                        guard reply.preview == steps, !reply.finished else { throw CalibrationFailure("Preview was not applied") }
                        apply(reply)
                    } else if wantsSave {
                        set(.saving, "Saving both offsets on the Pico…")
                        let reply: CalibrationReply
                        do {
                            reply = try await transport.request("save", ticket: ticket, steps: nil)
                            guard reply.finished, reply.stored, reply.saved == steps else {
                                throw CalibrationFailure("Save acknowledgement did not match")
                            }
                        } catch {
                            await finish(error: "Save unconfirmed. Reconnect the Pico over USB with battery off to inspect stored offsets.")
                            return
                        }
                        apply(reply)
                        await finish()
                        return
                    } else {
                        let reply = try await transport.request("heartbeat", ticket: ticket, steps: nil)
                        guard !reply.finished else { throw CalibrationFailure("Calibration session ended") }
                        apply(reply)
                    }
                    try? await Task.sleep(for: .milliseconds(250))
                }
            } catch {
                let reason = wantsCancel ? nil : error.localizedDescription
                await finish(error: handshakeReceived ? reason :
                    (reason ?? "Connection cancelled.") + " If the Pico has not booted yet, its one-time calibration request remains armed. Reconnect over USB with battery off to prepare and cancel it.")
            }
        }
    }

    func saveAndFinish() {
        guard phase == .adjusting else { return }
        wantsSave = true
        // Freeze controls immediately; the worker first flushes the latest preview.
        set(.saving, "Applying final preview and saving…")
    }

    func cancel() {
        guard blocksUSB, phase != .finishing else { return }
        wantsCancel = true
        if phase == .awaitingBoot, let ticket {
            set(.finishing, "Cancelling prepared calibration…")
            let previous = work
            work = Task {
                await previous?.value
                await cancelPending(ticket)
            }
        } else if phase == .connecting {
            work?.cancel()
            transport.close()
        }
    }

    /// App delegate waits for cleanup before allowing a normal application termination.
    func shutdown() async {
        cancel()
        await work?.value
    }

    private func cancelPending(_ ticket: CalibrationTicket) async {
        let removed = await board.cancelPrepared(ticket)
        await finish(error: removed ? nil : "The one-time calibration request remains on the Pico. Reconnect the original board over USB and prepare/cancel it to disarm, or allow its next calibration boot to time out.")
    }

    private func apply(_ reply: CalibrationReply) {
        saved = LegOffsets(steps: reply.saved)
        applied = LegOffsets(steps: reply.preview)
        pulses = reply.pulses
        isStored = reply.stored
    }

    private func finish(error: String? = nil) async {
        set(.finishing, "Disconnecting calibration Wi-Fi…")
        transport.close()
        recovery = await wifi.restore()
        board.release()
        storage.clear()
        ticket = nil
        set(error == nil ? .finished : .error, error ?? (wantsSave ? "Calibration saved. Servos released and calibration disconnected." : "Calibration ended. Preview changes were discarded."))
    }

    private func set(_ phase: Phase, _ message: String) {
        self.phase = phase
        self.message = message
        log?(message)
    }
}
