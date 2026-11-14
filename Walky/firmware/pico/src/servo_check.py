"""Walky servo check, MicroPython on Raspberry Pi Pico 2 W.

Bounded actions for assembly: center, opposed full-range sweep, and alignment dance.
Started by modes.py; importing touches no hardware.
"""
import time
from machine import Pin, WDT, reset
import bootlog
import calibration
from servo_output import Servos

ACTIONS = ("center", "sweep", "dance")


def wait_ms(duration, watchdog):
    deadline = time.ticks_add(time.ticks_ms(), duration)
    while time.ticks_diff(deadline, time.ticks_ms()) > 0:
        watchdog.feed()
        time.sleep_ms(20)


def countdown(led, watchdog):
    for _ in range(6):
        led.toggle()
        wait_ms(500, watchdog)


def sweep(servos, watchdog):
    servos.set_degrees(0, 0)
    wait_ms(300, watchdog)
    # Opposite physical directions, so both pulses reach the same end together.
    for left in (-90,) + (90, -90) * 6:
        servos.set_degrees(left, -left)
        wait_ms(460, watchdog)
    servos.set_degrees(0, 0)
    wait_ms(300, watchdog)


def dance(servos, watchdog):
    # Angles are offsets from horizontal (90 degrees on the servo dial).
    peak = servos.span()
    servos.set_degrees(0, 0)
    # Send each destination immediately so the servo sets its own travel speed.
    # Dwell times include travel, with extra time for the full-arc reversal.
    wait_ms(300, watchdog)
    for left, right, dwell_ms in (
        (-45, -45, 200), (45, 45, 260), (0, 0, 160),
        (45, 0, 140), (0, 45, 140), (45, -45, 240), (-45, 45, 240),
        (0, 0, 140), (peak, peak, 240), (-peak, -peak, 460), (0, 0, 300),
        # March: one leg leads and the other follows half a beat later.
        (60, 0, 180), (60, 60, 180), (0, 60, 180), (-60, 0, 200),
        (-60, -60, 180), (0, -60, 180), (60, 0, 200), (0, 0, 180),
        # Double kicks with the opposite leg held at a diagonal.
        (-30, 30, 160), (60, 30, 240), (-30, 30, 240), (60, 30, 240),
        (30, -30, 180), (30, 60, 240), (30, -30, 240), (30, 60, 240),
        # Widen the scissors, then swing the legs through the full split.
        (-30, 30, 180), (45, -45, 200), (-60, 60, 280),
        (peak, -peak, 400), (-peak, peak, 460), (0, 0, 260),
        # Finish in unison with two small rebounds and a bow.
        (30, 30, 140), (-30, -30, 180), (30, 30, 180), (-30, -30, 180),
        (60, 60, 240), (0, 0, 300),
    ):
        servos.set_degrees(left, right)
        wait_ms(dwell_ms, watchdog)


def run(action):
    if action not in ACTIONS:
        raise ValueError("Unknown servo check: " + action)
    # RP2350 hardware watchdog resets a stalled interpreter. It cannot be
    # disabled, so successful completion also resets; modes.py does not
    # restart an action after that reset.
    watchdog = WDT(timeout=2000)
    servos = Servos()
    led = None
    try:
        steps, _, error = calibration.load()
        if error:
            bootlog.write("servo check " + action + ": untrimmed, " + error)
        servos.set_trim(steps)
        led = Pin("LED", Pin.OUT)
        print("Walky:", action, "starts in 3 seconds. Keep shafts clear.")
        countdown(led, watchdog)
        led.on()
        if action == "sweep":
            sweep(servos, watchdog)
        elif action == "dance":
            dance(servos, watchdog)
        else:
            servos.set_degrees(0, 0)
            print("Centering only; releasing in 3 seconds.")
            wait_ms(3000, watchdog)
    except BaseException as error:
        bootlog.write("servo check " + action + ": failed")
        bootlog.exception(error)
        raise
    finally:
        servos.release()
        if led is not None:
            led.off()
        # Also handles Ctrl-C/errors.
        reset()
