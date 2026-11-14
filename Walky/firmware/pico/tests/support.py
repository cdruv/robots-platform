import json
import os
import sys
import time
import machine
import network
import socket

MODULES = ('main', 'bootlog', 'modes', 'servo_check', 'body', 'link', 'calibration', 'servo_output')


class Clock:
    def __init__(self):
        self.now = 0

    def ticks_ms(self):
        return self.now

    def ticks_add(self, ticks, delta):
        return time.ticks_add(ticks, delta)

    def ticks_diff(self, first, second):
        return time.ticks_diff(first, second)

    def sleep_ms(self, duration):
        self.now = self.ticks_add(self.now, duration)
        # A loop that a test forgot to end must fail, not hang the suite.
        assert self.now < 3600000, 'fake clock ran for an hour'


def fresh():
    # The runner's cwd is a disposable directory, never the source tree.
    for name in os.listdir():
        os.remove(name)
    for name in MODULES:
        sys.modules.pop(name, None)
    machine.clear()
    socket.clear()
    network.WLAN.active_interfaces = {}
    clock = Clock()
    import servo_check
    import body
    servo_check.time = clock
    body.time = clock
    return clock


def write(name, text):
    with open(name, 'w') as target:
        target.write(text)


def read(name):
    with open(name) as source:
        return source.read()


class raises:
    def __init__(self, exception):
        self.exception = exception

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        if kind is None:
            raise AssertionError('Expected ' + self.exception.__name__)
        return isinstance(value, self.exception)


class replace:
    """Temporary fault injection; no mock framework or runtime emulation."""
    def __init__(self, obj, name, value):
        self.obj, self.name, self.value = obj, name, value

    def __enter__(self):
        self.previous = getattr(self.obj, self.name)
        setattr(self.obj, self.name, self.value)

    def __exit__(self, *args):
        setattr(self.obj, self.name, self.previous)


def fail(*args):
    raise OSError('injected filesystem failure')


def released():
    assert all(output.stopped for output in machine.outputs)
    assert (machine.pins[0], machine.pins[1]) == (0, 0)
    assert machine.resets == 1


def line(**message):
    return (json.dumps(message) + '\n').encode()


class FilesystemFault:
    def __init__(self, operation):
        self.operation = operation

    def __getattr__(self, name):
        return fail if name == self.operation else getattr(os, name)
