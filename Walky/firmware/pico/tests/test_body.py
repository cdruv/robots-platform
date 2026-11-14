import errno
import machine
import network
import socket
from support import fresh, read, raises, replace, line, FilesystemFault

BOARD = '626f617264'


def make(saved=(0, 0), stored=False, error=None):
    import body
    from servo_output import Servos
    arms = []
    return body.Body(Servos(), saved, stored, BOARD, lambda: arms.append(True), error), arms


def legs(left, right, n=None):
    return {'t': 'legs', 'l': left, 'r': right, 'n': n}


def last_pulses():
    return [machine.pulses[-2][1] // 1000, machine.pulses[-1][1] // 1000]


def test_starts_limp_and_reports_to_a_new_client_at_once():
    body, arms = make(saved=(3, -4), stored=True)
    state = body.step([], 40, 1)
    assert state == {'t': 'state', 'v': 1, 'board': BOARD, 'mode': 'limp', 'target': (0, 0),
                     'pulses': None, 'trim': (3, -4), 'saved': (3, -4), 'stored': True,
                     'slew': 360, 'n': None, 'error': None, 'up_ms': 40}
    assert not machine.outputs and not arms


def test_legs_engage_with_mirror_and_trim_and_arm_the_watchdog_first():
    body, arms = make(saved=(2, 0), stored=True)
    body.step([], 0, 1)
    assert body.step([legs(10, -20, n=7)], 5, 1) is None  # Leg targets are not answered.
    assert arms == [True]
    assert machine.pulses == [(0, 1621000), (1, 1720000)]
    state = body.state(5)
    assert (state['mode'], state['target'], state['pulses'], state['n']) == ('active', (10, -20), [1621, 1720], 7)


def test_targets_are_clamped_to_ninety_degrees():
    body, _ = make()
    body.step([legs(200, -1e30)], 0, 1)
    assert body.target == (90, -90)
    assert last_pulses() == [2490, 2490]


def test_legs_go_limp_500_ms_after_the_last_target():
    body, _ = make()
    body.step([legs(0, 0)], 0, 1)
    body.step([], 499, 1)
    assert body.servos.engaged
    body.step([], 500, 1)
    state = body.state(500)
    assert not body.servos.engaged and all(output.stopped for output in machine.outputs)
    assert (machine.pins[0], machine.pins[1]) == (0, 0)
    assert (state['mode'], state['pulses']) == ('limp', None)
    # A steady stream keeps them on; only the gap counts.
    for now in (1000, 1400, 1800):
        body.step([legs(0, 0)], now, 1)
    body.step([], 2299, 1)
    assert body.servos.engaged
    body.step([], 2300, 1)
    assert not body.servos.engaged


def test_speed_is_limited_to_360_degrees_per_second_from_elapsed_time():
    body, _ = make()
    body.step([legs(0, 0)], 0, 1)
    body.step([legs(90, -90)], 100, 1)
    assert body.target == (90, -90)
    assert last_pulses() == [1500 + 36 * 11, 1500 + 36 * 11]
    body.step([legs(90, -90)], 150, 1)
    assert last_pulses() == [1500 + 54 * 11, 1500 + 54 * 11]
    body.step([legs(90, -90)], 400, 1)
    assert last_pulses() == [2490, 2490]
    writes = len(machine.pulses)
    body.step([legs(90, -90)], 420, 1)
    assert len(machine.pulses) == writes  # Arrived: nothing to rewrite.


def test_first_target_after_limp_is_applied_directly():
    body, _ = make()
    body.step([legs(0, 0)], 0, 1)
    body.step([{'t': 'limp'}], 10, 1)
    body.step([legs(90, 90)], 20, 1)
    assert last_pulses() == [2490, 510]


def test_backlog_is_handled_in_order_with_one_servo_write():
    body, _ = make()
    body.step([legs(0, 0)], 0, 1)
    writes = len(machine.pulses)
    body.step([legs(1, 1), legs(2, 2), legs(3, 3)], 50, 1)
    assert machine.pulses[writes:] == [(0, 1533000), (1, 1467000)]
    # A limp at the end of a batch holds; one followed by a target does not.
    state = body.step([legs(5, 5), {'t': 'limp', 'n': 4}], 60, 1)
    assert state['mode'] == 'limp' and not body.servos.engaged
    writes = len(machine.pulses)
    body.step([{'t': 'limp'}, legs(8, 8)], 70, 1)
    assert machine.pulses[writes:] == [(0, 1588000), (1, 1412000)]


def test_trim_preview_does_not_engage_and_applies_while_active():
    body, arms = make()
    state = body.step([{'t': 'trim', 'n': 1, 'steps': [2, -2]}], 0, 1)
    assert (state['trim'], state['saved'], state['stored'], state['pulses']) == ((2, -2), (0, 0), False, None)
    assert not machine.outputs and not arms
    body.step([legs(0, 0)], 10, 1)
    assert last_pulses() == [1511, 1489]
    state = body.step([{'t': 'trim', 'n': 2, 'steps': [4, 0]}], 20, 1)
    assert last_pulses() == [1522, 1500] and state['pulses'] == [1522, 1500]
    state = body.step([{'t': 'trim', 'n': 3, 'steps': [19, 0]}], 30, 1)
    assert state['error']['n'] == 3 and state['trim'] == (4, 0) and state['mode'] == 'active'


def test_save_persists_the_preview_and_state_confirms_it():
    import calibration
    body, _ = make()
    state = body.step([{'t': 'trim', 'steps': [2, -2]}, {'t': 'save', 'n': 9}], 0, 1)
    assert calibration.load() == ((2, -2), True, None)
    assert state['stored'] and state['saved'] == state['trim'] == (2, -2) and state['error'] is None
    assert not machine.outputs


def test_failed_save_is_reported_and_keeps_the_previous_trim():
    import calibration
    body, _ = make(saved=(1, 1), stored=True)
    calibration.save((1, 1))
    with replace(calibration, 'os', FilesystemFault('rename')):
        state = body.step([{'t': 'trim', 'steps': [5, 5]}, {'t': 'save', 'n': 2}], 0, 1)
    assert state['error']['n'] == 2 and state['saved'] == (1, 1) and state['trim'] == (5, 5)
    assert calibration.load() == ((1, 1), True, None)


def test_client_change_goes_limp_reverts_the_preview_and_clears_the_error():
    body, _ = make(saved=(1, 1), stored=True)
    body.step([{'t': 'trim', 'steps': [6, 6]}, legs(0, 0), {'t': 'dance', 'n': 3}], 0, 1)
    assert body.servos.engaged
    for session in (None, 2):
        body.step([{'t': 'trim', 'steps': [6, 6]}, legs(0, 0), {'t': 'dance', 'n': 3}], 0, 1)
        state = body.step([], 10, session)
        assert (state['mode'], state['trim'], state['error'], state['n']) == ('limp', (1, 1), None, None)
        assert not body.servos.engaged


def test_lines_that_cannot_be_carried_out_set_the_error_and_move_nothing():
    for bad in ({'t': 'dance'}, {}, legs('up', 0), legs(True, 0), legs(None, None),
                {'t': 'trim', 'steps': [0]}, {'t': 'trim'}):
        fresh()
        body, arms = make()
        state = body.step([dict(bad, n=12)], 0, 1)
        assert state['error']['n'] == 12 and state['error']['msg']
        assert state['mode'] == 'limp' and not machine.outputs and not arms
        # The error stays while later lines succeed.
        assert body.step([{'t': 'ping', 'n': 13}], 5, 1)['error']['n'] == 12
    # A bad target while moving neither stops the legs nor refreshes the timeout.
    body, _ = make()
    body.step([legs(0, 0)], 0, 1)
    body.step([legs('up', 0)], 400, 1)
    assert body.servos.engaged
    body.step([], 500, 1)
    assert not body.servos.engaged


def test_state_goes_out_ten_times_a_second_and_right_after_other_commands():
    body, _ = make()
    body.step([], 0, 1)
    reports = [now for now in range(5, 1005, 5) if body.step([legs(0, 0)], now, 1)]
    assert reports == list(range(100, 1005, 100))
    state = body.step([{'t': 'ping', 'n': 77}], 1010, 1)
    assert state['n'] == 77 and state['up_ms'] == 1010


def test_unreadable_stored_trim_is_reported_until_a_save_succeeds():
    body, _ = make(error='calibration.json ignored: bad')
    assert body.step([], 0, 1)['error'] == {'n': None, 'msg': 'calibration.json ignored: bad'}
    assert body.step([{'t': 'save'}], 5, 1)['error'] is None


# run(): the loop with the real link over fake sockets.

def off():
    assert all(output.stopped for output in machine.outputs)
    assert (machine.pins[0], machine.pins[1], machine.pins['LED']) == (0, 0, 0)
    assert all(listener.closed for listener in socket.listeners)
    assert network.WLAN.active_interfaces[network.WLAN.IF_AP] is False


def test_usb_interrupt_before_any_motion_stops_the_link_without_reset():
    import body
    client = socket.Client(line(t='trim', steps=[1, 1]), None, KeyboardInterrupt())
    socket.arriving.append(client)
    body.run()
    off()
    assert client.closed and not machine.outputs
    assert (machine.resets, machine.watchdogs) == (0, 0)
    log = read('boot.log')
    assert 'stopped over USB' in log and 'Traceback' not in log
    assert read('link.json')  # Credentials were created for the access point.


def test_any_exit_after_the_servos_started_releases_them_and_resets():
    for failure in (KeyboardInterrupt(), OSError(errno.EIO)):
        fresh()
        import body
        client = socket.Client(line(t='legs', l=10, r=10), None)
        socket.arriving.extend([client, None, failure])
        body.run()
        assert machine.pulses == [(0, 1610000), (1, 1390000)]
        off()
        assert client.closed
        assert (machine.resets, machine.watchdogs) == (1, 1)
        assert 'Traceback' in read('boot.log')


def test_failure_before_any_motion_is_raised_without_reset():
    import body
    socket.arriving.append(OSError(errno.EIO))
    with raises(OSError):
        body.run()
    off()
    assert (machine.resets, machine.watchdogs) == (0, 0)


def test_silent_client_goes_limp_and_keeps_getting_state():
    import body
    client = socket.Client(line(t='legs', l=10, r=10, n=1), *([None] * 130 + [KeyboardInterrupt()]))
    socket.arriving.append(client)
    body.run()
    states = client.lines()
    modes = [state['mode'] for state in states]
    # The first state goes out on connect, in the same pass as the target.
    assert modes[0] == 'active' and modes[-1] == 'limp' and states[-1]['pulses'] is None
    assert 6 <= len(states) <= 8  # About 650 ms at 10 Hz.
    assert machine.feeds > 100
    off()


def test_replacing_client_finds_the_legs_limp_and_the_stored_trim():
    import body
    import calibration
    calibration.save((2, 2))
    old = socket.Client(line(t='trim', steps=[9, 9]), line(t='legs', l=0, r=0))
    new = socket.Client(None, None, KeyboardInterrupt())
    socket.arriving.extend([old, None, None, new])
    body.run()
    assert old.closed and old.lines()[-1]['mode'] == 'active' and old.lines()[-1]['trim'] == [9, 9]
    first = new.lines()[0]
    assert (first['mode'], first['pulses'], first['trim'], first['saved']) == ('limp', None, [2, 2], [2, 2])
    off()
