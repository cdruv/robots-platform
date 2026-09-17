# Rovbot motor controller

This sketch is the safety and closed-loop control boundary between the
Raspberry Pi and the Pololu motor driver. The Pi requests left and right wheel
speeds; only the Arduino produces PWM and direction signals.

## Safety model

The controller starts with `SLP` low in `DISARMED`. It cannot arm until it has:

- no latched faults;
- an active hardware watchdog;
- enough valid battery samples and a recovered battery voltage;
- a fresh command requesting zero speed on both wheels; and
- a separate valid arm command.

While armed, wheel commands must be refreshed at least every 250 ms. A command
timeout, malformed message, out-of-range target, low battery, encoder stall, or
control-loop overrun latches a fault, writes both PWM outputs to zero, and pulls
`SLP` low. A fault never clears itself.

The hardware watchdog resets the microcontroller if the main loop stops running
for approximately one second. The startup sequence then drives `SLP` low again.
This is a backstop for a wedged sketch; the 250 ms command watchdog is the normal
command-loss protection.

This is not a substitute for an accessible physical motor-power cutoff.

## Serial protocol

The link is ASCII at 115200 baud. Each message is one newline-terminated line.
Carriage returns are ignored. Fields may not contain spaces.

Wheel speeds use integer milliradians per second. Using physical units keeps the
Pi interface stable if the encoder decoder is later changed from 720 counts per
wheel revolution to full quadrature at approximately 1440 counts per revolution.

Commands from the Pi:

```text
C,1,<sequence>,<left_mrad_s>,<right_mrad_s>
A,1,<sequence>
D,1,<sequence>
F,1,<sequence>
```

- `C` updates the bounded wheel-speed request and refreshes the command timer.
- `A` arms only after a fresh zero-speed `C` command.
- `D` disarms immediately. It does not clear an existing fault.
- `F` clears faults only while both requested speeds are zero, the battery has
  recovered, and the hardware watchdog is active.

Sequence values are echoed for correlation. They are deliberately not required
to be monotonic, so a Pi process can restart its sequence at zero without
requiring an Arduino reset.

On boot, the Arduino emits:

```text
B,1,<counts_per_rev>,<control_period_ms>,<command_timeout_ms>,<max_pwm>,<max_target_mrad_s>
```

At 10 Hz it emits:

```text
T,1,<millis>,<last_sequence>,<state>,<faults>,<left_count>,<right_count>,<left_target_mrad_s>,<right_target_mrad_s>,<left_measured_mrad_s>,<right_measured_mrad_s>,<left_pwm>,<right_pwm>,<battery_mv>
```

States:

| Value | State |
| ---: | --- |
| 0 | Disarmed |
| 1 | Armed |
| 2 | Fault |

Fault bits:

| Bit/value | Meaning |
| ---: | --- |
| 0 / 1 | Wheel-command timeout |
| 1 / 2 | Malformed or unsupported protocol message |
| 2 / 4 | Wheel target outside the configured limit |
| 3 / 8 | Low motor-battery voltage |
| 4 / 16 | Left wheel appears stalled |
| 5 / 32 | Right wheel appears stalled |
| 6 / 64 | Control loop was delayed too long |
| 7 / 128 | Hardware watchdog failed to start |

## Host startup sequence

After opening the USB serial port, allow for an Arduino reset and wait for the
`B` line or telemetry. Then:

```text
C,1,1,0,0
A,1,2
C,1,3,1000,1000
C,1,4,1000,1000
...
```

Continue sending `C` commands at 20-50 Hz, including when the target is zero.
To stop normally, send a zero-speed command and then `D`:

```text
C,1,20,0,0
D,1,21
```

After a fault, send a fresh zero command, clear, and arm again only after the
cause has been corrected:

```text
C,1,30,0,0
F,1,31
A,1,32
```

## Values that require hardware calibration

See [motor_controller_config.h](motor_controller_config.h) for calibration and

Before floor operation, compare `battery_mv` with a multimeter and tune each
wheel with the chassis raised. Test forward and reverse step commands separately.
Increase the speed target gradually and verify that measured speed has the same
sign as its target. A sign error makes the controller drive harder in the wrong
direction, so correct wiring/sign conventions before continuing.

## Bench test checklist

1. Keep the wheels off the ground and retain access to motor power.
2. Upload the sketch and verify state `0`, zero PWM, and plausible battery
   voltage.
3. Verify that `A` alone cannot arm.
4. Send zero `C`, then `A`, and verify state `1` with zero PWM.
5. Stream a small target such as `1000,1000` and check speed signs.
6. Stop sending commands and verify a timeout fault within roughly 250 ms.
7. Clear and re-arm, then test `D`, malformed input, an oversized target, and
   unplugging USB.
8. Briefly restrain one raised wheel only if it is mechanically safe, and verify
   the appropriate stall fault. Do not hold a stalled motor unnecessarily.
9. Repeat at low speed on the floor only after all stop paths work.

The bounded host-side test utility automates the zero/arm/refresh/stop sequence:

```sh
python3 -m venv Rovbot/.venv
source Rovbot/.venv/bin/activate
python3 -m pip install -r Rovbot/tools/requirements.txt
python3 Rovbot/tools/motor_serial_test.py \
  --port /dev/cu.usbmodemXXXX \
  --left 1000 --right 1000 --duration 1 \
  --wheels-raised
```

Use `--clear-faults` after correcting a reported fault. The utility refuses to
run without the explicit `--wheels-raised` acknowledgement and limits each run
to ten seconds. It is a bench tool, not the eventual Raspberry Pi service.
