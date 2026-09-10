# Rovbot project
2WD Romi chassis based rover robot, a personal project built to explore robotics, perception, SLAM, high level autonomy, and decision making.

For the currently installed hardware and its specifications, read [Hardware](HARDWARE.md).

### Target architecture
- **Arduino:** Real-time motor control, encoders, watchdogs, and final actuator safety.
- **On-robot Raspberry Pi 4:** Hardware I/O, sensor drivers, telemetry, basic preprocessing/state estimation, command arbitration, and local fail-safe behavior. Hosts **ROS 2 Lyrical Luth** on **Ubuntu Server 26.04 LTS**, which provides a distributed communication layer and robotics infrastructure between onboard and external nodes, plus standard interfaces for sensors, transforms, SLAM, navigation, recording, visualization, and reusable robotics packages.
- **External server:** Heavy SLAM, Nav2, perception, VLM/LLM/VLA models, simulation, and experimental robotics workloads.
- **Custom brain layer:** Combines and processes all sensory inputs; adds mission logic, world state, subsystem routing, manual/autonomous mode handling, and capability orchestration. It also collects and routes information to and from an externally hosted LLM model, performs model/provider selection, and applies configurations, character policies, and personalities.
- **Control center:** A macOS application that will connect to the Pi over WiFi for debugging, configuration, and control. Examples: telemetry, logs, visualizing the robot's decisions in real time, configuring policies/personalities, tuning configurations, and routing an Xbox controller to the robot.

### Design principles
- The Arduino accepts bounded left/right wheel-velocity targets; it remains authoritative for low-level actuation and safety.
- The Pi–Arduino USB serial protocol is defined and implemented on the Arduino: [motor controller README](firmware/motor_controller/README.md). The Pi `ros2_control` interface is not written yet.
- Keep hardware, robotics algorithms, models, and autonomy loosely coupled so components can move between Pi, workstation, and cloud without redesigning the system, and so they can be reused on other robots later.
