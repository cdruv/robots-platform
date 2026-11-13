"""One boot, one calibration client. No production control routes."""
import json
import time
import bootlog
import calibration
from servo_output import Servos, release, CENTER_US

PORT = 8765
MAX_LINE = 1024
NUDGE_MS = 400
# The CYW43 firmware default, mode 4 (auto with noise reduction), made the access
# point stop accepting frames from an associated client after 10-20 s.
CYW43_IOCTL_GET_INTERFERENCE_MODE = 0x1a6
CYW43_IOCTL_SET_INTERFERENCE_MODE = 0x1a9
CYW43_INTERFERE_AUTO = 3


class Session:
    """Protocol state separated from sockets/hardware for host tests."""
    def __init__(self, request, servos):
        self.request = request
        self.servos = servos
        self.saved, self.stored = calibration.load()
        self.preview = self.saved
        self.ready = False
        self.finished = False
        self.last_id = -1

    def handle(self, message):
        if type(message) is not dict:
            raise ValueError('Expected object')
        identity = self.request
        if (message.get('version') != 1 or message.get('session') != identity['session']
                or message.get('board') != identity['board']):
            raise ValueError('Calibration identity mismatch')
        request_id = message.get('id')
        if type(request_id) is not int or request_id <= self.last_id:
            raise ValueError('Request IDs must increase')
        self.last_id = request_id
        command = message.get('command')
        if self.finished or (not self.ready and command != 'hello'):
            raise ValueError('Handshake required or session finished')
        if command == 'hello':
            self.ready = True
        elif command == 'preview':
            steps = calibration.validate_steps(message.get('steps'))
            self.servos.center(steps)
            self.preview = steps
        elif command == 'nudge':
            # Identify one leg: step toward a longer pulse, then back to centre.
            side = message.get('side')
            if type(side) is not int or side not in (0, 1):
                raise ValueError('Nudge side must be 0 (left) or 1 (right)')
            self.servos.write(side, CENTER_US + self.servos.excursion(side))
            time.sleep_ms(NUDGE_MS)
            self.servos.write(side, CENTER_US)
        elif command == 'save':
            self.saved = calibration.save(self.preview)
            self.stored = True
            self.finished = True
        elif command == 'cancel':
            self.finished = True
        elif command not in ('status', 'heartbeat'):
            raise ValueError('Unknown calibration command')
        if self.finished:
            self.servos.close()
        return {'version': 1, 'board': identity['board'], 'session': identity['session'],
                'id': request_id, 'saved': list(self.saved), 'preview': list(self.preview),
                'stored': self.stored, 'pulses': [1500 + calibration.trim_us(v) for v in self.preview],
                'finished': self.finished}


def timeout_reason(now, start, last_message, connected):
    if time.ticks_diff(now, start) >= 600000:
        return 'session limit'
    if connected and time.ticks_diff(now, last_message) >= 5000:
        return 'client idle'
    if not connected and time.ticks_diff(now, last_message) >= 60000:
        return 'waiting for client'
    return None


def set_interference_mode(ap, mode):
    ap.ioctl(CYW43_IOCTL_SET_INTERFERENCE_MODE, bytearray((mode, 0, 0, 0)))
    current = bytearray(4)
    ap.ioctl(CYW43_IOCTL_GET_INTERFERENCE_MODE, current)
    return current[0]


def radio_state(ap):
    try:
        return 'ap_active=%s stations=%d' % (ap.active(), len(ap.status('stations')))
    except Exception as error:
        return 'radio state unavailable: %r' % (error,)


def run(request):
    import network
    import socket
    from machine import Pin, WDT, reset
    from servo_check import countdown, wait_ms

    watchdog = WDT(timeout=2000)
    servos = listener = client = ap = None
    led = Pin('LED', Pin.OUT)
    try:
        steps, _ = calibration.load()
        countdown(led, watchdog)
        servos = Servos(steps)
        servos.center()
        led.on()
        start = last_message = time.ticks_ms()
        network.WLAN(network.WLAN.IF_STA).active(False)
        ap = network.WLAN(network.WLAN.IF_AP)
        ap.config(ssid=request['ssid'], key=request['password'], security=network.WLAN.SEC_WPA_WPA2)
        ap.active(True)
        interference = set_interference_mode(ap, CYW43_INTERFERE_AUTO)
        ap.ifconfig(('192.168.4.1', '255.255.255.0', '192.168.4.1', '192.168.4.1'))
        bootlog.write('calibration: access point %s up, active %s, interference mode %d, ifconfig %s' %
                      (request['ssid'], ap.active(), interference, ap.ifconfig()))
        listener = socket.socket()
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(('192.168.4.1', PORT))
        listener.listen(1)
        listener.setblocking(False)
        session = Session(request, servos)
        pending = b''
        outgoing = b''
        last_command = 'none'
        while True:
            now = time.ticks_ms()
            reason = timeout_reason(now, start, last_message, client is not None)
            if reason:
                raise OSError('Calibration timed out (%s): elapsed_ms=%d idle_ms=%d '
                              'last=%s id=%d rx_pending=%d tx_pending=%d' %
                              (reason, time.ticks_diff(now, start), time.ticks_diff(now, last_message),
                               last_command, session.last_id, len(pending), len(outgoing)))
            watchdog.feed()
            if client is None:
                try:
                    client, address = listener.accept()
                    bootlog.write('calibration: client connected from %s' % (address,))
                    client.setblocking(False)
                    last_message = now
                    listener.close()
                    listener = None
                except OSError as error:
                    if error.args[0] not in (11, 35):
                        raise
            elif outgoing:
                try:
                    count = client.send(outgoing)
                    if count == 0:
                        raise OSError('Calibration disconnected')
                    outgoing = outgoing[count:]
                    if not outgoing and session.finished:
                        bootlog.write('calibration: finished, stored trim %s' % (list(session.saved),))
                        # Give the TCP stack a bounded chance to deliver the final reply.
                        wait_ms(200, watchdog)
                        return
                except OSError as error:
                    if error.args[0] not in (11, 35):
                        raise
            else:
                try:
                    chunk = client.recv(256)
                    if not chunk:
                        raise OSError('Calibration disconnected')
                    pending += chunk
                except OSError as error:
                    if error.args[0] not in (11, 35):
                        raise
                if len(pending) > MAX_LINE:
                    raise ValueError('Calibration message too large')
                if b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    message = json.loads(line.decode())
                    response = session.handle(message)
                    last_command = message['command']
                    last_message = time.ticks_ms()
                    outgoing = (json.dumps(response) + '\n').encode()
            time.sleep_ms(10)
    except BaseException as error:
        bootlog.write('calibration: failed, %s' % radio_state(ap))
        bootlog.exception(error)
        raise
    finally:
        try:
            servos.close() if servos is not None else release()
            if client is not None:
                client.close()
            if listener is not None:
                listener.close()
            if ap is not None:
                ap.active(False)
            led.off()
        finally:
            reset()
