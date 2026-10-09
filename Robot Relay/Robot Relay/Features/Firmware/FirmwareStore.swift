import Foundation
import Observation

@Observable
final class FirmwareStore {
    private(set) var state = FirmwareState()

    /// The power-on menu's choice: a mode, or "" for nothing. nil follows the board.
    var bootSelection: String?

    private(set) var consoleLines: [ConsoleLine] = []

    let calibration: CalibrationController?

    private let service: any FirmwareService
    private var tasks: [Task<Void, Never>] = []

    init(service: any FirmwareService, calibration: CalibrationController? = nil) {
        self.calibration = calibration
        self.service = service
        calibration?.log = { [weak self] text in
            self?.consoleLines.append(ConsoleLine(text: text, kind: .output))
        }
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

    var canUpload: Bool { hasDevice && calibration?.blocksUSB != true && !state.isBusy && !state.changedFiles.isEmpty }

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
        hasDevice && calibration?.blocksUSB != true && !state.isBusy && !state.modes.isEmpty && currentBoot != nil && selectedBoot != currentBoot
    }

    func refreshLocal() {
        Task { await service.refreshLocal() }
    }

    func chooseFolder(_ folder: URL) {
        Task { await service.setFolder(folder) }
    }

    func upload() {
        guard canUpload else { return }
        Task { await service.upload() }
    }

    func applyBoot() {
        guard canApplyBoot else { return }
        let mode = selectedBoot
        bootSelection = nil
        Task { await service.setBootMode(mode.isEmpty ? nil : mode) }
    }

    func clearConsole() {
        consoleLines.removeAll()
    }

    private func apply(_ new: FirmwareState) {
        state = new
    }
}
