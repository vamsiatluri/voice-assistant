# Lessons

What took the longest to get right, building a phone assistant you can talk to like a person.
Each one cost hours; most of them fail silently.

## Talking over it

**You can't hear the user over your own voice unless the phone thinks it's a phone call.**
Played as media (`USAGE_MEDIA`), the assistant's own voice came back through the "echo-cancelled"
microphone at 70-79 dB, as loud as the person talking to it. The echo canceller only knows what to
remove when the sound is a call: `MODE_IN_COMMUNICATION`, `USAGE_VOICE_COMMUNICATION`, the speaker
chosen with `setCommunicationDevice`, and the mic opened as `VOICE_COMMUNICATION`. With that, its
own voice measures about 0 dB and anything sustained is a person.

**The phone's own speech recognisers won't transcribe audio you hand them.** Both the default and
the on-device `SpeechRecognizer` returned empty results for piped PCM, every time. The phone keeps
recording when it hears talking (from 1.5 s back, until a pause) and the brain transcribes it with
ElevenLabs Scribe.

**Leaving call mode takes about a second**, and blocks while it does. Switching back to normal
audio before listening for the user's answer swallowed quick replies ("yes" to "shall I send it?").
Start listening first, leave call mode afterwards, off the main thread.

**"Somebody talked" is not "somebody talked to me".** In a family room, people talk. The first
version stopped mid-sentence for any voice that loosely matched the owner's, then answered
conversations it was never part of. Now it **pauses**, transcribes, and asks Claude, with what it
was saying as context, whether the words were addressed to it. If not, it resumes from where it
paused and ignores the room for the rest of that reply. Lean towards "no": the user can always say
the wake phrase.

**A one-word classifier with a tiny `max_tokens` can answer with nothing.** Newer Claude models
think before answering, even at low effort, and thinking can't be switched off. With `max_tokens: 5`
the model often spent the whole budget thinking and returned no text at all, and the code read the
empty answer as "yes". Give it room (a few hundred tokens), read the last YES/NO word, and treat
no answer as the safe default.

## Wake word and voiceprint

**The wake score and the voiceprint disagree in noisy rooms.** With the TV on, the owner's own wake
phrase scored 0.45-0.55 on the voiceprint against a 0.55 threshold. A very confident wake detection
(0.9+) now relaxes the voiceprint threshold slightly.

**Ready-made wake-word models are non-commercial.** Every pre-trained openWakeWord model is
CC BY-NC-SA. Train your own; it's an hour in a free notebook.

## Android

- **Cleartext HTTP is blocked**, including to devices on your own Wi-Fi. The TV's info endpoint is
  plain HTTP, so it's read over a raw socket.
- **Other apps are invisible** unless declared in `<queries>` (Android 11+). Launching one you didn't
  declare silently does nothing.
- **Updating the app kills the always-on service**, and a microphone foreground service can only be
  restarted from the foreground. Open the app after every install.
- **mDNS discovery of the TV was unreliable**; a sweep of the /24 for the TV's port is the fallback.
- **A background service can't open another app, and Android doesn't tell you.** `startActivity` from
  the always-on service returns normally and nothing appears (logcat: "Background activity launch
  blocked"). The assistant had already said "I've opened Amazon". Open directly only while your own
  screen is showing; otherwise post a notification to tap, and have the brain say exactly that.
- **Wake-on-LAN broadcast alone didn't wake the TV.** Send it to the broadcast address and the TV's
  own IP.
- **The first key press after the user allows the app on the TV is sometimes ignored.** Verify the
  state and press once more.

## The brain

- **The API rejects a request with more than 20 strict tools.** Adding the 21st took every request
  down at once ("my brain is not responding"). Mark simple tools `strict: false`, and the deploy
  script now refuses to ship more than 20.
- **Build the tool list in one place.** A new tool added to one list but not the one the request
  actually used meant the model kept saying it couldn't do something it could.
- **Saying it isn't doing it.** "I'll stop listening" in words did nothing until there was a tool
  that actually told the phone to stop. If a behaviour matters, make it a tool call, not a sentence.
- **Stream the voice sentence by sentence.** Voicing each sentence as it's written (and playing a
  short pre-recorded "one moment" when a web search starts) is what makes it feel conversational.
- **Answer simple commands on the phone.** "Pause", "TV off", "volume to 20" are matched on the
  phone and answered from pre-recorded lines in under a second; the phone tells the brain afterwards
  so the conversation still makes sense.
- **Things the assistant says on its own belong in the conversation.** An alert spoken out of the
  blue wasn't in the history, so "ignore that" was applied to the previous topic instead.

## Deploying

- **CloudFormation keeps old parameter values.** Changing a default in the template doesn't change a
  stack that already has a value; pass it explicitly on every deploy.
- **A green deploy is not proof.** The deploy script checks that the live function's code hash
  matches the zip it just built.
