SOL_SOCKET, SO_REUSEADDR = 1, 2
messages = []
no_client = False
closed = False
replies = b''


class Client:
    def setblocking(self, value):
        assert value is False

    def recv(self, size):
        if not messages:
            raise OSError(11)
        chunk = messages[0][:size]
        messages[0] = messages[0][size:]
        if not messages[0]:
            messages.pop(0)
        return chunk

    def send(self, data):
        global replies
        replies += data
        return len(data)

    def close(self):
        global closed
        closed = True


class socket(Client):
    def setsockopt(self, *args):
        pass

    def bind(self, address):
        assert address == ('192.168.4.1', 8765)

    def listen(self, backlog):
        assert backlog == 1

    def accept(self):
        if no_client:
            raise OSError(11)
        return Client(), None
