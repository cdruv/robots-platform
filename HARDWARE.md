# HARDWARE.md

## Main hardware

- Pololu Romi chassis.
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
- Pi connects to Arduino over USB.

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

Do not connect #3543 `VREG`, `VBAT`, or `VSW` directly to Arduino/Pi power rails.

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

Examples:

```text
7.2 V VSW → ~2.30 V at A0
8.4 V VSW → ~2.69 V at A0
```

## Useful #3543 labels

- `VBAT`: direct battery voltage.
- `VSW`: switched battery voltage.
- `VREG`: regulated 5 V rail by default.
- `CTRL`: electronic power-switch control.
- `SLP`: motor-driver sleep/enable.

Factory #3543 solder-jumper configuration is unchanged.
