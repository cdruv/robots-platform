#include <Arduino.h>
#include <WDT.h>

#include "motor_controller_config.h"
#include "serial_protocol.h"
#include "wheel_controller.h"

#include <stdint.h>

// Pololu #3543 motor driver and #3542 encoder connections.
constexpr uint8_t PIN_ENC_L_A = 2;
constexpr uint8_t PIN_ENC_L_B = 4;
constexpr uint8_t PIN_ENC_R_A = 3;
constexpr uint8_t PIN_ENC_R_B = 7;

constexpr uint8_t PIN_PWM_L = 5;
constexpr uint8_t PIN_PWM_R = 6;
constexpr uint8_t PIN_DIR_L = 8;
constexpr uint8_t PIN_DIR_R = 9;
constexpr uint8_t PIN_SLEEP = 10;
constexpr uint8_t PIN_BATTERY = A0;

// This decoder interrupts on both edges of channel A and samples channel B.
// That is half of the encoder's full-quadrature resolution (~1440 counts/rev).
constexpr int32_t ENCODER_COUNTS_PER_WHEEL_REV = 720;

constexpr uint32_t CONTROL_PERIOD_US = 20000;       // 50 Hz
constexpr uint32_t MAX_CONTROL_INTERVAL_US = 100000;
constexpr uint32_t COMMAND_TIMEOUT_MS = 250;
constexpr uint32_t PREARM_COMMAND_MAX_AGE_MS = 250;
constexpr uint32_t HARDWARE_WATCHDOG_MS = 1000;

// Telemetry is published from the end of controlTick() instead of from its own
// timer, so one frame always describes one tick. Publishing every tick gives the
// host enough frames to observe a sequence echo inside the arm handshake's quiet
// window, and an independent link-failure signal faster than the 250 ms command
// watchdog. 1 => 50 Hz, 2 => 25 Hz, 5 => 10 Hz.
constexpr uint8_t TELEMETRY_DECIMATION = 1;
static_assert(TELEMETRY_DECIMATION >= 1, "TELEMETRY_DECIMATION must be >= 1");

// Free space required in the USB CDC transmit FIFO before a frame is started.
// The FIFO is 256 bytes and the longest possible telemetry line is 134, so this
// threshold both fits a worst-case frame and leaves headroom. It matters because
// the core's write() spins until the host drains once the FIFO is full: a host
// that stops reading would stall loop() and trip FAULT_CONTROL_OVERRUN or the
// hardware watchdog. Dropping a status frame is correct; stalling control is not.
constexpr int TELEMETRY_MIN_TX_SPACE = 160;

constexpr int32_t TARGET_DEADBAND_MRAD_S = 100;

constexpr float SPEED_FILTER_ALPHA = 0.35f;

// A0 conversion for the installed 10k/4.7k divider and 12-bit ADC.
// The calibrated voltage scale and thresholds live in motor_controller_config.h.
constexpr uint32_t ADC_MAX_READING = 4095;
constexpr uint32_t BATTERY_R_HIGH_OHMS = 10000;
constexpr uint32_t BATTERY_R_LOW_OHMS = 4700;
constexpr bool ENABLE_LOW_BATTERY_FAULT = true;
constexpr uint32_t LOW_BATTERY_DEBOUNCE_MS = 500;
constexpr uint8_t BATTERY_SAMPLES_BEFORE_ARM = 10;

enum class ControllerState : uint8_t {
  DISARMED = 0,
  ARMED = 1,
  FAULT = 2,
};

enum Fault : uint16_t {
  FAULT_NONE = 0,
  FAULT_COMMAND_TIMEOUT = 1u << 0,
  FAULT_PROTOCOL = 1u << 1,
  FAULT_TARGET_RANGE = 1u << 2,
  FAULT_LOW_BATTERY = 1u << 3,
  FAULT_LEFT_STALL = 1u << 4,
  FAULT_RIGHT_STALL = 1u << 5,
  FAULT_CONTROL_OVERRUN = 1u << 6,
  FAULT_HARDWARE_WATCHDOG = 1u << 7,
};

volatile int32_t encoderLeft = 0;
volatile int32_t encoderRight = 0;

