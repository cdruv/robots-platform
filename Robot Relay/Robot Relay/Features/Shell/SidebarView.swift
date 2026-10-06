import SwiftUI

struct SidebarView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            brand
            VStack(spacing: 2) {
                ForEach(AppSection.allCases) { section in
                    NavRow(title: section.title, isActive: app.selection == section) {
                        app.selection = section
                    }
                }
            }
            Spacer(minLength: 0)
            stats
            version
        }
        .padding(.horizontal, 14)
        // Extra top inset keeps the brand row clear of the window's traffic lights.
        .padding(.top, 44)
        .padding(.bottom, 18)
    }

    private var brand: some View {
        HStack(spacing: 10) {
            RobotFace(size: .small)
            VStack(alignment: .leading, spacing: 1) {
                Text(app.connection.links.robotName)
                    .font(.nocturne(14, .medium))
                Text(app.connection.links.phone.isConnected ? app.connection.links.phone.deviceName : "phone —")
                    .font(.nocturneMono(10.5))
                    .foregroundStyle(Nocturne.neutral600)
            }
        }
    }

    private var version: some View {
        Text(AppVersion.display)
            .font(.nocturneMono(10))
            .foregroundStyle(Nocturne.neutral600)
            .textSelection(.enabled)
    }

    private var stats: some View {
        let links = app.connection.links
        let system = app.live.snapshot.system
        let rail = links.pico.railVolts.map { String(format: "%.1f V", $0) }
        return VStack(spacing: 6) {
            if links.phone.isConnected {
                StatRow(label: "Battery", value: system.map { "\($0.batteryPercent)%" })
                StatRow(label: "Thermal", value: system.map { "\($0.thermal)" })
                StatRow(label: "Servo rail", value: rail)
            } else {
                StatRow(label: "Battery", value: nil)
                StatRow(label: "Pico", value: links.pico.route == .usb ? "usb" : nil)
                StatRow(label: "Servo rail", value: rail)
            }
        }
    }
}

private struct NavRow: View {
    let title: String
    let isActive: Bool
    let action: () -> Void

    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.nocturne(13))
                .foregroundStyle(isActive ? Nocturne.accent : Nocturne.neutral400)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(background, in: RoundedRectangle(cornerRadius: Nocturne.Radius.md))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
    }

    private var background: Color {
        if isActive { return Nocturne.accent.opacity(0.10) }
        return isHovering ? Nocturne.text.opacity(0.05) : .clear
    }
}

private struct StatRow: View {
    let label: String
    /// `nil` renders as an em dash.
    let value: String?
    var isMuted = false

    var body: some View {
        HStack {
            Text(label)
                .font(.nocturne(11))
                .foregroundStyle(Nocturne.neutral600)
            Spacer()
            Text(value ?? "—")
                .font(.nocturneMono(11))
                .foregroundStyle(value == nil || isMuted ? Nocturne.neutral600 : Nocturne.neutral400)
        }
    }
}
