#pragma once

#include <stdint.h>

// Independent speed controller for one wheel; no hardware I/O.
class WheelController {
 public:
  void reset();
  int16_t update(int32_t targetMradS, float measuredMradS, float dtSeconds);

 private:
  float integralPwm = 0.0f;
  int8_t previousTargetSign = 0;
};
