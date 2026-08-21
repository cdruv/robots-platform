#include <Arduino.h>

constexpr uint8_t PIN_ENC_L_A = 2;
constexpr uint8_t PIN_ENC_L_B = 4;
constexpr uint8_t PIN_ENC_R_A = 3;
constexpr uint8_t PIN_ENC_R_B = 7;

constexpr uint8_t PIN_PWM_L = 5;
constexpr uint8_t PIN_PWM_R = 6;
constexpr uint8_t PIN_DIR_L = 8;
constexpr uint8_t PIN_DIR_R = 9;
constexpr uint8_t PIN_SLEEP = 10;

constexpr int16_t MIN_PWM = 56;
constexpr int16_t MAX_PWM = 96;
constexpr uint32_t MIN_RUN_TIME_MS = 600;
constexpr uint32_t MAX_RUN_TIME_MS = 1800;
constexpr uint32_t MIN_WAIT_TIME_MS = 150;
constexpr uint32_t MAX_WAIT_TIME_MS = 500;

volatile int32_t encoderLeft = 0;
volatile int32_t encoderRight = 0;

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

void stopMotors()
{
  analogWrite(PIN_PWM_L, 0);
  analogWrite(PIN_PWM_R, 0);
}

void setMotor(uint8_t pwmPin, uint8_t dirPin, int16_t pwm)
{
  digitalWrite(dirPin, pwm < 0 ? HIGH : LOW);
  analogWrite(pwmPin, abs(pwm));
}

void readEncoders(int32_t &left, int32_t &right)
{
  noInterrupts();
  left = encoderLeft;
  right = encoderRight;
  interrupts();
}

void runPhase(const char *name, int16_t leftPwm, int16_t rightPwm,
              uint32_t runTimeMs)
{
  int32_t leftStart;
  int32_t rightStart;
  readEncoders(leftStart, rightStart);

  setMotor(PIN_PWM_L, PIN_DIR_L, leftPwm);
  setMotor(PIN_PWM_R, PIN_DIR_R, rightPwm);
  delay(runTimeMs);
  stopMotors();

  int32_t leftEnd;
  int32_t rightEnd;
  readEncoders(leftEnd, rightEnd);

  Serial.print(name);
  Serial.print(',');
  Serial.print(leftPwm);
  Serial.print(',');
  Serial.print(rightPwm);
  Serial.print(',');
  Serial.print(runTimeMs);
  Serial.print(',');
  Serial.print(leftEnd - leftStart);
  Serial.print(',');
  Serial.print(rightEnd - rightStart);
  Serial.print(',');
  Serial.print(leftEnd);
  Serial.print(',');
  Serial.println(rightEnd);

  delay(random(MIN_WAIT_TIME_MS, MAX_WAIT_TIME_MS + 1));
}

void setup()
{
  // Start with the motor driver disabled while its pins are configured.
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

  pinMode(PIN_ENC_L_A, INPUT);
  pinMode(PIN_ENC_L_B, INPUT);
  pinMode(PIN_ENC_R_A, INPUT);
  pinMode(PIN_ENC_R_B, INPUT);
  pinMode(A1, INPUT);

  attachInterrupt(digitalPinToInterrupt(PIN_ENC_L_A), onLeftEncoderA, CHANGE);
  attachInterrupt(digitalPinToInterrupt(PIN_ENC_R_A), onRightEncoderA, CHANGE);

  Serial.begin(115200);
  Serial.println(
      "phase,left_pwm,right_pwm,duration_ms,left_delta,right_delta,left_total,"
      "right_total");

  randomSeed(analogRead(A1) ^ micros());

  digitalWrite(PIN_SLEEP, HIGH);
}

void loop()
{
  const int16_t outerPwm = random(MIN_PWM, MAX_PWM + 1);
  const int16_t innerPwm = random(MIN_PWM / 2, outerPwm);
  const uint32_t runTimeMs =
      random(MIN_RUN_TIME_MS, MAX_RUN_TIME_MS + 1);
  const long choice = random(100);

  if (choice < 60) {
    runPhase("forward", outerPwm, outerPwm, runTimeMs);
  } else if (choice < 77) {
    runPhase("curve_left", innerPwm, outerPwm, runTimeMs);
  } else if (choice < 94) {
    runPhase("curve_right", outerPwm, innerPwm, runTimeMs);
  } else if (choice < 97) {
    runPhase("pivot_left", -innerPwm, outerPwm, runTimeMs);
  } else {
    runPhase("pivot_right", outerPwm, -innerPwm, runTimeMs);
  }
}
