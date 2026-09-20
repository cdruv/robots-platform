#!/usr/bin/env python3
"""Short, bounded bench test for the Rovbot Arduino motor controller."""

from __future__ import annotations

import argparse
import sys
import time

try:
    import serial
except ImportError:
    print(
        "pyserial is required; install it in a virtual environment with "
        "'python3 -m pip install pyserial'",
        file=sys.stderr,
    )
    raise SystemExit(2)


BAUD_RATE = 115200
PROTOCOL_VERSION = "2"
SEND_PERIOD_SECONDS = 0.05
MAX_TARGET_MRAD_S = 6000
MAX_TEST_DURATION_SECONDS = 10.0


class MotorLink:
    def __init__(self, port: str) -> None:
        self.serial = serial.Serial(
            port, BAUD_RATE, timeout=0.01, write_timeout=0.1
        )
        self.rx_buffer = bytearray()

    def close(self) -> None:
        self.serial.close()

    def send(self, command: str, *fields: int) -> None:
        parts = [command, PROTOCOL_VERSION, *(str(field) for field in fields)]
        message = (",".join(parts) + "\n").encode("ascii")
        if self.serial.write(message) != len(message):
            raise serial.SerialTimeoutException("incomplete serial command")

    def command_wheels(self, left_mrad_s: int, right_mrad_s: int) -> None:
        self.send("C", left_mrad_s, right_mrad_s)

    def read_available_lines(self) -> list[str]:
        lines: list[str] = []
        waiting = self.serial.in_waiting
        if waiting:
            self.rx_buffer.extend(self.serial.read(waiting))
        while b"\n" in self.rx_buffer:
            raw, _, remainder = self.rx_buffer.partition(b"\n")
            self.rx_buffer = bytearray(remainder)
            lines.append(raw.decode("ascii", errors="replace").strip())
        return lines


def parse_state(line: str) -> tuple[int, int] | None:
    fields = line.split(",")
    if len(fields) != 14 or fields[0:2] != ["T", PROTOCOL_VERSION]:
        return None
    try:
        return int(fields[3]), int(fields[4])
    except ValueError:
        return None


def print_and_check_telemetry(link: MotorLink) -> tuple[int, int] | None:
    latest_state = None
    for line in link.read_available_lines():
        print(line)
        parsed = parse_state(line)
        if parsed is not None:
            latest_state = parsed
    return latest_state


def wait_for_state(
    link: MotorLink, expected_state: int, timeout_seconds: float = 1.0
) -> None:
    deadline = time.monotonic() + timeout_seconds
    next_send = 0.0
    last_state = None
    while time.monotonic() < deadline:
        now = time.monotonic()
        if now >= next_send:
            link.command_wheels(0, 0)
            next_send = now + SEND_PERIOD_SECONDS
        state = print_and_check_telemetry(link)
        if state is not None:
            last_state = state
            if state[0] == expected_state:
                return
            if state[0] == 2 and expected_state == 1:
                raise RuntimeError(f"Arduino entered FAULT; fault bits={state[1]}")
        time.sleep(0.005)
    raise RuntimeError(
        f"Arduino did not reach state {expected_state}; last state/faults={last_state}"
    )


def safe_stop(link: MotorLink) -> None:
    # Try disarming even if the zero command fails. The watchdog is the fallback.
    try:
        link.command_wheels(0, 0)
    finally:
        link.send("D")


def validate_args(args: argparse.Namespace) -> None:
    if not args.wheels_raised:
        raise ValueError("refusing to run without --wheels-raised")
    if not 0.0 < args.duration <= MAX_TEST_DURATION_SECONDS:
        raise ValueError(
            f"duration must be greater than 0 and at most "
            f"{MAX_TEST_DURATION_SECONDS:g} seconds"
        )
    for name in ("left", "right"):
        value = getattr(args, name)
        if not -MAX_TARGET_MRAD_S <= value <= MAX_TARGET_MRAD_S:
            raise ValueError(
                f"{name} target must be between "
                f"{-MAX_TARGET_MRAD_S} and {MAX_TARGET_MRAD_S} mrad/s"
            )


def run(args: argparse.Namespace) -> None:
    validate_args(args)
    link = MotorLink(args.port)
    try:
        # Opening USB serial can reset the Arduino. Keep the driver safe while
        # the board boots and begins publishing telemetry.
        time.sleep(1.5)
        print_and_check_telemetry(link)

        # Discard old status and terminate any partial command left by a prior
        # connection. Confirm DISARMED before accepting a later ARMED sample.
        link.rx_buffer.clear()
        link.serial.reset_input_buffer()
        link.serial.write(b"\n")
        link.send("D")
        link.command_wheels(0, 0)
        if args.clear_faults:
            link.send("F")
        wait_for_state(link, 0)

        link.command_wheels(0, 0)
        link.send("A")
        wait_for_state(link, 1)

        print(
            f"armed; commanding left={args.left}, right={args.right} mrad/s "
            f"for {args.duration:g} seconds"
        )
        deadline = time.monotonic() + args.duration
        next_send = 0.0
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now >= next_send:
                link.command_wheels(args.left, args.right)
                next_send = now + SEND_PERIOD_SECONDS

            state = print_and_check_telemetry(link)
            if state is not None and state[0] == 2:
                raise RuntimeError(f"Arduino entered FAULT; fault bits={state[1]}")
            time.sleep(0.005)
    finally:
        try:
            safe_stop(link)
            print("zero command and disarm requested")
        except (OSError, serial.SerialException) as error:
            print(
                f"could not send the final stop ({error}); "
                "the Arduino command watchdog should stop the motors",
                file=sys.stderr,
            )
        link.close()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, help="Arduino USB serial device")
    parser.add_argument("--left", type=int, default=1000, help="left target, mrad/s")
    parser.add_argument(
        "--right", type=int, default=1000, help="right target, mrad/s"
    )
    parser.add_argument(
        "--duration", type=float, default=1.0, help="bounded test duration"
    )
    parser.add_argument(
        "--clear-faults",
        action="store_true",
        help="request fault clear before arming",
    )
    parser.add_argument(
        "--wheels-raised",
        action="store_true",
        help="confirm that the drive wheels are clear of the ground",
    )
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        run(args)
    except (ValueError, RuntimeError, OSError, serial.SerialException) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
