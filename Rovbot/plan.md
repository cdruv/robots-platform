# Rovbot Milestone 1 — Xbox-driven rover with telemetry, via a macOS Control Center

## Context

The original Arduino controller was bench-verified: `firmware/motor_controller/` implements a closed-loop,
fault-latching wheel-velocity controller, and `tools/motor_serial_test.py` has driven it successfully
over USB serial. The protocol simplifications in Phase 1 still need a hardware recheck.
Everything above that line is empty — there is no Pi, no ROS 2 workspace, and no
macOS app anywhere in the repo.

This milestone builds the first complete vertical slice through the architecture in `AGENTS.md`:

```
Xbox pad --BT--> macOS Control Center --WiFi/WebSocket--> Raspberry Pi 4 --USB--> Arduino --> motors
                        ^                                       |
                        \-------------- telemetry + logs -------/
```

Success = holding the deadman button on an Xbox controller drives the rover, and the Mac app shows
live wheel velocities, odometry, Arduino state/fault bits, battery voltage, and ROS logs.

Deliberately **out of scope**: SLAM, Nav2, the brain layer, the offboard server, and video. Video
gets a documented seam (§9) but no implementation — there is no camera in `HARDWARE.md` yet.

## Decisions locked

| Decision | Choice | Rationale |
|---|---|---|
| Controller path | Pad → Mac → WiFi → Pi | Matches the stated architecture; Control Center stays in the loop |
| Mac publishes | `sensor_msgs/Joy`, not `Twist` | Deadman/scaling/turbo stay in tested ROS params on the Pi; Swift stays a thin input publisher; a clock-skew trap disappears (§6) |
| Pi driver | `ros2_control` + `diff_drive_controller` | `/odom`, TF, `/joint_states` for free; no rewrite before SLAM |
| Transport (v1) | `rosbridge_server` — JSON over WebSocket | ~1 day to a working app vs ~4 for a CDR decoder |
| Transport (later) | `foxglove_bridge` added alongside | Second port, no migration; switch when video/bandwidth demands it. Swift hides both behind one `RobotTransport` protocol |
| macOS stack | Native SwiftUI + Swift 6 | §7 |
| OS / distro | Ubuntu Server 26.04 LTS `resolute` arm64 + ROS 2 Lyrical Luth | **Verified**: `packages.ros.org/ros2/ubuntu/dists/resolute/main/binary-arm64` carries every package below |

Verified available as arm64 debs: `ros-lyrical-ros-base`, `-ros2-control`, `-ros2-controllers`,
`-diff-drive-controller`, `-joint-state-broadcaster`, `-gpio-controllers`, `-controller-manager`,
`-robot-state-publisher`, `-xacro`, `-joy`, `-teleop-twist-joy`, `-twist-mux`, `-rosbridge-suite`,
`-foxglove-bridge`, `-rmw-cyclonedds-cpp`, plus `ros-dev-tools`.

---

## Ownership at a glance

| Phase | You | Me |
|---|---|---|
| 1 Firmware prep | Upload sketch, run bench test | Edit firmware, update README |
| 2 SD card + first boot | **All of it** (physical) | Write the cloud-init files + runbook |
| 3 Pi provisioning | Run one script (or grant SSH) | Write `provision_pi.sh`, udev, systemd, runbook |
| 4 ROS 2 workspace | — | All packages, tests, fake Arduino |
| 5 Bench bring-up | Hold the chassis, cut power if needed | Drive the tests, read telemetry, fix |
| 6 Teleop chain | Pair the pad to the Mac | twist_mux/teleop configs, launch files |
| 7 macOS app | Install Xcode, first signed run | The whole app |
| 8 Floor calibration | Tape measure, 2 m runs, 360° spins | Turn your numbers into tuned params |

**Open checkpoint:** after Phase 2 succeeds, decide whether I get SSH access to the Pi from this Mac.
With it, Phases 3–5 collapse into me working directly and verifying against real hardware. Without
it, I hand you scripts and we round-trip through paste-backs. I'll write the scripts either way, so
nothing is wasted by deciding late.

