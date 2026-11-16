import Foundation
import Testing
@testable import Robot_Relay

@MainActor
struct BodyStoreTests {
    final class Recorder: BodyService {
        var sent: [BodyCommand] = []
        func states() -> AsyncStream<BodyLink?> { AsyncStream { _ in } }
        func drive(left: Double, right: Double) { sent.append(.legs(left: left, right: right)) }
        func limp() { sent.append(.limp) }
        func setTrim(_ steps: [Int]) { sent.append(.trim(steps)) }
        func saveTrim() { sent.append(.save) }
    }

    private let start = ContinuousClock.now

    private func connected(mode: PicoBodyState.Mode = .limp, trim: [Int] = [0, 0], saved: [Int] = [0, 0], stored: Bool = false) -> BodyLink {
        let pico = PicoBodyState(board: "e66118604b", mode: mode, target: [0, 0], pulses: mode == .active ? [1500, 1500] : nil,
                                 trim: trim, saved: saved, stored: stored, error: nil)
        return BodyLink(phase: "connected", pico: pico, rttMs: 12)
    }

    private func store(_ link: BodyLink?) -> (BodyStore, Recorder) {
        let service = Recorder()
        let store = BodyStore(service: service)
        store.apply(link)
        return (store, service)
    }

    @Test func cannotEngageWithoutThePico() {
        for link in [nil, BodyLink(phase: "searching", pico: nil, rttMs: nil)] {
            let (store, service) = store(link)
            store.engage(.drive, at: start)
            store.engage(.calibrate, at: start)
            store.tick(at: start)
            #expect(store.mode == .off)
            #expect(service.sent.isEmpty)
        }
    }

    @Test func keysStepOnPressMoveSteadilyWhileHeldAndStopOnRelease() {
        let (store, service) = store(connected())
        let leftUp = BodyStore.Nudge(side: .left, sign: 1)
        let rightDown = BodyStore.Nudge(side: .right, sign: -1)
        store.press(leftUp, at: start)
        #expect(store.targets == LegAngles(), "keys do nothing until driving")

        store.movesTogether = false
        store.engage(.drive, at: start)
        store.press(leftUp, at: start)
        #expect(service.sent.last == .legs(left: 1, right: 0), "a press is sent at once, not on the next tick")
        store.press(leftUp, at: start + .milliseconds(30))
        #expect(store.targets == LegAngles(left: 1, right: 0), "a press steps 1°; key repeat adds nothing")
        store.tick(at: start + .milliseconds(150))
        #expect(store.targets.left == 1, "no continuous motion before the 150 ms hold delay")
        store.tick(at: start + .milliseconds(400))
        #expect(abs(store.targets.left - 46) < 1e-9, "1° plus 0.25 s at 180°/s")
        #expect(service.sent.last == .legs(left: store.targets.left, right: 0))

        store.release(leftUp)
        store.press(rightDown, at: start + .milliseconds(400))
        store.tick(at: start + .seconds(5))
        #expect(abs(store.targets.left - 46) < 1e-9, "a released key stops moving its leg")
        #expect(store.targets.right == -90, "held keys stop at the range end")

        store.releaseKeys()
        store.centre()
        store.movesTogether = true
        store.press(rightDown, at: start + .seconds(5))
        #expect(store.targets == LegAngles(left: -1, right: -1), "together, any key moves both legs")
        store.stop()
        store.engage(.drive, at: start + .seconds(6))
        store.tick(at: start + .seconds(8))
        #expect(store.targets == LegAngles(), "keys held when driving stopped are forgotten")
    }

    @Test func drivingStartsAtHorizontalAndRepeatsTheCurrentTargets() {
        let (store, service) = store(connected())
        store.movesTogether = false
        store.setTarget(.left, 40)
        store.engage(.drive, at: start)
        #expect(service.sent == [.legs(left: 0, right: 0)], "a target left on a slider before engaging is not applied")
        store.setTarget(.left, 40)
        store.setTarget(.right, -200)
        store.tick(at: start + .milliseconds(50))
        store.tick(at: start + .milliseconds(100))
        #expect(service.sent.suffix(2) == [.legs(left: 40, right: -90), .legs(left: 40, right: -90)])
        store.movesTogether = true
        store.setTarget(.right, 15)
        store.centre()
        #expect(store.targets == LegAngles())
        store.setTarget(.right, 15)
        #expect(store.targets == LegAngles(left: 15, right: 15))
    }

    @Test func stopAvailabilityTracksRelayAndPicoActivity() {
        let (store, _) = store(nil)
        #expect(!store.canStop)
        store.apply(connected())
        #expect(!store.canStop)
        for mode in [BodyStore.Mode.drive, .calibrate] {
            store.engage(mode, at: start)
            #expect(store.canStop)
            store.stop()
            #expect(!store.canStop)
        }
        store.apply(connected(mode: .active))
        #expect(store.canStop)
        store.stop()
        #expect(store.canStop)
        store.apply(connected())
        #expect(!store.canStop)
        store.apply(BodyLink(phase: "searching", pico: nil, rttMs: nil))
        #expect(!store.canStop)
    }

