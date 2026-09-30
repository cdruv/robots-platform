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
- **Sensors:** CameraX initially; Android sensor and audio APIs.
- **UI:** Native Android interface; choose the 3D renderer separately.
- **Inference:** Multiple backend endpoints with some way of picking a model
  or provider for certain type of work.
- **Body controller:** Pico 2 W + MicroPython for servo execution and watchdogs.
  Communicate with the Android app over local Wi-Fi.
- **Research/training:** Python, PyTorch, MuJoCo, and Stable-Baselines3.
- **Observability:** Timestamped structured logs, recording/replay, and Rerun.

### Architecture constraints
- Separate perception, world state, personality/planning, skills, locomotion,
  and hardware adapters. Divide into concrete subsystems.
- LLMs request bounded skills; they do not directly command servo angles.
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
