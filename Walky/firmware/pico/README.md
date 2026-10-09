  # Walky Pico firmware

For the Pico 2 W with MicroPython 1.28, Waveshare Pico Servo Driver,
and standard positional MG90S servos. No GrowBot software or Wi-Fi is used yet.

## Files

`src/` contains the deployable firmware: `main.py` and its modules.
Select `src/` as the firmware source folder in Robot Relay; it remembers the
selection for this robot and uploads changed top-level Python files to the Pico’s filesystem root. Documentation stays here and host
tests stay in `tests/`.

- `main.py`: the entry point MicroPython runs on every boot. It only calls
  `modes.run()`.
- `modes.py`: picks what runs at power-on from `mode.txt`. `MODES` lists every
  request it accepts. Importing it touches no hardware.
- `servo_check.py`: bounded assembly actions, `center` and `sweep`.
- `body.py`: the body controller. Not written yet: it prints and moves nothing.
- `mode.txt` (on the Pico only): what runs at power-on, one of `MODES`.

Robot Relay reads `modes.MODES` from the board to fill its Arm menu, and its
Arm and Disarm write and delete `mode.txt` with the commands below. A new mode
is added to `MODES` here; Relay needs no change.

## Modes

| `mode.txt` | Runs |
| --- | --- |
| missing | Nothing. GP0 and GP1 low, no servo motion. |
| `body` | The body controller, on every power-on. |
| `center` | Center both servos for five seconds, then release. Every power-on. |
| `sweep` | Center, small right sweep, small left sweep, center, release. Every power-on. |
| `once:center`, `once:sweep` | The same action on the next boot only. The file is deleted before any motion. |

A plain mode stays until you delete the file or write another one. Anything
else in the file is deleted and nothing moves.

`center` and `sweep` start with a five-second LED countdown, then one finite
action. The LED goes off at completion and the Pico resets. Neither that
automatic reset nor a watchdog reset restarts a plain mode (checked using RP2
`reset_cause`), so it runs once per power cycle. Power-on-style resets do
restart it: this includes plugging in USB, and potentially brownouts or a
RUN-pin reset. Keep battery power off when connecting USB, and use the
batteries that passed testing; unstable power can restart motion. A soft
restart after a cold boot can also retain the power-on reset cause.

A two-second hardware watchdog resets a stalled program. Cleanup stops PWM
on normal completion, Ctrl-C, or Python exceptions. Removing power is the
immediate stop for a battery-powered test. There is no remote stop interface.

50 Hz PWM uses 1500 microseconds as nominal neutral. `sweep` stays within
1400–1600 microseconds; these are deliberately small pulse changes, not
calibrated angles. The initial move from an unknown shaft position to center
can still be large. Test with the legs/horns off first. No position feedback
exists, and the program cannot detect wiring faults or stalls.

"Release" means PWM is disabled, not that servo supply power is cut or that
the legs must fall freely. Gearing can resist movement with power off.

## Upload, with the bare Pico on USB

Exit `mpremote repl` with Control-X first. Activate the environment created
in the setup instructions:

```sh
source ~/.venvs/pico/bin/activate
```

From this directory:

```sh
mpremote fs ls
mpremote fs cp src/main.py src/modes.py src/servo_check.py src/body.py :
```

This replaces existing files with those names. If a `main.py` you want to keep
is already there, back it up first: `mpremote fs cp :main.py main-backup.py`.
The upload does not arm anything. `mpremote repl` then Enter should show
`>>>`; after `import modes; modes.run()` it should report idle with no motion
(with no `mode.txt`).

## Assemble, still leaving the legs off

Disconnect USB, keep the carrier off, and mount/wire the components.
Robot's left signal: socket 0 / GP0. Right: socket 1 / GP1.
Match G/V/S labels. Servos take power from the carrier supply, not GPIO.
Use the specified battery pack. Leave the carrier off for arming over USB.

## Arm the first battery test

With the carrier OFF, connect the Pico's own USB port. Exit any REPL session,
then run this command. It saves a request; it does not start movement:

```sh
mpremote exec "with open('mode.txt', 'w') as f: f.write('once:sweep')"
```

Unplug USB, keep the shafts clear, then switch on battery power. After the
five-second blinking countdown, both center, the right sweeps, then the left.
About 12 seconds after power-on the LED goes off and signals are released.
Switch off. If movement is wrong, buzzing is strong, or anything gets hot,
switch off immediately. Do not attach legs until the test works.

A `once:` request is for the next boot. Do not issue `mpremote reset` after
arming while the carrier is off: it would consume the request without servo
power. To cancel, or to stop a plain mode, with the carrier off and USB
connected:

```sh
mpremote exec "import os; os.remove('mode.txt')"
```

## Center before fitting the legs

Arm `center` (every power-on) or `once:center`:

```sh
mpremote exec "with open('mode.txt', 'w') as f: f.write('center')"
```

Unplug USB and switch on battery power. Wait for the LED to go off (roughly
10 seconds), then switch off. Without turning the shafts, fit each horn and
leg so the leg is perpendicular to the body; secure the screws. Never force
an actively holding servo. If a shaft moves during fitting, remove the leg
and switch off and on to center again.

## Validation

Host tests use fake MicroPython hardware and time to check no-motion boot,
plain and once: requests, bounded pulses, completion and interruption cleanup.
They do not verify actual PWM waveforms, servo position, or power behavior.
Run: `python3 -m unittest discover -s tests -v`.

API references: [PWM](https://docs.micropython.org/en/v1.28.0/library/machine.PWM.html),
[watchdog](https://docs.micropython.org/en/v1.28.0/library/machine.WDT.html).
