#pragma once

#include <Arduino.h>
#include <stddef.h>
#include <stdint.h>

namespace SerialProtocol {

enum class CommandType : uint8_t {
  INVALID,
  WHEEL_SPEED,
  ARM,
  DISARM,
  CLEAR_FAULTS,
};

struct Command {
  CommandType type = CommandType::INVALID;
  int32_t leftMradS = 0;
  int32_t rightMradS = 0;
};

// Parsing reports requests only. The caller owns limits, arming, and faults.
class Receiver {
 public:
  // Processes at most 64 bytes per call. Delivers each complete nonempty line
  // immediately, including INVALID for malformed or overlong messages.
  void poll(Stream &input, void (*onCommand)(const Command &));

 private:
  static constexpr size_t RX_BUFFER_SIZE = 96;
  char rxBuffer[RX_BUFFER_SIZE];
  size_t rxLength = 0;
  bool rxOverflow = false;
};

struct Telemetry {
  uint32_t millis;
  uint8_t state;
  uint16_t faults;
  int32_t leftCount;
  int32_t rightCount;
  int32_t leftTargetMradS;
  int32_t rightTargetMradS;
  int32_t leftMeasuredMradS;
  int32_t rightMeasuredMradS;
  int16_t leftPwm;
  int16_t rightPwm;
  uint32_t batteryMv;
};

void publishTelemetry(Print &output, const Telemetry &telemetry);

}  // namespace SerialProtocol
