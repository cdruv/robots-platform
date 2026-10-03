# Yobot

Personal robotics project and platform: keep the hardware simple and build our
own software/brain stack for learning and fun.

- Train neural networks for walking and other movements; explore autonomy and interactions.
- Build LLM-driven characters, personalities, and roles.
- Use a Pixel 8 as the brain, sensors, and face, linked over Wi-Fi to a Pico controlling two leg servos.
- Keep project code, documentation, dependencies, and tooling inside `Yobot/`.
- Hardware baseline and planned parts: [HARDWARE.md](HARDWARE.md).

## Platform decision: native Android app

Build Yobot’s onboard application natively for the Pixel 8.

### Rationale
Prioritize direct camera/IMU/audio access, native ML and C++ integration,
lifecycle control, detailed telemetry, and future hardware expansion.

### Initial stack
- **App:** Kotlin, Android SDK, Gradle; Android Studio/ADB for development.
  Code lives in [onboard-android](onboard-android/README.md): a pure Kotlin/JVM
  `:core` module for all logic and contracts, and an Android `:app` module for
  platform implementations.
- **Sensors:** CameraX; Android sensor, speech recognition and audio APIs.
  Hearing is live: continuous on-device `SpeechRecognizer` transcription.
- **Face:** Procedural 2D on Compose Canvas with AGSL shaders, behind a `Face`
  contract so another renderer (e.g. 3D) can be swapped in. No 3D commitment.
- **Inference:** A replaceable backend interface and an explicit request entry
  point. Provider integration is a future iteration; no backend is connected yet.
- **Body controller:** Pico 2 W + MicroPython for servo execution and watchdogs.
  Communicate with the Android app over local Wi-Fi.
- **Research/training:** Python, PyTorch, MuJoCo, and Stable-Baselines3.
- **Observability:** Timestamped structured JSONL events to Logcat, a TCP stream
  served by the phone (`tools/telemetry-tail.sh` on the Mac), and an on-screen debug
  overlay. File sinks, recording/replay, and Rerun belong to later iterations on the Mac.

### Architecture constraints
- Separate perception, world state, personality/planning, skills, locomotion,
  and hardware adapters. Divide into concrete subsystems.
- Senses own sensing; outputs own expression and actuation. Keep them separate
  even when they share hardware.
- One executive owns robot state and processes events sequentially on a single
  thread. Hardware and network work runs on its own loops at the edges and talks
  to the executive only via events in and targets out; the executive never blocks.
- Keep policy and cognition pure and unit-testable.
- LLMs request bounded skills; they do not directly command servo angles.
- Every event and decision is telemetered, so behaviour can be inspected and replayed.
- Keep motion execution and safety local, independent of rendering and
  cloud availability. Offload heavier experiments to a workstation as needed.
- Keep model backends and hardware interfaces replaceable.

### Locomotion contract
- Train deployed policies using only observations available to the mounted
  Pixel 8. Record sensor axes and units, phone orientation, observation history,
  action-to-servo mapping, body geometry, and control rate with each policy.
- Keep servo PWM, command timeout, and stop behavior on the Pico. Define
  priority between walking and other motions in Yobot's local protocol.
- Measure link and control timing before choosing a training rate.
