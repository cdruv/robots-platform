//
//  Robot_RelayApp.swift
//  Robot Relay
//
//  Created by Vadym Sidorov on 10/4/26.
//

import SwiftUI

@main
struct Robot_RelayApp: App {
    @State private var app = AppModel(services: RobotServices.isTestHost ? .placeholder() : .live())

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1000, height: 620)
        .windowResizability(.contentMinSize)
    }
}
