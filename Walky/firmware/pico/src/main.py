"""Walky Pico entry point. MicroPython runs this file on every boot.

modes.py picks what runs, from mode.txt.
"""
import bootlog

try:
    import modes
    modes.run()
except BaseException as error:
    bootlog.exception(error)
    raise
