"""Scripted peers for link.py. `arriving` is what successive accept calls do:
a Client is handed out, None is one EAGAIN, an exception is raised. An empty
list is EAGAIN forever.
"""
import errno

SOL_SOCKET, SO_REUSEADDR, IPPROTO_TCP, TCP_NODELAY = 1, 2, 6, 1
arriving = []
listeners = []


def clear():
    global arriving, listeners
    arriving, listeners = [], []


class Client:
    """`script` is what successive recv calls do: bytes are delivered (b'' is
    the peer closing), None is one EAGAIN, an exception is raised. An exhausted
    script is EAGAIN forever. `window` caps how many bytes one send accepts.
    """

    def __init__(self, *script, window=None):
        self.script = list(script)
        self.window = window
        self.sent = b''
        self.closed = self.nodelay = False

    def setblocking(self, value):
        assert value is False

    def setsockopt(self, level, option, value):
        assert (level, option, value) == (IPPROTO_TCP, TCP_NODELAY, 1)
        self.nodelay = True

    def recv(self, size):
        assert not self.closed
        if not self.script:
            raise OSError(errno.EAGAIN)
        item = self.script.pop(0)
        if item is None:
            raise OSError(errno.EAGAIN)
        if isinstance(item, BaseException):
            raise item
        if len(item) > size:
            self.script.insert(0, item[size:])
        return item[:size]

    def send(self, data):
        assert not self.closed
        count = len(data) if self.window is None else min(self.window, len(data))
        if count == 0:
            raise OSError(errno.EAGAIN)
        self.sent += data[:count]
        return count

    def lines(self):
        import json
        return [json.loads(line) for line in self.sent.decode().split('\n') if line]

    def close(self):
        self.closed = True


class socket:
    def __init__(self):
        self.closed = False
        listeners.append(self)

    def setsockopt(self, level, option, value):
        assert (level, option, value) == (SOL_SOCKET, SO_REUSEADDR, 1)

    def bind(self, address):
        assert address == ('192.168.4.1', 8765)

    def listen(self, backlog):
        assert backlog == 1

    def setblocking(self, value):
        assert value is False

    def accept(self):
        assert not self.closed
        item = arriving.pop(0) if arriving else None
        if item is None:
            raise OSError(errno.EAGAIN)
        if isinstance(item, BaseException):
            raise item
        return item, ('192.168.4.16', 50000)

    def close(self):
        self.closed = True
