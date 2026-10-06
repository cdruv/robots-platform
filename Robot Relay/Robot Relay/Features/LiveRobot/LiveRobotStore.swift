import Foundation
import Observation

@Observable
final class LiveRobotStore {
    private(set) var snapshot = LiveSnapshot()
    private(set) var isRecording = false
    private(set) var isMuted = false
    var focusedStream: LiveStream?

    private let service: any LiveStreamService
    private var task: Task<Void, Never>?

    init(service: any LiveStreamService) {
        self.service = service
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self, service] in
            for await snapshot in service.snapshots() {
                self?.snapshot = snapshot
            }
        }
    }

    func toggleRecording() {
        isRecording.toggle()
        Task { await service.setRecording(isRecording) }
    }

    func toggleMuted() {
        isMuted.toggle()
        Task { await service.setMuted(isMuted) }
    }

    func takeSnapshot() {
        Task { await service.takeSnapshot() }
    }

    func focus(_ stream: LiveStream) {
        focusedStream = focusedStream == stream ? nil : stream
    }
}
