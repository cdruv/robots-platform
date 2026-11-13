"""A short log in flash that survives resets. Robot Relay reads it over USB.

Logging never raises: a full or broken filesystem must not stop the robot.
"""
import os
import sys

FILE = 'boot.log'
LIMIT = 4096


def write(text):
    try:
        try:
            if os.stat(FILE)[6] > LIMIT:
                try:
                    os.remove(FILE + '.1')
                except OSError:
                    pass
                os.rename(FILE, FILE + '.1')
        except OSError:
            pass
        with open(FILE, 'a') as target:
            target.write(text + '\n')
    except Exception:
        pass


def exception(error):
    try:
        with open(FILE, 'a') as target:
            sys.print_exception(error, target)
    except Exception:
        pass
