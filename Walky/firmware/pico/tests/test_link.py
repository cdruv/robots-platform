import errno
import os
import network
import socket
from support import write, read, raises, line

SECRET = {'ssid': 'Walky-test', 'password': 'p' * 24}


def start(*arriving):
    import link
    socket.arriving.extend(arriving)
    return link.Link(SECRET)


def test_credentials_are_created_once_per_board_without_touching_the_radio():
    import link
    first = link.credentials()
    assert first['ssid'] == 'Walky-617264'  # Last six hex digits of the board id.
    assert len(first['password']) >= 16
    assert link.credentials() == first
    assert network.WLAN.active_interfaces == {} and socket.listeners == []
    for damaged in ('{', '[]', '{"ssid":"Walky-617264","password":"short"}'):
        write(link.FILE, damaged)
        replaced = link.credentials()
        assert replaced['ssid'] == first['ssid'] and len(replaced['password']) >= 16
        assert link.credentials() == replaced


def test_access_point_comes_up_with_the_credentials_and_keeps_them_out_of_the_log():
    radio = start()
    configured = network.WLAN.configured
    assert (configured['ssid'], configured['key']) == (SECRET['ssid'], SECRET['password'])
    assert network.WLAN.active_interfaces == {network.WLAN.IF_STA: False, network.WLAN.IF_AP: True}
    assert network.WLAN.interference == 3
    assert not radio.connected and radio.session is None and radio.poll() == []
    radio.send({'lost': True})
    log = read('boot.log')
    assert 'access point Walky-test up' in log and 'interference mode 3' in log
    assert SECRET['password'] not in log


def test_lines_arrive_in_order_across_chunk_boundaries():
    client = socket.Client(b'{"t":"le', b'gs","l":1}\n{"t":"limp"}\n{"t":', None, b'"ping"}\n')
    radio = start(client)
    assert radio.poll() == [{'t': 'legs', 'l': 1}, {'t': 'limp'}]
    assert radio.connected and radio.session is not None and client.nodelay
    assert radio.poll() == [{'t': 'ping'}]
    assert radio.poll() == []


def test_new_connection_replaces_the_client_and_the_listener_stays_open():
    old = socket.Client(line(t='ping'), None, line(t='legs', l=5, r=5))
    new = socket.Client(line(t='limp'))
    radio = start(old, new)
    assert radio.poll() == [{'t': 'ping'}]
    first = radio.session
    assert radio.poll() == [{'t': 'limp'}]
    assert old.closed and not new.closed
    assert radio.session not in (None, first)
    radio.send({'for': 'new'})
    assert old.sent == b'' and new.lines() == [{'for': 'new'}]
    assert not socket.listeners[0].closed


def test_bad_lines_and_socket_failures_drop_only_that_client():
    bad = (b'{bad\n', b'[1]\n', b'\n', b'\xff\n', b'x' * 300, b'{"pad":"' + b'x' * 300 + b'"}\n',
           b'', OSError(errno.ECONNRESET))
    for failure in bad:
        socket.clear()
        client = socket.Client(line(t='ping'), failure)
        following = socket.Client(line(t='limp'))
        radio = start(client, following)
        # Whatever was complete before the failure is still delivered.
        assert radio.poll() == [{'t': 'ping'}]
        assert client.closed and not radio.connected and radio.session is None
        assert radio.poll() == [{'t': 'limp'}]
        assert radio.connected and not following.closed


def test_send_keeps_only_the_newest_unsent_line_and_never_splits_one():
    client = socket.Client(window=0)
    radio = start(client)
    radio.poll()
    for number in (1, 2, 3):
        radio.send({'state': number})
    assert client.sent == b''
    client.window = 7
    for _ in range(10):
        radio.poll()
    # The line already started is finished; of the two behind it only the newest goes out.
    assert client.lines() == [{'state': 1}, {'state': 3}]


def test_failed_send_drops_the_client():
    client = socket.Client()
    radio = start(client)
    radio.poll()
    client.send = lambda data: (_ for _ in ()).throw(OSError(errno.ECONNRESET))
    radio.send({'state': 1})
    assert client.closed and not radio.connected


def test_close_takes_down_client_listener_and_access_point():
    client = socket.Client()
    radio = start(client)
    radio.poll()
    radio.close()
    assert client.closed and socket.listeners[0].closed
    assert network.WLAN.active_interfaces[network.WLAN.IF_AP] is False


def test_listener_failure_is_not_swallowed():
    radio = start(OSError(errno.EIO))
    with raises(OSError):
        radio.poll()
