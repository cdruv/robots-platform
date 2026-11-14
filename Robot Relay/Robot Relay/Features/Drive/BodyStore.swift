import Foundation
import Observation
import os

/// Drives the legs and trims them from the Drive tab.
///
/// The Pico lets the legs go limp 500 ms after the last target, so while engaged this store
/// sends the current targets 50 times a second. That stream is the only thing keeping the
/// servos on: when it stops for any reason (STOP, a lost link, Relay quitting) the legs go limp.
/// A lost link never re-engages by itself.
@Observable
final class BodyStore {
    enum Mode: Equatable {
        case off, drive, calibrate
    }

    enum SaveState: Equatable {
        case idle, saving, saved
        case failed(String)
    }

    /// 50 Hz, the most the body link takes.
    static let interval: Duration = .milliseconds(20)
    static let wiggleDegrees = 15.0
    static let wiggleDuration: Duration = .milliseconds(400)
    static let saveTimeout: Duration = .seconds(2)
    static let range: ClosedRange<Double> = -90...90
    /// A key press moves a leg this far at once, so a tap is a fine step...
    static let keyStepDegrees = 1.0
    /// ...and only a key held this long keeps moving it, at `keyDegreesPerSecond`.
    static let keyHoldDelay: Duration = .milliseconds(150)
    static let keyDegreesPerSecond = 180.0

    /// One drive key: which leg it moves and which way (+1 or -1).
    struct Nudge: Hashable {
        var side: LegSide
        var sign: Double
    }

    /// The phone's latest report; nil while the phone is not connected.
    private(set) var link: BodyLink?
    private(set) var mode: Mode = .off
    /// Drive targets in degrees from horizontal.
    private(set) var targets = LegAngles()
    /// Moving one slider moves both legs.
    var movesTogether = true
    /// The trim being tried while calibrating, in degrees.
    private(set) var trim = LegOffsets(left: 0, right: 0)
    private(set) var save: SaveState = .idle

    private let service: any BodyService
    private var states: Task<Void, Never>?
    private var stream: Task<Void, Never>?
    private var wiggling: (side: LegSide, until: ContinuousClock.Instant)?
    private var saveDeadline: ContinuousClock.Instant?
    private var activity: NSObjectProtocol?
    /// Drive keys held down, each with the time it starts moving the leg continuously.
    private var held: [Nudge: ContinuousClock.Instant] = [:]
    private var lastTick: ContinuousClock.Instant?

    init(service: any BodyService) {
        self.service = service
    }

    var pico: PicoBodyState? { link?.pico }
    var canEngage: Bool { pico != nil }

    func start() {
        guard states == nil else { return }
        states = Task { [weak self, service] in
            for await link in service.states() {
                self?.apply(link)
            }
        }
    }

    /// Takes the phone's report. Internal so tests can feed reports without a stream.
    func apply(_ link: BodyLink?) {
        self.link = link
        guard let pico = link?.pico else {
            if mode != .off {
                Logger.body.notice("body link lost while engaged; legs go limp on the Pico's timeout")
                if save == .saving { save = .failed("The link dropped before the Pico confirmed the save.") }
                disengage()
            }
            return
        }
        if save == .saving, pico.stored, pico.saved == trim.steps, pico.trim == pico.saved {
            save = .saved
            saveDeadline = nil
            Logger.body.notice("trim \(pico.saved, privacy: .public) saved on the Pico")
        }
    }

