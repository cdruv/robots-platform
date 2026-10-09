import Foundation
import Observation

@Observable
final class FirmwareStore {
    private(set) var state = FirmwareState()

    /// The power-on menu's choice: a mode, or "" for nothing. nil follows the board.
    var bootSelection: String?

    /// The offsets being edited, starting from the stored ones (or neutral when unknown).
    var offsets = LegOffsets(left: 0, right: 0)

    private(set) var consoleLines: [ConsoleLine] = []

    private let service: any FirmwareService
    private var tasks: [Task<Void, Never>] = []

    init(service: any FirmwareService) {
        self.service = service
    }

    func start() {
        guard tasks.isEmpty else { return }
        tasks.append(Task { [weak self, service] in
            for await state in service.state() {
                self?.apply(state)
            }
        })
        tasks.append(Task { [weak self, service] in
            for await line in service.console() {
                self?.consoleLines.append(line)
            }
        })
    }

    var hasDevice: Bool { state.hasDevice }

    var storedOffsets: LegOffsets? { state.storedOffsets }

    /// Calibration needs the board to report stored offsets, which it doesn't yet.
    var canCalibrate: Bool { hasDevice && storedOffsets != nil && !state.isBusy }

    var canUpload: Bool { hasDevice && !state.isBusy && !state.changedFiles.isEmpty }

    /// What `mode.txt` holds now: a mode, or "" for none. nil until read.
    var currentBoot: String? {
        switch state.bootMode {
        case .armed(let mode): mode
        case .idle: ""
        case nil: nil
        }
    }

    /// The menu: nothing, then the firmware's modes in its order.
    var bootOptions: [(value: String, label: String)] {
        [("", "nothing")] + state.modes.map { ($0, PicoArm.menuLabel($0)) }
    }

    var selectedBoot: String {
        if let bootSelection, bootOptions.contains(where: { $0.value == bootSelection }) { return bootSelection }
        if let currentBoot, bootOptions.contains(where: { $0.value == currentBoot }) { return currentBoot }
        return state.modes.first ?? ""
    }

    var canApplyBoot: Bool {
        hasDevice && !state.isBusy && !state.modes.isEmpty && currentBoot != nil && selectedBoot != currentBoot
    }

    var unsavedOffsetCount: Int {
        LegSide.allCases.count { isUnsaved($0) }
    }

    func isUnsaved(_ side: LegSide) -> Bool {
        guard let storedOffsets else { return false }
        return offsets[side] != storedOffsets[side]
    }

    func refreshLocal() {
        Task { await service.refreshLocal() }
    }

    func chooseFolder(_ folder: URL) {
        Task { await service.setFolder(folder) }
    }

    func upload() {
        Task { await service.upload() }
    }

    func applyBoot() {
        guard canApplyBoot else { return }
        let mode = selectedBoot
        bootSelection = nil
        Task { await service.setBootMode(mode.isEmpty ? nil : mode) }
    }

    func writeOffsets() {
        let offsets = offsets
        Task { await service.writeOffsets(offsets) }
    }

    func centerLegs() {
        Task { await service.centerLegs() }
    }

    func sweepLegs() {
        Task { await service.sweepLegs(degrees: 10) }
    }

    func clearConsole() {
        consoleLines.removeAll()
    }

    private func apply(_ new: FirmwareState) {
        if state.storedOffsets == nil, let stored = new.storedOffsets { offsets = stored }
        state = new
    }
}
