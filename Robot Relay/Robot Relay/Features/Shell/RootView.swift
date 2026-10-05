import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        HStack(spacing: 0) {
            SidebarView()
                .frame(width: 184)
            Rectangle()
                .fill(Nocturne.neutral900)
                .frame(width: 1)
            detail
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 1000, minHeight: 620)
        .foregroundStyle(Nocturne.text)
        .font(.nocturne(13))
        .tint(Nocturne.accent)
        .ignoresSafeArea(.container, edges: .top)
        .background(Nocturne.bg.ignoresSafeArea())
        .task { app.start() }
    }

    @ViewBuilder
    private var detail: some View {
        switch app.selection {
        case .liveRobot: LiveRobotView()
        case .telemetry: TelemetryView()
        case .firmware: FirmwareView()
        case .drive: DriveView()
        }
    }
}

#Preview {
    RootView()
        .environment(AppModel(services: .placeholder()))
        .preferredColorScheme(.dark)
}