ControllerState controllerState = ControllerState::DISARMED;
uint16_t faultBits = FAULT_NONE;

int32_t requestedLeftMradS = 0;
int32_t requestedRightMradS = 0;
float measuredLeftMradS = 0.0f;
float measuredRightMradS = 0.0f;
int16_t appliedLeftPwm = 0;
int16_t appliedRightPwm = 0;

bool haveWheelCommand = false;
uint32_t lastWheelCommandMs = 0;
uint32_t lastReceivedSequence = 0;

uint32_t batteryMv = 0;
uint8_t batterySampleCount = 0;
bool lowBatteryTiming = false;
uint32_t lowBatterySinceMs = 0;

bool leftStallTiming = false;
bool rightStallTiming = false;
uint32_t leftStallSinceMs = 0;
uint32_t rightStallSinceMs = 0;

bool hardwareWatchdogActive = false;

SerialProtocol::Receiver serialReceiver;

uint32_t lastControlUs = 0;
uint8_t telemetryTickCount = 0;
int32_t previousLeftCount = 0;
int32_t previousRightCount = 0;

WheelController leftController;
WheelController rightController;

void onLeftEncoderA()
{
  encoderLeft +=
      digitalRead(PIN_ENC_L_A) == digitalRead(PIN_ENC_L_B) ? 1 : -1;
}

void onRightEncoderA()
{
  encoderRight +=
      digitalRead(PIN_ENC_R_A) == digitalRead(PIN_ENC_R_B) ? 1 : -1;
}

void readEncoderCounts(int32_t &left, int32_t &right)
{
  noInterrupts();
  left = encoderLeft;
  right = encoderRight;
  interrupts();
}

void writeMotor(uint8_t pwmPin, uint8_t directionPin, int16_t pwm)
{
  pwm = constrain(pwm, -MotorConfig::MAX_PWM, MotorConfig::MAX_PWM);
  digitalWrite(directionPin, pwm < 0 ? HIGH : LOW);
  analogWrite(pwmPin, pwm < 0 ? -pwm : pwm);
}

void disableMotorDriver()
{
  analogWrite(PIN_PWM_L, 0);
  analogWrite(PIN_PWM_R, 0);
  digitalWrite(PIN_DIR_L, LOW);
  digitalWrite(PIN_DIR_R, LOW);
  digitalWrite(PIN_SLEEP, LOW);
  appliedLeftPwm = 0;
  appliedRightPwm = 0;
}

void resetControlState()
{
  requestedLeftMradS = 0;
  requestedRightMradS = 0;
  leftController.reset();
  rightController.reset();
  leftStallTiming = false;
  rightStallTiming = false;
}

void latchFault(uint16_t fault)
{
  faultBits |= fault;
  controllerState = ControllerState::FAULT;
  haveWheelCommand = false;
  resetControlState();
  disableMotorDriver();
}

void disarm()
{
  haveWheelCommand = false;
  resetControlState();
  disableMotorDriver();
  controllerState = faultBits == FAULT_NONE ? ControllerState::DISARMED
                                            : ControllerState::FAULT;
}

uint32_t readBatteryMillivolts()
{
  const uint32_t raw = analogRead(PIN_BATTERY);
  const uint64_t numerator =
      static_cast<uint64_t>(raw) * MotorConfig::ADC_REFERENCE_MV *
      (BATTERY_R_HIGH_OHMS + BATTERY_R_LOW_OHMS);
  const uint64_t denominator =
      static_cast<uint64_t>(ADC_MAX_READING) * BATTERY_R_LOW_OHMS;
  return static_cast<uint32_t>((numerator + denominator / 2) / denominator);
}

