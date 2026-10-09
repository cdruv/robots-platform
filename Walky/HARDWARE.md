# Walky Hardware

| Component | Specification |
| --- | --- |
| Phone | Google Pixel 8: compute, display/face, camera, microphone, speaker, accelerometer, gyroscope |
| Controller | 1× Raspberry Pi Pico 2 W with pre-soldered headers; Wi-Fi link to phone, PWM servo control |
| Carrier | 1× Waveshare Pico Servo Driver |
| Actuators | 2× Miuzei MG90S metal-gear micro servos, standard 180° positional type |
| Power | 4× rechargeable lithium AA cells; low-profile four-AA holder, no cover, bare leads |
| Structure | PLA prints: 1× backplate, 1× body, 2× legs with servo-horn slots |
| Mounting | Servo horns/screws, double-sided foam tape, rubber bands for the phone |

The Waveshare carrier is a direct GPIO breakout, without a PCA9685/I2C servo
controller. Servo signals: left → GP0/socket 0, right → GP1/socket 1 (robot's
perspective). The Pico generates the PWM.

Servo power comes from the supply rail, not a Pico GPIO pin; the Pico and servos
share ground. The servo supply target is 5–6 V with at least 2 A
available. AA-sized 3.7 V 14500 cells are electrically different from the
specified AA cells.

The leg's mechanical neutral is 90°. Phone orientation, leg geometry, and servo
spacing are part of the physical configuration used when training a gait.
