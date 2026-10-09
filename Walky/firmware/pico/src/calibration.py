"""Persistent trim and calibration metadata. Importing touches no hardware."""
import json
import os

VERSION = 1
FILE = 'calibration.json'
REQUEST_FILE = 'calibration-request.json'


def validate_steps(values):
    if not isinstance(values, (list, tuple)) or len(values) != 2:
        raise ValueError('Expected left/right half-degree steps')
    if any(type(v) is not int or not -18 <= v <= 18 for v in values):
        raise ValueError('Trim must be integer half-degree steps in -18..18')
    return tuple(values)


def trim_us(step):
    # Round half away from zero, identically to Swift.
    return (1 if step >= 0 else -1) * ((abs(step) * 11 + 1) // 2)


def load():
    try:
        with open(FILE) as source:
            data = json.load(source)
    except OSError as error:
        if error.args[0] == 2:
            return (0, 0), False
        raise
    if type(data) is not dict or data.get('version') != VERSION:
        raise ValueError('Unsupported calibration version')
    return validate_steps(data.get('steps')), True


def save(steps):
    steps = validate_steps(steps)
    temporary = FILE + '.tmp'
    with open(temporary, 'w') as target:
        json.dump({'version': VERSION, 'steps': list(steps)}, target)
        target.flush()
    os.sync()
    os.rename(temporary, FILE)
    os.sync()
    actual, stored = load()
    if not stored or actual != steps:
        raise OSError('Calibration readback failed')
    return actual


def board_id():
    import machine
    import binascii
    return binascii.hexlify(machine.unique_id()).decode()


def snapshot():
    result = {'version': VERSION, 'board': board_id()}
    try:
        steps, stored = load()
        result.update(steps=list(steps), stored=stored)
    except Exception as error:
        result['error'] = str(error)
    return result


def validate_request(data):
    if type(data) is not dict or data.get('version') != VERSION or data.get('board') != board_id():
        raise ValueError('Invalid calibration request identity')
    for key, minimum, maximum in (('session', 32, 64), ('password', 16, 63), ('ssid', 1, 32)):
        value = data.get(key)
        if not isinstance(value, str) or not minimum <= len(value) <= maximum:
            raise ValueError('Invalid calibration ' + key)
        # MicroPython has no str.isalnum(); credentials are deliberately ASCII.
        if not all(c in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in value):
            raise ValueError('Invalid calibration ' + key)
    return data


def arm(data):
    validate_request(data)
    with open(REQUEST_FILE + '.tmp', 'w') as target:
        json.dump(data, target)
        target.flush()
    os.sync()
    os.rename(REQUEST_FILE + '.tmp', REQUEST_FILE)
    os.sync()


def consume_request():
    try:
        with open(REQUEST_FILE) as source:
            raw = source.read(1025)
    except OSError as error:
        if error.args[0] == 2:
            return None
        raise
    # Invalid requests are consumed too, and must never fall through to motion.
    os.remove(REQUEST_FILE)
    os.sync()
    if len(raw) > 1024:
        raise ValueError('Calibration request too large')
    return validate_request(json.loads(raw))
