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

void forceSafeOutputs()
{
  digitalWrite(PIN_PWM_L, LOW);
  digitalWrite(PIN_PWM_R, LOW);
  digitalWrite(PIN_DIR_L, LOW);
  digitalWrite(PIN_DIR_R, LOW);
  digitalWrite(PIN_SLEEP, LOW);
}

void setup()
{
  // Preload low output values before changing the pins to outputs.
  forceSafeOutputs();

  // Disable the motor drivers before configuring their other control pins.
  pinMode(PIN_SLEEP, OUTPUT);
  pinMode(PIN_PWM_L, OUTPUT);
  pinMode(PIN_PWM_R, OUTPUT);
  pinMode(PIN_DIR_L, OUTPUT);
  pinMode(PIN_DIR_R, OUTPUT);

  forceSafeOutputs();

  // The #3543 board supplies the encoder signal pull-ups.
  pinMode(PIN_ENC_L_A, INPUT);
  pinMode(PIN_ENC_L_B, INPUT);
  pinMode(PIN_ENC_R_A, INPUT);
  pinMode(PIN_ENC_R_B, INPUT);
  pinMode(A0, INPUT);

  Serial.begin(115200);
}

void loop()
{
  // Remain in the safe, motor-driver-disabled state indefinitely.
  forceSafeOutputs();
  delay(10);
}
