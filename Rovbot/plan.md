# Rovbot Milestone 1 — Xbox-driven rover with telemetry, via a macOS Control Center

## Context

The Arduino side is done and bench-verified: `firmware/motor_controller/` implements a closed-loop,
fault-latching wheel-velocity controller, and `tools/motor_serial_test.py` has driven it successfully
over USB serial. Everything above that line is empty — there is no Pi, no ROS 2 workspace, and no
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

**Before anything:** `firmware/motor_controller/` and `tools/` are staged but uncommitted. Commit them
first so the firmware edits in Phase 1 are a reviewable diff.

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

## Phase 1 — Firmware prep (me writes, you flash)

Three changes to `firmware/motor_controller/`, all small, all unblocking work downstream.

**None of this is required for the robot to drive.** Counts are cumulative, so 10 Hz telemetry delays
data but never loses it, and odometry would still be correct. This phase exists because (a) the Phase 4
driver's arm handshake, staleness thresholds and `velocity_rolling_window_size` all derive their numbers
from the telemetry rate, so changing it before writing that code is free and changing it after costs a
full bench re-validation; and (b) right now `motor_serial_test.py` works and nothing else is in the
system, which makes this the cheapest moment the firmware will ever be touched — once the Pi is in the
loop, a firmware regression presents as a driver bug.

**Reduced-scope fallback if you'd rather not touch working firmware:** take 1.3 only (documentation,
zero risk) and build the driver against 10 Hz. The cost is blind arm timeouts instead of diagnosable
refusals, and a host-side staleness threshold that duplicates rather than supplements the Arduino's
250 ms watchdog. Both are livable for teleop; neither is livable once closed-loop heading control or
Nav2 appears. `TELEMETRY_DECIMATION` keeps the door open — `1` is 50 Hz, `5` is 10 Hz.

**1.1 Telemetry 10 Hz → 50 Hz.** `motor_controller.ino:29` sets `TELEMETRY_PERIOD_MS = 100`, against a
50 Hz `controller_manager`. Two concrete payoffs, not "more data is better":

*Arm failures become diagnosable.* `A` is silent on failure, and the echo can only be observed during a
quiet window (each subsequent `C` overwrites `lastReceivedSequence`) bounded at ~150 ms by the pre-arm
freshness rule. At 50 Hz that window holds ~7 frames; at 10 Hz, 1–2, so one late frame drops you into a
blind timeout. The difference is *"refused: battery 5.31 V < 5.80 V recovery"* versus *"arm timed out."*

*You gain an independent link-failure layer.* An Arduino that is alive but deaf, or a half-dead USB
link, is caught only by host-side staleness detection — the Pi keeps sending `C`, so nothing necessarily
trips on the Arduino side. At 10 Hz the threshold must sit at ≥250 ms to avoid false positives, which is
the Arduino's own command watchdog; the detector fires no earlier than the protection it was meant to
supplement. At 50 Hz you can warn at 100 ms and act at 500 ms.

Secondary: feedback lag drops from ~140 ms (100 ms transport + ~37 ms of the Arduino's
`SPEED_FILTER_ALPHA = 0.35f` EMA) to ~57 ms, and `/joint_states` stops stuttering in a `0,0,0,0,5Δ`
pattern as 80% of `read()` cycles stop seeing stale data.

Replace the standalone telemetry timer (`motor_controller.ino:464-469`) with a decimation counter at
the *end of* `controlTick()`:

```cpp
constexpr uint8_t TELEMETRY_DECIMATION = 1;   // 1 => 50 Hz, 5 => 10 Hz
```

Publishing from inside the control tick also fixes a latent coherence bug: `publishTelemetry()`
currently re-reads encoder counts at publish time (`motor_controller.ino:400-402`), so `left_count`
and `left_measured_mrad_s` are sampled at different instants.

Bandwidth check: ~75 B/frame typical, ~97 B worst case → 3.8–4.9 kB/s, plus ~1.4 kB/s of host commands,
against 11.5 kB/s nominal. Tight on paper, and the UNO R4 WiFi's `Serial` is native USB CDC where 115200
is a nominal setting anyway. It fits.

**1.2 Guard telemetry writes.** *Only needed because 1.1 is happening — skip it if you skip 1.1.*
At 50 Hz, a host that stops draining fills the USB CDC TX buffer and
`Serial.print` blocks → `loop()` stalls → `FAULT_CONTROL_OVERRUN`, or worse the 1 s hardware watchdog.
That is Pi-side software health reaching into Arduino-side safety. Wrap `publishTelemetry` in
`Serial.availableForWrite() >= 128` and skip the frame otherwise. Telemetry becomes lossy-but-never-
blocking, which is correct for a status stream.

