import Foundation
import Observation

@Observable
final class FirmwareStore {
    /// nil until the service reports one.
    private(set) var file: FirmwareFile?
    /// 0…1 while an upload is running.
    private(set) var uploadProgress: Double?

    var armMode: ArmMode = .center
    var repeatEveryBoot = false
    /// nil: unknown. Set only after arming from here.
    private(set) var isArmed: Bool?

    /// The offsets being edited, starting from the stored ones (or neutral when unknown).
    var offsets: LegOffsets
    /// What the device holds; nil when unknown.
    private(set) var storedOffsets: LegOffsets?

    private(set) var consoleLines: [ConsoleLine] = []

    /// nil when no Pico is reachable; actions are disabled then.
    let deviceInfo: FirmwareDeviceInfo?

    private let service: any FirmwareService
    private var task: Task<Void, Never>?

    init(service: any FirmwareService) {
        self.service = service
        deviceInfo = service.deviceInfo
        file = service.file
        storedOffsets = service.storedOffsets
        offsets = service.storedOffsets ?? LegOffsets(left: 0, right: 0)
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self, service] in
            for await line in service.console() {
                self?.append(line)
            }
        }
    }

    var hasDevice: Bool { deviceInfo != nil }

    var isUploading: Bool { uploadProgress != nil }

    var unsavedOffsetCount: Int {
        LegSide.allCases.count { isUnsaved($0) }
    }

    func isUnsaved(_ side: LegSide) -> Bool {
        guard let storedOffsets else { return false }
        return offsets[side] != storedOffsets[side]
    }

    func upload() {
        guard !isUploading, let file else { return }
        uploadProgress = 0
        Task {
            for await progress in service.upload(file) {
                uploadProgress = progress
            }
            self.file?.device = file.local
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
