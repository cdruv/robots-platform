# Robot Relay

Custom robot control terminal for macOS. It is used to stream the phone's logs, live sensor data and robot state, update firmware and calibrate some of the robot's parts.

## Requirements

- The connection popover's activity footer shows the commands the app runs, and that text is selectable so a line can be copied into a terminal.
- Phone telemetry is NDJSON from onboard-android `TcpServerSink`, over USB (`adb forward`, plain) or Wi-Fi (TLS to the phone's Bonjour `_walky._tcp` endpoint; Relay accepts only the certificate fingerprint pinned from a USB hello). Secrets (Pico pairing, Relay's key) are sent over USB only. Wi-Fi discovery is event-driven: after failed attempts the link waits for the phone to reappear instead of polling. Client-only lines carry a `link` key and no `seq`; the `body` and `pair` lines are defined by onboard-android's `BodyRelay`. A new `session` means the phone app restarted and `seq` starts over; within a session, Relay skips events it has already seen when the phone replays recent events after a reconnect. Ping/pong is queued behind pending telemetry, so RTT includes any backlog.
- Relay learns the Pico 2 W was plugged in or unplugged from IOKit USB notifications (vendor 0x2E8A); never check the board on a timer. The board is read with one `mpremote exec` only on connect and after an upload or mode change, because every mpremote call interrupts the board, takes the serial port and stops the Pico's link to the phone until its next power-on. All mpremote calls share one queue. Relay never runs `mpremote reset`.
- The Pico's Wi-Fi credentials are read over USB, kept in the Keychain and sent to the phone on every USB hello. 

Install with `scripts/install_local.sh` (Release build into `/Applications`), or open `Robot Relay.xcodeproj`.

## Logging

Diagnostics use `os.Logger` through `Logger.<category>` from `Robot Relay/App/Logging.swift`.