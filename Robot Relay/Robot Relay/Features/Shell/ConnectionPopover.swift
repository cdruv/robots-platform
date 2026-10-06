import SwiftUI

/// The robot's links: phone app, Pico, controller.
struct ConnectionPopover: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var connection = app.connection
        let links = connection.links
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(links.robotName)
                    .font(.nocturne(14, .medium))
                Text("\(links.linkCount) links")
                    .font(.nocturneMono(11))
                    .foregroundStyle(Nocturne.neutral500)
            }

            VStack(spacing: 0) {
                phoneRow(links.phone)
                FadedRule()
                picoRow(links.pico)
                FadedRule()
                controllerRow(links.controller)
            }

            HStack(spacing: 8) {
                TextField("host:port", text: $connection.addressDraft)
                    .textFieldStyle(.plain)
                    .font(.nocturneMono(12))
                    .padding(.horizontal, 10)
                    .frame(minHeight: 30)
                    .background(Nocturne.surface, in: RoundedRectangle(cornerRadius: Nocturne.Radius.md))
                    .overlay(
                        RoundedRectangle(cornerRadius: Nocturne.Radius.md)
                            .strokeBorder(connection.isAddressInvalid ? Nocturne.accent300 : Nocturne.divider, lineWidth: 1)
                    )
                    .onSubmit { connection.togglePhone() }
                Button("adb forward") { connection.adbForward() }
                    .buttonStyle(.nocturneSecondary)
            }

            if !links.activity.isEmpty {
                ActivityFooter(entries: links.activity) { connection.clearActivity() }
            }
        }
        .padding(16)
        .frame(width: 470)
        .foregroundStyle(Nocturne.text)
        .presentationBackground(Nocturne.surface)
        .preferredColorScheme(.dark)
    }

    private func phoneRow(_ phone: PhoneLink) -> some View {
        let isIdle = phone.state == .disconnected
        return LinkRow(
            name: "Phone app",
            tone: phone.isConnected ? .on : isIdle ? .off : .idle,
            meta: "\(phone.deviceName) · \(phone.transport)"
        ) {
            switch phone.state {
            case .connected:
                let rtt = phone.rttMs.map { "\($0) ms" } ?? "—"
                Text("\(Text(phone.address).foregroundStyle(Nocturne.neutral200)) · RTT \(rtt)\n\(phone.eventsPerSecond) ev/s · \(phone.dropped) dropped")
            case .connecting:
                Text("\(Self.dash(phone.address))\nConnecting…")
            case .retrying(let date):
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    Text("\(Self.dash(phone.address))\nRetrying in \(ConnectionStore.secondsUntil(date)) s")
                }
            case .disconnected:
                Text("\(Self.dash(phone.address))\nNot connected")
            }
        } action: {
            Button(isIdle ? "Connect" : "Disconnect") { app.connection.togglePhone() }
                .buttonStyle(isIdle ? .nocturneSecondary : .nocturneGhost)
        }
    }

    private static func dash(_ text: String) -> String {
        text.isEmpty ? "—" : text
    }

    /// Status only: the USB port, MicroPython release and power-on mode, as read on connect.
    /// Uploads and the power-on mode are changed in the Firmware tab.
    private func picoRow(_ pico: PicoLink) -> some View {
        let rail = pico.railVolts.map { String(format: "servo rail %.1f V", $0) } ?? "rail not measured"
        let meta = switch pico.route {
        case .viaPhone: "via phone · Wi‑Fi"
        case .usb: "USB · mpremote"
        case .offline: "offline"
        case nil: "—"
        }
        let isUSB = pico.route == .usb
        return LinkRow(
            name: "Pico 2 W",
            tone: pico.route == .viaPhone ? .warning : isUSB ? .idle : .off,
            meta: meta
        ) {
            switch pico.route {
            case nil:
                Text("No data")
            case .offline:
                Text("Not connected")
            case .usb:
                let port = (pico.port ?? "—").replacingOccurrences(of: "/dev/", with: "")
                let runtime = pico.runtime.map { "MicroPython \($0)" } ?? "MicroPython —"
                let mode = Text("power-on \(pico.arm?.label ?? "—")").foregroundStyle(Nocturne.neutral200)
                Text("\(Text(port).foregroundStyle(Nocturne.neutral200)) · \(runtime)\n\(mode) · \(rail)")
            case .viaPhone:
                let state = Text(pico.arm?.label ?? "mode —").foregroundStyle(Nocturne.neutral200)
                if let reset = pico.lastWatchdogReset {
                    Text("\(state) · \(rail)\n\(Text("watchdog reset \(Fmt.ago(reset))").foregroundStyle(Nocturne.accent300))")
                } else {
                    Text("\(state) · \(rail)\nreset cause unknown")
                }
            }
        } action: {
            Button("Firmware") {
                app.selection = .firmware
                app.isConnectionPopoverPresented = false
            }
            .buttonStyle(.nocturneGhost)
            .help("Upload firmware and choose the power-on mode")
        }
    }

    private func controllerRow(_ controller: ControllerLink) -> some View {
        let isPaired = controller.isPaired == true
        return LinkRow(
            name: "Controller",
            tone: isPaired ? .on : .off,
            meta: "\(controller.name ?? "—") · \(controller.transport ?? "—")"
        ) {
            Text(controller.isPaired.map { $0 ? "Paired" : "Not paired" } ?? "No data")
        } action: {
            Button(isPaired ? "Unpair" : "Pair…") { app.connection.pairController() }
                .buttonStyle(isPaired ? .nocturneGhost : .nocturneSecondary)
                .disabled(controller.isPaired == nil)
        }
    }
}

