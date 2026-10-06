# Robot Relay

Custom robot control terminal for macOS. It is used to stream telemetry (logs), live sensor data and robot state, update firmware and calibrate some of the robot's parts.

## Requirements

- Every Relay action must have a terminal equivalent in `scripts/`, and a new feature ships with its script. The connection popover's activity footer shows the commands the app runs, and that text is selectable so a line can be copied into a terminal.
- Phone telemetry is NDJSON from onboard-android `TcpServerSink`. Client-only lines carry a `link` key and no `seq`. A new `session` means the phone app restarted and `seq` starts over; within a session, Relay skips events it has already seen when the phone replays recent events after a reconnect. Ping/pong is queued behind pending telemetry, so RTT includes any backlog.

Install with `scripts/install_local.sh` (Release build into `/Applications`), or open `Robot Relay.xcodeproj`.
