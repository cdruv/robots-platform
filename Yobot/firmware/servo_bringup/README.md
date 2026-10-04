# Yobot servo bring-up

For the Pico 2 W with MicroPython 1.28, Waveshare Pico Servo Driver,
and standard positional MG90S servos. No GrowBot software or Wi-Fi is used.
This is a temporary assembly tool, not the future phone body controller.

## Behavior

- Normal boot: GP0 and GP1 low, no servo motion.
- Explicitly armed boot: five-second LED countdown, then one finite action.
- `test`: nominal center, small right sweep, small left sweep, center, release.
- `center`: nominal center for five seconds, then release. No sweep.
- LED goes off at completion. The Pico resets into idle afterward.
- One-shot requests are consumed before motion. Optional repeat mode retains
  the setting for the next power cycle; see below.
- A two-second hardware watchdog resets a stalled program. Cleanup stops PWM
  on normal completion, Ctrl-C, or Python exceptions. Removing power is the
  immediate stop for a battery-powered test. There is no remote stop interface.

50 Hz PWM uses 1500 microseconds as nominal neutral. The test stays within
1400–1600 microseconds; these are deliberately small pulse changes, not
calibrated angles. The initial move from an unknown shaft position to center
can still be large. Test with the legs/horns off first. No position feedback
exists, and the program cannot detect wiring faults or stalls.

## Upload now, with the bare Pico on USB

Exit `mpremote repl` with Control-X first. Activate the environment created
in the setup instructions:

```sh
source ~/.venvs/pico/bin/activate
```

From this directory:

```sh
mpremote fs ls
mpremote fs cp main.py :main.py
mpremote reset
```

This writes `main.py` and replaces an existing file with that name. If one
already exists, back it up first: `mpremote fs cp :main.py main-backup.py`.
A fresh MicroPython installation need not contain a main.py.
The upload does not arm anything. `mpremote repl` then Enter should show
`>>>`; after `import main; main.main()` it should report idle with no motion.

## Assemble, still leaving the legs off

Disconnect USB, keep the carrier off, and mount/wire the components.
Robot's left signal: socket 0 / GP0. Right: socket 1 / GP1.
Match G/V/S labels. Servos take power from the carrier supply, not GPIO.
Use the specified battery pack. Leave the carrier off for arming over USB.

## Arm the first battery test

With the carrier OFF, connect the Pico's own USB port. Exit any REPL session,
then run this command. It saves a request; it does not start movement:

```sh
mpremote exec "with open('bringup_mode.txt', 'w') as f: f.write('test')"
```

Unplug USB, keep the shafts clear, then switch on battery power. After the
five-second blinking countdown, both center, the right sweeps, then the left.
About 12 seconds after power-on the LED goes off and signals are released.
Switch off. If movement is wrong, buzzing is strong, or anything gets hot,
switch off immediately. Do not attach legs until the test works.

The request is for the next normal boot. Do not issue `mpremote reset` after
arming while the carrier is off: it would consume the test without servo power.
To repeat, explicitly arm again. To cancel before unplugging USB:

```sh
mpremote exec "import os; os.remove('bringup_mode.txt')"
```

## Center before fitting the legs

Repeat the arming procedure using `center`:

```sh
mpremote exec "with open('bringup_mode.txt', 'w') as f: f.write('center')"
```

Unplug USB and switch on battery power. Wait for the LED to go off (roughly
10 seconds), then switch off. Without turning the shafts, fit each horn and
leg so the leg is perpendicular to the body; secure the screws. Never force
an actively holding servo. If a shaft moves during fitting, remove the leg
and repeat centering. Fine trim and range calibration are later steps.

## Validation

Host tests use fake MicroPython hardware and time to check no-motion boot,
one-shot requests, bounded pulses, completion and interruption cleanup.
They do not verify actual PWM waveforms, servo position, or power behavior.
Run: `python3 -m unittest discover -s tests -v`.

API references: [PWM](https://docs.micropython.org/en/v1.28.0/library/machine.PWM.html),
[watchdog](https://docs.micropython.org/en/v1.28.0/library/machine.WDT.html).

## Repeat the same action on every power-on

Upload the updated main.py first. With battery power OFF and USB connected:

```sh
mpremote fs cp main.py :main.py
mpremote exec "with open('bringup_mode.txt', 'w') as f: f.write('repeat:center')"
```

Disconnect USB, then switch battery power on. Center runs once on each power
cycle: five-second countdown, five-second hold, PWM off. Switch off, wait a
few seconds, then on to repeat. Use `repeat:test` for the movement test instead.
To disable, with battery power off and USB connected:

```sh
mpremote exec "import os; os.remove('bringup_mode.txt')"
```

The same file holds both repeat and one-shot settings. Writing `center` or
`test` replaces repeat mode with one-shot mode. A missing file means idle.

The automatic reset after completion and watchdog resets do not restart a
repeat action (checked using RP2 reset_cause). Power-on-style resets do:
this includes plugging in USB, and potentially brownouts or a RUN-pin reset.
Use the batteries that passed testing; unstable power can restart motion.
Keep battery power off when connecting USB. A soft restart after a cold boot
can also retain the power-on reset cause. This is an assembly convenience,
not a way to distinguish a deliberate switch press from every power fault.

"Release" means PWM is disabled, not that servo supply power is cut or that
the legs must fall freely. Gearing can resist movement with power off.
