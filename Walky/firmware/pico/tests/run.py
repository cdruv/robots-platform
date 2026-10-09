"""One test suite, executed by the pinned MicroPython Unix interpreter."""
import sys

if sys.implementation.name != 'micropython' or sys.implementation.version[:3] != (1, 28, 0):
    print('Tests require MicroPython 1.28.0; run tests/test.sh.')
    sys.exit(1)

from support import fresh

passed = failed = 0
for module_name in ('test_firmware', 'test_calibration'):
    module = __import__(module_name)
    for name in sorted(dir(module)):
        if not name.startswith('test_'):
            continue
        try:
            fresh()
            getattr(module, name)()
            print('PASS', module_name + '.' + name)
            passed += 1
        except BaseException as error:
            print('FAIL', module_name + '.' + name)
            sys.print_exception(error)
            failed += 1
print('%d passed, %d failed (MicroPython 1.28.0)' % (passed, failed))
sys.exit(1 if failed else 0)
