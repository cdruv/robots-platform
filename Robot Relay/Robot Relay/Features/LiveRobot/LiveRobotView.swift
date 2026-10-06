import SwiftUI

/// Camera leads; microphone, attitude, face and system as tiles.
struct LiveRobotView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let store = app.live
        let snapshot = store.snapshot
        let isOnline = app.connection.links.phone.isConnected
        VStack(spacing: 0) {
            ViewHeader(
                "Live Robot",
                subtitle: "camera \(Fmt.dash(snapshot.camera?.fps)) fps · mic \(Fmt.dash(snapshot.mic?.sampleRateKHz)) kHz · imu \(Fmt.dash(snapshot.imu?.rateHz)) Hz"
            )
            tiles(store: store, snapshot: snapshot, links: app.connection.links)
                .opacity(isOnline ? 1 : 0.35)
                .overlay {
                    if !isOnline {
                        Text("Phone offline · open the connection chip to reconnect")
                            .font(.nocturneMono(11))
                            .foregroundStyle(Nocturne.neutral400)
                    }
                }
                .padding(EdgeInsets(top: 6, leading: 20, bottom: 0, trailing: 20))
                .dimmedBehindPopover()
            ViewFooter {
                Text(store.isRecording ? "rec on" : "rec off")
                    .foregroundStyle(store.isRecording ? Nocturne.accent300 : Nocturne.neutral600)
                if store.isMuted {
                    Text("muted")
                }
                Text("session —")
                Spacer()
                Text("R record · M mute · ⌘1–5 focus a stream")
            }
        }
        .background { shortcuts(store: store) }
    }

    private func tiles(store: LiveRobotStore, snapshot: LiveSnapshot, links: RobotLinks) -> some View {
        GeometryReader { geometry in
            let gap: CGFloat = 12
            // Columns are 1.4fr 1fr 1fr.
            let cameraWidth = (geometry.size.width - gap * 2) * 1.4 / 3.4
            HStack(spacing: gap) {
                CameraTile(camera: snapshot.camera, isRecording: store.isRecording, isFocused: store.focusedStream == .camera) {
                    store.takeSnapshot()
                } onRecord: {
                    store.toggleRecording()
                }
                .frame(width: cameraWidth)
                VStack(spacing: gap) {
                    HStack(spacing: gap) {
                        MicrophoneTile(mic: snapshot.mic, isMuted: store.isMuted, isFocused: store.focusedStream == .microphone)
                        AttitudeTile(imu: snapshot.imu, isFocused: store.focusedStream == .attitude)
                    }
                    HStack(spacing: gap) {
                        FaceTile(face: snapshot.face, isFocused: store.focusedStream == .face)
                        SystemTile(system: snapshot.system, phone: links.phone, pico: links.pico, isFocused: store.focusedStream == .system)
                    }
                }
            }
        }
    }

    /// Invisible buttons that carry the view's keyboard shortcuts.
    private func shortcuts(store: LiveRobotStore) -> some View {
        Group {
            Button("Record") { store.toggleRecording() }
                .keyboardShortcut("r", modifiers: [])
            Button("Mute") { store.toggleMuted() }
                .keyboardShortcut("m", modifiers: [])
            ForEach(LiveStream.allCases, id: \.self) { stream in
                Button("Focus stream \(stream.rawValue)") { store.focus(stream) }
                    .keyboardShortcut(KeyEquivalent(Character("\(stream.rawValue)")), modifiers: .command)
            }
        }
        .opacity(0)
        .frame(width: 0, height: 0)
        .accessibilityHidden(true)
    }
}
