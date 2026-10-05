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
                Spacer()
                Button("Add robot…") { connection.addRobot() }
                    .buttonStyle(.nocturneGhost)
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
                            .strokeBorder(Nocturne.divider, lineWidth: 1)
                    )
                Button("adb forward") { connection.adbForward() }
                    .buttonStyle(.nocturneSecondary)
            }
        }
        .padding(16)
        .frame(width: 470)
        .foregroundStyle(Nocturne.text)
        .presentationBackground(Nocturne.surface)
        .preferredColorScheme(.dark)
    }

    private func phoneRow(_ phone: PhoneLink) -> some View {
        LinkRow(
            name: "Phone app",
            tone: phone.isConnected ? .on : .off,
            meta: "\(phone.deviceName) · \(phone.transport)"
        ) {
            if phone.isConnected {
                Text("\(Text(phone.address).foregroundStyle(Nocturne.neutral200)) · RTT \(phone.rttMs) ms\n\(phone.eventsPerSecond) ev/s · \(phone.dropped) dropped")
            } else {
                Text("\(phone.address)\nNot connected")
            }
        } action: {
            Button(phone.isConnected ? "Disconnect" : "Connect") { app.connection.togglePhone() }
                .buttonStyle(phone.isConnected ? .nocturneGhost : .nocturneSecondary)
        }
    }

    private func picoRow(_ pico: PicoLink) -> some View {
        let rail = pico.railVolts.map { String(format: "servo rail %.1f V", $0) } ?? "servo rail off"
        return LinkRow(
            name: "Pico 2 W",
            tone: pico.route == .viaPhone ? .warning : pico.route == .usb ? .idle : .off,
            meta: pico.route == .viaPhone ? "via phone · Wi‑Fi" : pico.route == .usb ? "USB · mpremote" : "offline"
        ) {
            if pico.route == .offline {
                Text("Not connected")
            } else {
                let state = Text(pico.isArmed ? "armed" : "released").foregroundStyle(Nocturne.neutral200)
                if let reset = pico.lastWatchdogReset {
                    Text("\(state) · \(rail)\n\(Text("watchdog reset \(Fmt.ago(reset))").foregroundStyle(Nocturne.accent300))")
                } else {
                    Text("\(state) · \(rail)")
                }
            }
        } action: {
            Button("Release") { app.connection.releasePico() }
                .buttonStyle(.nocturneGhost)
                .disabled(!pico.isArmed)
        }
    }

    private func controllerRow(_ controller: ControllerLink) -> some View {
        LinkRow(
            name: "Controller",
            tone: controller.isPaired ? .on : .off,
            meta: "\(controller.name) · \(controller.transport)"
        ) {
            Text(controller.isPaired ? "Paired" : "Not paired")
        } action: {
            Button(controller.isPaired ? "Unpair" : "Pair…") { app.connection.pairController() }
                .buttonStyle(controller.isPaired ? .nocturneGhost : .nocturneSecondary)
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