**Before hardware bring-up:** upload the current firmware and use the matching host tool;
Phase 1 describes the protocol version change.

---

## Phase 0 — Gather (you)

- **microSD reader** for the Mac (the 128 GB card is listed in `HARDWARE.md`; a reader is not).
- **USB-C power cable** for the Pi 4 from the power bank, and a **USB-A→micro-B** cable Pi→Arduino.
- **Ethernet cable** for first boot — strongly recommended. If WiFi credentials are wrong in
  cloud-init you have no way in, and the Pi is headless.
- Optional but cheap insurance: **micro-HDMI cable + USB keyboard**, so a failed boot is debuggable
  instead of a re-flash.
- **Xcode** from the App Store (large download; start it early).

---

## Phase 1 — Firmware and bench client

The firmware and `tools/motor_serial_test.py` now use protocol version 2. Upload the
updated sketch before running the updated client; version 1 is incompatible.

- Keep one telemetry frame per 20 ms control tick, using that tick's encoder sample.
  `TELEMETRY_DECIMATION` can lower the rate later if measurements justify it.
- Keep the whole-frame transmit-space guard at every telemetry rate. Drop a status
  frame when the host stops reading rather than letting serial output stall control.
- Commands carry no sequence numbers. Arm by sending zero targets and `A,2`, then
  keep streaming zeros until fresh telemetry reports `ARMED`, or a bounded timeout
  fails the attempt. See §4.3 for connection handling.
- Malformed input is ignored while disarmed or faulted and still stops an armed
  controller. It never refreshes the command timer or clears a fault.

Keep the existing target limits, explicit arm/disarm and fault clearing, command
watchdog, hardware watchdog, battery/stall checks, and 720 CPR encoder decoder.

**You:** upload, then re-run the bench tool with `--wheels-raised` and complete the
firmware README's stop-path checks. The protocol change has not been bench-verified yet.

---

## Phase 2 — SD card, Ubuntu, first boot (you)

1. Install **Raspberry Pi Imager** on the Mac.
2. Choose *Raspberry Pi 4* → *Other general-purpose OS* → *Ubuntu* → **Ubuntu Server 26.04 LTS (64-bit)**.
3. In Advanced Options set hostname `rovbot`, enable SSH with **public-key only**, paste
   `~/.ssh/id_ed25519.pub`, set username `rovbot`, and fill in WiFi + country.
4. Write, eject, insert, **boot on Ethernet first**.
5. `ssh rovbot@rovbot.local`, then `sudo apt update && sudo apt full-upgrade -y && sudo reboot`.
6. Confirm WiFi survives an unplugged Ethernet cable before you trust it.

I'll provide `Rovbot/pi/cloud-init/{user-data,network-config}` as a reproducible alternative to the
Imager GUI — same result, but in git. Note `optional: false` on `wlan0` so cloud-init waits for the
network instead of racing past it.

**Checkpoint:** `ssh rovbot@rovbot.local` works and `vcgencmd get_throttled` returns `0x0`. Non-zero
means the power bank is browning out the Pi under load — that shows up later as phantom
`controller_manager` overruns, so catch it now. Then decide the SSH-access question.

---

## Phase 3 — Provision the Pi (me writes `Rovbot/pi/provision_pi.sh`)

Idempotent, re-runnable, each step logged:

- **ROS 2 Lyrical**: locale → `ros-apt-source_1.3.0.resolute` deb (resolves via
  `$(. /etc/os-release && echo $VERSION_CODENAME)`) → `apt install ros-lyrical-ros-base ros-dev-tools`
  plus the control/teleop/bridge packages listed above. `ros-base`, not `desktop` — RViz on the Pi is
  wasted disk.
