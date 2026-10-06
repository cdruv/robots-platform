import SwiftUI

struct CameraTile: View {
    let camera: LiveSnapshot.Camera?
    let isRecording: Bool
    let isFocused: Bool
    let onSnapshot: () -> Void
    let onRecord: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Nocturne.Radius.md)
        VStack(spacing: 0) {
            GeometryReader { geometry in
                // Video is not streamed yet; this frame marks where it will render.
                Text(camera == nil ? "NO VIDEO" : "LIVE FRAME · PLACEHOLDER")
                    .font(.nocturne(11))
                    .tracking(0.9)
                    .foregroundStyle(Nocturne.neutral600)
                    .frame(width: geometry.size.width * 0.72, height: geometry.size.width * 0.72 * 9 / 16)
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Nocturne.neutral800, lineWidth: 1))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            HStack(spacing: 8) {
                Text(Self.stats(camera))
                    .font(.nocturneMono(11))
                    .foregroundStyle(Nocturne.neutral500)
                    .lineLimit(1)
                Spacer(minLength: 0)
                HStack(spacing: 6) {
                    Button("Snapshot", action: onSnapshot)
                        .buttonStyle(.nocturneSecondary)
                    Button(action: onRecord) {
                        Circle().frame(width: 6, height: 6)
                        Text(isRecording ? "Stop" : "Record")
                    }
                    .buttonStyle(.nocturnePrimary)
                }
                .fixedSize()
            }
            .padding(EdgeInsets(top: 0, leading: 12, bottom: 10, trailing: 12))
        }
        .overlay(alignment: .top) {
            TileCaption(caption: "Camera", value: camera.map { "\($0.fps) fps · \($0.width)×\($0.height)" } ?? "—")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            EllipticalGradient(
                colors: [Color(hex: 0x1e2030), Color(hex: 0x13141f)],
                center: UnitPoint(x: 0.5, y: 0.45),
                startRadiusFraction: 0,
                endRadiusFraction: 0.5
            )
        )
        .clipShape(shape)
        .overlay(shape.strokeBorder(isFocused ? Nocturne.accent600 : Nocturne.neutral900, lineWidth: 1))
    }

    private static func stats(_ camera: LiveSnapshot.Camera?) -> String {
        guard let camera else { return "motion — · brightness — · frame —" }
        return "motion \(String(format: "%.2f", camera.motion)) · brightness \(String(format: "%.2f", camera.brightness)) · frame \(Fmt.grouped(camera.frame))"
    }
}

struct MicrophoneTile: View {
    let mic: LiveSnapshot.Microphone?
    let isMuted: Bool
    let isFocused: Bool

    /// Bars shown flat when there is no data.
    private static let barCount = 16

    var body: some View {
        Tile(
            caption: "Microphone",
            value: isMuted ? "muted" : mic.map { "\(Fmt.signed($0.levelDb, decimals: 0).replacingOccurrences(of: "+", with: "")) dB" } ?? "—",
            isFocused: isFocused
        ) {
            let bars = mic?.bars ?? Array(repeating: 0, count: Self.barCount)
            GeometryReader { geometry in
                HStack(alignment: .center, spacing: 2) {
                    ForEach(bars.indices, id: \.self) { index in
                        let level = isMuted ? 0.04 : bars[index]
                        Rectangle()
                            .fill(Self.color(for: level))
                            .frame(height: max(1, geometry.size.height * level))
                    }
                }
                .frame(maxHeight: .infinity)
            }
            .padding(EdgeInsets(top: 8, leading: 12, bottom: 0, trailing: 12))

            Group {
                if mic == nil {
                    Text("—").foregroundStyle(Nocturne.neutral600)
                } else if let transcript = mic?.transcript {
                    Text("“\(transcript.text)” \(Text("\(transcript.isFinal ? "final" : "partial") · \(String(format: "%.2f", transcript.confidence))").font(.nocturneMono(10)).foregroundStyle(Nocturne.neutral600))")
                } else {
                    Text(" ")
                }
            }
            .font(.nocturne(12))
            .foregroundStyle(Nocturne.accent200)
            .lineLimit(1)
            .padding(EdgeInsets(top: 6, leading: 12, bottom: 10, trailing: 12))
        }
    }

    private static func color(for level: Double) -> Color {
        switch level {
        case ..<0.13: Nocturne.neutral800
        case ..<0.40: Nocturne.neutral700
        case ..<0.56: Nocturne.neutral600
        case ..<0.80: Nocturne.accent700
        default: Nocturne.accent500
        }
    }
}

struct AttitudeTile: View {
    let imu: LiveSnapshot.IMU?
    let isFocused: Bool

