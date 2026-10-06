import Testing
@testable import Robot_Relay

struct PicoModeTests {
    @Test func readsTheModeLine() {
        #expect(PicoMode.parse("mode=test\n") == .armed("test"))
        #expect(PicoMode.parse("mode=repeat:center\r\n") == .armed("repeat:center"))
        #expect(PicoMode.parse("mode=\n") == .idle)
    }

    @Test func ignoresNoiseAroundTheLine() {
        #expect(PicoMode.parse("Yobot: idle; servo signals off.\n  mode=center \n>>> ") == .armed("center"))
    }

    @Test func noLineIsUnknown() {
        #expect(PicoMode.parse("") == nil)
        #expect(PicoMode.parse("Traceback (most recent call last):\nOSError: 2\n") == nil)
    }

    @Test func armWritesWhatMainPyAccepts() {
        #expect(PicoMode.value(mode: .test, repeatEveryBoot: false) == "test")
        #expect(PicoMode.value(mode: .center, repeatEveryBoot: true) == "repeat:center")
        #expect(PicoMode.armScript("repeat:test")
            == "with open('bringup_mode.txt', 'w') as f: f.write('repeat:test')")
    }
}

struct PicoUSBTests {
    @Test func prefersUSBModemPortsInSortedOrder() {
        #expect(PicoUSB.choosePort(["/dev/cu.usbserial-1", "/dev/cu.usbmodem2101", "/dev/cu.usbmodem1101"])
            == "/dev/cu.usbmodem1101")
        #expect(PicoUSB.choosePort(["/dev/cu.usbserial-2", "/dev/cu.usbserial-1"]) == "/dev/cu.usbserial-1")
        #expect(PicoUSB.choosePort([]) == nil)
    }

    @Test func vendorIsRaspberryPi() {
        #expect(PicoUSB.vendorID == 0x2E8A)
    }
}

struct PicoArmTests {
    @Test func labels() {
        #expect(PicoArm.idle.label == "idle")
        #expect(PicoArm.armed("test").label == "armed: test")
        #expect(PicoArm.armed("repeat:center").label == "repeat: center")
        #expect(PicoArm.armed("bogus").label == "armed: bogus")
    }

    @Test func onlyIdleIsDisarmed() {
        #expect(!PicoArm.idle.isArmed)
        #expect(PicoArm.armed("test").isArmed)
        #expect(PicoArm.armed("repeat:test").isArmed)
    }
}
