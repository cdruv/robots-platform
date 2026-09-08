## Project

2WD Romi rover, currently focused on **Milestone 1**:

- Receive wireless velocity commands from a laptop/Xbox controller.
- Control both wheels using encoder feedback.
- Measure travel and estimate differential-drive odometry.
- Log telemetry.
- Stop safely on command loss or faults.

The long-term design has three compute layers:

```text
Pixel 8  <--Wi-Fi or direct USB networking-->  Raspberry Pi 4  <--USB serial-->  Arduino UNO R4  -->  Pololu #3543  -->  motors
```

The Pixel is a planned expansion and is not required to complete Milestone 1. The laptop remains the operator and development machine; visualization and other heavy development tools should run there rather than on the Pi.

## Responsibility boundaries



### Pixel 8 — perception and tracking/ML/AI processing

- Provide camera, IMU, microphone, and speaker data.
- Run native Android perception, visual-inertial tracking, object/person tracking, neural inference, and interaction logic.
- Send timestamped sensor measurements, perception results, and bounded high-level requests to the Pi.
- Use a versioned, transport-independent network protocol over either Wi-Fi or direct USB networking.

ROS 2 in an Android-hosted Linux environment may be explored later, but core robot operation must not depend on it. The Pixel must not command motor PWM or bypass command arbitration on the Pi.

Direct Pixel-to-Pi USB networking is being explored as an alternative to Wi-Fi. Retain both transports so deployment can select or switch between them without changing message schemas or higher-level behavior. Neither transport is currently the finalized default.

### Raspberry Pi 4 — robot integration and ROS 2

Software baseline:

- Ubuntu Server 26.04 LTS (Resolute Raccoon), ARM64.
- ROS 2 Lyrical Luth.
- Headless `ros-base` installation. Run GUI tools such as RViz on the development laptop, not on the Pi.

Responsibilities:

- Host the stable onboard ROS 2 graph and robot model.
- Bridge the Pixel's native protocol to standard ROS 2 messages.
- Implement the `ros2_control` hardware interface for the Arduino.
- Own differential-drive kinematics, wheel odometry, command arbitration, robot-wide velocity/acceleration limits, and telemetry/rosbag logging.
- Own the TF tree, state estimation, and future SLAM/localization, Nav2, and behavior execution.
- Convert accepted base velocity commands into bounded left/right wheel-velocity targets for the Arduino.

There must be one authoritative producer for each command and transform. In particular, do not independently implement differential-drive `v, ω` conversion on both the Pi and Arduino, and do not allow multiple nodes to publish the same TF edge.

### Arduino UNO R4 — real-time control and safety

- Acquire encoder counts and estimate wheel speeds.
- Run the per-wheel closed-loop controllers.
- Generate motor PWM/direction signals and control the driver sleep input.
- Measure motor-battery voltage.
- Enforce local setpoint bounds, command timeout, startup interlocks, and fault handling.
- Immediately enter a safe stopped state on command loss or a local fault.
- Report encoder, wheel-speed, battery, controller-state, and fault telemetry to the Pi.

The Arduino accepts bounded left/right wheel-velocity targets; it remains authoritative for low-level actuation and safety. No Pixel, Pi, ROS 2, Wi-Fi, or laptop failure may prevent its local watchdog from stopping the motors.

## Command and state paths

```text
teleop / Nav2 / Pixel request
        -> Pi command arbitration and limits
        -> Pi differential-drive controller
        -> left/right wheel-velocity targets
        -> Arduino watchdog and wheel PID
        -> motor PWM/direction
```

```text
encoders / battery / Arduino state
        -> versioned USB serial telemetry
        -> ros2_control and ROS 2 topics on Pi
        -> odometry / state estimation / logging
```

The Pi-Arduino serial protocol should be small, versioned, bounded, and independently testable. Commands need sequence/validity information and a defined refresh/timeout contract. Telemetry should expose raw encoder counts and fault state so Pi-side behavior can be reproduced from logs.

## Frames and localization

Design around this TF structure:

```text
map
└── odom
    └── base_link
        ├── left_wheel_link
        ├── right_wheel_link
        └── phone_link
            ├── camera_link
            │   └── camera_optical_frame
            └── imu_link
```

- Initially, encoder-derived wheel odometry feeds the Pi-side estimator that publishes `odom -> base_link`.
- Later, Pixel IMU and visual-inertial measurements may be fused with wheel odometry.
- Future SLAM or global localization publishes `map -> odom`.
- Phone, camera, and IMU transforms become fixed extrinsics after the phone mount is finalized and calibrated.
- Pixel samples must carry capture timestamps. The bridge must translate them into the Pi/ROS time domain using an explicit clock-synchronization strategy; network arrival time is not sensor time.



## Current status

Hardware is assembled for Milestone 1:

- Romi chassis assembled.
- Pololu #3543 motor/power board mounted.
- Pololu #3542 encoders installed.
- Arduino UNO R4 and Raspberry Pi mounted on the front/top plate.
- Power bank mounted at the rear.
- ElectroCookie proto shield assembled and mounted on the Arduino.
- Motor/encoder/control wiring, safe startup, low-power open-loop motion, and basic encoder direction/counting have been validated with 6× NiMH cells.
- Battery-voltage divider is implemented on the proto shield.

Pixel is mounted in the front on the top plate, facing display / front camera outwards, upside down at a slight angle so camera can cover both the surface the robot is on and a wide area in front of him.

## Next software work

Recommended implementation order:

1. Safe Arduino startup state.
2. Encoder counting and direction validation.
3. Low-power open-loop motor test.
4. Wheel-speed estimation.
5. Closed-loop per-wheel speed control.
6. Arduino watchdog, bounds, and fault state machine.
7. Versioned USB serial protocol with wheel targets and telemetry.
8. Pi serial transport and `ros2_control` hardware interface.
9. Pi differential-drive control, wheel odometry, and telemetry logging.
10. Laptop/Xbox wireless teleoperation through Pi command arbitration.
11. Wheel geometry, encoder scale, track width, and odometry calibration.
12. Pixel protocol, switchable Wi-Fi/direct-USB transport, time synchronization, sensor bridges, and fixed-frame calibration.

## Design constraints

- Milestone 1 must work without the Pixel or an Internet connection.
- High-level components request bounded base motion; only the Arduino controls PWM.
- Safety does not depend on the Pixel link, Wi-Fi, ROS 2 scheduling, or a remote process.
- Keep the Arduino protocol and Pixel protocol versioned so either endpoint can evolve independently.
- Keep the Pixel protocol independent of its transport; Wi-Fi and direct USB networking must remain selectable without changing application messages.
- Preserve raw, timestamped observations in telemetry; derived state alone is insufficient for debugging and calibration.
- Treat phone visual-inertial pose as an additional measurement, not an unquestioned global pose source.
- Keep future additions compatible with the same boundaries: range sensors, SLAM/localization, Nav2, and higher-level AI planning must feed the Pi integration and arbitration layer rather than the motor driver directly.
