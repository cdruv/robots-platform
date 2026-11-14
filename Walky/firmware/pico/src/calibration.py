"""Persistent trim and calibration metadata. Importing touches no hardware."""
import json
import os

VERSION = 1
FILE = 'calibration.json'


def validate_steps(values):
    if not isinstance(values, (list, tuple)) or len(values) != 2:
        raise ValueError('Expected left/right half-degree steps')
    if any(type(v) is not int or not -18 <= v <= 18 for v in values):
        raise ValueError('Trim must be integer half-degree steps in -18..18')
    return tuple(values)


def trim_us(step):
    # Half-degree steps at 11 us per degree; halves round away from zero.
    return (1 if step >= 0 else -1) * ((abs(step) * 11 + 1) // 2)


def load():
    """Stored trim as (steps, stored, error).

    A missing file is the normal uncalibrated state. An unreadable one is
    reported as an error and treated as uncalibrated, so the legs still move.
    """
    try:
        with open(FILE) as source:
            data = json.load(source)
        if type(data) is not dict or data.get('version') != VERSION:
            raise ValueError('Unsupported calibration version')
        return validate_steps(data.get('steps')), True, None
    except OSError as error:
        if error.args[0] == 2:
            return (0, 0), False, None
        raise
    except ValueError as error:
        return (0, 0), False, FILE + ' ignored: ' + str(error)


def save(steps):
    steps = validate_steps(steps)
    temporary = FILE + '.tmp'
    with open(temporary, 'w') as target:
        json.dump({'version': VERSION, 'steps': list(steps)}, target)
        target.flush()
    os.sync()
    os.rename(temporary, FILE)
    os.sync()
    actual, stored, _ = load()
    if not stored or actual != steps:
        raise OSError('Calibration readback failed')
    return actual


def board_id():
    import machine
    import binascii
    return binascii.hexlify(machine.unique_id()).decode()


def snapshot():
    steps, stored, error = load()
    result = {'version': VERSION, 'board': board_id(), 'steps': list(steps), 'stored': stored}
    if error:
        result['error'] = error
    return result