    var body: some View {
        Tile(caption: "Attitude", value: "\(Fmt.dash(imu?.rateHz)) Hz", isFocused: isFocused) {
            HStack(spacing: 14) {
                AttitudeDial(attitude: imu.map { ($0.pitch, $0.roll) })
                VStack(alignment: .leading, spacing: 6) {
                    LabeledValue(label: "pitch", value: imu.map { Fmt.signed($0.pitch) + "°" } ?? "—")
                    LabeledValue(label: "roll", value: imu.map { Fmt.signed($0.roll) + "°" } ?? "—")
                    LabeledValue(label: "|a|", value: imu.map { String(format: "%.2f", $0.accel) } ?? "—", unit: "m/s²")
                    Text(imu?.motionState ?? "—")
                        .foregroundStyle(imu == nil ? Nocturne.neutral600 : Nocturne.accent300)
                }
                .font(.nocturneMono(12))
                .foregroundStyle(Nocturne.neutral400)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 4, leading: 12, bottom: 8, trailing: 12))
        }
    }
}

/// Artificial horizon: the line rotates with roll and shifts with pitch. Without data
/// only the dial is drawn.
struct AttitudeDial: View {
    let attitude: (pitch: Double, roll: Double)?

    var body: some View {
        Canvas { context, _ in
            context.stroke(
                Path(ellipseIn: CGRect(x: 4, y: 4, width: 68, height: 68)),
                with: .color(Nocturne.neutral800),
                lineWidth: 1
            )
            var ticks = Path()
            ticks.move(to: CGPoint(x: 38, y: 4))
            ticks.addLine(to: CGPoint(x: 38, y: 10))
            ticks.move(to: CGPoint(x: 4, y: 38))
            ticks.addLine(to: CGPoint(x: 10, y: 38))
            ticks.move(to: CGPoint(x: 66, y: 38))
            ticks.addLine(to: CGPoint(x: 72, y: 38))
            context.stroke(ticks, with: .color(Nocturne.neutral700), lineWidth: 1)

            guard let attitude else { return }
            var horizon = context
            horizon.translateBy(x: 38, y: 38)
            horizon.rotate(by: .degrees(attitude.roll))
            horizon.translateBy(x: 0, y: -attitude.pitch * 1.4)
            var line = Path()
            line.move(to: CGPoint(x: -26, y: 0))
            line.addLine(to: CGPoint(x: 26, y: 0))
            horizon.stroke(line, with: .color(Nocturne.accent400), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            horizon.fill(Path(ellipseIn: CGRect(x: -3, y: -3, width: 6, height: 6)), with: .color(Nocturne.accent))
        }
        .frame(width: 76, height: 76)
    }
}

struct FaceTile: View {
    let face: LiveSnapshot.Face?
    let isFocused: Bool

    var body: some View {
        Tile(caption: "Face", value: face?.expression ?? "—", isFocused: isFocused) {
            HStack(spacing: 14) {
                RobotFace(size: .large)
                    .opacity(face == nil ? 0.35 : 1)
                VStack(alignment: .leading, spacing: 4) {
                    LabeledValue(label: "Expression", value: face?.expression ?? "—")
                    LabeledValue(label: "Speech", value: face?.speech ?? "—")
                    Text("last: \(face?.lastEvent ?? "—")")
                        .font(.nocturneMono(11))
                        .foregroundStyle(Nocturne.neutral600)
                        .lineLimit(1)
                }
                .font(.nocturne(12))
                .foregroundStyle(Nocturne.neutral400)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 4, leading: 12, bottom: 10, trailing: 12))
        }
    }
}

/// Phone battery and thermal from the live stream; device, link and Pico from the links.
struct SystemTile: View {
    let system: LiveSnapshot.System?
    let phone: PhoneLink
    let pico: PicoLink
    let isFocused: Bool

    var body: some View {
        let isConnected = phone.isConnected
        Tile(caption: "System", value: phone.deviceName, isFocused: isFocused) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 8) {
                GridRow {
                    LabeledValue(label: "battery", value: system.map { "\($0.batteryPercent)%" } ?? "—")
                    LabeledValue(label: "thermal", value: Fmt.dash(system?.thermal))
                }
                GridRow {
                    LabeledValue(label: "link", value: isConnected ? phone.rttMs.map { "\($0) ms" } ?? "—" : "—")
                    LabeledValue(label: "dropped", value: isConnected ? "\(phone.dropped)" : "—")
                }
                GridRow {
                    LabeledValue(label: "servo rail", value: pico.railVolts.map { String(format: "%.1f V", $0) } ?? "—")
                    LabeledValue(label: "pico", value: pico.route == nil || pico.route == .offline ? "—" : pico.arm?.label ?? "—")
                }
            }
            .font(.nocturneMono(12))
            .foregroundStyle(Nocturne.neutral400)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 4, leading: 12, bottom: 10, trailing: 12))
        }
    }
}
