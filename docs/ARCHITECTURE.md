# Architecture

```
 Android phone                                 Your AWS account
 ┌──────────────────────────────┐   HTTPS      ┌───────────────────────────────────────────┐
 │ AssistantService (always on) │ ───────────▶ │ CloudFront ─▶ Lambda "brain" (Node 20)     │
 │  wake word (openWakeWord)    │ ◀─ NDJSON ── │   Claude (tools, web search, memory)       │
 │  voiceprint (CAM++)          │   streamed   │   ElevenLabs voice (Polly fallback)        │
 │  talk-over (call-mode audio) │   sentences  │   DynamoDB: history, facts, settings,      │
 │  FastPath: home commands     │              │             to-do, notices                 │
 │  texts, calendar, calls      │              │   Scheduler: keep warm, mail cleanup        │
 │  car mode, sleep mode        │              └───────────────────────────────────────────┘
 │  Home devices (local Wi-Fi) ─┼──▶ TV, ...         Plugins: email (Gmail / Microsoft 365),
 └──────────────────────────────┘                   Claude Code bridge (relay on your computer)
```

## One turn

1. **Wake.** The mic runs openWakeWord on 80 ms chunks. A detection is checked against your
   voiceprint (if enrolled) using the last ~1.5 s of audio.
2. **Hear.** Android's speech recogniser (on-device when available) turns the request into text.
3. **Fast path.** Plain home commands ("pause", "TV off", "volume to 20") are matched on the phone,
   answered from a pre-recorded clip and done locally. The brain is told afterwards.
4. **Brain.** Everything else is POSTed to `/api/chat` with context the phone has and the brain
   doesn't: the next week of calendar, recent texts, recent missed calls, whether car mode is on.
5. **Stream.** The brain streams NDJSON: one line per voiced sentence (`{t:"audio", text, audio}`)
   as Claude writes it, then a `done` line with the full reply and any **phone actions**
   (send a text, add an event, TV command, go quiet...). The phone plays sentences as they land.
6. **Follow-up.** After it speaks, the phone listens once more without the wake phrase.

While it speaks, the mic stays open in call mode (see [LESSONS.md](LESSONS.md)). Sustained speech
that matches your voice pauses playback, the audio goes to `/api/transcribe`, Claude decides whether
it was said to the assistant, and the phone either stops and handles it or resumes.

## Where the time goes

| Step | Typical |
|---|---|
| Wake word to "listening" | ~0.4 s |
| End of speech to text | near instant (streaming recogniser) |
| Fast-path command | under 0.1 s to the first sound |
| Brain, first spoken sentence | 1-3 s; web searches add a spoken "one moment" |

## Code map

| Path | What |
|---|---|
| `lambda/index.mjs` | Routes, the system prompt (built from `config.json`), tools, streaming voice |
| `lambda/mail.mjs`, `cleanup.mjs`, `charges.mjs` | Email plugin |
| `lambda/bridge.mjs`, `bridge/*.py` | Claude Code bridge plugin |
| `android/.../AssistantService.kt` | The always-on service: wake, listen, talk-over, playback, actions |
| `android/.../FastPath.kt` | On-phone home commands and their pre-recorded lines |
| `android/.../SamsungTv.kt` | TV control on the local network |
| `android/.../HomeScan.kt`, `HomeActivity.kt` | "What's on your Wi-Fi": mDNS + SSDP discovery, classification, connect |
| `android/.../Persona.kt` | Name, wake phrase and form of address from `config.json` |
| `shared/commands.json` | The one list of what you can say: the brain and the phone both read it |
| `infra/template.yaml` | The whole AWS side, one stack |
| `scripts/` | setup, keys, deploy, mail sign-in, the private-string check |
