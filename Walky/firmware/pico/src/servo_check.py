"""Walky servo check, MicroPython on Raspberry Pi Pico 2 W.

Bounded actions for assembly: center both servos, or sweep each a little and
return to center. Started by modes.py; importing touches no hardware.
"""
import time
from machine import Pin, WDT, reset
import calibration
from servo_output import Servos, release, CENTER_US, MIN_US, MAX_US

ACTIONS = ("center", "sweep")


def wait_ms(duration, watchdog):
    deadline = time.ticks_add(time.ticks_ms(), duration)
    while time.ticks_diff(deadline, time.ticks_ms()) > 0:
        watchdog.feed()
        time.sleep_ms(20)


def sweep(servos, side, watchdog):
    current = CENTER_US
    excursion = servos.excursion(side)
    for target in (CENTER_US - excursion, CENTER_US + excursion, CENTER_US):
        while current != target:
            current += max(-5, min(5, target - current))
            servos.write(side, current)
            wait_ms(20, watchdog)
        wait_ms(200, watchdog)


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
        print("Walky:", action, "starts in 5 seconds. Keep shafts clear.")
        for _ in range(10):
            led.toggle()
            wait_ms(500, watchdog)
        led.on()
        servos = Servos(steps)
        servos.center()
        wait_ms(1000, watchdog)

        if action == "sweep":
            print("RIGHT / socket 1")
            sweep(servos, 1, watchdog)
            print("LEFT / socket 0")
            sweep(servos, 0, watchdog)
            print("Centered; releasing shortly.")
            wait_ms(1000, watchdog)
        else:
            print("Centering only; releasing in 4 seconds.")
            wait_ms(4000, watchdog)
    finally:
        servos.close() if servos is not None else release()
        if led is not None:
            led.off()
        # Also handles Ctrl-C/errors.
        reset()
