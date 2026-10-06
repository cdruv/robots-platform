import SwiftUI

/// Manual driving from an Xbox controller. Named in the design, not designed yet.
struct DriveView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let controller = app.connection.links.controller
        VStack(spacing: 0) {
            ViewHeader("Drive", subtitle: "\(controller.name ?? "—") controller · manual navigation")
            VStack(spacing: 12) {
                Kicker("Not designed yet")
                Text("Manual driving and movement visualization will live here.")
                    .font(.nocturne(13))
                    .foregroundStyle(Nocturne.neutral400)
                HStack(spacing: 8) {
                    StatusDot(tone: controller.isPaired == true ? .on : .off)
                    Text(controller.isPaired.map { $0 ? "controller paired" : "controller not paired" } ?? "controller —")
                        .font(.nocturneMono(11))
                        .foregroundStyle(Nocturne.neutral500)
                }
                if controller.isPaired == false {
                    Button("Pair…") { app.connection.pairController() }
                        .buttonStyle(.nocturneSecondary)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .dimmedBehindPopover()
            ViewFooter {
                Text("drive off")
                Spacer()
                Text("left stick move · right stick turn")
            }
        }
    }
}