void updateBattery(uint32_t nowMs)
{
  const uint32_t sampleMv = readBatteryMillivolts();
  if (batterySampleCount == 0) {
    batteryMv = sampleMv;
  } else {
    batteryMv = (batteryMv * 7u + sampleMv) / 8u;
  }
  if (batterySampleCount < BATTERY_SAMPLES_BEFORE_ARM) {
    ++batterySampleCount;
  }

  if (!ENABLE_LOW_BATTERY_FAULT ||
      controllerState != ControllerState::ARMED) {
    lowBatteryTiming = false;
    return;
  }

  if (batteryMv >= MotorConfig::LOW_BATTERY_MV) {
    lowBatteryTiming = false;
    return;
  }

  if (!lowBatteryTiming) {
    lowBatteryTiming = true;
    lowBatterySinceMs = nowMs;
  } else if (static_cast<uint32_t>(nowMs - lowBatterySinceMs) >=
             LOW_BATTERY_DEBOUNCE_MS) {
    latchFault(FAULT_LOW_BATTERY);
  }
}

void checkStall(int32_t targetMradS, float measuredMradS, int16_t pwm,
                uint16_t fault, bool &timing, uint32_t &sinceMs,
                uint32_t nowMs)
{
  const int32_t targetMagnitude = targetMradS >= 0 ? targetMradS
                                                   : -targetMradS;
  const float measuredMagnitude = measuredMradS >= 0.0f ? measuredMradS
                                                        : -measuredMradS;
  const int16_t pwmMagnitude = pwm >= 0 ? pwm : -pwm;
  const bool looksStalled =
      targetMagnitude >= MotorConfig::STALL_MIN_TARGET_MRAD_S &&
      measuredMagnitude <= MotorConfig::STALL_MAX_MEASURED_MRAD_S &&
      pwmMagnitude >= MotorConfig::STALL_MIN_PWM;

  if (!looksStalled) {
    timing = false;
    return;
  }

  if (!timing) {
    timing = true;
    sinceMs = nowMs;
  } else if (static_cast<uint32_t>(nowMs - sinceMs) >= MotorConfig::STALL_TIMEOUT_MS) {
    latchFault(fault);
  }
}

void acceptWheelCommand(uint32_t sequence, int32_t leftMradS,
                        int32_t rightMradS)
{
  if (leftMradS < -MotorConfig::MAX_TARGET_MRAD_S ||
      leftMradS > MotorConfig::MAX_TARGET_MRAD_S ||
      rightMradS < -MotorConfig::MAX_TARGET_MRAD_S ||
      rightMradS > MotorConfig::MAX_TARGET_MRAD_S) {
    latchFault(FAULT_TARGET_RANGE);
    return;
  }

  if (leftMradS > -TARGET_DEADBAND_MRAD_S &&
      leftMradS < TARGET_DEADBAND_MRAD_S) {
    leftMradS = 0;
  }
  if (rightMradS > -TARGET_DEADBAND_MRAD_S &&
      rightMradS < TARGET_DEADBAND_MRAD_S) {
    rightMradS = 0;
  }

  // Unlike A/D/F, this echo follows the range check: an out-of-range C latches
  // FAULT_TARGET_RANGE above and is never echoed. See README.md.
  lastReceivedSequence = sequence;
  requestedLeftMradS = leftMradS;
  requestedRightMradS = rightMradS;
  lastWheelCommandMs = millis();
  haveWheelCommand = true;
}

// The sequence echo must be assigned before the precondition checks below.
// It reports receipt, not success, which is the only way the host can tell a
// deliberate refusal from a lost command. See the sequence-echo contract in
// README.md before moving this line under a guard clause.
void tryArm(uint32_t sequence)
{
  lastReceivedSequence = sequence;
  const uint32_t nowMs = millis();
  const bool batteryReady =
      batterySampleCount >= BATTERY_SAMPLES_BEFORE_ARM &&
      (!ENABLE_LOW_BATTERY_FAULT || batteryMv >= MotorConfig::BATTERY_RECOVERY_MV);
  const bool freshZeroCommand =
      haveWheelCommand && requestedLeftMradS == 0 &&
      requestedRightMradS == 0 &&
      static_cast<uint32_t>(nowMs - lastWheelCommandMs) <=
          PREARM_COMMAND_MAX_AGE_MS;

  if (controllerState != ControllerState::DISARMED ||
      faultBits != FAULT_NONE || !hardwareWatchdogActive || !batteryReady ||
      !freshZeroCommand) {
    return;
  }

  leftController.reset();
  rightController.reset();
  digitalWrite(PIN_SLEEP, HIGH);
  controllerState = ControllerState::ARMED;
}

