# Walky

Personal robotics project: a small two legged robot a Pixel 8 serving as onboard computer and most of I/O. Project focus is in learned locomotion, multimodal perception, LLM driven autonomy and decision making. 

- Hardware and physical layout: [HARDWARE.md](HARDWARE.md).
- Onboard app is native Android: `onboard-android/`.
- macOS control center for robot: `../Robot Relay/`.

## Architectural Decisions and Rules

- All Android robot work must belong to a single foreground session that stops and releases resources whenever the app loses foreground focus or the display turns off, resuming only while the app is foregrounded and the display is on and unlocked.
- One executive owns robot state and processes events sequentially on a single
thread. Edge loops pass events in and targets out; the executive never blocks.
- A reflex is a named high-rate path from a sense straight to an output,
bypassing the executive (today IMU → face). We will probably need to rethink
this mechanism once IMU will be a continuous input stream to locomotion NN. 
- The language model requests bounded skills. It does not command servo angles.
- The face stays 2D behind `Face`. Do not add a 3D renderer.
- `:core` is pure Kotlin: contracts, policy, and cognition, unit-testable
without Android. `:app` is the Android implementation.
- MicroPython on the Pico owns servo command timeout, stop, and watchdogs, and
must keep that safety behavior if rendering or any cloud link is down.
The body protocol gives walking and other motions an explicit priority. 
- Train deployed policies only on observations the mounted Pixel 8 can produce.
Record sensor axes and units, phone orientation, observation history,
action-to-servo mapping, body geometry, and control rate with each policy.

- All servo motion, including firmware actions and Android-originated commands, must apply persisted per-leg calibration exactly once in the Pico’s final output layer before enforcing PWM limits.