    @Test func stopAlwaysSendsLimpAndEndsTheStream() {
        let (store, service) = store(connected())
        store.stop()
        #expect(service.sent == [.limp], "STOP works even when Relay thinks the legs are off")
        store.engage(.drive, at: start)
        store.stop()
        #expect(store.mode == .off)
        let count = service.sent.count
        store.tick(at: start + .seconds(1))
        #expect(service.sent.count == count)
        #expect(service.sent.last == .limp)
    }

    @Test func aLostLinkDisengagesAndDoesNotReengageWhenItReturns() {
        for lost in [nil, BodyLink(phase: "searching", pico: nil, rttMs: nil)] {
            let (store, service) = store(connected())
            store.engage(.drive, at: start)
            store.apply(lost)
            #expect(store.mode == .off)
            store.apply(connected())
            let count = service.sent.count
            store.tick(at: start + .seconds(1))
            #expect(store.mode == .off)
            #expect(service.sent.count == count)
        }
    }

    @Test func calibratingHoldsHorizontalAndSendsEachTrimChangeInHalfDegreeSteps() {
        let (store, service) = store(connected(trim: [2, -2], saved: [2, -2], stored: true))
        store.setTrim(.left, 3)
        #expect(service.sent.isEmpty, "trim is only adjustable while calibrating")
        store.engage(.calibrate, at: start)
        #expect(store.trim == LegOffsets(left: 1, right: -1))
        #expect(service.sent == [.legs(left: 0, right: 0)])
        store.setTrim(.left, 1.5)
        store.setTrim(.left, 1.5)
        store.setTrim(.right, 1.3)
        store.setTrim(.right, 40)
        #expect(service.sent.dropFirst() == [.trim([3, -2]), .trim([3, 3]), .trim([3, 18])])
        store.tick(at: start + .milliseconds(50))
        #expect(service.sent.last == .legs(left: 0, right: 0))
    }

    @Test func wiggleStepsOneLegForAMomentAndReturns() {
        let (store, service) = store(connected())
        store.engage(.calibrate, at: start)
        store.wiggle(.right, at: start)
        store.tick(at: start + .milliseconds(50))
        #expect(service.sent.last == .legs(left: 0, right: BodyStore.wiggleDegrees))
        store.tick(at: start + .milliseconds(399))
        #expect(service.sent.last == .legs(left: 0, right: BodyStore.wiggleDegrees))
        store.tick(at: start + .milliseconds(400))
        #expect(service.sent.last == .legs(left: 0, right: 0))
    }

    @Test func saveIsConfirmedOnlyByThePicosState() {
        let (store, service) = store(connected())
        store.engage(.calibrate, at: start)
        store.setTrim(.left, 2)
        store.saveTrim(at: start)
        #expect(service.sent.suffix(2) == [.trim([4, 0]), .save])
        #expect(store.save == .saving)
        // The preview alone, or an older saved value, is not a confirmation.
        store.apply(connected(trim: [4, 0], saved: [0, 0], stored: false))
        store.apply(connected(trim: [4, 0], saved: [2, 0], stored: true))
        #expect(store.save == .saving)
        store.tick(at: start + .milliseconds(1900))
        #expect(store.save == .saving)
        store.apply(connected(trim: [4, 0], saved: [4, 0], stored: true))
        #expect(store.save == .saved)
        store.tick(at: start + .seconds(5))
        #expect(store.save == .saved)
        store.setTrim(.left, 2.5)
        #expect(store.save == .idle, "a changed trim is unsaved again")
    }

    @Test func saveFailsAfterTwoSecondsWithoutConfirmationOrWhenTheLinkDrops() {
        let (store, _) = store(connected())
        store.engage(.calibrate, at: start)
        store.setTrim(.left, 2)
        store.saveTrim(at: start)
        store.tick(at: start + .seconds(2))
        guard case .failed = store.save else {
            Issue.record("expected a failed save, got \(store.save)")
            return
        }
        #expect(store.mode == .calibrate)

        store.saveTrim(at: start + .seconds(3))
        store.apply(nil)
        guard case .failed = store.save else {
            Issue.record("expected a failed save, got \(store.save)")
            return
        }
        #expect(store.mode == .off)
    }

