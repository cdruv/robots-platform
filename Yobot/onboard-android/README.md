# Yobot onboard app

Native Android app for Yobot on the Pixel 8. It renders an animated face full-screen
in portrait, keeps the screen on, listens continuously with on-device speech recognition,
and streams structured telemetry (including every transcript) to Logcat, to a TCP port for
the workstation, and to a debug overlay on top of the face.

## Modules

- `:core` — Kotlin/JVM contracts for events, state, executive, cognition, inference, skills,
  outputs, and senses, plus the telemetry implementation (queue, batching, sinks) and its tests.
- `:app` — the face renderer, the hearing sense, the Logcat sink, the debug overlay, and the
  composition root `Brain`.

[ARCHITECTURE.md](ARCHITECTURE.md) describes how the pieces connect.

## Build and run

```sh
./gradlew :core:test
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The last command installs on a connected phone. On first launch the app asks for the
microphone permission; hearing starts as soon as it is granted and runs while the app is in
the foreground. `local.properties` only needs `sdk.dir`.

## Debug overlay

Tap the round three-dot button in the bottom-right corner to show or hide the overlay. The face
dims behind it. It shows:

- hearing state (listening, speech, muted, error), recognizer engine, session count, and a
  mic level bar, with a `[mute]` toggle;
- the live partial transcript, or the last final one;
- the telemetry stream: port, number of connected clients, the phone's IP addresses, and
  dropped-event count;
- readable event summaries, newest at the bottom. Tap an event to expand its full,
  selectable JSON record. Partial transcripts are hidden by default; use `[show partials]`
  to include them (the live transcript remains visible above).

## Streaming telemetry to the Mac

The phone serves JSON lines on TCP port 7777. Over USB:

```sh
./tools/telemetry-tail.sh              # raw JSON lines
./tools/telemetry-tail.sh --pretty     # time, severity/event label, source, and summary, via jq
./tools/telemetry-tail.sh | tee run.jsonl
```

The script runs `adb forward tcp:7777 tcp:7777`, connects to `localhost:7777`, and reconnects
every second, so it survives app restarts. Over Wi-Fi, pass the phone's address shown in the
overlay: `./tools/telemetry-tail.sh 192.168.1.42`. Each new connection first receives the last
200 events. Logcat has the same events: `adb logcat -s yobot`.

Example lines:

```json
{"seq":12,"tsWallMs":1790000000000,"tsMonoNs":123456789,"source":"hearing","kind":"log.info","payload":{"msg":"hearing using on-device recognizer, locale en-US"}}
{"seq":40,"tsWallMs":1790000004000,"tsMonoNs":223456789,"source":"sense","kind":"HeardUtterance","payload":{"type":"HeardUtterance","text":"hello yobot","isFinal":true,"confidence":0.91}}
```

## Next iterations

1. Executive: own `RobotState`, consume percepts sequentially, and keep policy pure.
2. Connect a model: implement `InferenceBackend`, inject it into `Inference` in `Brain`.
3. Add outputs (voice, sounds, haptics) and further senses behind their existing contracts.

A direct inference call still fails with “No inference provider is connected.” No requests
run on launch or on a timer.