- **udev** → `Rovbot/ros2_ws/src/rovbot_bringup/udev/99-rovbot.rules`, keyed on the UNO R4 WiFi's
  VID/PID (verify with `lsusb`; sketch and bootloader enumerate differently):
  ```
  SUBSYSTEM=="tty", ATTRS{idVendor}=="2341", ATTRS{idProduct}=="....", \
    SYMLINK+="rovbot_arduino", MODE="0660", GROUP="dialout", ENV{ID_MM_DEVICE_IGNORE}="1"
  ```
  Keep ModemManager away from the motor port with `ID_MM_DEVICE_IGNORE`. Malformed
  probes no longer fault a disarmed controller, but another process must not interfere
  with an active link. Check for competing serial-device services during provisioning.
- **Real-time limits**: `rtprio` and `memlock` in `/etc/security/limits.conf` for
  `controller_manager`'s `thread_priority: 50` / `lock_memory: true`. A PREEMPT_RT kernel is *not*
  needed — 50 Hz is a 20 ms budget against tens of microseconds of syscall work. Set the `performance`
  CPU governor to cut jitter.
- **Time sync**: chrony. Needed because `cmd_vel_timeout` is evaluated against `header.stamp`.
- **DDS**: CycloneDDS with an explicit unicast peer list rather than multicast discovery, which is a
  reliable source of pain over WiFi. Pin `ROS_DOMAIN_ID`.
- **systemd** `rovbot.service` — `Restart=on-failure`, starts the bringup launch. Left **disabled**
  until Phase 5 passes; you do not want a rover that drives itself on boot while you're still debugging.

---

## Phase 4 — ROS 2 workspace (me)

`Rovbot/ros2_ws/src/` — three packages, not five. Add `ros2_ws/{build,install,log}/` to `.gitignore`.

```
rovbot_description/   urdf/{rovbot.urdf.xacro, rovbot.ros2_control.xacro, rovbot.properties.xacro}
                      config/geometry.yaml          # wheel_radius + wheel_separation, ONE source
rovbot_hardware/      include+src/{protocol,serial_port,link,system}.{hpp,cpp}
                      rovbot_hardware.xml           # pluginlib
                      test/{test_protocol,test_link,test_integrator}.cpp, fake_arduino.py
rovbot_bringup/       launch/{rovbot,control,teleop,bridge}.launch.py
                      config/{rovbot_controllers,twist_mux,teleop_twist_joy}.yaml
                      udev/99-rovbot.rules
```

**No `rovbot_msgs` yet** — the three services are all `std_srvs/srv/Trigger`, and status is already
covered by one stock broadcaster (§4.2). **No `rovbot_teleop` yet** — stock `joy` + `teleop_twist_joy` + `twist_mux`
are the entire chain. Both get created the first time they'd hold real content.

`rovbot_description` is separate because it's the one artifact RViz, the future workstation, and any
simulator all need, and it must install without compiling a plugin that links `termios`.

### 4.1 `RovbotSystemHardware` — the core

A `hardware_interface::SystemInterface` plugin. **Joints:** `position` (rad, integrated from cumulative
counts), `velocity` (rad/s), and a `velocity` command. **GPIO state interfaces** for Arduino status:
`state`, `faults`, `battery_voltage`, `left_pwm`, `right_pwm`, `left_target`, `right_target`,
`telemetry_age`, `link_state`.

**Serial I/O: start in the driver cycle.** Open the port nonblocking. `read()` consumes
available bytes with a fixed per-cycle budget, assembles newline-delimited records,
and retains an incomplete record for the next cycle. Bound the receive buffer and
resynchronize at the next newline after an oversized record. Use complete version 2
telemetry only; reject other versions and field counts.

`write()` sends current wheel targets at 50 Hz once telemetry establishes the link.
Send zeros whenever inactive, waiting to arm, or handling a fault. Check finite
values, convert rad/s to integer mrad/s, and proportionally limit both wheels to the
configured maximum so saturation preserves turn radius.

Keep a small fixed transmit buffer and its unsent offset. A partial write is normal
stream behavior: retain the remainder and retry on a later cycle when the port is
writable. Never interleave another line into a partial line or spin on `EAGAIN`.
Do not queue historical wheel targets; format the latest target when the buffer is
free. Treat a write that cannot complete within a bounded deadline as a link failure.

