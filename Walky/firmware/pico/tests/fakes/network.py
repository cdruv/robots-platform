class WLAN:
    IF_STA, IF_AP, SEC_WPA_WPA2 = 0, 1, 4194308
    active_interfaces = {}

    def __init__(self, interface):
        self.interface = interface

    def active(self, value):
        self.active_interfaces[self.interface] = value

    def config(self, **kwargs):
        assert kwargs['security'] == self.SEC_WPA_WPA2
        assert len(kwargs['key']) >= 16

    def ifconfig(self, value):
        assert value[0] == '192.168.4.1'
