import Testing
@testable import Robot_Relay

struct PicoModeTests {
    @Test func readsTheModeLine() {
        #expect(PicoMode.parse("mode=test\n") == .armed("test"))
        #expect(PicoMode.parse("mode=once:sweep\r\n") == .armed("once:sweep"))
        #expect(PicoMode.parse("mode=\n") == .idle)
    }

    @Test func ignoresNoiseAroundTheLine() {
        #expect(PicoMode.parse("Yobot: idle; servo signals off.\n  mode=center \n>>> ") == .armed("center"))
    }

    @Test func noLineIsUnknown() {
        #expect(PicoMode.parse("") == nil)
        #expect(PicoMode.parse("Traceback (most recent call last):\nOSError: 2\n") == nil)
    }

    @Test func armWritesTheReadmeCommand() {
        #expect(PicoMode.armScript("once:sweep")
            == "with open('mode.txt', 'w') as f: f.write('once:sweep')")
    }

    @Test func readsTheModesTheFirmwareLists() {
        let output = "mode=\nmodes=body,center,sweep,once:center,once:sweep\r\n"
        #expect(PicoMode.parse(output) == .idle)
        #expect(PicoMode.parseModes(output) == ["body", "center", "sweep", "once:center", "once:sweep"])
    }

    @Test func firmwareWithoutModesListsNone() {
        #expect(PicoMode.parseModes("mode=test\nmodes=\n").isEmpty)
        #expect(PicoMode.parseModes("mode=test\n").isEmpty)
    }

    @Test func dropsModesThatCouldNotBeWrittenBack() {
        #expect(PicoMode.parseModes("modes=test, body ,it's,a b,\n") == ["test", "body"])
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
        #expect(PicoArm.armed("body").label == "boot: body")
        #expect(PicoArm.armed("once:center").label == "once: center")
        #expect(PicoArm.armed("bogus").label == "boot: bogus")
    }

    @Test func menuLabels() {
        #expect(PicoArm.menuLabel("body") == "body")
        #expect(PicoArm.menuLabel("once:sweep") == "once: sweep")
    }

    @Test func onlyIdleIsDisarmed() {
        #expect(!PicoArm.idle.isArmed)
        #expect(PicoArm.armed("test").isArmed)
        #expect(PicoArm.armed("once:sweep").isArmed)
    }
}