/// Everything the app did this session (newest 500, oldest first), as the commands you would
/// type to do it yourself. Scrolls, stays pinned to the newest line, selectable for copying.
private struct ActivityFooter: View {
    let entries: [LinkActivity]
    let clear: () -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 3) {
                    ForEach(entries) { entry in
                        line(entry)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .textSelection(.enabled)
            }
            .defaultScrollAnchor(.bottom)
            .defaultScrollAnchor(.bottom, for: .sizeChanges)
            .scrollBounceBehavior(.basedOnSize)
            .frame(height: 104)

            Button(action: clear) {
                Image(systemName: "trash")
            }
            .buttonStyle(NocturneButtonStyle(kind: .secondary, fontSize: 10, foreground: Nocturne.neutral400))
            .help("Clear activity")
        }
        .font(.nocturneMono(10.5))
        .foregroundStyle(Nocturne.neutral600)
    }

    private func line(_ entry: LinkActivity) -> Text {
        Text("\(Text(entry.date, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute().second()).foregroundStyle(Nocturne.neutral700))  \(command(entry))")
    }

    private func command(_ entry: LinkActivity) -> Text {
        switch entry.state {
        case .running:
            Text("\(entry.text)…").foregroundStyle(Nocturne.neutral500)
        case .ok(nil):
            Text(entry.text)
        case .ok(let detail?):
            Text("\(entry.text)  · \(detail)")
        case .failed(let detail):
            Text("\(entry.text)  · \(Text(detail).foregroundStyle(Nocturne.accent300))")
        }
    }
}

private struct LinkRow<Detail: View, Action: View>: View {
    let name: String
    let tone: StatusDot.Tone
    let meta: String
    @ViewBuilder var detail: Detail
    @ViewBuilder var action: Action

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    StatusDot(tone: tone)
                    Text(name)
                        .font(.nocturne(13, .medium))
                        .foregroundStyle(tone == .off ? Nocturne.neutral500 : Nocturne.text)
                }
                Text(meta)
            }
            .frame(width: 104, alignment: .leading)
            detail
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
            action
        }
        .font(.nocturneMono(11.5))
        .foregroundStyle(Nocturne.neutral500)
        .lineLimit(2)
        .padding(.vertical, 10)
    }
}
