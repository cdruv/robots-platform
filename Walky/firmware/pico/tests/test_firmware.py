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
    actions = ('center', 'sweep', 'dance')
    assert modes.MODES == actions + tuple('once:' + action for action in actions)
    for mode in modes.MODES:
        write('mode.txt', mode)
        assert modes.consume() == mode.split(':')[-1]
        assert ('mode.txt' in os.listdir()) == (not mode.startswith('once:'))


def test_once_center_is_finite_neutral_and_consumed():
    import servo_check
    boot('once:center')
    assert 'mode.txt' not in os.listdir()
    assert machine.pulses == [(0, 1500000), (1, 1500000)]
    assert servo_check.time.now == 6000
    released()


def test_once_sweep_limits_order_and_return_to_center():
    import servo_check
    boot('once:sweep')
    assert 'mode.txt' not in os.listdir()
    targets = [1500, 500] + [2500, 500] * 6 + [1500]
    assert machine.pulses == [(side, us * 1000) for us in targets for side in (0, 1)]
    assert servo_check.time.now == 9580
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


def test_interruption_releases_both():
    machine.interrupt = True
    with raises(KeyboardInterrupt):
        boot('once:sweep')
    released()


def test_retired_modes_disarm_without_motion():
    for action in ('body', 'rotation-check', 'range-left', 'range-right'):
        for prefix in ('', 'once:'):
            with raises(ValueError):
                boot(prefix + action)
            assert 'mode.txt' not in os.listdir()
            assert not machine.outputs


def test_bad_mode_and_failed_consumption_never_move():
    for mode in ('walk', 'once:walk', 'once:once:body', 'test', 'repeat:center'):
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
    assert machine.pulses[2:-2] == [(side, us * 1000) for us in [500] + [2500, 500] * 6
                                    for side in (0, 1)]
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


def test_boot_log_records_each_boot_decision():
    import modes
    modes.run()
    machine.cause = 3
    modes.run()
    assert read('boot.log') == ('boot: power-on\nboot: mode none, idle\n'
                                'boot: reset cause 3, not power-on; nothing runs\n')


def test_dance_mirrors_alignment_poses_and_respects_trim_at_extremes():
    import calibration
    import servo_check
    calibration.save((-18, 18))
    boot('once:dance')
    assert 'mode.txt' not in os.listdir()
    left = [v // 1000 for pin, v in machine.pulses if pin == 0]
    right = [v // 1000 for pin, v in machine.pulses if pin == 1]
    poses = list(zip(left, right))
    assert poses[0] == poses[-1] == (1401, 1599)
    for angle in (-81, -45, 45, 81):
        assert (1401 + 11 * angle, 1599 - 11 * angle) in poses
    assert (1896, 1599) in poses and (1401, 1104) in poses
    for values in (left, right):
        assert min(values) >= 500 and max(values) <= 2500
        assert len(values) == 40  # Initial center plus 39 direct pose commands.
    assert (2292, 2490) in poses and (510, 708) in poses  # Opposed full splits.
    assert servo_check.time.now - 3000 >= 3 * 2820
    released()
