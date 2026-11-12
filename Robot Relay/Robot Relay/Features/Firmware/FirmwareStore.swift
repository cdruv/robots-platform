import Foundation
import Observation

@Observable
final class FirmwareStore {
    private(set) var state = FirmwareState()

    /// The power-on menu's choice: a mode, or "" for nothing. nil follows the board.
    private var bootSelection: String?

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

    var canUpload: Bool { hasDevice && calibration?.blocksUSB != true && !state.isBusy && !state.filesToUpload.isEmpty }

    /// What `mode.txt` holds now: a mode, or "" for none. nil until read.
    var currentBoot: String? {
        switch state.bootMode {
        case .armed(let mode): mode
        case .idle: ""
        case nil: nil
        }
    }

    private var selectedRequest: String {
        let request = bootSelection ?? currentBoot ?? ""
        return state.modes.contains(request) ? request : ""
    }

    private func actionName(_ request: String) -> String {
        request.hasPrefix("once:") ? String(request.dropFirst(5)) : request
    }

    var bootOptions: [(value: String, label: String)] {
        var seen: Set<String> = [""]
        var options = [("", "nothing")]
        for request in state.modes {
            let action = actionName(request)
            if seen.insert(action).inserted { options.append((action, action)) }
        }
        return options
    }

    var selectedBoot: String {
        get { actionName(selectedRequest) }
        set {
            let once = "once:" + newValue
            if state.modes.contains(once) && (runOnce || !state.modes.contains(newValue)) {
                bootSelection = once
            } else {
                bootSelection = newValue
            }
        }
    }

    var canChooseFrequency: Bool {
        !selectedBoot.isEmpty && state.modes.contains(selectedBoot) && state.modes.contains("once:" + selectedBoot)
    }

    var runOnce: Bool {
        get { selectedRequest.hasPrefix("once:") }
        set {
            guard canChooseFrequency else { return }
            bootSelection = newValue ? "once:" + selectedBoot : selectedBoot
        }
    }

    var canApplyBoot: Bool {
        hasDevice && calibration?.blocksUSB != true && !state.isBusy && !state.modes.isEmpty && currentBoot != nil && selectedRequest != currentBoot
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
        let mode = selectedRequest
        bootSelection = nil
        Task { await service.setBootMode(mode.isEmpty ? nil : mode) }
    }

    func clearConsole() {
        consoleLines.removeAll()
    }

    private func apply(_ new: FirmwareState) {
        if new.port != state.port { bootSelection = nil }
        state = new
    }
}
