#pragma once

// Minimal host shim: exercises the real parser and controller without hardware.
#include <cstdint>
#include <string>

#define F(text) text
constexpr int HIGH = 1, LOW = 0, INPUT = 0, OUTPUT = 1, CHANGE = 1, A0 = 14;
inline uint32_t testMillis = 0;
inline int testPins[32] = {};

inline uint32_t millis() { return testMillis; }
inline uint32_t micros() { return testMillis * 1000u; }
inline void noInterrupts() {}
inline void interrupts() {}
inline void pinMode(int, int) {}
inline void analogReadResolution(int) {}
inline void attachInterrupt(int, void (*)(), int) {}
inline int digitalPinToInterrupt(int pin) { return pin; }
inline int digitalRead(int pin) { return testPins[pin]; }
inline void digitalWrite(int pin, int value) { testPins[pin] = value; }
inline void analogWrite(int pin, int value) { testPins[pin] = value; }
inline int analogRead(int) { return 2000; }
template <typename T, typename L, typename H> T constrain(T value, L low, H high) {
  return value < low ? low : value > high ? high : value;
}

class Print {
 public:
  std::string output;
  void print(const char *value) { output += value; }
  void print(char value) { output += value; }
  template <typename T> void print(T value) { output += std::to_string(value); }
  template <typename T> void println(T value) { print(value); output += "\r\n"; }
};

class Stream : public Print {
 public:
  std::string input;
  size_t offset = 0;
  int txSpace = 256;
  void begin(int) {}
  int available() { return static_cast<int>(input.size() - offset); }
  int read() { return available() ? static_cast<unsigned char>(input[offset++]) : -1; }
  int availableForWrite() { return txSpace; }
};

inline Stream Serial;
