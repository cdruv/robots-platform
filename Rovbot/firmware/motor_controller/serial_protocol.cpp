#include "serial_protocol.h"

#include <Arduino.h>
#include <limits.h>
#include <string.h>

namespace SerialProtocol {
namespace {
constexpr uint8_t PROTOCOL_VERSION = 1;
constexpr uint8_t MAX_SERIAL_BYTES_PER_LOOP = 64;

bool parseUnsigned32(const char *text, uint32_t &value)
{
  if (*text == '\0') {
    return false;
  }

  uint32_t result = 0;
  for (const char *p = text; *p != '\0'; ++p) {
    if (*p < '0' || *p > '9') {
      return false;
    }
    const uint32_t digit = static_cast<uint32_t>(*p - '0');
    if (result > (UINT32_MAX - digit) / 10u) {
      return false;
    }
    result = result * 10u + digit;
  }
  value = result;
  return true;
}

bool parseSigned32(const char *text, int32_t &value)
{
  if (*text == '\0') {
    return false;
  }

  bool negative = false;
  if (*text == '-') {
    negative = true;
    ++text;
  }
  if (*text == '\0') {
    return false;
  }

  const uint32_t limit = negative ? 2147483648u : 2147483647u;
  uint32_t magnitude = 0;
  for (const char *p = text; *p != '\0'; ++p) {
    if (*p < '0' || *p > '9') {
      return false;
    }
    const uint32_t digit = static_cast<uint32_t>(*p - '0');
    if (magnitude > (limit - digit) / 10u) {
      return false;
    }
    magnitude = magnitude * 10u + digit;
  }

  if (!negative) {
    value = static_cast<int32_t>(magnitude);
  } else if (magnitude == 2147483648u) {
    value = INT32_MIN;
  } else {
    value = -static_cast<int32_t>(magnitude);
  }
  return true;
}

size_t splitTokens(char *line, char **tokens, size_t maxTokens)
{
  size_t count = 1;
  tokens[0] = line;
  for (char *p = line; *p != '\0'; ++p) {
    if (*p != ',') {
      continue;
    }
    *p = '\0';
    if (count >= maxTokens) {
      return maxTokens + 1;
    }
    tokens[count++] = p + 1;
  }
  return count;
}

bool parseHeader(char **tokens, size_t tokenCount, size_t expectedCount,
                 uint32_t &sequence)
{
  uint32_t version = 0;
  return tokenCount == expectedCount &&
         parseUnsigned32(tokens[1], version) &&
         version == PROTOCOL_VERSION &&
         parseUnsigned32(tokens[2], sequence);
}

Command parseLine(char *line)
{
  Command command;
  char *tokens[6];
  const size_t tokenCount = splitTokens(line, tokens, 6);
  if (tokenCount == 0 || strlen(tokens[0]) != 1) {
    return command;
  }

  switch (tokens[0][0]) {
    case 'C':
      if (parseHeader(tokens, tokenCount, 5, command.sequence) &&
          parseSigned32(tokens[3], command.leftMradS) &&
          parseSigned32(tokens[4], command.rightMradS)) {
        command.type = CommandType::WHEEL_SPEED;
      }
      break;
    case 'A':
    case 'D':
    case 'F':
      if (parseHeader(tokens, tokenCount, 3, command.sequence)) {
        command.type = tokens[0][0] == 'A' ? CommandType::ARM
                       : tokens[0][0] == 'D' ? CommandType::DISARM
                                            : CommandType::CLEAR_FAULTS;
      }
      break;
  }
  return command;
}

}  // namespace

void Receiver::poll(Stream &input, void (*onCommand)(const Command &))
{
  uint8_t processed = 0;
  while (input.available() > 0 && processed < MAX_SERIAL_BYTES_PER_LOOP) {
    ++processed;
    const char c = static_cast<char>(input.read());

    if (c == '\r') {
      continue;
    }

    if (c == '\n') {
      if (rxOverflow) {
        onCommand(Command{});
      } else if (rxLength > 0) {
        rxBuffer[rxLength] = '\0';
        onCommand(parseLine(rxBuffer));
      }
      rxLength = 0;
      rxOverflow = false;
      continue;
    }

    if (rxOverflow) {
      continue;
    }

    if (rxLength >= RX_BUFFER_SIZE - 1) {
      rxOverflow = true;
      continue;
    }
    rxBuffer[rxLength++] = c;
  }
}

void publishBoot(Print &output, const BootInfo &info)
{
  output.print(F("B,"));
  output.print(PROTOCOL_VERSION);
  output.print(',');
  output.print(info.countsPerRev);
  output.print(',');
  output.print(info.controlPeriodMs);
  output.print(',');
  output.print(info.commandTimeoutMs);
  output.print(',');
  output.print(info.maxPwm);
  output.print(',');
  output.println(info.maxTargetMradS);
}

void publishTelemetry(Print &output, const Telemetry &telemetry)
{
  output.print(F("T,"));
  output.print(PROTOCOL_VERSION);
  output.print(',');
  output.print(telemetry.millis);
  output.print(',');
  output.print(telemetry.lastSequence);
  output.print(',');
  output.print(telemetry.state);
  output.print(',');
  output.print(telemetry.faults);
  output.print(',');
  output.print(telemetry.leftCount);
  output.print(',');
  output.print(telemetry.rightCount);
  output.print(',');
  output.print(telemetry.leftTargetMradS);
  output.print(',');
  output.print(telemetry.rightTargetMradS);
  output.print(',');
  output.print(telemetry.leftMeasuredMradS);
  output.print(',');
  output.print(telemetry.rightMeasuredMradS);
  output.print(',');
  output.print(telemetry.leftPwm);
  output.print(',');
  output.print(telemetry.rightPwm);
  output.print(',');
  output.println(telemetry.batteryMv);
}

}  // namespace SerialProtocol
