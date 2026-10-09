# Robot Relay

Custom robot control terminal for macOS. It is used to stream telemetry (logs), live sensor data and robot state, update firmware and calibrate some of the robot's parts.

## Requirements

- The connection popover's activity footer shows the commands the app runs, and that text is selectable so a line can be copied into a terminal.
- Phone telemetry is NDJSON from onboard-android `TcpServerSink`. Client-only lines carry a `link` key and no `seq`. A new `session` means the phone app restarted and `seq` starts over; within a session, Relay skips events it has already seen when the phone replays recent events after a reconnect. Ping/pong is queued behind pending telemetry, so RTT includes any backlog.
- The Pico 2 W is found over USB by IOKit (vendor 0x2E8A) as it is plugged and unplugged; nothing polls. The board is read with one `mpremote exec` (files and hashes, `mode.txt`, `modes.MODES`) only on connect and after an upload or mode change, because every mpremote call interrupts the board and takes the serial port. All mpremote calls share one queue. Relay never runs `mpremote reset`.
- The connection popover only shows link status, and its log only the connection checks. Uploading firmware, the power-on mode and leg calibration live in the Firmware tab, whose console shows its own commands. The firmware's own `MODES` fills the mode menu; Relay keeps no list of its own and only writes a mode the board listed.

Install with `scripts/install_local.sh` (Release build into `/Applications`), or open `Robot Relay.xcodeproj`.

Versioning: bump major.minor by hand in `MARKETING_VERSION` (target › General › Version). The build number (`CFBundleVersion`) is the build time, YYYYMMDD.HHMM, stamped by the target's "Stamp Build Number" phase on every build, from Xcode and `install_local.sh` alike. Read both through `AppVersion`.

- Live leg calibration is an isolated, one-use Pico access-point session prepared over USB. Reserve the USB queue throughout preparation and calibration; no mpremote reads, uploads, or mode changes may interrupt it. Calibration uses acknowledged previews and an explicit save, then closes Wi-Fi and attempts to restore the Mac network. Stored calibration is included in the ordinary single USB snapshot. Never route production driving through this service.
