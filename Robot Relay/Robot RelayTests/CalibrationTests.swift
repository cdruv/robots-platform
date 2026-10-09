import Foundation
import Testing
@testable import Robot_Relay

@MainActor
struct CalibrationTests {
    final class Board: CalibrationBoard {
        var isUSBConnected = true
        var prepared: CalibrationTicket?
        var reserved = false
        var released = false
        var cancelled = false
        func prepare(_ ticket: CalibrationTicket) async throws { prepared = ticket; reserved = true }
        func cancelPrepared(_ ticket: CalibrationTicket) async -> Bool { cancelled = true; return isUSBConnected }
        func reserve() async { reserved = true }
        func release() { reserved = false; released = true }
    }
    actor WiFi: CalibrationWiFi {
        var joined: String?
        var restored = false
        let fail: Bool
        let recovery: String?
        init(fail: Bool = false, recovery: String? = nil) { self.fail = fail; self.recovery = recovery }
        func join(_ ticket: CalibrationTicket) async throws {
            joined = ticket.ssid
            if fail { throw CalibrationFailure("Wi-Fi denied") }
        }
        func restore() async -> String? { restored = true; return recovery }
    }
    final class Storage: CalibrationTicketStorage {
        var ticket: CalibrationTicket?
        var failSave = false
        func save(_ ticket: CalibrationTicket) throws {
            if failSave { throw CalibrationFailure("Keychain unavailable") }
            self.ticket = ticket
        }
        func load() -> CalibrationTicket? { ticket }
        func clear() { ticket = nil }
    }
    final class Transport: CalibrationTransport {
        var commands: [String] = []
        var previews: [[Int]] = []
        var preview = [0, 0]
        var saved = [0, 0]
        var closed = false
        var failSave = false
        var failHeartbeat = false
        var previewGate: CheckedContinuation<Void, Never>?
        var holdPreview = false
        func connect() async throws {}
        func close() { closed = true }
        func request(_ command: String, ticket: CalibrationTicket, steps: [Int]?) async throws -> CalibrationReply {
            commands.append(command)
            if command == "preview" {
                previews.append(steps!)
                if holdPreview {
                    await withCheckedContinuation { previewGate = $0 }
                }
                preview = steps!
            }
            if command == "save" {
                saved = preview
                if failSave { throw CalibrationFailure("Reply lost") }
            }
            if command == "heartbeat", failHeartbeat { throw CalibrationFailure("Link lost") }
            let offsets = LegOffsets(steps: preview)!
            return CalibrationReply(version: 1, board: ticket.board, session: ticket.session, id: 1,
                saved: saved, preview: preview, stored: command == "save",
                pulses: [offsets.left, offsets.right].map { ServoMath.pulseMicros(offsetDegrees: $0) },
                finished: command == "save" || command == "cancel")
        }
    }
    let snapshot = CalibrationSnapshot(version: 1, board: "board-a", steps: [0, 0], stored: false)

