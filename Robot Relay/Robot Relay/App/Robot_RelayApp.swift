//
//  Robot_RelayApp.swift
//  Robot Relay
//
//  Created by Vadym Sidorov on 10/4/26.
//

import SwiftUI
import AppKit
import os

@main
struct Robot_RelayApp: App {
    @NSApplicationDelegateAdaptor(RelayAppDelegate.self) private var delegate
    @State private var app = Robot_RelayApp.makeModel()

    private static func makeModel() -> AppModel {
        let placeholder = RobotServices.isTestHost
        Logger.app.notice("launch \(AppVersion.display, privacy: .public), \(placeholder ? "placeholder" : "live", privacy: .public) services")
        return AppModel(services: placeholder ? .placeholder() : .live())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .onAppear { delegate.cleanup = { await app.firmware.calibration?.shutdown() } }
                .preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1500, height: 930)
        .windowResizability(.contentMinSize)
    }
}

@MainActor
final class RelayAppDelegate: NSObject, NSApplicationDelegate {
    var cleanup: (() async -> Void)?
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard !RobotServices.isTestHost, let cleanup else { return .terminateNow }
        Logger.app.info("quit requested, running calibration shutdown first")
        Task {
            await cleanup()
            Logger.app.info("shutdown complete, quitting")
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}
