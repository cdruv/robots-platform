"""One boot, one calibration client. No production control routes."""
import json
import time
import calibration
from servo_output import Servos, release

PORT = 8765
MAX_LINE = 1024


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


def expired(now, start, last_message, connected):
    return (time.ticks_diff(now, start) >= 600000 or
            time.ticks_diff(now, last_message) >= (2000 if connected else 60000))


def run(request):
    import network
    import socket
    from machine import Pin, WDT, reset
    from servo_check import wait_ms

    watchdog = WDT(timeout=2000)
    servos = listener = client = ap = None
    led = Pin('LED', Pin.OUT)
    try:
        steps, _ = calibration.load()
        for _ in range(10):
            led.toggle()
            wait_ms(500, watchdog)
        servos = Servos(steps)
        servos.center()
        led.on()
        start = last_message = time.ticks_ms()
        network.WLAN(network.WLAN.IF_STA).active(False)
        ap = network.WLAN(network.WLAN.IF_AP)
        ap.config(ssid=request['ssid'], key=request['password'], security=network.WLAN.SEC_WPA_WPA2)
        ap.active(True)
        ap.ifconfig(('192.168.4.1', '255.255.255.0', '192.168.4.1', '192.168.4.1'))
        listener = socket.socket()
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(('192.168.4.1', PORT))
        listener.listen(1)
        listener.setblocking(False)
        session = Session(request, servos)
        pending = b''
        outgoing = b''
        while True:
            now = time.ticks_ms()
            if expired(now, start, last_message, client is not None):
                raise OSError('Calibration timed out')
            watchdog.feed()
            if client is None:
                try:
                    client, _ = listener.accept()
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
                    response = session.handle(json.loads(line.decode()))
                    last_message = time.ticks_ms()
                    outgoing = (json.dumps(response) + '\n').encode()
            time.sleep_ms(10)
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
