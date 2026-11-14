//
//  Robot_RelayApp.swift
//  Robot Relay
//
//  Created by Vadym Sidorov on 10/4/26.
//

import SwiftUI
import os

@main
struct Robot_RelayApp: App {
    @State private var app = Robot_RelayApp.makeModel()

    private static func makeModel() -> AppModel {
        let placeholder = RobotServices.isTestHost
        Logger.app.notice("launch \(AppVersion.display, privacy: .public), \(placeholder ? "placeholder" : "live", privacy: .public) services")
        return AppModel(services: placeholder ? .placeholder() : .live())
    }

    var body: some Scene {
        WindowGroup {//
            RootView()
                .environment(app)
                .preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1500, height: 930)
        .windowResizability(.contentMinSize)
    }
}
