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

Malformed or unsupported messages are ignored while disarmed or faulted. They
never update targets, refresh the command timer, or clear an existing fault.

The hardware watchdog resets the microcontroller if the main loop stops running
for approximately one second. The startup sequence then drives `SLP` low again.
This is a backstop for a wedged sketch; the 250 ms command watchdog is the normal
command-loss protection.

This is not a substitute for an accessible physical motor-power cutoff.

## Serial protocol

The link is ASCII at 115200 baud. Each message is one newline-terminated line.
Carriage returns are ignored. Fields may not contain spaces.

The current protocol is version **2**. Version 1 commands and telemetry are not
compatible: update the firmware and host tool together.

Wheel speeds use integer milliradians per second. Using physical units keeps the
Pi interface stable if the encoder decoder is later changed from 720 counts per
wheel revolution to full quadrature at approximately 1440 counts per revolution.

Commands from the Pi:

```text
C,2,<left_mrad_s>,<right_mrad_s>
A,2
D,2
F,2
```

- `C` updates the bounded wheel-speed request and refreshes the command timer.
- `A` arms only after a fresh zero-speed `C` command.
- `D` disarms immediately. It does not clear an existing fault.
- `F` clears faults only while both requested speeds are zero, the battery has
  recovered, and the hardware watchdog is active.

Commands have no sequence numbers or individual acknowledgements. The host
observes state changes in telemetry and uses a bounded timeout for arming and
fault clearing. It continues sending zero targets while waiting.

The Arduino emits telemetry at 50 Hz, beginning with the first control tick:

```text
T,2,<millis>,<state>,<faults>,<left_count>,<right_count>,<left_target_mrad_s>,<right_target_mrad_s>,<left_measured_mrad_s>,<right_measured_mrad_s>,<left_pwm>,<right_pwm>,<battery_mv>
```

Telemetry is published from the end of the control tick, not from a timer of its
own, so one frame reports the counts, speeds and PWM of one 20 ms tick. Frames are
emitted in every state, including `DISARMED` and `FAULT`. `TELEMETRY_DECIMATION` in
the sketch publishes every Nth tick: `1` is 50 Hz and `5` is 10 Hz.

On the UNO R4 WiFi, `Serial` uses a synchronous UART to the USB bridge, without
hardware flow control. The installed Renesas core 1.6.0 reports zero from
`availableForWrite()` even when it can transmit, so telemetry must not use that
value as a readiness check. A frame is at most 160 bytes; at 115200 baud its wire
time is under 14 ms of the 20 ms tick. Hardware timing still needs bench verification.
Native USB builds retain the whole-frame transmit-space check and skip a frame
when the FIFO lacks room. Telemetry can be lost in transport; cumulative counts
preserve net wheel travel across skipped frames, but intermediate speed, PWM,
and state samples can be lost.

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
| 1 / 2 | Malformed or unsupported protocol message while armed |
| 2 / 4 | Wheel target outside the configured limit |
| 3 / 8 | Low motor-battery voltage |
| 4 / 16 | Left wheel appears stalled |
| 5 / 32 | Right wheel appears stalled |
| 6 / 64 | Control loop was delayed too long |
| 7 / 128 | Hardware watchdog failed to start |

## Host startup sequence

After opening the USB serial port, allow for an Arduino reset. Drain old input,
send a newline to terminate any partial command, and send `D,2`. Wait for fresh
telemetry showing `DISARMED` before arming; report an existing fault instead of
automatically clearing it. Valid telemetry establishes the connection.

Then arm with:

```text
C,2,0,0
A,2
```

Keep sending `C,2,0,0` at 20–50 Hz while waiting for fresh telemetry showing
`ARMED`. Only then send nonzero targets such as `C,2,1000,1000`. On a fault or a
one-second arm timeout, send `D,2` and report the last observed state and faults.
A timeout does not distinguish a refused command from a communication failure,
and fault bits do not describe every possible arm precondition.

Continue sending `C` commands at 20-50 Hz, including when the target is zero.
To stop normally, send a zero-speed command and then `D`:

```text
C,2,0,0
D,2
```

After a fault, send a fresh zero command, clear, and arm again only after the
cause has been corrected:

```text
C,2,0,0
F,2
```

Continue streaming zeros and wait for `DISARMED`, then repeat the arm procedure.
`F` is refused while either requested target is nonzero. A fault discards the
stored wheel command, so `A` needs a zero `C` after the fault and no more than
250 ms before arming. If clearing times out, report failure and remain stopped.

## Values that require hardware calibration

See [motor_controller_config.h](motor_controller_config.h) for the values that
depend on the installed hardware: the ADC reference used to scale battery
readings, the feed-forward and PI gains, the PWM and wheel-speed limits, the
low-battery and recovery thresholds, and the stall-detection thresholds and
timeout. Each constant documents how to measure its replacement. They are
compile-time settings shared by both wheels, so rebuild and upload after a change.

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
7. Verify malformed input leaves a disarmed controller disarmed. Clear and
   re-arm, then test `D`, malformed input while armed, an oversized target, and
   unplugging USB. Malformed input must not clear a latched fault.
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

## Host checks without hardware

From the repository root, with the Python environment above activated:

```sh
c++ -std=c++17 -Wall -Wextra -Werror \
  -I Rovbot/firmware/motor_controller/tests \
  Rovbot/firmware/motor_controller/tests/controller_test.cpp \
  Rovbot/firmware/motor_controller/serial_protocol.cpp \
  Rovbot/firmware/motor_controller/wheel_controller.cpp \
  -o /tmp/rovbot-controller-test
/tmp/rovbot-controller-test
c++ -std=c++17 -Wall -Wextra -Werror -DARDUINO_UNOR4_WIFI -DNO_USB \
  -I Rovbot/firmware/motor_controller/tests \
  Rovbot/firmware/motor_controller/tests/controller_test.cpp \
  Rovbot/firmware/motor_controller/serial_protocol.cpp \
  Rovbot/firmware/motor_controller/wheel_controller.cpp \
  -o /tmp/rovbot-controller-wifi-test
/tmp/rovbot-controller-wifi-test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s Rovbot/tools -p 'test_*.py'
```

The C++ checks run the real parser and sketch against a small Arduino shim. The
Python checks use mocked serial I/O and time. They cover protocol compatibility,
fragmented input, state transitions, timeouts, and telemetry formatting; they do
not replace the wheels-raised checks of actual USB timing and motor behavior.