**1.3 Document the sequence-echo contract** in `firmware/motor_controller/README.md` — zero risk, and
the best cost/benefit in the plan. `tryArm()` (`:269`) and `tryClearFaults()` (`:294`) both set
`lastReceivedSequence = sequence` as the **first statement**, before every precondition check and before
the early `return` at `:283`. That means the telemetry echo confirms *receipt*, not *success* — the only
reason the driver can distinguish "the Arduino processed my `A` and refused" from "my `A` was lost."

Today that behavior is an implementation accident. Anyone tidying `tryArm()` would naturally move that
line below the guard clause, silently breaking the §4.3 handshake with no test failing. Writing it into
the README is the whole fix. Also fix the sentence truncated at `README.md:122`.

**Do not** change: out-of-range targets faulting instead of clamping (faulting is correct for the
safety-authoritative device; the driver saturates on its side), or the 720 CPR decoder (0.305 mm/count
is plenty, and doubling it doubles ISR load).

**You:** upload, then re-run `tools/motor_serial_test.py --wheels-raised` to confirm nothing regressed.

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
  `ID_MM_DEVICE_IGNORE` is not optional. **ModemManager will open the port and send AT commands to it**,
  and since any malformed line latches `FAULT_PROTOCOL` *even while disarmed*
  (`motor_controller.ino:327-329`), that means an intermittent unexplained fault on every boot. Purge
  `brltty` too — it steals USB-serial devices on Ubuntu.
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
covered twice over (§4.2). **No `rovbot_teleop` yet** — stock `joy` + `teleop_twist_joy` + `twist_mux`
are the entire chain. Both get created the first time they'd hold real content.

`rovbot_description` is separate because it's the one artifact RViz, the future workstation, and any
simulator all need, and it must install without compiling a plugin that links `termios`.

### 4.1 `RovbotSystemHardware` — the core

A `hardware_interface::SystemInterface` plugin. **Joints:** `position` (rad, integrated from cumulative
counts), `velocity` (rad/s), and a `velocity` command. **GPIO state interfaces** for Arduino status:
`state`, `faults`, `battery_voltage`, `left_pwm`, `right_pwm`, `left_target`, `right_target`,
`telemetry_age`, `link_state`, `last_sequence`.

**Threading: one reader thread; writes stay in `write()`.** The reader `ppoll()`s the fd, splits on
`\n` (stripping the `\r` from Arduino `println`), parses, and publishes into a triple-buffered POD
snapshot behind one atomic index — so `read()` is a lock-free load, no syscalls. It earns its keep for
three reasons: it guarantees the Arduino's TX buffer is drained continuously (per 1.2, a non-draining
host can trip an Arduino-side safety fault — decoupling removes that coupling entirely); it decouples
telemetry arrival phase from the 50 Hz `read()` phase; and a USB hiccup can never stall the control
loop. Writes stay in `write()` because the keepalive must be phase-locked to the control cycle. Exactly
one thread reads the fd, exactly one writes it.

**`write()` — one complete line, every cycle, no exceptions** (except `WAIT_BOOT`, `LINK_LOST`, and the
deliberate arm window). At 50 Hz the Arduino sees a `C` every 20 ms — 12.5× margin on its 250 ms
watchdog, with no separate timer to forget.

```
1. !active_                        -> left = right = 0    // write() may be called while INACTIVE
2. link_state != ARMED             -> left = right = 0    // MANDATORY, see below
3. !std::isfinite(cmd)             -> 0, throttled WARN
4. rad/s -> mrad/s, llround
5. PROPORTIONAL saturation: m = max(|l|,|r|); if m > max: scale BOTH by max/m
6. format into char[48], ONE ::write(), loop on partial writes
```

Four traps encoded there:

- **NaN.** Command interfaces initialise to NaN by default; `llround(NaN * 1000.0)` is unspecified and
  produces garbage the Arduino answers with `FAULT_TARGET_RANGE`. Check `isfinite` *and* set
  `initial_value="0.0"` in the URDF.
- **Proportional, not per-wheel, saturation.** Clamping only the faster wheel silently changes the
  commanded curvature — poison for path following. Scaling both preserves the turn radius.
- **Rule 2 is correctness, not just safety.** `tryClearFaults()` refuses while either requested target
  is nonzero. If `diff_drive_controller` is still commanding motion when you send `F`, the clear
  silently does nothing and the robot is stuck in FAULT forever. This is the subtlest trap in the
  contract.
- **Line atomicity.** A partial write is a malformed line is a latched fault. One `::write()` per line,
  fixed stack buffer, no `ostringstream` in the RT path.

**Position integration.** Wraparound is not the hazard (2³¹ counts ÷ 687.5 counts/s ≈ 36 days of
full-speed driving). The hazard is the Arduino rebooting and counts jumping to 0. One mechanism covers
both: unsigned-arithmetic deltas for defined wraparound, a plausibility bound (3× max rate + slack) that
rejects jumps, and **re-baseline on reboot, never zero** — `position_rad_` survives arm/disarm cycles
and reboots; only `on_configure` resets it. Zeroing on activate would teleport `/odom`.

