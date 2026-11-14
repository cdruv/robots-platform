"""The only PWM output boundary.

Callers give leg angles in degrees from horizontal, where the same sign is the
same physical direction on both legs. The mirror, the persisted trim and the
pulse limits are applied here, once. Importing touches no hardware, and neither
does constructing Servos: the signals start on the first set_degrees.
"""
from machine import Pin, PWM
import calibration

SERVO_PINS = (0, 1)
CENTER_US = 1500
MIN_US = 500
MAX_US = 2500
US_PER_DEG = 11
# The shafts point outward, so the same physical direction needs opposite
# pulse changes on the left and right servo.
MIRROR = (1, -1)


class Servos:
    def __init__(self, steps=(0, 0)):
        self.steps = calibration.validate_steps(steps)
        self.pulses = None
        self._outputs = []

    @property
    def engaged(self):
        return self.pulses is not None

    def set_trim(self, steps):
        """Takes effect on the next set_degrees; never starts a signal."""
        self.steps = calibration.validate_steps(steps)

    def span(self):
        """Largest whole-degree swing both legs reach either way without clipping."""
        centers = [CENTER_US + calibration.trim_us(step) for step in self.steps]
        return min(min(center - MIN_US, MAX_US - center) for center in centers) // US_PER_DEG

    def set_degrees(self, left, right):
        pulses = [
            max(MIN_US, min(MAX_US, CENTER_US + sign * round(degrees * US_PER_DEG) + calibration.trim_us(step)))
            for sign, degrees, step in zip(MIRROR, (left, right), self.steps)
        ]
        try:
            while len(self._outputs) < len(SERVO_PINS):
                self._outputs.append(PWM(Pin(SERVO_PINS[len(self._outputs)]), freq=50, duty_u16=0))
            self.pulses = pulses
            for output, pulse in zip(self._outputs, pulses):
                output.duty_ns(pulse * 1000)
        except BaseException:
            self.release()
            raise

    def release(self):
        """Signals off and both pins driven low. Safe to call in any state."""
        outputs, self._outputs, self.pulses = self._outputs, [], None
        try:
            for output in outputs:
                output.deinit()
        finally:
            for number in SERVO_PINS:
                Pin(number, Pin.OUT, value=0)