    /// Starts the target stream. Driving starts from horizontal; calibrating holds horizontal
    /// and starts from the trim the Pico is using.
    func engage(_ new: Mode, at now: ContinuousClock.Instant = .now) {
        guard new != .off, new != mode, let pico else { return }
        if mode == .calibrate { discardPreview() }
        targets = LegAngles()
        trim = LegOffsets(steps: pico.trim) ?? LegOffsets(left: 0, right: 0)
        wiggling = nil
        held = [:]
        lastTick = nil
        save = .idle
        mode = new
        Logger.body.notice("legs engaged for \(new == .drive ? "driving" : "calibration", privacy: .public)")
        if activity == nil {
            // App Nap would throttle the stream and let the Pico's timeout drop the legs.
            activity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .latencyCritical], reason: "Driving Walky's legs")
        }
        tick(at: now)
        stream = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.interval)
                guard !Task.isCancelled else { return }
                self?.tick(at: .now)
            }
        }
    }

    /// Legs limp now, whatever the mode. A trim that was being tried and not saved is dropped.
    func stop() {
        if mode == .calibrate { discardPreview() }
        service.limp()
        if mode != .off { Logger.body.notice("legs stopped") }
        disengage()
    }

    func setTarget(_ side: LegSide, _ degrees: Double) {
        let value = min(max(degrees, Self.range.lowerBound), Self.range.upperBound)
        if movesTogether {
            targets = LegAngles(left: value, right: value)
        } else {
            targets[side] = value
        }
    }

    func centre() {
        targets = LegAngles()
    }

    /// A drive key went down. With `movesTogether` any key moves both legs. Ignored unless
    /// driving, and for a key already held (key repeat).
    func press(_ nudge: Nudge, at now: ContinuousClock.Instant = .now) {
        guard mode == .drive, held[nudge] == nil else { return }
        held[nudge] = now + Self.keyHoldDelay
        setTarget(nudge.side, targets[nudge.side] + nudge.sign * Self.keyStepDegrees)
        // Sent now rather than on the next tick.
        service.drive(left: targets.left, right: targets.right)
    }

    func release(_ nudge: Nudge) {
        held[nudge] = nil
    }

    /// Forgets every held key, for when key-ups can no longer arrive (Relay lost focus).
    func releaseKeys() {
        held = [:]
    }

    /// Moves the legs for the keys held between the last tick and `now`.
    private func steer(to now: ContinuousClock.Instant) {
        guard let lastTick else { lastTick = now; return }
        // A beat stamped earlier than the last one moves nothing.
        guard now > lastTick else { return }
        self.lastTick = now
        guard !held.isEmpty else { return }
        func seconds(_ duration: Duration) -> Double {
            Double(duration.components.seconds) + Double(duration.components.attoseconds) / 1e18
        }
        // How far each leg moves; opposite keys on one leg cancel.
        var change = LegAngles()
        for (nudge, moving) in held where moving < now {
            let degrees = nudge.sign * Self.keyDegreesPerSecond * seconds(now - max(lastTick, moving))
            for side in LegSide.allCases where movesTogether || side == nudge.side {
                change[side] += degrees
            }
        }
        // Together, setting one leg sets both.
        for side in movesTogether ? [.left] : LegSide.allCases where change[side] != 0 {
            setTarget(side, targets[side] + change[side])
        }
    }

    func setTrim(_ side: LegSide, _ degrees: Double) {
        var new = trim
        let snapped = (degrees / LegOffsets.step).rounded() * LegOffsets.step
        new[side] = min(max(snapped, LegOffsets.range.lowerBound), LegOffsets.range.upperBound)
        guard mode == .calibrate, new != trim, let steps = new.steps else { return }
        trim = new
        save = .idle
        saveDeadline = nil
        service.setTrim(steps)
    }

    /// Steps one leg toward + and back, to check which leg a side is and which way + goes.
    func wiggle(_ side: LegSide, at now: ContinuousClock.Instant = .now) {
        guard mode == .calibrate else { return }
        wiggling = (side, now + Self.wiggleDuration)
    }

    func saveTrim(at now: ContinuousClock.Instant = .now) {
        guard mode == .calibrate, save != .saving, let steps = trim.steps else { return }
        service.setTrim(steps)
        service.saveTrim()
        save = .saving
        saveDeadline = now + Self.saveTimeout
    }

    /// One beat of the stream. Internal so tests can step time.
    func tick(at now: ContinuousClock.Instant) {
        switch mode {
        case .off:
            return
        case .drive:
            steer(to: now)
            service.drive(left: targets.left, right: targets.right)
        case .calibrate:
            var pose = LegAngles()
            if let active = wiggling {
                if now < active.until { pose[active.side] = Self.wiggleDegrees } else { wiggling = nil }
            }
            service.drive(left: pose.left, right: pose.right)
        }
        if save == .saving, let saveDeadline, now >= saveDeadline {
            save = .failed("The Pico did not confirm the save. Check the stored trim before relying on it.")
            self.saveDeadline = nil
            Logger.body.error("trim save not confirmed within \(Self.saveTimeout.components.seconds, privacy: .public) s")
        }
    }

    private func discardPreview() {
        if let pico, pico.trim != pico.saved { service.setTrim(pico.saved) }
    }

    private func disengage() {
        mode = .off
        wiggling = nil
        held = [:]
        saveDeadline = nil
        stream?.cancel()
        stream = nil
        if let activity { ProcessInfo.processInfo.endActivity(activity) }
        activity = nil
    }
}
