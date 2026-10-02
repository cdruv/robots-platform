# Yobot onboard app

The Android app that runs on the Pixel 8 as Yobot's brain, senses and face.
How it is put together: [ARCHITECTURE.md](ARCHITECTURE.md).

## Modules

- `:core` — pure Kotlin/JVM: executive, cognition, inference, skills, telemetry and all contracts.
- `:app` — Android: senses, outputs, face renderer, composition root.

## Build and run

```sh
./gradlew :core:test           # JVM tests for all logic
./gradlew :app:installDebug    # build and install on the connected phone
```

## Configuration

Optional keys in `local.properties` (gitignored), compiled into `BuildConfig`.
The app builds without any of them.

| Key | Default | Purpose |
| --- | --- | --- |
| `yobot.openrouter.apiKey` | empty | OpenRouter API key; without it every thought fails and the face looks confused. |
| `yobot.model` | `anthropic/claude-haiku-4.5` | Model id sent to OpenRouter. |
| `yobot.telemetry.host` | empty | Mac IP for the TCP telemetry stream; empty disables it. |
| `yobot.telemetry.port` | `5555` | TCP telemetry port. |

## Telemetry

Every executive event and effect, inference request/response and log line is one
JSON object per line.

```sh
nc -lk 5555                    # on the Mac: live stream (set yobot.telemetry.host to the Mac's IP)
adb logcat -s yobot            # same events, one compact line each
adb pull /sdcard/Android/data/com.vadymsidorov.yobot/files/telemetry   # rotated JSONL files
```

The phone reconnects to the listener on its own; events produced while disconnected
are dropped and reported as `telemetry/dropped` counts.