    func waitUntil(_ predicate: () -> Bool) async throws {
        for _ in 0..<300 {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw CalibrationFailure("Test condition timed out")
    }

    func ready(wifi: WiFi? = nil, transport: Transport? = nil) async throws -> (CalibrationController, Board, Storage) {
        let board = Board(), storage = Storage()
        let controller = CalibrationController(board: board, wifi: wifi ?? WiFi(), transport: transport ?? Transport(), storage: storage)
        controller.prepare(snapshot: snapshot)
        try await waitUntil { controller.phase == .awaitingBoot }
        #expect(controller.blocksUSB)
        #expect(!controller.canConnect)
        #expect(storage.ticket?.board == "board-a")
        board.isUSBConnected = false
        #expect(controller.canConnect)
        return (controller, board, storage)
    }

    @Test func preparePreviewSaveAndRestore() async throws {
        let wifi = WiFi(), transport = Transport()
        let (controller, board, storage) = try await ready(wifi: wifi, transport: transport)
        controller.connect()
        try await waitUntil { controller.phase == .adjusting }
        controller.offsets = LegOffsets(left: -0.5, right: 9)
        controller.saveAndFinish()
        try await waitUntil { controller.phase == .finished }
        #expect(transport.previews == [[-1, 18]])
        #expect(transport.saved == [-1, 18])
        #expect(controller.pulses == [1494, 1599])
        #expect(board.released && !controller.blocksUSB)
        #expect(storage.ticket == nil)
        #expect(await wifi.restored)
    }

    @Test func coalescesEditsAndOnlyAcknowledgesAppliedPreview() async throws {
        let transport = Transport()
        let (controller, _, _) = try await ready(transport: transport)
        controller.connect()
        try await waitUntil { controller.phase == .adjusting }
        transport.holdPreview = true
        controller.offsets.left = 1
        try await waitUntil { transport.previewGate != nil }
        #expect(controller.applied?.left == 0)
        controller.offsets.left = 2
        controller.offsets.left = 3
        transport.holdPreview = false
        transport.previewGate?.resume()
        transport.previewGate = nil
        try await waitUntil { controller.applied?.left == 3 }
        #expect(transport.previews == [[2, 0], [6, 0]])
        controller.cancel()
        try await waitUntil { controller.phase == .finished }
        #expect(!transport.commands.contains("save"))
    }

    @Test func saveLostReplyIsUnconfirmed() async throws {
        let transport = Transport()
        transport.failSave = true
        let (controller, board, _) = try await ready(transport: transport)
        controller.connect()
        try await waitUntil { controller.phase == .adjusting }
        controller.saveAndFinish()
        try await waitUntil { controller.phase == .error }
        #expect(controller.message.contains("Save unconfirmed"))
        #expect(board.released && transport.closed)
    }

    @Test func joinFailureRestoresAndShowsManualRecovery() async throws {
        let wifi = WiFi(fail: true, recovery: "Select previous network")
        let (controller, board, _) = try await ready(wifi: wifi)
        controller.connect()
        try await waitUntil { controller.phase == .error }
        #expect(controller.message.contains("Wi-Fi denied"))
        #expect(controller.recovery == "Select previous network")
        #expect(await wifi.restored)
        #expect(board.released)
    }

    @Test func permissionFailureNeverJoins() async throws {
        let board = Board(), wifi = WiFi()
        let controller = CalibrationController(board: board, wifi: wifi, transport: Transport(), storage: Storage(),
            permission: { throw CalibrationFailure("Location permission denied") })
        controller.prepare(snapshot: snapshot)
        try await waitUntil { controller.phase == .awaitingBoot }
        board.isUSBConnected = false
        controller.connect()
        try await waitUntil { controller.phase == .error }
        #expect(await wifi.joined == nil)
        #expect(controller.message.contains("permission denied"))
    }

    @Test func cancelPreparedAndOlderFirmware() async throws {
        let board = Board(), storage = Storage()
        let controller = CalibrationController(board: board, wifi: WiFi(), transport: Transport(), storage: storage)
        controller.prepare(snapshot: nil)
        #expect(controller.phase == .idle)
        controller.prepare(snapshot: CalibrationSnapshot(version: 2, board: "board-b", steps: [0, 0]))
        #expect(board.prepared == nil)
        controller.prepare(snapshot: snapshot)
        try await waitUntil { controller.phase == .awaitingBoot }
        controller.cancel()
        try await waitUntil { controller.phase == .finished }
        #expect(board.cancelled && board.released)
        #expect(storage.ticket == nil)
    }

    @Test func lostLinkCleansUp() async throws {
        let transport = Transport(), wifi = WiFi()
        transport.failHeartbeat = true
        let (controller, board, _) = try await ready(wifi: wifi, transport: transport)
        controller.connect()
        try await waitUntil { controller.phase == .error }
        #expect(board.released && transport.closed)
        #expect(await wifi.restored)
    }

    @Test func keychainFailureDoesNotRunUSBCommands() async throws {
        let board = Board(), storage = Storage()
        storage.failSave = true
        let controller = CalibrationController(board: board, wifi: WiFi(), transport: Transport(), storage: storage)
        controller.prepare(snapshot: snapshot)
        try await waitUntil { controller.phase == .error }
        #expect(board.prepared == nil && !board.cancelled)
    }

    @Test func armingFailureKeepsExceptionAndRedactsCredentials() {
        let result = CommandResult(status: 1,
            stdout: "calibration.arm(encoded-secret)\npassword-secret session-secret",
            stderr: "Traceback (most recent call last):\n  File calibration.py, line 75\nAttributeError: 'str' object has no attribute 'isalnum'")
        let message = CalibrationCommandDiagnostics.failure(result, secrets: ["encoded-secret", "password-secret", "session-secret"])
        #expect(message.contains("AttributeError"))
        #expect(message.contains("isalnum"))
        #expect(!message.contains("encoded-secret"))
        #expect(!message.contains("password-secret"))
        #expect(!message.contains("session-secret"))
        #expect(CalibrationCommandDiagnostics.failure(CommandResult(status: 7, stdout: "", stderr: ""), secrets: []) == "exit 7")
    }

    @Test func restorationDoesNotReplaceUserSelectedNetwork() {
        #expect(CalibrationWiFiPolicy.shouldRestore(current: "Walky-Cal-1", temporary: "Walky-Cal-1"))
        #expect(CalibrationWiFiPolicy.shouldRestore(current: nil, temporary: "Walky-Cal-1"))
        #expect(!CalibrationWiFiPolicy.shouldRestore(current: "User selected network", temporary: "Walky-Cal-1"))
    }

    @Test func usbReservationPublishesAndExcludesOperations() async {
        let pico = PicoUSBLink(runner: CommandRunner())
        var reservations: [Bool] = []
        pico.observe { _ in reservations.append(pico.calibrationReserved) }
        _ = await pico.reserveForCalibration()
        var ran = false
        await pico.withBoard(reportingTo: CommandRunner()) { _ in ran = true }
        pico.refresh()
        #expect(!ran)
        #expect(pico.calibrationReserved)
        pico.releaseCalibration()
        #expect(reservations == [false, true, false])
    }

    @Test func shutdownCancelsLiveSessionAndRestoresNetwork() async throws {
        let transport = Transport(), wifi = WiFi()
        let (controller, board, _) = try await ready(wifi: wifi, transport: transport)
        controller.connect()
        try await waitUntil { controller.phase == .adjusting }
        await controller.shutdown()
        #expect(transport.commands.contains("cancel"))
        #expect(board.released)
        #expect(await wifi.restored)
    }

    @Test func usbSnapshotRoundingAndIdentityValidation() throws {
        let a = PicoMode.parseCalibration("calibration={\"version\":1,\"board\":\"a\",\"steps\":[-1,18],\"stored\":true}")
        #expect(a?.offsets == LegOffsets(left: -0.5, right: 9))
        #expect(a?.supported == true)
        #expect(PicoMode.parseCalibration("calibration=null") == nil)
        #expect(PicoMode.parseCalibration("calibration={\"version\":1,\"board\":\"b\",\"error\":\"bad data\"}")?.supported == false)
        #expect(ServoMath.pulseMicros(offsetDegrees: -0.5) == 1494)
        let ticket = CalibrationTicket(board: "a")
        let reply = CalibrationReply(version: 1, board: "b", session: ticket.session, id: 1,
            saved: [0, 0], preview: [0, 0], stored: false, pulses: [1500, 1500], finished: false)
        #expect(throws: CalibrationFailure.self) { try reply.validate(ticket: ticket, id: 1) }
    }
}
