# Yobot onboard app

A minimal native Android scaffold for Yobot on the Pixel 8. The app currently
renders one static neutral face, full-screen in portrait, and keeps the screen on.
There is no blinking, gaze animation, touch handling, sensing, speech, vibration,
memory, autonomous behavior, or model connection.

## Modules

- `:core` — compact Kotlin/JVM contracts for events, state, executive, cognition,
  inference, skills, outputs, senses, and telemetry.
- `:app` — the face renderer and composition root. No placeholder Android adapters.

[ARCHITECTURE.md](ARCHITECTURE.md) describes the extension points.

## Build and run

```sh
./gradlew :core:test
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The last command installs on a connected phone. Launch Yobot to display the face.
No runtime permissions or service credentials are needed. `local.properties` may
still specify `sdk.dir`; previous `yobot.*` provider/telemetry keys are no longer read
or compiled into the app.

## Next iteration: connect a model explicitly

1. Implement `InferenceBackend` for the chosen provider.
2. Inject it into `Inference` in `Brain`.
3. Add an explicit manual request entry point and verify a request/response round trip.
4. Add cognition, sensing, or output behavior only when needed.

Until then, a direct inference call fails with “No inference provider is connected.”
No requests run on launch or on a timer. Other subsystems are interfaces only;
add implementations and connect them in `Brain` when their features are needed.
