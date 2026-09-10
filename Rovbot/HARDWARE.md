## System topology

The long-term rover has onboard actuation plus onboard and offboard compute:

```text
                    offboard compute server
                            ^
                            | Wi-Fi, ROS 2
                            v
Sensors -->  Raspberry Pi 4  <--USB serial-->  Arduino UNO R4  -->  Pololu #3543  -->  motors
                            ^
                            | Wi-Fi
                            v
                      control center
```

- The offboard compute server is the remote ROS 2 host for heavy Nav2, SLAM, and ML. It joins the same ROS 2 graph as the Pi.
- The Raspberry Pi 4 is the onboard network and ROS 2 integration computer.
- The Arduino UNO R4 is the real-time wheel-control and safety controller.
- The Pololu #3543 board switches motor power and drives the motors.
- The control center is a macOS operator app that connects to the Pi over Wi-Fi for debug, configuration, telemetry, and teleop (including Xbox).
- The UNO R4's own Wi-Fi is not part of the normal command path.

## Installed hardware

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



## Data connections

```text
offboard workstation       <--Wi-Fi/Ethernet, ROS 2--------------------------->  Raspberry Pi 4
control center             <--Wi-Fi, operator/debug path---------------------->  Raspberry Pi 4
Raspberry Pi 4             <--USB serial-------------------------------------->  Arduino UNO R4
Arduino UNO R4             --> encoder/control wiring------------------------->  Pololu #3543
```

The Wi-Fi topology is not yet fixed; it may use an existing network or a rover-hosted network. The Pi-to-Arduino USB connection is the only normal high-level actuation path.

## Power

Motor domain:

```text
6× NiMH AA → #3543 → motors + encoders
```

Computer domain:

```text
power bank → Raspberry Pi 4 → USB → Arduino UNO R4
```

Ground is shared between #3543 and Arduino.

Do not connect #3543 `VREG`, `VBAT`, or `VSW` directly to Arduino/Pi power rails. Verify USB current behavior, the bank's simultaneous-output behavior, continuous current capacity, runtime, connector retention, and Pi undervoltage margin.

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

With the factory `Btn Jmp` intact, the pushbutton toggles power; the slide switch's On position latches power on and must be returned to Off before the pushbutton can turn it off.

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

The Arduino is the only component that drives these signals. The Pi sends bounded left/right wheel-velocity targets; Pi does not send PWM commands.

Initial open-loop tests validated both motors in both directions and basic encoder counting at PWM 56–96. The test decoder counts both edges of channel A (about 720 counts per wheel revolution); wheel scale and odometry remain uncalibrated.

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

