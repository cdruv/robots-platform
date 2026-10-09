"""Hardware only: built-ins, JSON, filesystem, and imports remain MicroPython's."""
PWRON_RESET = 1


def clear():
    global pulses, outputs, pins, resets, cause, interrupt
    pulses, outputs, pins = [], [], {}
    resets, cause, interrupt = 0, PWRON_RESET, False


clear()


class Pin:
    OUT = 1

    def __init__(self, number, mode=None, value=None):
        self.number = number
        if value is not None:
            pins[number] = value

    def toggle(self):
        pins[self.number] = 1 - pins.get(self.number, 0)

    def on(self):
        pins[self.number] = 1

    def off(self):
        pins[self.number] = 0


class PWM:
    def __init__(self, pin, freq, duty_u16):
        assert freq == 50 and duty_u16 == 0
        self.number, self.stopped = pin.number, False
        outputs.append(self)

    def duty_ns(self, value):
        pulses.append((self.number, value))
        if interrupt:
            raise KeyboardInterrupt

    def deinit(self):
        self.stopped = True


class WDT:
    def __init__(self, timeout):
        assert timeout == 2000

    def feed(self):
        pass


def unique_id():
    return b'board'


def reset_cause():
    return cause


def reset():
    global resets, cause
    resets += 1
    cause = 3
