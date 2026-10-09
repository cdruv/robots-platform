import os
import sys
import machine
from support import write, read, raises, replace, released, ticket, FilesystemFault


def boot(mode):
    import modes
    write('mode.txt', mode)
    modes.run()


def test_import_touches_no_hardware():
    import modes
    assert (machine.outputs, machine.pins, machine.resets) == ([], {}, 0)


def test_no_file_is_idle():
    import modes
    modes.run()
    assert (machine.outputs, machine.resets) == ([], 0)
    assert (machine.pins[0], machine.pins[1]) == (0, 0)


def test_modes_lists_exactly_the_accepted_requests():
    import modes
    assert modes.MODES == ('body', 'center', 'sweep', 'once:center', 'once:sweep')
    for mode in modes.MODES:
        write('mode.txt', mode)
        assert modes.consume() == mode.split(':')[-1]
        assert ('mode.txt' in os.listdir()) == (not mode.startswith('once:'))


def test_once_center_is_finite_neutral_and_consumed():
    import servo_check
    boot('once:center')
    assert 'mode.txt' not in os.listdir()
    assert machine.pulses == [(0, 1500000), (1, 1500000)]
    assert servo_check.time.now <= 10500
    released()


def test_once_sweep_limits_order_and_return_to_center():
    import servo_check
    boot('once:sweep')
    assert 'mode.txt' not in os.listdir()
    assert all(1400000 <= value <= 1600000 for _, value in machine.pulses)
    movement = [(pin, value) for pin, value in machine.pulses if value != 1500000]
    assert movement[0][0] == 1 and movement[-1][0] == 0
    for pin in (0, 1):
        assert [v for p, v in machine.pulses if p == pin][-1] == 1500000
    assert servo_check.time.now <= 12000
    released()


def test_plain_mode_runs_again_only_after_power_cycle():
    import modes
    for mode in ('center', 'sweep'):
        machine.clear()
        boot(mode)
        assert read('mode.txt') == mode
        first_count = len(machine.pulses)
        assert first_count > 0
        modes.run()
        assert len(machine.pulses) == first_count
        machine.cause = machine.PWRON_RESET
        modes.run()
        assert len(machine.pulses) == 2 * first_count


def test_body_starts_on_power_on_but_not_after_reset():
    import body
    import modes
    calls = []
    with replace(body, 'main', lambda: calls.append(True)):
        boot('body')
        machine.cause = 3
        modes.run()
        assert len(calls) == 1
        machine.cause = machine.PWRON_RESET
        modes.run()
        assert len(calls) == 2
    assert not machine.outputs


def test_plain_mode_skips_watchdog_boot():
    machine.cause = 3
    boot('center')
    assert not machine.outputs


def test_interruption_releases_both():
    machine.interrupt = True
    with raises(KeyboardInterrupt):
        boot('once:sweep')
    released()


def test_bad_mode_and_failed_consumption_never_move():
    for mode in ('walk', 'once:walk', 'once:body', 'test', 'repeat:center'):
        with raises(ValueError):
            boot(mode)
        assert 'mode.txt' not in os.listdir()
    import modes
    with replace(modes, 'os', FilesystemFault('remove')):
        with raises(OSError):
            boot('once:sweep')
    assert not machine.outputs


def test_trimmed_center_and_sweep_stay_in_final_envelope():
    import calibration
    calibration.save((-18, 1))
    boot('once:sweep')
    assert all(1400000 <= value <= 1600000 for _, value in machine.pulses)
    assert [v for p, v in machine.pulses if p == 0][-1] == 1401000
    assert [v for p, v in machine.pulses if p == 1][-1] == 1506000
    released()


def test_calibration_overrides_without_consuming_normal_mode():
    import calibration
    import calibration_session
    calibration.arm(ticket())
    calls = []
    with replace(calibration_session, 'run', lambda request: calls.append(request)):
        boot('once:sweep')
    assert calls == [ticket()]
    assert read('mode.txt') == 'once:sweep'
    assert calibration.REQUEST_FILE not in os.listdir()
    assert not machine.outputs


def test_watchdog_boot_preserves_all_requests():
    import calibration
    calibration.arm(ticket())
    machine.cause = 3
    boot('once:sweep')
    assert read('mode.txt') == 'once:sweep'
    assert calibration.REQUEST_FILE in os.listdir()
    assert not machine.outputs


def test_corrupt_calibration_prevents_pwm():
    write('calibration.json', '{')
    with raises(ValueError):
        boot('once:center')
    assert not machine.outputs
    released()


def test_main_py_only_runs_modes():
    import modes
    calls = []
    with replace(modes, 'run', lambda: calls.append(True)):
        __import__('main')
    assert calls == [True]


def test_pulse_outside_bounds_rejected():
    import servo_output
    for pulse in (1399, 1601):
        with raises(ValueError):
            servo_output.pulse_us(pulse, 0)
