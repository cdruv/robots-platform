#pragma once

struct TestWatchdog {
  int begin(unsigned long) { return 1; }
  void refresh() {}
};
inline TestWatchdog WDT;
