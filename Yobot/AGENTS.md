# Yobot

Personal robotics project.
Learned locomotion, multimodal perception, LLM driven autonomy and decision making.
Hardware: [HARDWARE.md](HARDWARE.md).
Onboard app is native Android in `onboard-android/`.

## Architectural Decisions and Rules

- One executive owns robot state and processes events sequentially on a single
  thread. Edge loops pass events in and targets out; the executive never blocks.
- A reflex is a named high-rate path from a sense straight to an output,
  bypassing the executive (today IMU → face). We will probably need to rethink
  this mechanism once IMU will be a continuous input stream to locomotion NN. 
- The language model requests bounded skills. It does not command servo angles.
- The face stays 2D behind `Face`. Do not add a 3D renderer.
- `:core` is pure Kotlin: contracts, policy, and cognition, unit-testable
  without Android. `:app` is the Android implementation.
- Do not add file sinks, recording/replay, or Rerun yet. Those are a later
  Mac-side iteration.
- MicroPython on the Pico owns servo command timeout, stop, and watchdogs, and
  must keep that safety behavior if rendering or any cloud link is down.
  The body protocol gives walking and other motions an explicit priority. 
- Train deployed policies only on observations the mounted Pixel 8 can produce.
  Record sensor axes and units, phone orientation, observation history,
  action-to-servo mapping, body geometry, and control rate with each policy.
  Measure link and control timing before choosing that rate.