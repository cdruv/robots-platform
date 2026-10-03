# Onboard architecture

The app is a face-only scaffold. `YobotApplication` constructs `Brain`, and
`MainActivity` renders `Brain.face.state` with `FaceRenderer`.

```text
YobotApplication → Brain.face → MainActivity → static neutral FaceRenderer
                   Brain.inference → unconfigured backend (manual calls only)
```

## Code layout

`:app` contains seven Kotlin files: the application, `Brain`, activity,
`ComposeFace`, and three face drawing files. There are no placeholder Android
sensor, voice, sound, or vibration classes. `Brain` constructs only the face and
an unconfigured inference entry point.

`:core` keeps one contract file per subsystem, except outputs, which separate
the active face contract from future voice/sound/haptic contracts:

| File | Purpose |
| --- | --- |
| `output/Face.kt` | Face interface and the single neutral expression/state |
| `output/Output.kt` | Future voice, sound, haptic, and combined output contracts |
| `senses/Sense.kt` | Perception lifecycle and hearing-control interfaces |
| `events/Events.kt` | Typed events for future sensing, commands, and feedback |
| `state/RobotState.kt` | Minimal face state and future thought-trigger types |
| `executive/Executive.kt` | Executive and pure policy interfaces, result/effect contracts |
| `cognition/Cognition.kt` | Prompt/reply translation interface and intent type |
| `inference/Inference.kt` | Request/response types, provider interface, explicit entry point |
| `skills/SkillRegistry.kt` | Skill definition, call, and registry contracts |
| `telemetry/Telemetry.kt` | Logging, event, and sink contracts |

Only face rendering and explicit inference forwarding have implementations.
The default inference backend reports that no provider is connected. All other
subsystems are contracts, with no no-op instances, empty registries, or startup
wiring. Add concrete adapters when implementing their features.

There are no sensors, gestures, runtime permission prompts, memory, personalities,
background workers, heartbeat timers, frame animation loops, telemetry sinks,
provider credentials, camera dependencies, or HTTP client dependencies.

When implementing the executive, keep policy pure, state ownership sequential,
and hardware/network work outside its event loop. Locomotion remains a future
output with local safety handled by the body controller.
