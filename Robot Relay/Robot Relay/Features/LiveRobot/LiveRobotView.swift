import SwiftUI

/// Camera leads; microphone, attitude, face and system as tiles. Below them the legs and the
/// body link that carries them, under the camera and the tiles' columns respectively.
struct LiveRobotView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let store = app.live
        let snapshot = store.snapshot
        VStack(spacing: 0) {
            ViewHeader(
                "Live Robot",
                subtitle: "camera \(Fmt.dash(snapshot.camera?.fps)) fps · mic \(Fmt.dash(snapshot.mic?.sampleRateKHz)) kHz · imu \(Fmt.dash(snapshot.imu?.rateHz)) Hz"
            )
            content(store: store, snapshot: snapshot, links: app.connection.links)
                .padding(EdgeInsets(top: 6, leading: 20, bottom: 0, trailing: 20))
                .dimmedBehindPopover()
            ViewFooter {
                Text(store.isRecording ? "rec on" : "rec off")
                    .foregroundStyle(store.isRecording ? Nocturne.accent300 : Nocturne.neutral600)
                if store.isMuted {
                    Text("muted")
                }
                Text(Self.legs(app.body.mode))
                    .foregroundStyle(app.body.mode == .drive ? Nocturne.accent300 : Nocturne.neutral600)
                Spacer()
                Text("R record · M mute · ⌘1–5 focus a stream · esc stops legs")
            }
        }
        .background { shortcuts(store: store) }
        .modifier(DriveKeys(store: app.body))
    }

    private func content(store: LiveRobotStore, snapshot: LiveSnapshot, links: RobotLinks) -> some View {
        let isOnline = links.phone.isConnected
        let gap: CGFloat = 12
        return GeometryReader { geometry in
            // Columns are 1.4fr 1fr 1fr.
            let cameraWidth = (geometry.size.width - gap * 2) * 1.4 / 3.4
            VStack(spacing: gap) {
                streams(store: store, snapshot: snapshot, links: links, cameraWidth: cameraWidth, gap: gap)
                    .opacity(isOnline ? 1 : 0.35)
                    .overlay {
                        if !isOnline {
                            Text("Phone offline · open the connection chip to reconnect")
                                .font(.nocturneMono(11))
                                .foregroundStyle(Nocturne.neutral400)
                        }
                    }
                HStack(alignment: .top, spacing: gap) {
                    LegsCard(store: app.body)
                        .frame(width: cameraWidth)
                    BodyLinkCard(link: app.body.link)
                        .frame(maxWidth: .infinity)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func streams(store: LiveRobotStore, snapshot: LiveSnapshot, links: RobotLinks, cameraWidth: CGFloat, gap: CGFloat) -> some View {
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

    private static func legs(_ mode: BodyStore.Mode) -> String {
        switch mode {
        case .off: "legs off"
        case .drive: "driving · targets 50 Hz"
        case .calibrate: "calibrating"
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

#Preview {
    let app = AppModel(services: .placeholder())
    let _ = app.start()
    LiveRobotView()
        .environment(app)
        .frame(width: 1100, height: 680)
        .foregroundStyle(Nocturne.text)
        .background(Nocturne.bg)
        .tint(Nocturne.accent)
        .preferredColorScheme(.dark)
}
