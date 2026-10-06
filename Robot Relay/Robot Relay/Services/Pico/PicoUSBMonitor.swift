import Foundation
import IOKit
import IOKit.serial

/// Watches IOKit for serial ports on Raspberry Pi USB devices and reports the one to use.
/// A Pico in BOOTSEL mode has no serial port, so it counts as not connected.
final class PicoUSBMonitor {
    /// The chosen port, or nil when no Pico is attached. Called only when it changes.
    var onChange: ((String?) -> Void)?
    private(set) var port: String?

    private var notificationPort: IONotificationPortRef?
    private var iterators: [io_iterator_t] = []

    /// Starts watching and reports the current port if there is one. Callbacks run on the main queue.
    func start() {
        guard notificationPort == nil, let notificationPort = IONotificationPortCreate(kIOMainPortDefault) else { return }
        IONotificationPortSetDispatchQueue(notificationPort, .main)
        self.notificationPort = notificationPort
        let context = Unmanaged.passUnretained(self).toOpaque()
        for type in [kIOFirstMatchNotification, kIOTerminatedNotification] {
            var iterator: io_iterator_t = 0
            // Each call consumes its matching dictionary.
            let result = IOServiceAddMatchingNotification(
                notificationPort, type, IOServiceMatching(kIOSerialBSDServiceValue), Self.callback, context, &iterator
            )
            guard result == KERN_SUCCESS else { continue }
            Self.drain(iterator)  // Arms the notification.
            iterators.append(iterator)
        }
        rescan()
    }

    func stop() {
        iterators.forEach { IOObjectRelease($0) }
        iterators = []
        if let notificationPort { IONotificationPortDestroy(notificationPort) }
        notificationPort = nil
    }

    private func rescan() {
        let chosen = PicoUSB.choosePort(Self.serialPorts(vendorID: PicoUSB.vendorID))
        guard chosen != port else { return }
        port = chosen
        onChange?(chosen)
    }

    /// Every arrival or removal of any serial port triggers a full rescan.
    private nonisolated static let callback: IOServiceMatchingCallback = { context, iterator in
        drain(iterator)
        guard let context else { return }
        let monitor = Unmanaged<PicoUSBMonitor>.fromOpaque(context).takeUnretainedValue()
        MainActor.assumeIsolated { monitor.rescan() }
    }

    private nonisolated static func drain(_ iterator: io_iterator_t) {
        while case let service = IOIteratorNext(iterator), service != 0 {
            IOObjectRelease(service)
        }
    }

    /// `/dev/cu.*` callout paths of serial services whose USB device has `vendorID`.
    nonisolated static func serialPorts(vendorID: Int) -> [String] {
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching(kIOSerialBSDServiceValue), &iterator) == KERN_SUCCESS else {
            return []
        }
        defer { IOObjectRelease(iterator) }
        var ports: [String] = []
        while case let service = IOIteratorNext(iterator), service != 0 {
            defer { IOObjectRelease(service) }
            let vendor = IORegistryEntrySearchCFProperty(
                service, kIOServicePlane, "idVendor" as CFString, kCFAllocatorDefault,
                IOOptionBits(kIORegistryIterateRecursively | kIORegistryIterateParents)
            ) as? NSNumber
            guard vendor?.intValue == vendorID,
                  let path = IORegistryEntryCreateCFProperty(service, kIOCalloutDeviceKey as CFString, kCFAllocatorDefault, 0)?
                      .takeRetainedValue() as? String
            else { continue }
            ports.append(path)
        }
        return ports
    }
}

/// The pure parts of Pico USB detection, shared with tests.
nonisolated enum PicoUSB {
    /// Raspberry Pi's USB vendor ID, used by the MicroPython firmware on the Pico 2 W.
    static let vendorID = 0x2E8A

    /// The first port in sorted order, preferring `cu.usbmodem*` (USB CDC) over anything else.
    static func choosePort(_ candidates: [String]) -> String? {
        let sorted = candidates.sorted()
        return sorted.first { ($0 as NSString).lastPathComponent.hasPrefix("cu.usbmodem") } ?? sorted.first
    }
}