Service arm, disarm, and clear requests through the same writer. During lifecycle
transitions, a bounded wait may pump the same I/O functions while the normal cycle
is stopped; there must still be only one owner of the serial port.

This keeps serial work bounded without a reader thread or shared snapshot buffers.
Measure cycle duration under load before adding concurrency. Telemetry drops at the
Arduino are acceptable; command timeouts and telemetry freshness checks still apply.

**Position integration.** Wraparound is not the hazard (2³¹ counts ÷ 687.5 counts/s ≈ 36 days of
full-speed driving). The hazard is the Arduino rebooting and counts jumping to 0. One mechanism covers
both: unsigned-arithmetic deltas for defined wraparound, a plausibility bound (3× max rate + slack) that
rejects jumps, and **re-baseline on reboot, never zero** — `position_rad_` survives arm/disarm cycles
and reboots; only `on_configure` resets it. Zeroing on activate would teleport `/odom`.

**Connection and reset handling.** Valid telemetry establishes the connection.
Configure encoder CPR and operating limits to match the installed firmware's
`motor_controller.ino` and `motor_controller_config.h`; they are not sent over serial.

An unexpected transition out of `ARMED` or an implausible encoder jump must stop
motion and require explicit re-arm. Re-baseline counts on a detected reset; use
the telemetry timestamp as supporting evidence, allowing for its wrap.

### 4.2 Status — one stock broadcaster

Use the GPIO state interfaces with `gpio_controllers/GpioCommandController` in
broadcaster mode to publish one numeric status stream on `~/gpio_states`. The Mac
app decodes state and fault values and displays battery voltage, wheel targets,
PWM, link state, and telemetry age. It also tracks local receipt time so an old
status message is not displayed as current when the connection stops.

Do not add a second custom hardware-status mapping or a custom status controller
for this milestone. Add another representation only when a concrete consumer needs it.

### 4.3 Lifecycle, arming, and the return-code policy

Activation requires Arduino `ARMED`; deactivation requests `DISARMED`. A latched
fault keeps the Arduino in `FAULT` until explicitly cleared.

**Arming observes state.** After allowing for startup, discard buffered input,
terminate any partial command with a newline, and send `D,2`. Require fresh
version 2 telemetry showing `DISARMED` before arming. An existing fault requires
fault recovery first. Do not treat a previously cached `ARMED` sample as success.

Send `C,2,0,0`, then `A,2`. Continue sending zero targets at 50 Hz while reading
telemetry. Fresh `ARMED` telemetry permits motion; a fault or a one-second timeout
ends the attempt with `D,2` and a failure report containing the last state/faults.
There is no pause in command refresh and no automatic retry of arm requests.

A refusal can leave state and faults unchanged. Report an arm timeout honestly;
do not claim it identifies a lost command or a particular failed precondition.
For clearing, send zero targets and `F,2`, then keep streaming zeros while waiting
for `DISARMED`. After clearing succeeds, send a fresh zero command before arming.

**Return codes.**

| Condition | Action |
|---|---|
| Arduino FAULT | `read` OK, `write` **DEACTIVATE** — stops controllers, keeps the driver alive streaming zeros so `clear_faults` still works |
| Telemetry age > 0.10 s | OK + throttled WARN |
| Telemetry age > 0.50 s | `write` **DEACTIVATE** |
| Age > 2.0 s, `ENODEV`/`EIO`/`POLLHUP` | `read` **ERROR** |

`on_error` **must return `CallbackReturn::SUCCESS`** — on SUCCESS the component lands in UNCONFIGURED
and is recoverable via `set_hardware_component_state`; on ERROR/FAILURE it goes to FINALIZED and cannot
be recovered without restarting the process. Likewise `on_activate` returns **FAILURE**, not ERROR,
when arming is merely refused (low battery, latched stall) — FAILURE stays in INACTIVE and is retryable.
`DEACTIVATE` is honoured from `write()` only, so detect in `read()`, set a flag, act in `write()`.

