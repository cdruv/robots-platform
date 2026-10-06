import importlib
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import mock_open, patch

FIRMWARE = Path(__file__).resolve().parents[1]


class FirmwareTests(unittest.TestCase):
    def setUp(self):
        self.pulses = []
        self.outputs = []
        self.pins = {}
        self.clock = 0
        self.resets = 0
        self.interrupt = False
        self.cause = 1
        self.body_runs = 0
        owner = self

        class Pin:
            OUT = 1
            def __init__(self, number, mode=None, value=None):
                self.number = number
                if value is not None:
                    owner.pins[number] = value
            def toggle(self):
                pass
            def on(self):
                owner.pins[self.number] = 1
            def off(self):
                owner.pins[self.number] = 0

        class PWM:
            def __init__(self, pin, freq, duty_u16):
                assert freq == 50 and duty_u16 == 0
                self.number = pin.number
                self.stopped = False
                owner.outputs.append(self)
            def duty_ns(self, value):
                owner.pulses.append((self.number, value))
                if owner.interrupt:
                    raise KeyboardInterrupt
            def deinit(self):
                self.stopped = True

        class WDT:
            def __init__(self, timeout):
                assert timeout == 2000
            def feed(self):
                pass

        def sleep_ms(ms):
            owner.clock += ms
        def reset():
            owner.resets += 1
            owner.cause = 3

        machine = types.SimpleNamespace(Pin=Pin, PWM=PWM, WDT=WDT, reset=reset,
                                        reset_cause=lambda: owner.cause, PWRON_RESET=1)
        sys.path.insert(0, str(FIRMWARE))
        try:
            with patch.dict(sys.modules, machine=machine):
                for name in ('modes', 'servo_check', 'body'):
                    sys.modules.pop(name, None)
                self.modes = importlib.import_module('modes')
                self.check = sys.modules['servo_check']
                self.body = sys.modules['body']
        finally:
            sys.path.remove(str(FIRMWARE))
        self.check.time = types.SimpleNamespace(
            ticks_ms=lambda: owner.clock, ticks_add=lambda a, b: a + b,
            ticks_diff=lambda a, b: a - b, sleep_ms=sleep_ms)

        def body_main():
            owner.body_runs += 1
        self.body.main = body_main

    def boot(self, mode):
        """One boot with mode.txt holding mode; returns the os.remove mock."""
        with patch('builtins.open', mock_open(read_data=mode)), patch.object(
                self.modes.os, 'remove') as remove:
            self.modes.run()
        return remove

    def assert_released(self):
        self.assertTrue(all(p.stopped for p in self.outputs))
        self.assertEqual((self.pins[0], self.pins[1]), (0, 0))
        self.assertEqual(self.resets, 1)

    def test_import_touches_no_hardware(self):
        self.assertEqual((self.outputs, self.pins, self.resets), ([], {}, 0))

    def test_no_file_is_idle(self):
        with patch('builtins.open', side_effect=OSError(2, 'missing')):
            self.modes.run()
        self.assertEqual((self.outputs, self.resets, self.body_runs), ([], 0, 0))
        self.assertEqual((self.pins[0], self.pins[1]), (0, 0))

    def test_modes_lists_exactly_the_accepted_requests(self):
        self.assertEqual(self.modes.MODES,
                         ('body', 'center', 'sweep', 'once:center', 'once:sweep'))
        self.assertEqual(self.modes.MODE_FILE, 'mode.txt')
        for mode in self.modes.MODES:
            with self.subTest(mode=mode), patch('builtins.open', mock_open(read_data=mode)), \
                    patch.object(self.modes.os, 'remove'):
                self.assertEqual(self.modes.consume(), mode.split(':')[-1])

    def test_once_center_is_finite_neutral_and_consumed(self):
        self.boot('once:center').assert_called_once_with('mode.txt')
        self.assertEqual(self.pulses, [(0, 1500000), (1, 1500000)])
        self.assertLessEqual(self.clock, 10500)
        self.assert_released()

    def test_once_sweep_limits_order_and_return_to_center(self):
        self.boot('once:sweep').assert_called_once_with('mode.txt')
        self.assertTrue(all(1400000 <= v <= 1600000 for _, v in self.pulses))
        movement = [(p, v) for p, v in self.pulses if v != 1500000]
        self.assertEqual(movement[0][0], 1)
        self.assertEqual(movement[-1][0], 0)
        for pin in (0, 1):
            self.assertEqual([v for p, v in self.pulses if p == pin][-1], 1500000)
        self.assertLessEqual(self.clock, 12000)
        self.assert_released()

    def test_plain_mode_runs_again_only_after_power_cycle(self):
        for mode in ('center', 'sweep'):
            with self.subTest(mode=mode):
                self.setUp()
                self.boot(mode).assert_not_called()
                first_count = len(self.pulses)
                self.assertGreater(first_count, 0)
                self.boot(mode)  # Automatic reset after completion.
                self.assertEqual(len(self.pulses), first_count)
                self.cause = 1  # User switches power off then on.
                self.boot(mode).assert_not_called()
                self.assertEqual(len(self.pulses), 2 * first_count)

    def test_body_starts_on_every_power_on_but_not_after_a_reset(self):
        self.boot('body').assert_not_called()
        self.assertEqual((self.body_runs, self.outputs), (1, []))
        self.cause = 3  # Watchdog or machine.reset().
        self.boot('body')
        self.assertEqual(self.body_runs, 1)
        self.cause = 1
        self.boot('body')
        self.assertEqual(self.body_runs, 2)

    def test_plain_mode_skips_watchdog_boot(self):
        self.cause = 3
        self.boot('center')
        self.assertEqual(self.outputs, [])

    def test_interruption_releases_both(self):
        self.interrupt = True
        with self.assertRaises(KeyboardInterrupt):
            self.boot('once:sweep')
        self.assert_released()

    def test_bad_mode_and_failed_consumption_never_move(self):
        for mode in ('walk', 'once:walk', 'once:body', 'test', 'repeat:center'):
            with self.subTest(mode=mode):
                with patch('builtins.open', mock_open(read_data=mode)), patch.object(
                        self.modes.os, 'remove') as remove:
                    with self.assertRaises(ValueError):
                        self.modes.run()
                    remove.assert_called_once_with('mode.txt')
        with patch('builtins.open', mock_open(read_data='once:sweep')), patch.object(
                self.modes.os, 'remove', side_effect=OSError('read only')):
            with self.assertRaises(OSError):
                self.modes.run()
        self.assertEqual((self.outputs, self.body_runs), ([], 0))

    def test_main_py_only_runs_modes(self):
        calls = []
        fake = types.SimpleNamespace(run=lambda: calls.append('run'))
        spec = importlib.util.spec_from_file_location('launcher', FIRMWARE / 'main.py')
        with patch.dict(sys.modules, modes=fake):
            spec.loader.exec_module(importlib.util.module_from_spec(spec))
        self.assertEqual(calls, ['run'])

    def test_pulse_outside_bounds_rejected(self):
        for value in (1399, 1601):
            with self.assertRaises(ValueError):
                self.check.write_us(None, value)


if __name__ == '__main__':
    unittest.main()
