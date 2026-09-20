"""Host-tool checks; no serial port or motor hardware is opened."""

import argparse
import unittest
from unittest.mock import Mock, patch

import motor_serial_test as bench


TELEMETRY = "T,2,1000,1,0,12,-34,1000,-1000,900,-950,54,-55,7400"


class Clock:
    now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class MotorSerialTests(unittest.TestCase):
    def test_wire_format_and_fragmented_telemetry(self):
        port = Mock()
        port.write.side_effect = len
        with patch.object(bench.serial, "Serial", return_value=port):
            link = bench.MotorLink("unused")
        link.command_wheels(1000, -1000)
        for command in ("A", "D", "F"):
            link.send(command)
        self.assertEqual(
            [call.args[0] for call in port.write.call_args_list],
            [b"C,2,1000,-1000\n", b"A,2\n", b"D,2\n", b"F,2\n"],
        )
        for chunk in (TELEMETRY[:12].encode(), (TELEMETRY[12:] + "\r\n").encode()):
            port.in_waiting = len(chunk)
            port.read.return_value = chunk
            lines = link.read_available_lines()
            if not chunk.endswith(b"\n"):
                self.assertEqual(lines, [])
        self.assertEqual(lines, [TELEMETRY])
        self.assertEqual(bench.parse_state(lines[0]), (1, 0))
        for invalid in ("T,1,1000,7,1,0,12,-34,1000,-1000,900,-950,54,-55,7400",
                        TELEMETRY + ",0", TELEMETRY.replace(",1,0,", ",bad,0,")):
            self.assertIsNone(bench.parse_state(invalid))
        port.write.return_value = 1
        port.write.side_effect = None
        with self.assertRaises(bench.serial.SerialTimeoutException):
            link.send("A")

    def test_arm_wait_refreshes_only_zero_targets_and_times_out(self):
        link = Mock()
        clock = Clock()
        with patch.object(bench, "time", clock), patch.object(
            bench, "print_and_check_telemetry", return_value=(0, 0)
        ):
            with self.assertRaisesRegex(RuntimeError, "did not reach state 1"):
                bench.wait_for_state(link, 1, timeout_seconds=0.2)
        self.assertGreaterEqual(link.command_wheels.call_count, 3)
        self.assertTrue(all(call.args == (0, 0)
                            for call in link.command_wheels.call_args_list))

    def test_clear_wait_allows_fault_samples_before_disarmed(self):
        with patch.object(bench, "time", Clock()), patch.object(
            bench, "print_and_check_telemetry", side_effect=[(2, 2), None, (0, 0)]
        ):
            bench.wait_for_state(Mock(), 0)
        with patch.object(bench, "time", Clock()), patch.object(
            bench, "print_and_check_telemetry", return_value=(2, 2)
        ):
            with self.assertRaisesRegex(RuntimeError, "entered FAULT"):
                bench.wait_for_state(Mock(), 1)

    def test_failed_arm_disarms_without_sending_motion(self):
        link = Mock()
        args = argparse.Namespace(port="unused", wheels_raised=True,
                                  duration=0.1, left=1000, right=1000, clear_faults=False)
        with patch.object(bench, "MotorLink", return_value=link), patch.object(
            bench, "time", Clock()
        ), patch.object(bench, "print_and_check_telemetry", return_value=(0, 0)), patch(
            "builtins.print"
        ):
            with self.assertRaisesRegex(RuntimeError, "did not reach state 1"):
                bench.run(args)
        self.assertEqual([call.args[0] for call in link.send.call_args_list], ["D", "A", "D"])
        self.assertTrue(all(call.args == (0, 0)
                            for call in link.command_wheels.call_args_list))
        link.close.assert_called_once()

    def test_motion_waits_for_disarmed_then_armed_telemetry(self):
        link = Mock()
        # An old ARMED sample during startup must not bypass the new arm wait.
        samples = iter([(1, 0), (0, 0), (0, 0), (1, 0)])
        observed = []

        def read_status(_link):
            sample = next(samples, (1, 0))
            observed.append(sample)
            return sample

        def command_wheels(left, right):
            if left or right:
                self.assertGreaterEqual(len(observed), 4)
                self.assertEqual(observed[-1], (1, 0))

        link.command_wheels.side_effect = command_wheels
        args = argparse.Namespace(port="unused", wheels_raised=True,
                                  duration=0.1, left=1000, right=1000, clear_faults=False)
        with patch.object(bench, "MotorLink", return_value=link), patch.object(
            bench, "time", Clock()
        ), patch.object(bench, "print_and_check_telemetry", side_effect=read_status), patch(
            "builtins.print"
        ):
            bench.run(args)
        link.command_wheels.assert_any_call(1000, 1000)
        self.assertEqual(link.send.call_args.args, ("D",))
        link.close.assert_called_once()

    def test_disarm_is_attempted_even_if_zero_write_fails(self):
        link = Mock()
        link.command_wheels.side_effect = bench.serial.SerialException("write failed")
        with self.assertRaises(bench.serial.SerialException):
            bench.safe_stop(link)
        link.send.assert_called_once_with("D")


if __name__ == "__main__":
    unittest.main()
