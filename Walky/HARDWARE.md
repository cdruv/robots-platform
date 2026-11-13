# Walky Hardware and Layout

### Physical layout

Walky is fully assembled with the chassis cover installed. Its vertical, rectangular chassis consists of a 3D-printed body shell (163 × 75 × 25 mm) mounted on a slightly larger backplate (168 × 80 × 23 mm), enclosing the electronics. The Pixel 8 is mounted on the front in portrait orientation with its screen facing outward. Only the front camera is accessible.

Two identical legs (84.5 × 21 × 13 mm), each approximately half the chassis height, attach to independent servos on the left and right sides of the body. The servo shafts point outward, forming a horizontal rotation axis across the robot. Each leg swings through a 180° arc behind the robot, opposite the phone screen/face, from pointing down through horizontal at servo center to pointing up.
Walky’s servos can return to neutral under their own power from any shaft orientation set by hand, including positions outside their 180° commanded arc.



### Hardware


| Component  | Specification                                                                                |
| ---------- | -------------------------------------------------------------------------------------------- |
| Phone      | Google Pixel 8: compute, display/face, camera, microphone, speaker, accelerometer, gyroscope |
| Controller | 1× Raspberry Pi Pico 2 W with pre-soldered headers; Wi-Fi link to phone, PWM servo control   |
| Carrier    | 1× Waveshare Pico Servo Driver                                                               |
| Actuators  | 2× Miuzei MG90S metal-gear micro servos, standard 180° positional type                       |
| Power      | 4× rechargeable lithium 1.5V AA cells; low-profile four-AA holder, no cover, bare leads      |




### Notes

- Walky’s Pico 2 W runs [MicroPython v1.29.0](https://micropython.org/resources/firmware/RPI_PICO2_W-20260824-v1.29.0.uf2).
- The Waveshare carrier is a direct GPIO breakout, without a PCA9685/I2C servo
controller. Servo signals: left → GP0/socket 0, right → GP1/socket 1 (robot's
perspective). The Pico generates the PWM.
- Servo power comes from the supply rail, not a Pico GPIO pin; the Pico and servos  
share ground. The servo supply target is 5–6 V with at least 2 A  
available.