// The sequence echo must be assigned before the precondition checks below.
// It reports receipt, not success, which is the only way the host can tell a
// deliberate refusal from a lost command. See the sequence-echo contract in
// README.md before moving this line under a guard clause.
void tryClearFaults(uint32_t sequence)
{
  lastReceivedSequence = sequence;
  const bool batteryRecovered =
      !ENABLE_LOW_BATTERY_FAULT ||
      (batterySampleCount >= BATTERY_SAMPLES_BEFORE_ARM &&
       batteryMv >= MotorConfig::BATTERY_RECOVERY_MV);
  if (!hardwareWatchdogActive || !batteryRecovered ||
      requestedLeftMradS != 0 || requestedRightMradS != 0) {
    return;
  }

  faultBits = FAULT_NONE;
  controllerState = ControllerState::DISARMED;
  lowBatteryTiming = false;
  resetControlState();
  disableMotorDriver();
}

void processCommand(const SerialProtocol::Command &command)
{
  switch (command.type) {
    case SerialProtocol::CommandType::WHEEL_SPEED:
      acceptWheelCommand(command.sequence, command.leftMradS, command.rightMradS);
      return;
    case SerialProtocol::CommandType::ARM:
      tryArm(command.sequence);
      return;
    case SerialProtocol::CommandType::DISARM:
      lastReceivedSequence = command.sequence;
      disarm();
      return;
    case SerialProtocol::CommandType::CLEAR_FAULTS:
      tryClearFaults(command.sequence);
      return;
    case SerialProtocol::CommandType::INVALID:
      latchFault(FAULT_PROTOCOL);
      return;
  }
}

int32_t roundedSpeed(float speed)
{
  return static_cast<int32_t>(speed >= 0.0f ? speed + 0.5f : speed - 0.5f);
}

// The counts come from the caller's sample rather than a fresh read so that
// left_count and left_measured_mrad_s describe the same instant.
void publishTelemetry(uint32_t nowMs, int32_t leftCount, int32_t rightCount)
{
  // Lossy but never blocking. Counts are cumulative and every other field is a
  // current value, so a skipped frame delays information without losing any.
  if (Serial.availableForWrite() < TELEMETRY_MIN_TX_SPACE) {
    return;
  }

  SerialProtocol::publishTelemetry(Serial, {
      nowMs, lastReceivedSequence, static_cast<uint8_t>(controllerState), faultBits,
      leftCount, rightCount, requestedLeftMradS, requestedRightMradS,
      roundedSpeed(measuredLeftMradS), roundedSpeed(measuredRightMradS),
      appliedLeftPwm, appliedRightPwm, batteryMv});
}

