"""Walky Pico entry point. MicroPython runs this file on every boot.

modes.py picks what runs.
"""
import bootlog

try:
    import modes
    modes.run()
except KeyboardInterrupt:
    # Robot Relay interrupting the board over USB; not a fault.
    raise
except BaseException as error:
    bootlog.exception(error)
    raise
