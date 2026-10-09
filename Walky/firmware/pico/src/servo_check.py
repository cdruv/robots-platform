"""Walky servo check, MicroPython on Raspberry Pi Pico 2 W.

Bounded actions for assembly: center both servos, or sweep each a little and
return to center. Started by modes.py; importing touches no hardware.
"""
import time
from machine import Pin, PWM, WDT, reset

SERVO_PINS = (0, 1)  # Robot's left, right; Waveshare sockets 0, 1.
CENTER_US = 1500  # Nominal neutral, not a measured mechanical angle.
MIN_US = 1400
MAX_US = 1600
ACTIONS = ("center", "sweep")


def release(outputs):
    # Both pins share a PWM slice. Stop both, then explicitly drive low.
    for output in outputs:
        output.deinit()
    for number in SERVO_PINS:
        Pin(number, Pin.OUT, value=0)


def wait_ms(duration, watchdog):
    deadline = time.ticks_add(time.ticks_ms(), duration)
    while time.ticks_diff(deadline, time.ticks_ms()) > 0:
        watchdog.feed()
        time.sleep_ms(20)


def write_us(output, pulse):
    if not MIN_US <= pulse <= MAX_US:
        raise ValueError("Pulse outside servo check limits")
    output.duty_ns(pulse * 1000)


def sweep(output, watchdog):
    current = CENTER_US
    for target in (MIN_US, MAX_US, CENTER_US):
        step = 5 if target > current else -5
        for pulse in range(current + step, target + step, step):
            write_us(output, pulse)
            wait_ms(20, watchdog)
        current = target
        wait_ms(200, watchdog)


def run(action):
    if action not in ACTIONS:
        raise ValueError("Unknown servo check: " + action)
    # RP2350 hardware watchdog resets a stalled interpreter. It cannot be
    # disabled, so successful completion also resets; modes.py does not
    # restart an action after that reset.
    watchdog = WDT(timeout=2000)
    outputs = []
    led = None
    try:
        led = Pin("LED", Pin.OUT)
        print("Walky:", action, "starts in 5 seconds. Keep shafts clear.")
        for _ in range(10):
            led.toggle()
            wait_ms(500, watchdog)
        led.on()
        for number in SERVO_PINS:
            outputs.append(PWM(Pin(number), freq=50, duty_u16=0))
        for output in outputs:
            write_us(output, CENTER_US)
        wait_ms(1000, watchdog)

        if action == "sweep":
            print("RIGHT / socket 1")
            sweep(outputs[1], watchdog)
            print("LEFT / socket 0")
            sweep(outputs[0], watchdog)
            print("Centered; releasing shortly.")
            wait_ms(1000, watchdog)
        else:
            print("Centering only; releasing in 4 seconds.")
            wait_ms(4000, watchdog)
    finally:
        release(outputs)
        if led is not None:
            led.off()
        # Also handles Ctrl-C/errors.
        reset()
