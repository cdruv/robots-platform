class WLAN:
    IF_STA, IF_AP, SEC_WPA_WPA2 = 0, 1, 4194308
    active_interfaces = {}

    def __init__(self, interface):
        self.interface = interface

    def active(self, value=None):
        if value is None:
            return self.active_interfaces.get(self.interface, False)
        self.active_interfaces[self.interface] = value

    def ioctl(self, command, buffer):
        assert self.active()
        if command == 0x1a9:
            WLAN.interference = buffer[0]
        else:
            assert command == 0x1a6
            buffer[0] = WLAN.interference

    def status(self, key):
        assert key == 'stations'
        return [b'\x02\x00\x00\x00\x00\x01']

    def config(self, **kwargs):
        assert 'channel' not in kwargs
        assert kwargs['security'] == self.SEC_WPA_WPA2
        assert len(kwargs['key']) >= 16

    def ifconfig(self, value=None):
        if value is None:
            return self.address
        assert value[0] == '192.168.4.1'
        self.address = value
