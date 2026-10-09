"""The only PWM output boundary. Targets are nominal, trim is applied once."""
from machine import Pin, PWM
import calibration

SERVO_PINS = (0, 1)
CENTER_US = 1500
MIN_US = 1400
MAX_US = 1600


def release(outputs=()):
    try:
        for output in outputs:
            output.deinit()
    finally:
        for number in SERVO_PINS:
            Pin(number, Pin.OUT, value=0)


def pulse_us(nominal, step):
    if type(nominal) is not int:
        raise ValueError('Pulse target must be integer microseconds')
    pulse = nominal + calibration.trim_us(step)
    if not MIN_US <= pulse <= MAX_US:
        raise ValueError('Calibrated pulse outside servo limits')
    return pulse


class Servos:
    def __init__(self, steps):
        self.steps = calibration.validate_steps(steps)
        self.outputs = []
        try:
            for number in SERVO_PINS:
                self.outputs.append(PWM(Pin(number), freq=50, duty_u16=0))
        except BaseException:
            self.close()
            raise

    def write(self, side, nominal):
        self.outputs[side].duty_ns(pulse_us(nominal, self.steps[side]) * 1000)

    def center(self, steps=None):
        steps = self.steps if steps is None else calibration.validate_steps(steps)
        pulses = [pulse_us(CENTER_US, step) for step in steps]
        self.steps = steps
        for output, pulse in zip(self.outputs, pulses):
            output.duty_ns(pulse * 1000)
        return pulses

    def excursion(self, side):
        return 100 - abs(calibration.trim_us(self.steps[side]))

    def close(self):
        release(self.outputs)
