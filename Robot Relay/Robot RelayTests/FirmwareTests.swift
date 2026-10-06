import Foundation
import Testing
@testable import Robot_Relay

struct PicoBoardReadTests {
    let output = """
        board=Raspberry Pi Pico 2 W with RP2350|1.28.0
        file=main.py,141,dcff
        file=body.py,240,9955
        mode=body
        modes=body,center,sweep,once:center,once:sweep
        """

    @Test func readsBoardAndFiles() {
        #expect(PicoMode.parseBoard(output)?.board == "Raspberry Pi Pico 2 W with RP2350")
        #expect(PicoMode.parseBoard(output)?.runtime == "1.28.0")
        #expect(PicoMode.parseFiles(output) == [
            PicoFile(name: "body.py", bytes: 240, sha: "9955"),
            PicoFile(name: "main.py", bytes: 141, sha: "dcff"),
        ])
        #expect(PicoMode.parse(output) == .armed("body"))
    }

    @Test func aBrokenModesModuleStillShowsTheFiles() {
        let partial = "board=Pico|1.28.0\nfile=modes.py,10,abcd\nmode=\n"
        #expect(PicoMode.parseFiles(partial) == [PicoFile(name: "modes.py", bytes: 10, sha: "abcd")])
        #expect(PicoMode.parse(partial) == .idle)
        #expect(PicoMode.parseModes(partial).isEmpty)
    }

    @Test func noBoardLineMeansFilesUnknown() {
        #expect(PicoMode.parseFiles("") == nil)
        #expect(PicoMode.parseFiles("board=Pico|1.28.0\n") == [])
    }
}

struct FirmwareFileTests {
    @Test func mergesLocalAndBoardByName() {
        let local = [PicoFile(name: "main.py", bytes: 1, sha: "a"), PicoFile(name: "modes.py", bytes: 2, sha: "b")]
        let device = [PicoFile(name: "main.py", bytes: 1, sha: "a"), PicoFile(name: "modes.py", bytes: 3, sha: "c"),
                      PicoFile(name: "bringup.py", bytes: 4, sha: "d")]
        let files = FirmwareFile.merge(local: local, device: device)
        #expect(files.map(\.name) == ["bringup.py", "main.py", "modes.py"])
        #expect(files.map(\.differs) == [true, false, true])

        var state = FirmwareState()
        state.files = files
        #expect(state.changedFiles.isEmpty, "board files not read yet")
        state.hasDeviceFiles = true
        #expect(state.changedFiles.map(\.name) == ["modes.py"], "board-only files are never uploaded")
    }

    @Test func unreadBoardLeavesBoardCopiesUnknown() {
        let files = FirmwareFile.merge(local: [PicoFile(name: "main.py", bytes: 1, sha: "a")], device: nil)
        #expect(files.count == 1)
        #expect(files[0].device == nil)
    }

    @Test func hashesLocalPythonFiles() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data("abc".utf8).write(to: folder.appendingPathComponent("main.py"))
        try Data("x".utf8).write(to: folder.appendingPathComponent("README.md"))
        #expect(PicoFirmwareService.localFiles(in: folder) == [PicoFile(
            name: "main.py", bytes: 3, sha: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )])
        #expect(PicoFirmwareService.localFiles(in: folder.appendingPathComponent("missing")) == nil)
    }

    @Test func defaultFolderIsTheRepositoryFirmware() {
        let names = PicoFirmwareService.localFiles(in: PicoFirmwareService.defaultFolder)?.map(\.name) ?? []
        #expect(names.contains("main.py"))
        #expect(names.contains("modes.py"))
    }

    @MainActor
    @Test func chosenFolderIsRememberedUntilReset() async throws {
        let defaults = try #require(UserDefaults(suiteName: UUID().uuidString))
        let chosen = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let service = PicoFirmwareService(pico: PicoUSBLink(runner: CommandRunner()), defaults: defaults)
        await service.setFolder(chosen)
        #expect(defaults.string(forKey: PicoFirmwareService.folderKey) == chosen.standardizedFileURL.path)
        let relaunched = PicoFirmwareService(pico: PicoUSBLink(runner: CommandRunner()), defaults: defaults)
        var states = relaunched.state().makeAsyncIterator()
        let state = await states.next()
        #expect(state?.folder == chosen.standardizedFileURL.path)
        #expect(state?.isDefaultFolder == false)
        await relaunched.setFolder(nil)
        #expect(defaults.string(forKey: PicoFirmwareService.folderKey) == nil)
    }
}

@MainActor
struct PowerOnMenuTests {
    private final class Stub: FirmwareService {
        let initial: FirmwareState
        var applied: [String?] = []
        init(_ initial: FirmwareState) { self.initial = initial }
        func state() -> AsyncStream<FirmwareState> {
            let (stream, continuation) = AsyncStream.makeStream(of: FirmwareState.self)
            continuation.yield(initial)
            return stream
        }
        func console() -> AsyncStream<ConsoleLine> { AsyncStream { _ in } }
        func refreshLocal() async {}
        func setFolder(_ folder: URL?) async {}
        func upload() async {}
        func setBootMode(_ mode: String?) async { applied.append(mode) }
        func writeOffsets(_ offsets: LegOffsets) async {}
        func centerLegs() async {}
        func sweepLegs(degrees: Double) async {}
    }

    private func store(boot: PicoArm?, modes: [String] = ["body", "once:sweep"]) async -> (FirmwareStore, Stub) {
        var state = FirmwareState()
        state.port = "/dev/cu.usbmodem1"
        state.bootMode = boot
        state.modes = modes
        let stub = Stub(state)
        let store = FirmwareStore(service: stub)
        store.start()
        while store.state != state { await Task.yield() }
        return (store, stub)
    }

    @Test func menuStartsAtTheBoardsModeAndAppliesOnlyAChange() async {
        let (store, stub) = await store(boot: .armed("body"))
        #expect(store.bootOptions.map(\.label) == ["nothing", "body", "once: sweep"])
        #expect(store.selectedBoot == "body")
        #expect(!store.canApplyBoot)
        store.bootSelection = ""
        #expect(store.canApplyBoot)
        store.applyBoot()
        while stub.applied.isEmpty { await Task.yield() }
        #expect(stub.applied == [nil], "nothing deletes mode.txt")
    }

    @Test func cantApplyBeforeTheModeIsReadOrWithoutModes() async {
        let (unread, _) = await store(boot: nil)
        unread.bootSelection = "body"
        #expect(!unread.canApplyBoot)
        let (old, _) = await store(boot: .idle, modes: [])
        #expect(old.bootOptions.map(\.value) == [""])
        #expect(!old.canApplyBoot)
    }
}
