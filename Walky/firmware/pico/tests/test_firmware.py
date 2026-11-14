import os
import machine
from support import write, read, raises, replace, released, FilesystemFault


def boot(mode=None):
    """One boot with the body link stubbed out. Returns whether the link was started."""
    import body
    import modes
    if mode is not None:
        write('mode.txt', mode)
    starts = []
    with replace(body, 'run', lambda: starts.append(True)):
        modes.run()
    return bool(starts)


def test_import_touches_no_hardware():
    import modes
    import body
    import link
    assert (machine.outputs, machine.pins, machine.resets) == ([], {}, 0)


def test_no_mode_starts_the_body_link_with_signals_off():
    assert boot()
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
    assert not boot('once:center')
    assert 'mode.txt' not in os.listdir()
    assert machine.pulses == [(0, 1500000), (1, 1500000)]
    assert servo_check.time.now == 6000
    released()


def test_once_sweep_swings_opposed_through_the_full_arc_and_returns_to_center():
    import servo_check
    boot('once:sweep')
    assert 'mode.txt' not in os.listdir()
    # 90 degrees is 990 us. Opposed legs get equal pulses because the right servo is mirrored.
    targets = [1500, 510] + [2490, 510] * 6 + [1500]
    assert machine.pulses == [(side, us * 1000) for us in targets for side in (0, 1)]
    assert servo_check.time.now == 9580
    released()


def test_plain_mode_runs_on_each_power_on_and_the_link_follows_its_reset():
    for mode in ('center', 'sweep'):
        machine.clear()
        assert not boot(mode)
        assert read('mode.txt') == mode
        first_count = len(machine.pulses)
        assert first_count > 0
        # The servo check ended in a reset, which is not a power-on.
        assert boot()
        assert len(machine.pulses) == first_count
        machine.cause = machine.PWRON_RESET
        assert not boot()
        assert len(machine.pulses) == 2 * first_count


def test_reset_that_is_not_power_on_keeps_the_request_and_starts_the_link():
    machine.cause = 3
    assert boot('once:sweep')
    assert read('mode.txt') == 'once:sweep'
    assert not machine.outputs


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


def test_degrees_are_mirrored_trimmed_once_and_clipped_at_the_servo_limits():
    from servo_output import Servos
    servos = Servos((18, -18))
    assert not servos.engaged and servos.pulses is None and not machine.outputs
    servos.set_degrees(10, 10)
    assert servos.engaged and servos.pulses == [1500 + 110 + 99, 1500 - 110 - 99]
    servos.set_degrees(12.5, -30)
    assert servos.pulses == [1500 + 138 + 99, 1500 + 330 - 99]
    servos.set_degrees(90, 90)
    assert servos.pulses == [2500, 500]
    servos.set_degrees(-90, -90)
    assert servos.pulses == [1500 - 990 + 99, 1500 + 990 - 99]
    assert machine.pulses[-2:] == [(0, 609000), (1, 2391000)]
    servos.set_trim((0, 0))
    assert servos.pulses == [609, 2391] and servos.span() == 90
    servos.set_degrees(-90, -90)
    assert servos.pulses == [510, 2490]
    servos.release()
    assert not servos.engaged and servos.pulses is None
    assert all(output.stopped for output in machine.outputs)
    assert (machine.pins[0], machine.pins[1]) == (0, 0)
    servos.set_degrees(0, 0)
    assert servos.pulses == [1500, 1500] and not machine.outputs[-1].stopped


def test_trimmed_sweep_clips_at_the_servo_limits():
    import calibration
    calibration.save((-18, 1))
    boot('once:sweep')
    # Centers are 1401 and 1506; only the left leg's low end passes a limit.
    low, high = [(0, 500000), (1, 516000)], [(0, 2391000), (1, 2496000)]
    assert machine.pulses[2:-2] == low + (high + low) * 6
    assert machine.pulses[:2] == machine.pulses[-2:] == [(0, 1401000), (1, 1506000)]
    released()


def test_unreadable_calibration_runs_untrimmed_and_is_logged():
    write('calibration.json', '{')
    boot('once:center')
    assert machine.pulses == [(0, 1500000), (1, 1500000)]
    assert 'untrimmed, calibration.json ignored' in read('boot.log')
    released()


def test_main_py_only_runs_modes():
    import modes
    calls = []
    with replace(modes, 'run', lambda: calls.append(True)):
        __import__('main')
    assert calls == [True]


def test_main_py_logs_failures_but_not_a_usb_interrupt():
    import sys
    import modes

    def fails(error):
        def run():
            raise error
        return run

    with replace(modes, 'run', fails(KeyboardInterrupt())):
        with raises(KeyboardInterrupt):
            __import__('main')
    assert 'boot.log' not in os.listdir()
    sys.modules.pop('main', None)
    with replace(modes, 'run', fails(ValueError('broken'))):
        with raises(ValueError):
            __import__('main')
    assert 'ValueError: broken' in read('boot.log')


def test_boot_log_records_each_boot_decision():
    boot()
    boot('once:center')
    boot()
    assert read('boot.log') == ('boot: power-on, body link\n'
                                'boot: power-on, center\n'
                                'boot: reset cause 3, body link\n')


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
