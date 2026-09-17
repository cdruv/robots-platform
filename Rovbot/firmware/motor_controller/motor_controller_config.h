#pragma once

#include <stdint.h>

// Compile-time settings: rebuild and upload the sketch after changing them.
// These are provisional values, shared by both wheels, not a calibrated profile.
// Start with raised wheels and verify encoder signs in both directions. Calibrate
// voltage first, then feed-forward and PI response, then operating limits and
// stall detection. Validate the result under representative load before normal use.
// Record the measurements and test conditions when replacing a provisional value.
namespace MotorConfig {

// Effective ADC reference in millivolts, used to scale the battery reading.
// Before relying on battery protection, compare settled battery_mv telemetry with
// a multimeter across VSW and GND. Adjust using new = old * meter_mv / reported_mv,
// then verify at several voltages. This can absorb divider tolerance; inconsistent
// errors across voltages require investigating the measurement circuit.
constexpr uint32_t ADC_REFERENCE_MV = 5000;

// Base PWM counts added for every nonzero speed request to compensate for friction.
// During initial tuning, measure low-speed PWM/speed pairs and startup behavior
// for each wheel in both directions. Fit this offset with the slope below; verify
// reliable starts without excessive low-speed jumps. Revisit after loaded tests
// if starts are unreliable or low-speed motion is jerky.
constexpr float FEEDFORWARD_STATIC_PWM = 48.0f;

// PWM counts per milliradian/second of requested wheel speed.
// During initial tuning, fit PWM = static_offset + slope * abs(speed) to settled,
// unsaturated PWM/speed measurements, accounting for PI correction when measuring
// applied PWM. Check both wheels/directions and representative battery voltages
// and loads. Revisit if steady operation consistently needs large PI corrections.
constexpr float FEEDFORWARD_PWM_PER_MRAD_S = 0.0065f;

// Proportional correction: PWM counts per milliradian/second of speed error.
// After feed-forward tuning, start with integral action disabled or small and
// apply small speed steps. Increase gradually for faster correction; reduce if
// response oscillates or amplifies encoder noise. Recheck under load and after
// changing the speed filter or control period.
constexpr float KP_PWM_PER_MRAD_S = 0.0030f;

// Integral correction: PWM counts per milliradian of accumulated speed error.
// After proportional tuning, increase gradually until persistent speed error
// clears acceptably. Reduce if recovery overshoots or produces slow oscillation.
// Check starts, stops, reversals, and load changes; output saturation is a limit
// problem and should not be compensated by increasing this gain.
constexpr float KI_PWM_PER_MRAD = 0.0080f;

// Maximum absolute motor output in PWM counts on the existing 0..255 scale.
// The current cap is the highest value exercised by the basic motor test.
// Raise in small steps only after closed-loop response and stop paths work;
// observe speed, current, heating, and battery sag under representative load.
// Revisit the speed limit and stall thresholds whenever this cap changes.
constexpr int16_t MAX_PWM = 96;

// Maximum accepted absolute wheel-speed request in milliradians/second.
// After establishing a usable PWM cap, measure sustained wheel speeds under load
// across the intended battery range. Set below what the slower wheel can reliably
// track, leaving PWM headroom for correction; lower if targets cause sustained
// saturation. Revalidate when the PWM cap or measured operating envelope changes.
constexpr int32_t MAX_TARGET_MRAD_S = 6000;

// Low-voltage fault threshold in millivolts of filtered motor-battery voltage.
// After voltage calibration, validate against the installed NiMH cells' discharge
// guidance and measured loaded voltage/sag. Revisit if normal transients cause
// trips or testing shows the cutoff is too low; consider the existing filtering
// and debounce delay rather than lowering the threshold just to suppress faults.
constexpr uint32_t LOW_BATTERY_MV = 5400;

// Minimum filtered battery millivolts for arming and fault clearing.
// After validating the cutoff, measure voltage rebound when motors stop and set
// this above LOW_BATTERY_MV so a depleted pack's rebound does not permit repeated
// restarts. Recheck when changing the cutoff or if rearming quickly trips again.
constexpr uint32_t BATTERY_RECOVERY_MV = 5800;

// Minimum absolute requested speed (milliradians/second) for stall detection.
// After motor tuning, use low-speed starts and loaded runs to establish where
// reliable movement is expected. Lower only if those speeds start reliably;
// raise if intentional low-speed operation falsely qualifies as a stall.
// Requests below this threshold are not covered by stall detection.
constexpr int32_t STALL_MIN_TARGET_MRAD_S = 1500;

// Maximum absolute filtered speed (milliradians/second) considered stalled.
// Compare stationary encoder noise with the slowest healthy motion covered by
// the target threshold. Choose a value that separates them, then validate with
// brief, controlled obstruction tests. Revisit after speed-filter or scale changes.
constexpr int32_t STALL_MAX_MEASURED_MRAD_S = 250;

// Minimum absolute applied PWM required to consider a wheel stalled.
// After feed-forward/PI tuning, compare healthy startup and loaded-running drive
// with drive during a brief obstruction. Set where meaningful motion is expected;
// keep reachable within MAX_PWM. Revalidate after gain or PWM-cap changes.
constexpr int16_t STALL_MIN_PWM = 80;

// Milliseconds that all three stall conditions must continuously hold to fault.
// Measure normal startup/reversal delays, including speed-filter lag, and allow
// enough margin to avoid false trips. Validate prompt shutdown with a brief,
// mechanically safe obstruction test and an accessible power cutoff; do not hold
// a stalled motor unnecessarily. Revisit after gains or stall thresholds change.
constexpr uint32_t STALL_TIMEOUT_MS = 1200;

}  // namespace MotorConfig