    @Test func leavingCalibrationWithoutSavingPutsTheStoredTrimBack() {
        let (store, service) = store(connected(trim: [0, 0], saved: [0, 0], stored: true))
        store.engage(.calibrate, at: start)
        store.setTrim(.left, 3)
        store.apply(connected(trim: [6, 0], saved: [0, 0], stored: true))
        store.stop()
        #expect(service.sent.suffix(2) == [.trim([0, 0]), .limp])

        // Nothing to put back once it was saved.
        store.engage(.calibrate, at: start)
        store.apply(connected(trim: [6, 0], saved: [6, 0], stored: true))
        store.engage(.drive, at: start)
        #expect(!service.sent.suffix(2).contains(.trim([6, 0])))
        #expect(store.mode == .drive)
    }
}

struct BodyLineTests {
    private func read(_ messages: (String, String)...) throws -> [PhoneMessage] {
        var stream = PhoneStream()
        let bytes = messages.reduce(PhoneStreamFixture.preamble(session: "s")) { $0 + PhoneStreamFixture.message($1.0, $1.1) }
        return try stream.append(bytes).filter { if case .hello = $0 { false } else { true } }
    }

    private let state = #"{"t":"state","v":1,"board":"e66118604b","mode":"active","target":[12.5,-30.0],"pulses":[1638,1830],"trim":[2,-1],"saved":[2,0],"stored":true,"slew":360,"n":41,"error":{"n":40,"msg":"Unknown command"},"up_ms":123456}"#

    @Test func theLinkCarriesThePicosStateAsTheFirmwareNamesIt() throws {
        let pico = PicoBodyState(board: "e66118604b", mode: .active, target: [12.5, -30], pulses: [1638, 1830],
                                 trim: [2, -1], saved: [2, 0], stored: true, error: .init(msg: "Unknown command"))
        // The phone replays the latched state before the latched link.
        let messages = try read(("/body/state", state), ("/body/link", #"{"phase":"connected","rttMs":12}"#), ("/body/state", state))
        #expect(messages == Array(repeating: .body(BodyLink(phase: "connected", pico: pico, rttMs: 12)), count: 2))
    }

    @Test func aLinkThatIsNotConnectedCarriesNoPico() throws {
        let messages = try read(
            ("/body/link", #"{"phase":"connected"}"#), ("/body/state", state),
            ("/body/link", #"{"phase":"searching"}"#), ("/body/state", state),
        )
        #expect(messages.last == .body(BodyLink(phase: "searching", pico: nil, rttMs: nil)))
        #expect(messages.count == 3, "a state while not connected is not a report")
        // The phone omits nulls: a limp Pico has no pulses and no error.
        let limp = #"{"t":"state","v":1,"board":"b","mode":"limp","target":[0,0],"trim":[0,0],"saved":[0,0],"stored":false,"slew":360,"up_ms":5}"#
        guard case .body(let link)? = try read(("/body/link", #"{"phase":"connected"}"#), ("/body/state", limp)).last else {
            Issue.record("not decoded as a body report")
            return
        }
        #expect(link.pico?.pulses == nil && link.pico?.error == nil && link.pico?.mode == .limp)
    }

    @Test func commandsAreTheLinesThePhoneExpects() {
        func text(_ command: BodyCommand) -> String? { command.line.map { String(decoding: $0, as: UTF8.self) } }
        #expect(text(.legs(left: 12.5, right: -30)) == #"{"legs":[12.5,-30],"link":"body"}"#)
        #expect(text(.limp) == #"{"limp":true,"link":"body"}"#)
        #expect(text(.trim([3, -18])) == #"{"link":"body","trim":[3,-18]}"#)
        #expect(text(.save) == #"{"link":"body","save":true}"#)
        #expect(text(.pair(PicoPairing(ssid: "Walky-617264", password: "0123456789abcdef01234567")))
            == #"{"link":"pair","password":"0123456789abcdef01234567","ssid":"Walky-617264"}"#)
        #expect(text(.legs(left: .nan, right: 0)) == nil)
    }
}

@MainActor
struct PhoneBodyStatusTests {
    @Test func bodyReportsReachTheListenerAndEndWithTheConnection() {
        let connection = PhoneConnection()
        var reports: [BodyLink?] = []
        var hellos = 0
        connection.onBody = { reports.append($0) }
        connection.onHello = { _ in hellos += 1 }
        connection.handle(data: PhoneStreamFixture.preamble(session: "s"))
        connection.startStream()
        connection.handle(data: PhoneStreamFixture.preamble(session: "s"))
        #expect(hellos == 2, "the pairing is re-sent after a reconnect in the same session")
        connection.handle(data: PhoneStreamFixture.message("/body/link", #"{"phase":"searching"}"#))
        #expect(reports == [BodyLink(phase: "searching", pico: nil, rttMs: nil)])
        #expect(connection.status.body == PhoneConnection.Body(phase: "searching", mode: nil))
        connection.disconnect()
        #expect(reports.last == .some(nil))
        #expect(connection.status.body == nil)
    }
}
