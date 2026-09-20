#include "serial_protocol.h"

#include <Arduino.h>
#include <limits.h>
#include <string.h>

namespace SerialProtocol {
namespace {
constexpr char PROTOCOL_VERSION[] = "2";
constexpr uint8_t MAX_SERIAL_BYTES_PER_LOOP = 64;

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

bool parseHeader(char **tokens, size_t tokenCount, size_t expectedCount)
{
  return tokenCount == expectedCount &&
         strcmp(tokens[1], PROTOCOL_VERSION) == 0;
}

Command parseLine(char *line)
{
  Command command;
  char *tokens[4];
  const size_t tokenCount = splitTokens(line, tokens, 4);
  if (tokenCount == 0 || strlen(tokens[0]) != 1) {
    return command;
  }

  switch (tokens[0][0]) {
    case 'C':
      if (parseHeader(tokens, tokenCount, 4) &&
          parseSigned32(tokens[2], command.leftMradS) &&
          parseSigned32(tokens[3], command.rightMradS)) {
        command.type = CommandType::WHEEL_SPEED;
      }
      break;
    case 'A':
    case 'D':
    case 'F':
      if (parseHeader(tokens, tokenCount, 2)) {
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

void publishTelemetry(Print &output, const Telemetry &telemetry)
{
  output.print(F("T,"));
  output.print(PROTOCOL_VERSION);
  output.print(',');
  output.print(telemetry.millis);
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
