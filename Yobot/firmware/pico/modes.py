"""What the Pico runs at power-on, chosen by MODE_FILE.

MODE_FILE holds one of MODES. A plain mode runs on every power-on until the
file is deleted; once: runs on the next boot only and deletes the file first.
No file means idle. Importing touches no hardware.
"""
import os
from machine import reset_cause, PWRON_RESET

import body
import servo_check

MODE_FILE = "mode.txt"
# Every request MODE_FILE accepts. Robot Relay reads this to fill its Arm menu.
MODES = ("body", "center", "sweep", "once:center", "once:sweep")


def consume():
    try:
        with open(MODE_FILE) as source:
            mode = source.read(32).strip()
    except OSError as error:
        if error.args[0] == 2:  # ENOENT: normal, unarmed boot.
            return None
        raise
    if mode not in MODES or mode.startswith("once:"):
        os.remove(MODE_FILE)  # Consume one-shot or invalid requests.
        if mode not in MODES:
            raise ValueError("Use one of " + ", ".join(MODES) + "; request discarded")
        return mode.split(":", 1)[1]
    # RP2 machine.reset() and watchdog expiry both report WDT_RESET.
    # Keep the setting, but never restart after either reset.
    if reset_cause() != PWRON_RESET:
        return None
    return mode


def run():
    servo_check.release(())
    mode = consume()
    if mode is None:
        print("Yobot: idle; servo signals off. Nothing runs on this boot.")
    elif mode == "body":
        body.main()
    else:
        servo_check.run(mode)
