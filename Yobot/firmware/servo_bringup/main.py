"""Yobot servo bring-up, MicroPython on Raspberry Pi Pico 2 W.

Upload as main.py. Arm the NEXT boot by writing 'test' or 'center' to
bringup_mode.txt. Prefix with repeat: to retain it across power cycles.
"""
import os
import time
from machine import Pin, PWM, WDT, reset, reset_cause, PWRON_RESET

MODE_FILE = "bringup_mode.txt"
SERVO_PINS = (0, 1)  # Robot's left, right; Waveshare sockets 0, 1.
CENTER_US = 1500  # Nominal neutral, not a measured mechanical angle.
MIN_US = 1400
MAX_US = 1600


def release(outputs):
    # Both pins share a PWM slice. Stop both, then explicitly drive low.
    for output in outputs:
        output.deinit()
    for number in SERVO_PINS:
        Pin(number, Pin.OUT, value=0)


def consume_mode():
    try:
        with open(MODE_FILE) as source:
            mode = source.read(32).strip()
    except OSError as error:
        if error.args[0] == 2:  # ENOENT: normal, unarmed boot.
            return None
        raise
    if mode in ("repeat:test", "repeat:center"):
        # RP2 machine.reset() and watchdog expiry both report WDT_RESET.
        # Keep the setting, but never restart motion after either reset.
        if reset_cause() != PWRON_RESET:
            return None
        return mode.split(":", 1)[1]
    os.remove(MODE_FILE)  # Consume one-shot or invalid requests.
    if mode not in ("test", "center"):
        raise ValueError("Use test, center, repeat:test, or repeat:center; request discarded")
    return mode


def wait_ms(duration, watchdog):
    deadline = time.ticks_add(time.ticks_ms(), duration)
    while time.ticks_diff(deadline, time.ticks_ms()) > 0:
        watchdog.feed()
        time.sleep_ms(20)


def write_us(output, pulse):
    if not MIN_US <= pulse <= MAX_US:
        raise ValueError("Pulse outside bring-up limits")
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


def main():
    release(())
    mode = consume_mode()
    if mode is None:
        print("Yobot: idle; servo signals off. No action scheduled for this boot.")
        return

    # RP2350 hardware watchdog resets a stalled interpreter. It cannot be
    # disabled, so successful completion also resets into the unarmed boot.
    watchdog = WDT(timeout=2000)
    outputs = []
    led = None
    try:
        led = Pin("LED", Pin.OUT)
        print("Yobot:", mode, "starts in 5 seconds. Keep shafts clear.")
        for _ in range(10):
            led.toggle()
            wait_ms(500, watchdog)
        led.on()
        for number in SERVO_PINS:
            outputs.append(PWM(Pin(number), freq=50, duty_u16=0))
        for output in outputs:
            write_us(output, CENTER_US)
        wait_ms(1000, watchdog)

        if mode == "test":
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
        # Also handles Ctrl-C/errors. One-shot consumed; repeat mode skips the resulting watchdog reset.
        reset()


if __name__ == "__main__":
    main()
