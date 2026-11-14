"""The Pico's own Wi-Fi access point and the one TCP client it serves.

The phone joins this network and connects to the gateway address on PORT.
Lines are NDJSON; body.py defines what they mean. Importing touches no
hardware, and neither does credentials().
"""
import binascii
import errno
import json
import os
import bootlog
import calibration

FILE = 'link.json'
ADDRESS = '192.168.4.1'
PORT = 8765
# The channel the link was measured on (docs/body-link-plan.md, step 0).
CHANNEL = 3
MAX_LINE = 256
# The CYW43 firmware default, mode 4 (auto with noise reduction), made the access
# point stop accepting frames from an associated client after 10-20 s.
CYW43_IOCTL_GET_INTERFERENCE_MODE = 0x1a6
CYW43_IOCTL_SET_INTERFERENCE_MODE = 0x1a9
CYW43_INTERFERE_AUTO = 3


def credentials():
    """The network name and password, created once per board and then fixed.

    Robot Relay reads this over USB and hands it to the phone. An unreadable
    file is replaced, which needs a new pairing.
    """
    try:
        with open(FILE) as source:
            data = json.load(source)
        if (type(data) is dict and isinstance(data.get('ssid'), str)
                and isinstance(data.get('password'), str) and len(data['password']) >= 16):
            return {'ssid': data['ssid'], 'password': data['password']}
    except OSError as error:
        if error.args[0] != 2:
            raise
    except ValueError:
        pass
    data = {'ssid': 'Walky-' + calibration.board_id()[-6:],
            'password': binascii.hexlify(os.urandom(12)).decode()}
    with open(FILE + '.tmp', 'w') as target:
        json.dump(data, target)
        target.flush()
    os.sync()
    os.rename(FILE + '.tmp', FILE)
    os.sync()
    return data


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


class Link:
    """Access point, listener and the current client. Nothing here blocks.

    A new connection replaces the current client. A client that sends a
    malformed or oversized line, or whose socket fails, is dropped; the
    listener stays open either way.
    """

    def __init__(self, credentials):
        import network
        import socket
        self._socket = socket
        self._client = None
        self._listener = None
        self._count = 0
        self._ap = ap = network.WLAN(network.WLAN.IF_AP)
        try:
            network.WLAN(network.WLAN.IF_STA).active(False)
            ap.config(ssid=credentials['ssid'], key=credentials['password'],
                      security=network.WLAN.SEC_WPA_WPA2, channel=CHANNEL)
            ap.active(True)
            interference = set_interference_mode(ap, CYW43_INTERFERE_AUTO)
            ap.ifconfig((ADDRESS, '255.255.255.0', ADDRESS, ADDRESS))
            bootlog.write('link: access point %s up, channel %d, interference mode %d, ifconfig %s' %
                          (credentials['ssid'], CHANNEL, interference, ap.ifconfig()))
            listener = socket.socket()
            self._listener = listener
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((ADDRESS, PORT))
            listener.listen(1)
            listener.setblocking(False)
        except BaseException:
            bootlog.write('link: failed to start, %s' % radio_state(ap))
            self.close()
            raise

    @property
    def connected(self):
        return self._client is not None

    @property
    def session(self):
        """Identifies the current client; None while there is none."""
        return self._count if self._client is not None else None

    def poll(self):
        """Accept a waiting client, then return the messages received since the last call."""
        self._accept()
        messages = []
        if self._client is not None:
            try:
                self._receive(messages)
                self._flush()
            except (OSError, ValueError) as error:
                self._drop(error)
        return messages

    def send(self, message):
        """Queue one line for the client. Only the newest unsent line is kept."""
        if self._client is None:
            return
        self._waiting = (json.dumps(message) + '\n').encode()
        try:
            self._flush()
        except OSError as error:
            self._drop(error)

    def close(self):
        for closable in (self._client, self._listener):
            if closable is not None:
                try:
                    closable.close()
                except OSError:
                    pass
        self._client = self._listener = None
        self._ap.active(False)

    def _accept(self):
        try:
            client, address = self._listener.accept()
        except OSError as error:
            if error.args[0] != errno.EAGAIN:
                raise
            return
        if self._client is not None:
            self._drop('replaced')
        client.setblocking(False)
        client.setsockopt(self._socket.IPPROTO_TCP, self._socket.TCP_NODELAY, 1)
        self._client = client
        self._count += 1
        self._received = self._sending = b''
        self._waiting = None
        bootlog.write('link: client %d connected from %s' % (self._count, address))

    def _receive(self, messages):
        # Bounded per call so a flood cannot hold up the servo loop.
        for _ in range(8):
            try:
                chunk = self._client.recv(512)
            except OSError as error:
                if error.args[0] != errno.EAGAIN:
                    raise
                break
            if not chunk:
                raise OSError('closed by client')
            self._received += chunk
            lines = self._received.split(b'\n')
            self._received = lines.pop()
            for line in lines:
                if len(line) > MAX_LINE:
                    raise ValueError('line too long')
                message = json.loads(line.decode())
                if type(message) is not dict:
                    raise ValueError('expected an object')
                messages.append(message)
            if len(self._received) > MAX_LINE:
                raise ValueError('line too long')

    def _flush(self):
        # A line that is partly sent is always finished before the next starts.
        if not self._sending and self._waiting is not None:
            self._sending, self._waiting = self._waiting, None
        if self._sending:
            try:
                count = self._client.send(self._sending)
            except OSError as error:
                if error.args[0] != errno.EAGAIN:
                    raise
                return
            self._sending = self._sending[count:]

    def _drop(self, reason):
        bootlog.write('link: client %d dropped, %s' % (self._count, reason))
        try:
            self._client.close()
        except OSError:
            pass
        self._client = None
