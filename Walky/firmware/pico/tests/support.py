import os
import sys
import time
import machine

MODULES = ('main', 'modes', 'servo_check', 'body', 'calibration', 'servo_output', 'calibration_session')


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


def fresh():
    # The runner's cwd is a disposable directory, never the source tree.
    for name in os.listdir():
        os.remove(name)
    for name in MODULES:
        sys.modules.pop(name, None)
    machine.clear()
    clock = Clock()
    import servo_check
    import calibration_session
    servo_check.time = clock
    calibration_session.time = clock
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


def ticket():
    return dict(version=1, board='626f617264', session='a' * 32,
                password='b' * 32, ssid='Walky-Cal-test')


class FilesystemFault:
    def __init__(self, operation):
        self.operation = operation

    def __getattr__(self, name):
        return fail if name == self.operation else getattr(os, name)
