"""Walky body controller: leg targets from the phone, with the timeout, stop and
watchdog owned here.

Wire protocol "body v1". The Pico is the server (link.py: its own Wi-Fi network,
TCP port 8765) and the phone is its only client. Every line is one JSON object.

Phone -> Pico. Nothing is answered directly; the state line is the truth. "n" is
optional and opaque: the state echoes the "n" of the last line processed.

  {"t":"legs","n":7,"l":12.5,"r":-30}  Leg targets in degrees from horizontal,
                                       before trim. The same sign is the same
                                       physical direction on both legs. Clamped
                                       to +-90. Turns the servo signals on.
  {"t":"limp","n":8}                   Servo signals off now.
  {"t":"trim","n":9,"steps":[l,r]}     Try a trim without storing it: integer
                                       half-degree steps, -18..18. Does not turn
                                       the signals on. Goes back to the stored
                                       trim when the client changes.
  {"t":"save","n":10}                  Store the trim being tried.
  {"t":"ping","n":11}                  Send a state line now.

Pico -> phone, 10 times a second and right after any line other than "legs":

  {"t":"state","v":1,"board":"<hex id>","mode":"limp"|"active",
   "target":[l,r],"pulses":[usL,usR]|null,"trim":[l,r],"saved":[l,r],
   "stored":bool,"slew":360,"n":<echo>|null,
   "error":{"n":<echo>|null,"msg":"..."}|null,"up_ms":<ms since boot>}

  target  the last leg targets accepted, after clamping
  pulses  the pulse widths on the servo pins in microseconds, null while limp
  trim    the trim in use; saved is the one in flash; stored is false until a
          trim has been saved. A save has worked when stored is true and saved
          equals trim.
  slew    the speed limit below, in degrees per second
  error   the last line that could not be carried out, or a stored trim that
          could not be read. Kept until the client changes or a save succeeds.

Rules:
- The legs go limp when no "legs" line has arrived for DEADMAN_MS, and when the
  client changes or disconnects.
- Lines are handled in order once per loop pass and the servos are written once
  at the end of it, so a backlog is never replayed and a "limp" in it holds
  unless a later "legs" follows.
- Each leg moves toward its target at no more than MAX_DEG_PER_S. The first
  target after limp is applied directly, because the leg position is unknown.
- Pulses are clipped to 500-2500 us after trim (servo_output.py).
- A line that cannot be carried out sets "error" and the connection stays. A
  line that is not a JSON object, or is longer than 256 bytes, drops the client.
- A new connection replaces the current client.
"""
import time
import bootlog
import calibration

VERSION = 1
DEADMAN_MS = 500
STATE_MS = 100
MAX_DEG_PER_S = 360
MAX_DEG = 90
TICK_MS = 5


def _degrees(value):
    if type(value) not in (int, float) or value != value:
        raise ValueError('Leg targets must be numbers')
    return max(-MAX_DEG, min(MAX_DEG, value))


class Body:
    """Protocol and motion state, apart from sockets and timing so it runs on a host.

    arm() is called every time the servo signals are about to turn on.
    """

    def __init__(self, servos, saved, stored, board, arm, error=None):
        self.servos = servos
        self.saved = self.trim = tuple(saved)
        self.stored = stored
        self.board = board
        self.arm = arm
        self.storage_error = error
        self.error = None
        self.session = None
        self.echo = None
        self.active = False
        self.target = (0, 0)
        self.position = None
        self.written = None
        self.heard = self.moved = self.reported = 0
        servos.set_trim(self.trim)

    def step(self, messages, now, session):
        """One loop pass. Returns a state line to send, or None."""
        report = False
        if session != self.session:
            self.session = session
            self.active = False
            self.trim = self.saved
            self.error = None
            self.echo = None
            report = True
        for message in messages:
            kind = message.get('t')
            self.echo = message.get('n')
            try:
                self._handle(kind, message, now)
            except (ValueError, OSError) as error:
                self.error = {'n': self.echo, 'msg': str(error)}
                report = True
            if kind != 'legs':
                report = True
        if self.active and time.ticks_diff(now, self.heard) >= DEADMAN_MS:
            self.active = False
        self._write(now)
        if report or time.ticks_diff(now, self.reported) >= STATE_MS:
            self.reported = now
            return self.state(now)
        return None

    def _handle(self, kind, message, now):
        if kind == 'legs':
            self.target = (_degrees(message.get('l')), _degrees(message.get('r')))
            self.heard = now
            self.active = True
        elif kind == 'limp':
            self.active = False
        elif kind == 'trim':
            self.trim = calibration.validate_steps(message.get('steps'))
        elif kind == 'save':
            self.saved = calibration.save(self.trim)
            self.stored = True
            self.storage_error = None
        elif kind != 'ping':
            raise ValueError('Unknown command')

    def _write(self, now):
        if not self.active:
            if self.servos.engaged:
                self.servos.release()
            self.position = self.written = None
            return
        if self.position is None:
            self.arm()
            self.position = self.target
        else:
            reach = MAX_DEG_PER_S * time.ticks_diff(now, self.moved) / 1000
            self.position = tuple(at + max(-reach, min(reach, to - at))
                                  for at, to in zip(self.position, self.target))
        self.moved = now
        if self.written != (self.position, self.trim):
            self.servos.set_trim(self.trim)
            self.servos.set_degrees(*self.position)
            self.written = (self.position, self.trim)

    def state(self, now):
        error = self.error
        if error is None and self.storage_error:
            error = {'n': None, 'msg': self.storage_error}
        return {'t': 'state', 'v': VERSION, 'board': self.board,
                'mode': 'active' if self.active else 'limp',
                'target': self.target, 'pulses': self.servos.pulses,
                'trim': self.trim, 'saved': self.saved, 'stored': self.stored,
                'slew': MAX_DEG_PER_S, 'n': self.echo, 'error': error, 'up_ms': now}


def run():
    from machine import Pin, WDT, reset
    import link
    from servo_output import Servos

    servos = Servos()
    watchdog = radio = None
    led = Pin('LED', Pin.OUT)

    # The RP2350 watchdog cannot be stopped once started, so it starts with the
    # first servo signal: from then on a stalled loop resets the board.
    def arm():
        nonlocal watchdog
        if watchdog is None:
            watchdog = WDT(timeout=2000)

    try:
        saved, stored, error = calibration.load()
        body = Body(servos, saved, stored, calibration.board_id(), arm, error)
        radio = link.Link(link.credentials())
        lit = None
        while True:
            now = time.ticks_ms()
            if watchdog is not None:
                watchdog.feed()
            state = body.step(radio.poll(), now, radio.session)
            if state is not None:
                radio.send(state)
            # Steady with a client, slow blink while waiting for one.
            light = radio.connected or now // 500 % 2 == 0
            if light != lit:
                lit = light
                led.value(lit)
            time.sleep_ms(TICK_MS)
    except BaseException as error:
        if watchdog is not None:
            bootlog.write('body: stopped with the servos started; resetting')
            bootlog.exception(error)
        elif isinstance(error, KeyboardInterrupt):
            # Robot Relay interrupts the board to talk to it over USB. With the
            # servos never started this is routine: stop the link, stay at the REPL.
            bootlog.write('body: stopped over USB; link is down until the next reset')
        else:
            raise
    finally:
        try:
            servos.release()
            if radio is not None:
                radio.close()
            led.off()
        finally:
            if watchdog is not None:
                reset()
