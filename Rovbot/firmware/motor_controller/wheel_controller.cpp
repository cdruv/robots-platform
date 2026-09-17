#include "wheel_controller.h"

#include <Arduino.h>

#include "motor_controller_config.h"

namespace {
constexpr float INTEGRAL_PWM_LIMIT = 30.0f;
}  // namespace

void WheelController::reset()
{
  integralPwm = 0.0f;
  previousTargetSign = 0;
}

int16_t WheelController::update(int32_t targetMradS, float measuredMradS,
                                float dtSeconds)
{
  if (targetMradS == 0) {
    reset();
    return 0;
  }

  const int8_t targetSign = targetMradS > 0 ? 1 : -1;
  if (previousTargetSign != 0 && targetSign != previousTargetSign) {
    integralPwm = 0.0f;
  }
  previousTargetSign = targetSign;

  const float error = static_cast<float>(targetMradS) - measuredMradS;
  float candidateIntegral =
      integralPwm + MotorConfig::KI_PWM_PER_MRAD * error * dtSeconds;
  candidateIntegral = constrain(candidateIntegral, -INTEGRAL_PWM_LIMIT,
                                INTEGRAL_PWM_LIMIT);

  const float targetMagnitude =
      static_cast<float>(targetMradS > 0 ? targetMradS : -targetMradS);
  const float feedForward =
      static_cast<float>(targetSign) *
      (MotorConfig::FEEDFORWARD_STATIC_PWM +
       MotorConfig::FEEDFORWARD_PWM_PER_MRAD_S * targetMagnitude);

  const float candidateOutput =
      feedForward + MotorConfig::KP_PWM_PER_MRAD_S * error + candidateIntegral;

  // Do not keep integrating farther into saturation. Integration is still
  // allowed when the error would move a saturated output back toward range.
  const bool saturatedHigh = candidateOutput > MotorConfig::MAX_PWM;
  const bool saturatedLow = candidateOutput < -MotorConfig::MAX_PWM;
  if ((!saturatedHigh && !saturatedLow) ||
      (saturatedHigh && error < 0.0f) ||
      (saturatedLow && error > 0.0f)) {
    integralPwm = candidateIntegral;
  }

  float output =
      feedForward + MotorConfig::KP_PWM_PER_MRAD_S * error + integralPwm;
  output = constrain(output, -static_cast<float>(MotorConfig::MAX_PWM),
                     static_cast<float>(MotorConfig::MAX_PWM));
  return static_cast<int16_t>(output >= 0.0f ? output + 0.5f
                                           : output - 0.5f);
}
