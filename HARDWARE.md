# [HARDWARE.md](http://HARDWARE.md)

## System topology

The long-term rover has three compute layers:

```text
Pixel 8  <--Wi-Fi or direct USB networking-->  Raspberry Pi 4  <--USB serial-->  Arduino UNO R4  -->  Pololu #3543  -->  motors
```

- The Pixel 8 is the camera/IMU/audio and co-processing/tracking/AI module.
- The Raspberry Pi 4 is the onboard network and ROS 2 integration computer.
- The Arduino UNO R4 is the real-time wheel-control and safety controller.
- The Pololu #3543 board switches motor power and drives the motors.

The laptop/Xbox controller communicates wirelessly with the Pi for Milestone 1. The Pixel is a later addition and is not required for Milestone 1. Direct Pixel-to-Pi USB networking is being explored, while Wi-Fi remains a supported alternative; the default transport is not yet finalized. The UNO R4's own Wi-Fi is not part of the normal command path.

## Installed hardware

- Google Pixel 8, perception inputs used: IMU, front camera, microphone; communication outputs used: speaker, display.
- Pololu Romi chassis and two extension plates mounted on top.
- Pololu Romi Encoder Pair Kit #3542.
- Pololu Motor Driver and Power Distribution Board #3543.
- Arduino UNO R4 WiFi.
- Raspberry Pi 4 Model B.
- ElectroCookie Arduino proto shield.
- 128 GB microSD.
- 10,000 mAh USB power bank.
- 6× matched NiMH AA cells in the Romi chassis.

## Physical layout

- Pololu #3543 is mounted directly on the Romi chassis above the battery compartment.
- Arduino UNO R4 and Raspberry Pi 4 are mounted on the front/top plate.
- ElectroCookie proto shield is stacked directly on top of the Arduino.
- 10,000 mAh power bank is mounted at the rear of the upper plate.
- Motors and #3542 encoders remain in the standard Romi left/right chassis positions.
- Signal wiring runs between the #3543 and the Arduino/proto shield using jumper wires.
- Pi connects to Arduino over USB for both serial data and Arduino power.
- Pixel is mounted in front (in front of Pi and Arduino), facing frontal camera/display outwards. Display could be used to output debugging information and/or visualizations of robot state.



## Raspberry Pi software platform

- Ubuntu Server 26.04 LTS (Resolute Raccoon), ARM64.
- ROS 2 Lyrical Luth.
- Headless `ros-base` installation.

The Pi runs onboard ROS 2 services without a desktop environment. GUI tools such as RViz run on the development laptop.\

## Data connections

```text
Pixel 8        <--Wi-Fi or direct USB network, versioned protocol-->  Raspberry Pi 4
laptop/teleop  <--Wi-Fi, operator network path--------------------->  Raspberry Pi 4
Raspberry Pi 4 <--USB serial--------------------------------------->  Arduino UNO R4
Arduino UNO R4 --> encoder/control wiring-------------------------->  Pololu #3543
```

The Pixel-to-Pi transport is deliberately switchable. Direct USB networking is being evaluated as a potentially more stable primary link, while Wi-Fi must remain available for fallback, development, or cable-free operation. Both should carry the same transport-independent application protocol. The Wi-Fi topology is also not yet fixed; it may use an existing network or a rover-hosted network. Motion safety must not depend on either Pixel link. The Pi-to-Arduino USB connection is the only normal high-level actuation path.

## Power

Motor domain:

```text
6× NiMH AA → #3543 → motors + encoders
```

Computer domain:

```text
power bank → Raspberry Pi 4 → USB → Arduino UNO R4
```

Pixel domain, initially:

```text
Pixel internal battery → Pixel 8
```

Ground is shared between #3543 and Arduino.

Do not connect #3543 `VREG`, `VBAT`, or `VSW` directly to Arduino/Pi power rails. A direct Pixel-to-Pi USB data connection may also cause the Pi to source charging current to the phone. Before adopting it, verify USB current behavior, the bank's simultaneous-output behavior, continuous current capacity, runtime, connector retention, and Pi undervoltage margin. Do not rely on the Pi to charge the Pixel until that power path has been tested.

Nominal motor-battery voltage with 6× NiMH:

```text
~7.2 V
```



## #3543 ↔ Arduino wiring

```text
ELA   → D2
ELB   → D4
ERA   → D3
ERB   → D7

PWML  → D5
PWMR  → D6

DIRL  → D8
DIRR  → D9

SLP   → D10

GND   ↔ GND
```

`D2` and `D3` are used for encoder-A interrupts. Encoder-B channels are read on `D4` and `D7`.

The #3543 encoder outputs are open-drain and are pulled up to 5 V by the board, so Arduino encoder pins use normal `INPUT`, not `INPUT_PULLUP`.

Factory #3543 solder-jumper configuration is unchanged.

## Motor-control signals

- `PWM`: motor drive magnitude.
- `DIR`: motor direction.
- `SLP LOW`: motor drivers disabled/coasting.
- `SLP HIGH` with PWM active: motor driven.
- `SLP HIGH` with PWM zero: dynamic braking.

Initial software PWM limit should be conservative; planned starting cap is about:

```text
140 / 255
```

The Arduino is the only component that drives these signals. The Pi sends bounded left/right wheel-velocity targets; neither the Pi nor Pixel sends PWM commands.

## Battery-voltage sensing

Implemented on the proto shield:

```text
VSW ── 10 kΩ ──┬── A0
                │
                ├── 4.7 kΩ ── GND
                │
                └── 0.1 µF ── GND
```

Divider ratio:

```text
A0 / VSW = 4.7 / (10 + 4.7)
         ≈ 0.3197
```

Therefore:

```text
A0  ≈ VSW × 0.3197
VSW ≈ A0 × 3.1277
```

