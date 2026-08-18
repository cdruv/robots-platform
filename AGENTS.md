# AGENTS.md

## Project

2WD Romi rover, currently focused on **Milestone 1**:

- Receive wireless velocity commands from a laptop/Xbox controller.
- Control both wheels using encoder feedback.
- Measure travel and estimate differential-drive odometry.
- Log telemetry.
- Stop safely on command loss or faults.

Architecture:

- **Arduino UNO R4 WiFi**: real-time motor control, encoder reading, wheel control, local safety.
- **Raspberry Pi 4**: networking, controller bridge, telemetry/logging, future high-level autonomy.

## Current status

Hardware is assembled:

- Romi chassis assembled.
- Pololu #3543 motor/power board mounted.
- Pololu #3542 encoders installed.
- Arduino UNO R4 and Raspberry Pi mounted on the front/top plate.
- Power bank mounted at the rear.
- ElectroCookie proto shield assembled and mounted on the Arduino.
- Motor/encoder/control wiring is mostly complete.
- Battery-voltage divider is implemented on the proto shield.

Software is not yet implemented beyond basic flashing/bring-up work.

## Next software work

Recommended implementation order:

1. Safe Arduino startup state.
2. Encoder counting and direction validation.
3. Low-power open-loop motor test.
4. Wheel-speed estimation.
5. Closed-loop wheel-speed control.
6. Differential-drive `v, ω` command handling.
7. Arduino watchdog and fault state machine.
8. USB serial protocol between Pi and Arduino.
9. Pi command bridge and telemetry logging.
10. Laptop/Xbox teleoperation.
11. Odometry calibration and validation.

## Future expansion

Keep the design compatible with later additions:

- Camera.
- Microphone/speaker.
- IMU and proximity/range sensors.
- SLAM/localization.
- ROS 2.
- Higher-level autonomous navigation.
- LLM-based planning/interaction.

High-level software should command bounded motion goals such as linear/angular velocity, while the Arduino remains responsible for low-level motor control and safety.
