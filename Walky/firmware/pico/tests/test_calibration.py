import os
from support import fresh, write, raises, replace, FilesystemFault


def test_rounding_and_pair_validation():
    import calibration as cal
    assert [cal.trim_us(v) for v in (-18, -1, 0, 1, 18)] == [-99, -6, 0, 6, 99]
    for value in ([19, 0], [0], [True, 0], [0.5, 0], None):
        with raises(ValueError):
            cal.validate_steps(value)


def test_storage_missing_saved_and_unreadable():
    import calibration as cal
    assert cal.load() == ((0, 0), False, None)
    cal.save((-1, 18))
    assert cal.load() == ((-1, 18), True, None)
    assert cal.snapshot() == {'version': 1, 'board': '626f617264', 'steps': [-1, 18], 'stored': True}
    for data in ('{', '{"version":2,"steps":[0,0]}', '{"version":1,"steps":[100,0]}', '[]'):
        write(cal.FILE, data)
        steps, stored, error = cal.load()
        assert (steps, stored) == ((0, 0), False) and cal.FILE in error
        snapshot = cal.snapshot()
        assert snapshot['error'] == error and snapshot['steps'] == [0, 0] and not snapshot['stored']


def test_failed_replacement_preserves_last_save():
    import calibration as cal
    cal.save((2, 3))
    with replace(cal, 'os', FilesystemFault('rename')):
        with raises(OSError):
            cal.save((4, 5))
    assert cal.load() == ((2, 3), True, None)


def test_boot_log_rotates_and_never_raises():
    fresh()
    import bootlog
    for _ in range(300):
        bootlog.write('x' * 30)
    assert os.stat(bootlog.FILE)[6] <= bootlog.LIMIT + 31
    assert bootlog.FILE + '.1' in os.listdir()
    with replace(bootlog, 'FILE', 'missing/boot.log'):
        bootlog.write('lost')
        bootlog.exception(ValueError('lost'))