### 4.4 Fault recovery — ship without services first

`on_activate` arms and `on_deactivate` disarms, so

```sh
ros2 control set_hardware_component_state rovbot_arduino inactive
ros2 control set_hardware_component_state rovbot_arduino active
```

performs a disarm → re-arm cycle when no fault is latched. Keep fault clearing explicit;
a failed activation must report the existing fault rather than automatically clear it.
Until the service below exists, use the bench tool with `--clear-faults` after stopping
the ROS driver and correcting the cause. Only one process may own the port.

Add `~/arm`, `~/disarm`, `~/clear_faults` as `std_srvs/srv/Trigger` in a **second pass**, hosted on the
component's own node via `get_node()` — no custom broadcaster, no GPIO command plumbing. Callbacks set
a bounded pending request for the serial owner; they never write directly to the fd.

Startup hygiene: `tcflush(fd, TCIOFLUSH)` plus one deliberate bare `"\n"` in `on_configure`. A bare
newline with an empty RX buffer is ignored by the parser, so it's harmless when there's no garbage and
terminates it when there is.

---

## Phase 5 — Bench bring-up (joint, wheels raised)

Layered so almost nothing needs the robot powered:

**Tier 0 — plain gtest, no ROS, no hardware.** Encoder/decoder round-trips, boundary values, malformed
input, the position integrator, and the link state machine against a fake clock.

The existing firmware host tests compile the real parser and sketch behind a small
Arduino shim. They cover version mismatches, numeric limits, fragmented and oversized
lines, malformed input in each state, and telemetry field order. Reuse these tests and
add compatibility checks for commands emitted by the Pi driver. Run instructions are
in the firmware README.

**Tier 1 — `fake_arduino.py` over a pty.** ~250 lines porting the `.ino` state machine, behind
`pty.openpty()` so your `termios`/`O_NONBLOCK`/`tcflush` paths are exercised identically to the real
device (this is why a pty beats a TCP shim). It injects on demand: the boot delay, a mid-run reboot,
dropped bytes, malformed lines, stall faults, low battery, a silent arm refusal, telemetry starvation.
Validate it against `tools/motor_serial_test.py` before trusting it. `launch_testing` on top: publish a
twist, assert `/odom` moves correctly; kill telemetry, assert DEACTIVATE; inject a fault, assert
recovery. Also expose `use_mock_hardware` (stock `mock_components/GenericSystem`) as a ~6-line xacro
arg — it lets the URDF, controller configs, teleop chain and the Mac app be developed on the laptop
with no Pi and no Arduino at all.

**Tier 2 — real Arduino, wheels raised, motor power reachable.** The existing README checklist still
applies, plus: verify connection both after a reset and to an already-running Arduino;
repeat disarm/arm cycles while streaming zeros; deliberately inject malformed
lines in disarmed and armed states; unplug USB mid-run and confirm you land in UNCONFIGURED and **not** FINALIZED; and an
explicit sign-convention test — command +1 rad/s per wheel and confirm both `position` interfaces
*increase* and `/odom` `twist.linear.x > 0`. The firmware README already warns a sign error makes the PI
controller drive *harder* the wrong way; ROS adds two more places to invert it.

---

## Phase 6 — Teleop chain (me)

```
Mac --Joy@50Hz--> rosbridge --> /joy --> teleop_twist_joy --> twist_mux --> diff_drive_controller
                                        (require_enable_button,     ^
                                         publish_stamped_twist)     |
                              Mac --Bool@2Hz--> /e_stop lock -------/
```

