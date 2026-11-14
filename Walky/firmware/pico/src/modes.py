"""What the Pico runs after a reset.

The body link (body.py) runs after every reset. A power-on can run one servo
check first: MODE_FILE holds one of MODES. A plain mode runs on every power-on
until the file is deleted; once: runs on the next power-on only and deletes the
file first. Importing touches no hardware.
"""
import os
from machine import reset_cause, PWRON_RESET

import bootlog
import servo_check
from servo_output import Servos

MODE_FILE = "mode.txt"
# Every request MODE_FILE accepts. Robot Relay reads this to fill its Arm menu.
ACTIONS = servo_check.ACTIONS
MODES = ACTIONS + tuple("once:" + action for action in ACTIONS)


def consume():
    try:
        with open(MODE_FILE) as source:
            mode = source.read(32).strip()
    except OSError as error:
        if error.args[0] == 2:  # ENOENT: nothing armed.
            return None
        raise
    if mode not in MODES or mode.startswith("once:"):
        os.remove(MODE_FILE)  # Consume one-shot or invalid requests.
        if mode not in MODES:
            raise ValueError("Use one of " + ", ".join(MODES) + "; request discarded")
        return mode.split(":", 1)[1]
    return mode


def run():
    Servos().release()
    mode = None
    # A servo check ends in a reset, and RP2 reports machine.reset() and a
    # watchdog expiry alike, so only a real power-on may start one.
    if reset_cause() == PWRON_RESET:
        mode = consume()
        bootlog.write("boot: power-on, " + (mode or "body link"))
    else:
        bootlog.write("boot: reset cause %d, body link" % reset_cause())
    if mode is None:
        import body
        body.run()
    else:
        servo_check.run(mode)
