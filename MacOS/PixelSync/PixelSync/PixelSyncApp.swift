//
//  PixelSyncApp.swift
//  PixelSync
//
//  Created by Luigi Quitadamo on 14/05/2026.
//
import SwiftUI
import AppKit

// Il motore Bluetooth vive qui e parte al lancio dell'app, anche se il menu non viene mai aperto.
// L'icona nel Dock resta nascosta grazie a LSUIElement.
final class AppDelegate: NSObject, NSApplicationDelegate {
    let bleManager = BLEManager()
}

@main
struct PixelSyncApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        NSApplication.shared.applicationIconImage = NSImage(named: "AppIcon")
    }

    var body: some Scene {
        MenuBarExtra("PixelSync", systemImage: "iphone") {
            ContentView(bleManager: appDelegate.bleManager)
        }
        .menuBarExtraStyle(.window)
    }
}