void controlTick(uint32_t elapsedUs)
{
  const uint32_t nowMs = millis();

  int32_t leftCount = 0;
  int32_t rightCount = 0;
  readEncoderCounts(leftCount, rightCount);
  const int32_t leftDelta = leftCount - previousLeftCount;
  const int32_t rightDelta = rightCount - previousRightCount;
  previousLeftCount = leftCount;
  previousRightCount = rightCount;

  constexpr float MILLIRADIANS_PER_REVOLUTION = 6283.185307f;
  const float speedScale =
      MILLIRADIANS_PER_REVOLUTION * 1000000.0f /
      (static_cast<float>(ENCODER_COUNTS_PER_WHEEL_REV) * elapsedUs);
  const float rawLeftMradS = static_cast<float>(leftDelta) * speedScale;
  const float rawRightMradS = static_cast<float>(rightDelta) * speedScale;
  measuredLeftMradS +=
      SPEED_FILTER_ALPHA * (rawLeftMradS - measuredLeftMradS);
  measuredRightMradS +=
      SPEED_FILTER_ALPHA * (rawRightMradS - measuredRightMradS);

  updateBattery(nowMs);

  if (controllerState == ControllerState::ARMED &&
      elapsedUs > MAX_CONTROL_INTERVAL_US) {
    latchFault(FAULT_CONTROL_OVERRUN);
  }

  if (controllerState == ControllerState::ARMED &&
      (!haveWheelCommand ||
       static_cast<uint32_t>(nowMs - lastWheelCommandMs) >
           COMMAND_TIMEOUT_MS)) {
    latchFault(FAULT_COMMAND_TIMEOUT);
  }

  if (controllerState != ControllerState::ARMED) {
    disableMotorDriver();
  } else {
    const float dtSeconds = static_cast<float>(elapsedUs) / 1000000.0f;
    appliedLeftPwm = leftController.update(requestedLeftMradS,
                                           measuredLeftMradS, dtSeconds);
    appliedRightPwm = rightController.update(requestedRightMradS,
                                             measuredRightMradS, dtSeconds);

    digitalWrite(PIN_SLEEP, HIGH);
    writeMotor(PIN_PWM_L, PIN_DIR_L, appliedLeftPwm);
    writeMotor(PIN_PWM_R, PIN_DIR_R, appliedRightPwm);

    checkStall(requestedLeftMradS, measuredLeftMradS, appliedLeftPwm,
               FAULT_LEFT_STALL, leftStallTiming, leftStallSinceMs, nowMs);
    if (controllerState == ControllerState::ARMED) {
      checkStall(requestedRightMradS, measuredRightMradS, appliedRightPwm,
                 FAULT_RIGHT_STALL, rightStallTiming, rightStallSinceMs, nowMs);
    }
  }

  // Every state publishes, including DISARMED and FAULT: the host needs the
  // sequence echo to arm and the fault bits to diagnose a refusal. A fault
  // latched above therefore appears in the same frame as the data that caused it.
  if (++telemetryTickCount >= TELEMETRY_DECIMATION) {
    telemetryTickCount = 0;
    publishTelemetry(nowMs, leftCount, rightCount);
  }
}

void setup()
{
  // Preload safe values before any motor-control pin becomes an output.
  digitalWrite(PIN_SLEEP, LOW);
  digitalWrite(PIN_PWM_L, LOW);
  digitalWrite(PIN_PWM_R, LOW);
  digitalWrite(PIN_DIR_L, LOW);
  digitalWrite(PIN_DIR_R, LOW);

  pinMode(PIN_SLEEP, OUTPUT);
  pinMode(PIN_PWM_L, OUTPUT);
  pinMode(PIN_PWM_R, OUTPUT);
  pinMode(PIN_DIR_L, OUTPUT);
  pinMode(PIN_DIR_R, OUTPUT);
  disableMotorDriver();

  pinMode(PIN_ENC_L_A, INPUT);
  pinMode(PIN_ENC_L_B, INPUT);
  pinMode(PIN_ENC_R_A, INPUT);
  pinMode(PIN_ENC_R_B, INPUT);
  pinMode(PIN_BATTERY, INPUT);
  analogReadResolution(12);

  attachInterrupt(digitalPinToInterrupt(PIN_ENC_L_A), onLeftEncoderA, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_R_A), onRightEncoderA, CHANGE);

  Serial.begin(115200);

  readEncoderCounts(previousLeftCount, previousRightCount);
  lastControlUs = micros();

  hardwareWatchdogActive = WDT.begin(HARDWARE_WATCHDOG_MS) == 1;
  if (!hardwareWatchdogActive) {
    latchFault(FAULT_HARDWARE_WATCHDOG);
  }

  SerialProtocol::publishBoot(Serial, {
      ENCODER_COUNTS_PER_WHEEL_REV, CONTROL_PERIOD_US / 1000u, COMMAND_TIMEOUT_MS,
      MotorConfig::MAX_PWM, MotorConfig::MAX_TARGET_MRAD_S});
}

void loop()
{
  serialReceiver.poll(Serial, processCommand);

  const uint32_t nowUs = micros();
  const uint32_t elapsedUs = static_cast<uint32_t>(nowUs - lastControlUs);
  if (elapsedUs >= CONTROL_PERIOD_US) {
    lastControlUs = nowUs;
    controlTick(elapsedUs);
  }

  if (hardwareWatchdogActive) {
    WDT.refresh();
  }
}