**Two silent-failure traps.** `teleop_twist_joy` defaults `publish_stamped_twist: false`, while
`diff_drive_controller` subscribes to `TwistStamped` and `twist_mux` defaults `use_stamped: true`. To
DDS, a `Twist` publisher and a `TwistStamped` subscriber on the same topic name are two unrelated
topics — nothing errors, the robot simply never moves. **Set it explicitly.** And `cmd_vel_timeout` is
evaluated against `header.stamp`, so clock skew makes commands look permanently stale or permanently
fresh. Because `teleop_twist_joy` runs *on the Pi*, the Mac's clock never enters that path — a real
benefit of publishing `Joy` rather than `Twist`. chrony is still required for the rest of the system.

**No custom watchdog node.** `twist_mux` locks already are one: a lock with `timeout > 0` engages
itself when its publisher stops. Have the Mac publish `std_msgs/Bool false` on `/e_stop` at 2 Hz; if
the app crashes or WiFi drops, the lock engages at priority 255, masks every input, and
`cmd_vel_timeout` commands zero. Publishing `true` is a manual e-stop over the same channel.

**The one place a stock package collides with the firmware.** `diff_drive_controller` limits the *body
twist*, but worst-case wheel speed is `v/r + ω·b/(2r)`. With a naive `(0.20 m/s, 1.5 rad/s)` box and
nominal Romi geometry that's **8.91 rad/s against a 6.0 limit** — a twist well inside the configured box
produces an out-of-range wheel target, which is a latched stop. Fix in two layers: shrink the twist box
so the corner fits (`a + ω·b/2 ≤ 0.21` → `(0.15, 0.80)` works), *and* keep the proportional saturation
of §4.1 as the hard guarantee with a `saturation_events` counter. Don't change the firmware — faulting
is correct for the safety-authoritative device. Add a launch-time assertion comparing `geometry.yaml`,
the controller YAML, and the configured wheel limit. Keep that limit consistent with
the installed firmware when changing either configuration.

**The timeout ladder**, and why the Arduino watchdog is *not* first in normal operation: the driver
sends `C` at 50 Hz unconditionally, so a WiFi dropout never reaches it. A dropout instead walks deadman
→ `twist_mux` timeout (0.3 s) → `cmd_vel_timeout` (0.4 s), and the robot stops **under closed-loop
control with `SLP` still high** — dynamic braking, not a coast. The Arduino's 250 ms watchdog exists for
the case where the Pi software or the USB link itself dies, which is exactly its job. Ordering
constraints: mux timeouts (0.3) < `cmd_vel_timeout` (0.4) < driver stale-deactivate (0.5). At 0.21 m/s
the whole ladder keeps runaway under **~100 mm**.

---

## Phase 7 — Control Center, macOS (me)

`Rovbot/control_center/` — an Xcode project, per the repo's keep-it-in-the-project rule.

**Stack: SwiftUI + Swift 6, macOS 15+.** Reasoning, since you asked:

- **GameController.framework** reads Xbox Series X|S and One controllers natively over Bluetooth, with
  proper button/axis semantics and no polling loop. This is the argument that settles it: Electron's
  Gamepad API adds a browser event loop between your thumb and the motors, and Tauri would put you on
  `gilrs` in Rust for the same job. The app is macOS-only by definition — cross-platform frameworks
  charge you their tax and hand back nothing.
- **`URLSessionWebSocketTask`** for rosbridge. No dependencies, no SPM packages, `Codable` structs per
  message type — which in Swift is *more* type-safe than a generic CDR path, not less.
- **Swift Charts** for telemetry plots, **`NWBrowser`** to find `rovbot.local`, actors for the
  connection layer, `@Observable` for state.

**Architecture — one seam that matters:**

```swift
protocol RobotTransport {
    func subscribe<M: Decodable>(_ topic: String, as: M.Type) -> AsyncStream<M>
    func publish<M: Encodable>(_ msg: M, to topic: String)
    func call(service: String) async throws -> TriggerResult
}
```

`RosbridgeTransport` implements it now; `FoxgloveTransport` implements it later when video and
bandwidth make the CDR work worth doing. Nothing above the protocol changes. Everything else —
`JoyPublisher`, `TelemetryStore`, the views — is written against the protocol, never against JSON.

