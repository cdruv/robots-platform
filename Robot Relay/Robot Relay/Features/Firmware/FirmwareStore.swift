import Foundation
import Observation

@Observable
final class FirmwareStore {
    private(set) var file = FirmwareFile(
        name: "servo_bringup / main.py",
        device: .init(sha: "0fe53", bytes: 3402, date: "2026‑09‑28"),
        local: .init(sha: "34cc7", bytes: 3614, date: "2026‑10‑04")
    )
    /// 0…1 while an upload is running.
    private(set) var uploadProgress: Double?

    var armMode: ArmMode = .center
    var repeatEveryBoot = false
    private(set) var isArmed = false

    var offsets = LegOffsets(left: -3.0, right: 1.5)
    private(set) var storedOffsets = LegOffsets(left: -2.0, right: 1.5)

    private(set) var consoleLines: [ConsoleLine] = []

    let deviceInfo: FirmwareDeviceInfo

    private let service: any FirmwareService
    private var task: Task<Void, Never>?

    init(service: any FirmwareService) {
        self.service = service
        deviceInfo = service.deviceInfo
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self, service] in
            for await line in service.console() {
                self?.append(line)
            }
        }
    }

    var isUploading: Bool { uploadProgress != nil }

    var unsavedOffsetCount: Int {
        LegSide.allCases.count { isUnsaved($0) }
    }

    func isUnsaved(_ side: LegSide) -> Bool {
        offsets[side] != storedOffsets[side]
    }

    func upload() {
        guard !isUploading else { return }
        uploadProgress = 0
        Task {
            for await progress in service.upload(file) {
                uploadProgress = progress
            }
            file.device = file.local
            uploadProgress = nil
        }
    }

    func backUpDeviceCopy() {
        Task { await service.backUpDeviceCopy() }
    }

    func arm() {
        Task {
            await service.arm(mode: armMode, repeatEveryBoot: repeatEveryBoot)
            isArmed = true
        }
    }

    func writeOffsets() {
        let offsets = offsets
        Task {
            await service.writeOffsets(offsets)
            storedOffsets = offsets
        }
    }

    func centerLegs() {
        Task { await service.centerLegs() }
    }

    func sweepLegs() {
        Task { await service.sweepLegs(degrees: 10) }
    }

    func openREPL() {
        Task { await service.openREPL() }
    }

    private func append(_ line: ConsoleLine) {
        if let index = consoleLines.lastIndex(where: { $0.id == line.id }) {
            consoleLines[index] = line
        } else {
            consoleLines.append(line)
        }
    }
}
