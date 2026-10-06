"""Yobot Pico entry point. MicroPython runs this file on every boot.

modes.py picks what runs, from mode.txt.
"""
import modes

modes.run()