**v1 screens:** connection + robot state (DISARMED/ARMED/FAULT with decoded fault bit names, battery
volts, telemetry age); a drive view (live wheel target vs measured, a 2D odometry trail,
controller stick visualization, deadman indicator); a log view streaming `/rosout` with severity
filtering; and arm/disarm/clear-fault buttons wired to the Phase 4.4 services.

**You:** install Xcode, and run it once to accept the local signing certificate.

---

## Phase 8 — Floor calibration (your measurements, my tuning)

Nothing in the repo defines wheel radius or track width. Start from nominal Romi 0.035 m / 0.149 m,
then:

1. **Radius** — drive a tape-measured 2 m each way, compare to `/odom` x, correct via
   `left/right_wheel_radius_multiplier` so the nominal stays documented.
2. **Track width** — N in-place 360° rotations, measure residual heading, correct via
   `wheel_separation_multiplier`.
3. **UMBmark square** (CW and CCW, 2×2 m) — required because wheel-diameter-ratio error and track-width
   error *alias* if you only do one of the two tests above.
4. Fill in the odometry covariances from these runs; Nav2 and any future EKF consume them.
5. Validate the ladder at speed: kill WiFi and measure stopping distance (expect ≲100 mm); kill
   `ros2_control_node` and confirm the Arduino watchdog stop.

Also worth doing here: `HARDWARE.md:135` says the planned PWM cap is ~140/255 while the firmware uses
`MAX_PWM = 96`. Reconcile the doc, and re-check the §6 twist box if you raise the cap.

---

## §9 — The video seam (documented, not built)

When a camera arrives, it does **not** go through the ROS transport as `sensor_msgs/Image`. It gets its
own H.264 stream (WebRTC for low latency, or RTSP) decoded by `AVFoundation` in the Mac app, running
parallel to `RobotTransport`. That's the right design regardless of which bridge you're on, which is
why the transport choice doesn't constrain it. Control Center's layout should reserve the space now.

---

## Verification

| Gate | Check |
|---|---|
| Phase 1 | `motor_serial_test.py` still drives both wheels; telemetry lines arrive at ~50 Hz |
| Phase 2 | `ssh rovbot@rovbot.local` over WiFi; `vcgencmd get_throttled` = `0x0` |
| Phase 3 | `ros2 doctor` clean; `ls -l /dev/rovbot_arduino` resolves; no ModemManager in `udevadm monitor` at plug-in |
| Phase 4 | `colcon test` green — including compatibility checks against the real Arduino parser |
| Phase 4 (mock) | `use_mock_hardware:=true` on the laptop: `/odom`, TF, `/joint_states` all correct with no hardware |
| Phase 5 | Wheels raised: `ros2 topic pub` a `TwistStamped` → wheels turn the right way, `/odom` agrees; unplug USB → UNCONFIGURED, not FINALIZED |
| Phase 6 | Deadman held → rover drives. Released, WiFi killed, `ros2_control_node` killed → stops each time, <100 mm |
| Phase 7 | Pad drives the rover *through the app*; telemetry and `/rosout` render live; fault → clear → re-arm from a button |
| Phase 8 | 2 m commanded = 2 m ±2 cm measured; 360° spin ends within a few degrees |

## Risks

- **Pi 4 undervoltage on the power bank.** Already flagged in `HARDWARE.md`. Presents as
  `controller_manager` overruns that look like a software bug. `vcgencmd get_throttled` is in the Phase 2
  gate for exactly this reason.
- **50 Hz overrun rate on a Pi 4** under WiFi + bridge load is unmeasured. If it's bad, the first lever
  is `cpu_affinity`, not `is_async`.
- **UNO R4 WiFi USB VID/PID** differ between sketch and bootloader; the udev rule needs the real values
  from `lsusb`.
- **Arduino reboot mid-drive** loses motion during the ~1.5 s reset. Unrecoverable by design — log it,
  bump `reboot_count_`, let localization deal with the discontinuity. Don't guess at it.