**The `B` boot line is a configuration handshake.** Cross-check `counts_per_rev` etc. against
`expected_*` params and **fail hard on mismatch** — flashing firmware with a different CPR under an
unchanged URDF silently scales all odometry by 2× with no other symptom. A `B` seen at any *later* time
means the Arduino rebooted: bump a generation counter, re-baseline, require re-arm.

### 4.2 Status — two stock mechanisms, zero custom controllers

1. `gpio_controllers/GpioCommandController` in broadcaster mode (no command interfaces configured)
   publishes `control_msgs/DynamicJointState` on `~/gpio_states`.
2. The framework's built-in `~/hardware_status` — override `init_hardware_status_message()` and
   `update_hardware_status_message()` and it auto-publishes stamped `control_msgs/HardwareStatus`.
   `GenericHardwareState` maps almost exactly: state 0/1/2 → `POWER_STANDBY`/`POWER_ON`/`POWER_ERROR`;
   fault bits → `health_status` + `error_domain[]`; telemetry age → `connectivity_status`; the `B` line
   → `firmware_version`; everything numeric → `state_details[]` KeyValues.

**Do not write a `rovbot_status_broadcaster`.** It would duplicate both. The one honest gap — state
interfaces carry no timestamp — is exactly why `~/hardware_status` stays alongside the GPIO interfaces.

### 4.3 Lifecycle, arming, and the return-code policy

INACTIVE ≡ Arduino DISARMED, ACTIVE ≡ ARMED. The mapping is nearly 1:1, which is a good sign the
contract is sane.

**The arm handshake.** `A` is silent on failure, but 1.3's echo semantics turn a blind timeout into a
deterministic answer:

```
t=0      send C,seq,0,0                          ; note arm_zero_seq
t=20ms   send A,seq+1                             ; note arm_seq
t=20-170ms  SEND NOTHING, poll telemetry.
         Safe: pre-arm freshness is 250 ms from t=0, and so is the command watchdog if it armed.
         last_seq == arm_seq && state == 1  -> ARMED
         last_seq == arm_seq && state != 1  -> DETERMINISTIC REJECTION; read faults, report the
                                               failed precondition, do NOT retry blindly
t=170ms  resume C,seq,0,0 at 50 Hz; fall through to timeout only if the echo was missed
```

At 50 Hz that quiet window holds 7 telemetry frames; at 10 Hz it holds 1–2. That's the third
independent argument for 1.1. Same structure for fault clearing — and note `latchFault()` sets
`haveWheelCommand = false`, so a fresh `C` after `F` is **mandatory** before `A`.

**Return codes — the single easiest thing to get wrong.**

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

already performs a disarm → re-arm cycle. With `auto_clear_fault_mask: 3`
(`FAULT_COMMAND_TIMEOUT | FAULT_PROTOCOL` — the two "host went away or garbled a line" faults a healthy
host legitimately resolves) it clears those on the way through. That's ~80% of fault recovery for zero
new code. Everything else stays latched for a human, because it means a physical condition.

Add `~/arm`, `~/disarm`, `~/clear_faults` as `std_srvs/srv/Trigger` in a **second pass**, hosted on the
component's own node via `get_node()` — no custom broadcaster, no GPIO command plumbing. Callbacks set
an atomic request and wait on a condition variable; they never touch the fd.

Startup hygiene: `tcflush(fd, TCIOFLUSH)` plus one deliberate bare `"\n"` in `on_configure`. A bare
newline with an empty RX buffer is ignored by the parser, so it's harmless when there's no garbage and
terminates it when there is.

---

## Phase 5 — Bench bring-up (joint, wheels raised)

Layered so almost nothing needs the robot powered:

**Tier 0 — plain gtest, no ROS, no hardware.** Encoder/decoder round-trips, boundary values, malformed
input, the position integrator, and the link state machine against a fake clock.

The highest-value test in the plan: **compile the real `firmware/motor_controller/serial_protocol.cpp`
host-side behind a small Arduino shim and property-test that every line the driver can emit parses to
`CommandType != INVALID`.** That directly and exhaustively covers the "any malformed line latches
`FAULT_PROTOCOL`" rule, which is otherwise a latent, intermittent, field-only failure.

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
applies, plus: measure whether opening the port actually resets the UNO R4 WiFi (native USB CDC usually
doesn't, unlike a classic UNO — the design is correct either way, this just sets `boot_timeout_s`);
`last_seq` echo latency histogram; arm success rate over 100 attempts; deliberate malformed-line
injection; unplug USB mid-run and confirm you land in UNCONFIGURED and **not** FINALIZED; and an
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
the controller YAML, and the `B` line, because the driver auto-follows a firmware change and the YAML
does not.

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
volts, link RTT, telemetry age); a drive view (live wheel target vs measured, a 2D odometry trail,
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
| Phase 4 | `colcon test` green — including the round-trip fuzz against the real Arduino parser |
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
