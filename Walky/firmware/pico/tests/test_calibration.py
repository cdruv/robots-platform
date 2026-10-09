import json
import os
import machine
from support import fresh, write, read, raises, replace, released, ticket, FilesystemFault


def test_rounding_limits_and_pair_validation():
    import calibration as cal
    import servo_output
    assert [cal.trim_us(v) for v in (-18, -1, 0, 1, 18)] == [-99, -6, 0, 6, 99]
    assert servo_output.pulse_us(1500, -1) == 1494
    for value in ([19, 0], [0], [True, 0], [0.5, 0], None):
        with raises(ValueError):
            cal.validate_steps(value)
    for nominal, step in ((1502, 18), (1498, -18)):
        with raises(ValueError):
            servo_output.pulse_us(nominal, step)


def test_storage_missing_saved_corrupt_unknown():
    import calibration as cal
    assert cal.load() == ((0, 0), False)
    cal.save((-1, 18))
    assert cal.load() == ((-1, 18), True)
    for data in ('{', '{"version":2,"steps":[0,0]}', '{"version":1,"steps":[100,0]}'):
        write(cal.FILE, data)
        with raises(ValueError):
            cal.load()
        assert 'error' in cal.snapshot()


def test_failed_replacement_preserves_last_save():
    import calibration as cal
    cal.save((2, 3))
    with replace(cal, 'os', FilesystemFault('rename')):
        with raises(OSError):
            cal.save((4, 5))
    assert cal.load() == ((2, 3), True)


def test_request_validation_on_micropython_strings():
    import calibration as cal
    # Actual MicroPython strings, no compatibility shim: catches isalnum regressions.
    cal.arm(ticket())
    assert cal.consume_request() == ticket()
    for value in ('é' * 32, 'a' * 31 + '!', 'a' * 31 + ' '):
        with raises(ValueError):
            cal.validate_request(dict(ticket(), password=value))


def test_request_consumed_without_touching_mode():
    import calibration as cal
    write('mode.txt', 'once:sweep')
    cal.arm(ticket())
    assert cal.consume_request() == ticket()
    assert cal.consume_request() is None
    assert read('mode.txt') == 'once:sweep'
    write(cal.REQUEST_FILE, '{')
    with raises(ValueError):
        cal.consume_request()
    assert cal.REQUEST_FILE not in os.listdir()


def message(command, id=0, **kwargs):
    request = ticket()
    return dict(version=1, board=request['board'], session=request['session'],
                id=id, command=command, **kwargs)


def protocol():
    import calibration_session
    from servo_output import Servos
    servos = Servos((0, 0))
    return calibration_session.Session(ticket(), servos), servos


def test_preview_is_atomic_volatile_and_save_persists():
    import calibration as cal
    session, servos = protocol()
    session.handle(message('hello'))
    reply = session.handle(message('preview', 1, steps=[-1, 18]))
    assert reply['pulses'] == [1494, 1599]
    assert cal.load() == ((0, 0), False)
    previous = list(machine.pulses)
    with raises(ValueError):
        session.handle(message('preview', 2, steps=[0, 19]))
    assert machine.pulses == previous
    assert servos.steps == (-1, 18)
    reply = session.handle(message('save', 3))
    assert reply['finished']
    assert all(output.stopped for output in machine.outputs)
    assert cal.load() == ((-1, 18), True)


def test_cancel_discards_preview():
    import calibration as cal
    session, _ = protocol()
    session.handle(message('hello'))
    session.handle(message('preview', 1, steps=[3, 4]))
    session.handle(message('cancel', 2))
    assert all(output.stopped for output in machine.outputs)
    assert cal.load() == ((0, 0), False)


def test_handshake_identity_replay_unknown_commands():
    for request in (message('preview', steps=[0, 0]), [],
                    dict(message('hello'), session='wrong'), message('drive')):
        session, _ = protocol()
        with raises(ValueError):
            session.handle(request)
    session, _ = protocol()
    session.handle(message('hello'))
    with raises(ValueError):
        session.handle(message('heartbeat'))
    with raises(ValueError):
        session.handle(message('drive', 1))


def test_deadlines():
    from calibration_session import expired
    assert not expired(59999, 0, 0, False)
    assert expired(60000, 0, 0, False)
    assert not expired(1999, 0, 0, True)
    assert expired(2000, 0, 0, True)
    assert expired(600000, 0, 599999, True)


def test_network_cleanup_on_cancel_disconnect_malformed_and_timeout():
    import network
    import socket
    hello = (json.dumps(message('hello')) + '\n').encode()
    cancel = (json.dumps(message('cancel', 1)) + '\n').encode()
    cases = (([hello, cancel], False, None), ([hello, b''], False, OSError),
             ([b'{bad\n'], False, ValueError), ([b'x' * 1025], False, ValueError),
             ([hello], False, OSError), ([], True, OSError))
    for messages, no_client, error in cases:
        clock = fresh()
        import calibration_session
        network.WLAN.active_interfaces = {}
        socket.messages = list(messages)
        socket.no_client, socket.closed, socket.replies = no_client, False, b''
        if error is None:
            calibration_session.run(ticket())
            assert b'"finished": true' in socket.replies
        else:
            with raises(error):
                calibration_session.run(ticket())
        released()
        assert socket.closed
        assert network.WLAN.active_interfaces == {network.WLAN.IF_STA: False, network.WLAN.IF_AP: False}
        if no_client:
            assert clock.now >= 65000
