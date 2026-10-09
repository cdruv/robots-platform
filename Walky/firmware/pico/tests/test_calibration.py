"""Storage/protocol tests never import real MicroPython hardware."""
import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

FIRMWARE = Path(__file__).resolve().parents[1] / "src"


class CalibrationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        old = os.getcwd()
        os.chdir(self.directory.name)
        self.addCleanup(os.chdir, old)
        sys.path.insert(0, str(FIRMWARE))
        self.addCleanup(sys.path.remove, str(FIRMWARE))
        hardware = types.SimpleNamespace(Pin=lambda *a, **k: None, PWM=None, unique_id=lambda: b'board')
        with patch.dict(sys.modules, machine=hardware):
            for name in ('calibration', 'servo_output', 'calibration_session'):
                sys.modules.pop(name, None)
            self.cal = importlib.import_module('calibration')
            self.output = importlib.import_module('servo_output')
            self.session = importlib.import_module('calibration_session')
        self.ticket = dict(version=1, board='626f617264', session='a' * 32,
                           password='b' * 32, ssid='Walky-Cal-test')
        self.addCleanup(patch.stopall)
        patch.object(self.cal, 'board_id', return_value=self.ticket['board']).start()

    def test_rounding_limits_and_pair_validation(self):
        self.assertEqual([self.cal.trim_us(v) for v in (-18, -1, 0, 1, 18)], [-99, -6, 0, 6, 99])
        self.assertEqual(self.output.pulse_us(1500, -1), 1494)
        for value in ([19, 0], [0], [True, 0], [0.5, 0], None):
            with self.assertRaises(ValueError): self.cal.validate_steps(value)
        with self.assertRaises(ValueError): self.output.pulse_us(1502, 18)
        with self.assertRaises(ValueError): self.output.pulse_us(1498, -18)

    def test_storage_missing_saved_corrupt_unknown(self):
        self.assertEqual(self.cal.load(), ((0, 0), False))
        self.cal.save((-1, 18))
        self.assertEqual(self.cal.load(), ((-1, 18), True))
        for data in ('{', '{"version":2,"steps":[0,0]}', '{"version":1,"steps":[100,0]}'):
            Path(self.cal.FILE).write_text(data)
            with self.assertRaises(ValueError): self.cal.load()
            self.assertIn('error', self.cal.snapshot())

    def test_failed_replacement_preserves_last_save(self):
        self.cal.save((2, 3))
        with patch.object(self.cal.os, 'rename', side_effect=OSError('power loss')):
            with self.assertRaises(OSError): self.cal.save((4, 5))
        self.assertEqual(self.cal.load(), ((2, 3), True))

    def test_request_validation_on_micropython_strings(self):
        class MicroString(str):
            def __iter__(self):
                return (MicroString(c) for c in super().__iter__())
            def isalnum(self):
                raise AttributeError("'str' object has no attribute 'isalnum'")
        request = {key: MicroString(value) if isinstance(value, str) else value
                   for key, value in self.ticket.items()}
        self.cal.arm(request)
        self.assertEqual(self.cal.consume_request(), self.ticket)
        for value in ('é' * 32, 'a' * 31 + '!', 'a' * 31 + ' '):
            with self.assertRaises(ValueError):
                self.cal.validate_request(dict(self.ticket, password=value))

    def test_request_consumed_without_touching_mode(self):
        Path('mode.txt').write_text('once:sweep')
        self.cal.arm(self.ticket)
        self.assertEqual(self.cal.consume_request(), self.ticket)
        self.assertIsNone(self.cal.consume_request())
        self.assertEqual(Path('mode.txt').read_text(), 'once:sweep')
        Path(self.cal.REQUEST_FILE).write_text('{')
        with self.assertRaises(ValueError): self.cal.consume_request()
        self.assertFalse(Path(self.cal.REQUEST_FILE).exists())

    def protocol(self):
        class Servos:
            steps = None
            closed = False
            def center(self, steps): self.steps = steps
            def close(self): self.closed = True
        servos = Servos()
        return self.session.Session(self.ticket, servos), servos

    def message(self, command, id=0, **kwargs):
        return dict(version=1, board=self.ticket['board'], session=self.ticket['session'],
                    id=id, command=command, **kwargs)

    def test_preview_is_atomic_volatile_and_save_persists(self):
        session, servos = self.protocol()
        session.handle(self.message('hello'))
        reply = session.handle(self.message('preview', 1, steps=[-1, 18]))
        self.assertEqual(reply['pulses'], [1494, 1599])
        self.assertEqual(self.cal.load(), ((0, 0), False))
        with self.assertRaises(ValueError): session.handle(self.message('preview', 2, steps=[0, 19]))
        self.assertEqual(servos.steps, (-1, 18))
        reply = session.handle(self.message('save', 3))
        self.assertTrue(reply['finished'])
        self.assertTrue(servos.closed)
        self.assertEqual(self.cal.load(), ((-1, 18), True))

    def test_cancel_discards_preview(self):
        session, servos = self.protocol()
        session.handle(self.message('hello'))
        session.handle(self.message('preview', 1, steps=[3, 4]))
        session.handle(self.message('cancel', 2))
        self.assertTrue(servos.closed)
        self.assertEqual(self.cal.load(), ((0, 0), False))

    def test_handshake_identity_replay_unknown_commands(self):
        for message in (self.message('preview', steps=[0, 0]), [],
                        dict(self.message('hello'), session='wrong'), self.message('drive')):
            session, _ = self.protocol()
            with self.assertRaises(ValueError): session.handle(message)
        session, _ = self.protocol()
        session.handle(self.message('hello'))
        with self.assertRaises(ValueError): session.handle(self.message('heartbeat'))
        with self.assertRaises(ValueError): session.handle(self.message('drive', 1))

    def test_deadlines(self):
        with patch.object(self.session, 'time', types.SimpleNamespace(ticks_diff=lambda a, b: a-b)):
            self.assertFalse(self.session.expired(59999, 0, 0, False))
            self.assertTrue(self.session.expired(60000, 0, 0, False))
            self.assertFalse(self.session.expired(1999, 0, 0, True))
            self.assertTrue(self.session.expired(2000, 0, 0, True))
            self.assertTrue(self.session.expired(600000, 0, 599999, True))

    def run_network(self, messages, no_client=False):
        clock = [0]
        state = {'released': False, 'reset': False, 'closed': False, 'ap': False}
        owner = self
        class Servos:
            def __init__(self, steps): self.steps = steps
            def center(self, steps=None):
                if steps is not None: self.steps = steps
            def close(self): state['released'] = True
        class WLAN:
            IF_STA, IF_AP, SEC_WPA_WPA2 = 0, 1, 4194308
            def __init__(self, interface): self.interface = interface
            def active(self, value):
                if self.interface == self.IF_AP: state['ap'] = value
            def config(self, **kwargs):
                assert kwargs['security'] == self.SEC_WPA_WPA2
            def ifconfig(self, value): pass
        class Client:
            def setblocking(self, value): pass
            def recv(self, size):
                if messages: return messages.pop(0)
                raise OSError(11)
            def send(self, data): return len(data)
            def close(self): state['closed'] = True
        class Listener(Client):
            def setsockopt(self, *args): pass
            def bind(self, *args): pass
            def listen(self, *args): pass
            def accept(self):
                if no_client: raise OSError(11)
                return Client(), None
        class Pin:
            OUT = 1
            def __init__(self, *args): pass
            def toggle(self): pass
            def on(self): pass
            def off(self): pass
        class WDT:
            def __init__(self, timeout): assert timeout == 2000
            def feed(self): pass
        def wait(ms, watchdog=None): clock[0] += ms
        def reset(): state['reset'] = True
        machine = types.SimpleNamespace(Pin=Pin, WDT=WDT, reset=reset)
        modules = dict(machine=machine, network=types.SimpleNamespace(WLAN=WLAN),
                       socket=types.SimpleNamespace(socket=Listener, SOL_SOCKET=1, SO_REUSEADDR=2),
                       servo_check=types.SimpleNamespace(wait_ms=wait))
        fake_time = types.SimpleNamespace(ticks_ms=lambda: clock[0], ticks_diff=lambda a,b:a-b, sleep_ms=wait)
        with patch.dict(sys.modules, modules), patch.object(self.session, 'Servos', Servos), patch.object(self.session, 'time', fake_time):
            try:
                self.session.run(self.ticket)
            except (OSError, ValueError):
                pass
        self.assertTrue(state['released'])
        self.assertTrue(state['reset'])
        self.assertFalse(state['ap'])
        return state, clock[0]

    def test_network_cleanup_on_cancel_disconnect_malformed_and_timeout(self):
        hello = (json.dumps(self.message('hello')) + '\n').encode()
        cancel = (json.dumps(self.message('cancel', 1)) + '\n').encode()
        for messages in ([hello, cancel], [hello, b''], [b'{bad\n'], [b'x'*1025], [hello]):
            with self.subTest(messages=messages):
                self.run_network(list(messages))
        _, elapsed = self.run_network([], no_client=True)
        self.assertGreaterEqual(elapsed, 65000)
