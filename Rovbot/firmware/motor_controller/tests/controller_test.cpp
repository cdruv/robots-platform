#include <cassert>
#include <climits>
#include <iostream>
#include <vector>

#include "../motor_controller.ino"

std::vector<SerialProtocol::Command> commands;
void collect(const SerialProtocol::Command &command) { commands.push_back(command); }

void send(const std::string &text)
{
  Serial.input += text;
  while (Serial.available()) serialReceiver.poll(Serial, processCommand);
}

void tick(uint32_t elapsedMs = 20)
{
  testMillis += elapsedMs;
  controlTick(elapsedMs * 1000);
}

int main()
{
  using Type = SerialProtocol::CommandType;
  SerialProtocol::Receiver receiver;
  Stream input;
  input.input = "C,2,100";
  receiver.poll(input, collect);
  assert(commands.empty());
  input.input += "0,-1000\r\nA,2\nD,2\nF,2\n";
  receiver.poll(input, collect);
  assert(commands.size() == 4);
  assert(commands[0].type == Type::WHEEL_SPEED);
  assert(commands[0].leftMradS == 1000 && commands[0].rightMradS == -1000);
  assert(commands[1].type == Type::ARM);
  assert(commands[2].type == Type::DISARM);
  assert(commands[3].type == Type::CLEAR_FAULTS);

  commands.clear();
  input.input += "C,2,-2147483648,2147483647\n";
  receiver.poll(input, collect);
  assert(commands[0].type == Type::WHEEL_SPEED);
  assert(commands[0].leftMradS == INT32_MIN && commands[0].rightMradS == INT32_MAX);
  for (const char *line : {"C,1,7,0,0\n", "A,1,7\n", "A,2,7\n", "A\n",
                           "C,2,0\n", "C,2,,0\n", "C,2,0,0,1\n",
                           "C,2,2147483648,0\n", "C,2,-2147483649,0\n",
                           "C,2,+1,0\n", "C,2,1x,0\n", "X,2\n"}) {
    commands.clear();
    input.input += line;
    receiver.poll(input, collect);
    assert(commands.size() == 1 && commands[0].type == Type::INVALID);
  }
  commands.clear();
  input.input += std::string(120, 'x') + "\nC,2,0,0\n";
  const auto before = input.offset;
  receiver.poll(input, collect);
  assert(input.offset - before == 64 && commands.empty());
  while (input.available()) receiver.poll(input, collect);
  assert(commands.size() == 2 && commands[0].type == Type::INVALID);
  assert(commands[1].type == Type::WHEEL_SPEED);

  setup();
  for (int i = 0; i < 10; ++i) tick();
  assert(Serial.output.find("T,2,") == 0);
  send("garbage\nA,1,7\nA,2\n");
  assert(controllerState == ControllerState::DISARMED && faultBits == 0);
  assert(!haveWheelCommand && lastWheelCommandMs == 0);
  send("C,2,0,0\n");
  tick(251);
  send("A,2\n");
  assert(controllerState == ControllerState::DISARMED);
  batteryMv = MotorConfig::BATTERY_RECOVERY_MV - 1;
  send("C,2,0,0\nA,2\n");
  assert(controllerState == ControllerState::DISARMED && faultBits == 0);
  batteryMv = 7400;
  send("C,2,0,0\nA,2\nC,2,1000,1000\n");
  tick();
  assert(controllerState == ControllerState::ARMED && testPins[PIN_SLEEP] == HIGH);
  assert(appliedLeftPwm > 0 && appliedRightPwm > 0);
  send("garbage\n");
  assert(controllerState == ControllerState::FAULT && faultBits == FAULT_PROTOCOL);
  assert(testPins[PIN_SLEEP] == LOW && appliedLeftPwm == 0 && appliedRightPwm == 0);
  send("more garbage\nA,2\n");
  assert(controllerState == ControllerState::FAULT && faultBits == FAULT_PROTOCOL);
  send("C,2,1000,1000\nF,2\n");
  assert(controllerState == ControllerState::FAULT);
  send("C,2,0,0\nF,2\nC,2,0,0\nA,2\nC,2,6001,0\n");
  assert(faultBits == FAULT_TARGET_RANGE && testPins[PIN_SLEEP] == LOW);
  send("C,2,0,0\nF,2\nC,2,0,0\nA,2\n");
  for (int i = 0; i < 13; ++i) tick();
  assert(faultBits == FAULT_COMMAND_TIMEOUT && testPins[PIN_SLEEP] == LOW);
  send("C,2,0,0\nF,2\nC,2,0,0\nA,2\nD,2\n");
  assert(controllerState == ControllerState::DISARMED && !haveWheelCommand);

  Print output;
  SerialProtocol::publishTelemetry(output, {1000, 1, 0, 12, -34, 1000, -1000,
                                            900, -950, 54, -55, 7400});
  assert(output.output == "T,2,1000,1,0,12,-34,1000,-1000,900,-950,54,-55,7400\r\n");
  output.output.clear();
  SerialProtocol::publishTelemetry(output, {UINT32_MAX, UINT8_MAX, UINT16_MAX,
      INT32_MIN, INT32_MIN, INT32_MIN, INT32_MIN, INT32_MIN, INT32_MIN,
      INT16_MIN, INT16_MIN, UINT32_MAX});
  assert(output.output.size() <= TELEMETRY_MIN_TX_SPACE);
  Serial.output.clear();
  Serial.txSpace = 0;
  tick();
  assert(Serial.output.empty());
  Serial.txSpace = 256;
  tick();
  assert(Serial.output.find("T,2,") == 0);
  latchFault(FAULT_LEFT_STALL);
  Serial.output.clear();
  tick();
  assert(Serial.output.find(",2,16,") != std::string::npos);
  std::cout << "Parser, controller transitions, telemetry, and TX guard passed\n";
}
