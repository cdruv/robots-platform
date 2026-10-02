# Onboard architecture

```
 Senses ──percepts──▶ ┌───────────┐ ──targets──▶ Output (face, voice, haptics, sounds)
 (hearing, motion,    │ Executive │ ◀─feedback──
  vision, touch,      │  (actor)  │ ──skill calls──▶ Skills ──▶ Output
  system)             └───────────┘
                         │     ▲
                compose  │     │ ThoughtResult
                         ▼     │
                    Cognition ─▶ Inference ─▶ OpenRouter (OpenAI-compatible)

 Every event and effect ──▶ Telemetry ──▶ Logcat, JSONL files, TCP to the Mac
```

- **Executive** — the only owner of `RobotState`. One coroutine, one mailbox, never blocks.
  Policy is a pure `step(state, event, now) -> (state, effects)` function; a thin runtime
  carries out the effects.
- **Senses** — each runs on its own thread or callback loop and only posts percepts.
- **Output** — expression and actuation behind small contracts; calls return immediately.
  Locomotion will be another output.
- **Cognition** — pure translation between state and the model: builds the prompt from
  character, memory, skill catalogue and a situation snapshot; parses replies leniently.
- **Inference** — one request in flight; results carry a request id and stale ones are
  dropped. A single backend behind an interface, with a manager as the seam for routing.
- **Skills** — bounded, validated actions the model may request. Speech and expression
  are reply fields, not skills.
- **Telemetry** — non-blocking structured events; each sink is isolated from the others.
