//
//  Robot_RelayApp.swift
//  Robot Relay
//
//  Created by Vadym Sidorov on 10/4/26.
//

import SwiftUI
import AppKit

@main
struct Robot_RelayApp: App {
    @NSApplicationDelegateAdaptor(RelayAppDelegate.self) private var delegate
    @State private var app = AppModel(services: RobotServices.isTestHost ? .placeholder() : .live())

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .onAppear { delegate.cleanup = { await app.firmware.calibration?.shutdown() } }
                .preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1000, height: 620)
        .windowResizability(.contentMinSize)
    }
}

@MainActor
final class RelayAppDelegate: NSObject, NSApplicationDelegate {
    var cleanup: (() async -> Void)?
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard !RobotServices.isTestHost, let cleanup else { return .terminateNow }
        Task {
            await cleanup()
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}
