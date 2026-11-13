"""Walky servo check, MicroPython on Raspberry Pi Pico 2 W.

Bounded actions for assembly: center both servos, or sweep each a little and
return to center. Started by modes.py; importing touches no hardware.
"""
import time
from machine import Pin, WDT, reset
import bootlog
import calibration
from servo_output import RangeTestServos, Servos, release, CENTER_US, MIN_US, MAX_US

ACTIONS = ("center", "sweep", "rotation-check", "range-left", "range-right")


def wait_ms(duration, watchdog):
    deadline = time.ticks_add(time.ticks_ms(), duration)
    while time.ticks_diff(deadline, time.ticks_ms()) > 0:
        watchdog.feed()
        time.sleep_ms(20)


def countdown(led, watchdog):
    for _ in range(6):
        led.toggle()
        wait_ms(500, watchdog)


def sweep(servos, side, watchdog):
    current = CENTER_US
    excursion = servos.excursion(side)
    for target in (CENTER_US - excursion, CENTER_US + excursion, CENTER_US):
        while current != target:
            current += max(-5, min(5, target - current))
            servos.write(side, current)
            wait_ms(20, watchdog)
        wait_ms(200, watchdog)


def sweep_full_range(servos, side, watchdog):
    # Express the electrical endpoints in nominal coordinates. The output
    # layer applies the stored trim and validates every resulting pulse.
    trim = calibration.trim_us(servos.steps[side])
    low, high = servos.limits
    current = CENTER_US
    servos.write(side, current)
    wait_ms(500, watchdog)
    for target in (low - trim, high - trim, CENTER_US):
        while current != target:
            current += max(-5, min(5, target - current))
            servos.write(side, current)
            wait_ms(20, watchdog)


def run(action):
    if action not in ACTIONS:
        raise ValueError("Unknown servo check: " + action)
    # RP2350 hardware watchdog resets a stalled interpreter. It cannot be
    # disabled, so successful completion also resets; modes.py does not
    # restart an action after that reset.
    watchdog = WDT(timeout=2000)
    servos = None
    led = None
    try:
        steps, _ = calibration.load()
        led = Pin("LED", Pin.OUT)
        print("Walky:", action, "starts in 3 seconds. Keep shafts clear.")
        countdown(led, watchdog)
        led.on()
        if action in ("range-left", "range-right"):
            servos = RangeTestServos(steps)
            sweep_full_range(servos, 0 if action == "range-left" else 1, watchdog)
            return
        if action == "rotation-check":
            # A positional servo settles. A continuous servo can keep turning.
            # Leave the other output idle, even if its trim would cause creep.
            for side in (1, 0):
                servos = Servos(steps)
                direction = -1 if steps[side] < 0 else 1
                target = CENTER_US + direction * servos.excursion(side)
                servos.write(side, target)
                wait_ms(3000, watchdog)
                servos.close()
                servos = None
                if side == 1:
                    wait_ms(1000, watchdog)
            return
        servos = Servos(steps)
        servos.center()
        if action == "sweep":
            wait_ms(1000, watchdog)
            print("RIGHT / socket 1")
            sweep(servos, 1, watchdog)
            print("LEFT / socket 0")
            sweep(servos, 0, watchdog)
            print("Centered; releasing shortly.")
            wait_ms(1000, watchdog)
        else:
            print("Centering only; releasing in 3 seconds.")
            wait_ms(3000, watchdog)
    except BaseException as error:
        bootlog.write("servo check " + action + ": failed")
        bootlog.exception(error)
        raise
    finally:
        servos.close() if servos is not None else release()
        if led is not None:
            led.off()
        # Also handles Ctrl-C/errors.
        reset()
