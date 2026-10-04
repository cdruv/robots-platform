import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import mock_open, patch

SOURCE = Path(__file__).resolve().parents[1] / 'main.py'


class BringupTests(unittest.TestCase):
    def setUp(self):
        self.pulses = []
        self.outputs = []
        self.pins = {}
        self.clock = 0
        self.resets = 0
        self.interrupt = False
        self.cause = 1
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
        spec = importlib.util.spec_from_file_location('bringup', SOURCE)
        self.module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, machine=machine):
            spec.loader.exec_module(self.module)
        self.module.time = types.SimpleNamespace(
            ticks_ms=lambda: owner.clock, ticks_add=lambda a, b: a + b,
            ticks_diff=lambda a, b: a - b, sleep_ms=sleep_ms)

    def run_mode(self, mode):
        with patch('builtins.open', mock_open(read_data=mode)), patch.object(
                self.module.os, 'remove') as remove:
            self.module.main()
            remove.assert_called_once_with('bringup_mode.txt')

    def assert_released(self):
        self.assertTrue(all(p.stopped for p in self.outputs))
        self.assertEqual((self.pins[0], self.pins[1]), (0, 0))
        self.assertEqual(self.resets, 1)

    def test_idle_has_no_pwm_or_reset(self):
        with patch('builtins.open', side_effect=OSError(2, 'missing')):
            self.module.main()
        self.assertEqual(self.outputs, [])
        self.assertEqual(self.resets, 0)

    def test_center_is_finite_and_neutral_only(self):
        self.run_mode('center')
        self.assertEqual(self.pulses, [(0, 1500000), (1, 1500000)])
        self.assertLessEqual(self.clock, 10500)
        self.assert_released()

    def test_sweep_limits_order_and_return_to_center(self):
        self.run_mode('test')
        self.assertTrue(all(1400000 <= v <= 1600000 for _, v in self.pulses))
        movement = [(p, v) for p, v in self.pulses if v != 1500000]
        self.assertEqual(movement[0][0], 1)
        self.assertEqual(movement[-1][0], 0)
        for pin in (0, 1):
            self.assertEqual([v for p, v in self.pulses if p == pin][-1], 1500000)
        self.assertLessEqual(self.clock, 12000)
        self.assert_released()

    def test_interruption_releases_both(self):
        self.interrupt = True
        with self.assertRaises(KeyboardInterrupt):
            self.run_mode('test')
        self.assert_released()

    def test_bad_mode_and_failed_consumption_never_move(self):
        with self.assertRaises(ValueError):
            self.run_mode('walk')
        with patch('builtins.open', mock_open(read_data='test')), patch.object(
                self.module.os, 'remove', side_effect=OSError('read only')):
            with self.assertRaises(OSError):
                self.module.main()
        self.assertEqual(self.outputs, [])

    def test_repeat_runs_again_only_after_power_cycle(self):
        for mode in ("repeat:center", "repeat:test"):
            with self.subTest(mode=mode):
                self.setUp()
                with patch('builtins.open', mock_open(read_data=mode)), patch.object(
                        self.module.os, 'remove') as remove:
                    self.module.main()
                    first_count = len(self.pulses)
                    self.module.main()  # Automatic reset after completion.
                    self.assertEqual(len(self.pulses), first_count)
                    self.cause = 1  # User switches power off then on.
                    self.module.main()
                    self.assertEqual(len(self.pulses), 2 * first_count)
                    remove.assert_not_called()

    def test_repeat_skips_watchdog_boot(self):
        self.cause = 3
        with patch('builtins.open', mock_open(read_data='repeat:center')):
            self.module.main()
        self.assertEqual(self.outputs, [])

    def test_pulse_outside_bounds_rejected(self):
        for value in (1399, 1601):
            with self.assertRaises(ValueError):
                self.module.write_us(None, value)


if __name__ == '__main__':
    unittest.main()
