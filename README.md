# Run your Claude Code threads by voice, from anywhere

Walk away from your computer and keep working. Your phone tells you, by name, when a Claude Code
thread finishes or asks you something, and you answer out loud. You can also tell any thread
anything, whether it's waiting or not: *"tell the Growth thread to pause the ads"*. Say the wake
phrase with the phone in your pocket, the screen locked or the car driving, and it reaches the
right thread on your Mac.

| Your Claude threads, by voice | |
|---|---|
| **Talk to any thread by name** | "Tell the App thread to ship the fix to QA", "ask the Website thread what's blocking it". Delivered into that thread as your instruction, even if it isn't waiting on you. |
| **Hear them as they happen** | A thread that finishes or asks you a question is announced by its real title, with what it said. |
| **Answer by voice** | Reply to what a thread asked, now or later. An answer to an earlier question carries that question with it, so the thread knows what you meant. |
| **It knows when you're away** | Say "I'm heading out", or just leave the Mac idle for a few minutes, and threads come to your phone. Touch the Mac and they go back to the screen. |
| **"What's waiting on me?"** | Your threads by title: which are waiting, what you haven't answered, what your pinned ones last said. |
| **Driving** | Car mode turns on with your car's Bluetooth and reads threads and texts as they come in, hands-free. |
| **With earbuds in** | At the gym or on a walk, it speaks in your Bluetooth earbuds and pauses your music while you talk, then picks it back up. Your request and your answers to a thread come from the earbuds' mic, so the phone can stay in your pocket. The wake phrase is still heard by the phone, which keeps your music from sounding like a phone call. |
| **Morning briefing** | Threads waiting on you, next to your email, calendar and overnight texts. |
| **CI failures fix themselves** | When a GitHub Actions workflow goes red, the thread that owns that code is told to fix it and re-run it to green, then tells you what broke. Promoting to main or deploying to production still waits for you. |
| **Only you give instructions** | Text from emails, web pages, texts or the threads themselves is never passed on as an instruction. Once it has read any of that, it won't send, save or relay anything in the same breath: it tells you what it would do, and your next words do it. |

Setup: the Claude Code bridge in **[docs/PLUGINS.md](docs/PLUGINS.md#claude-code-bridge-claude)**.

## It's also a full voice assistant

Say your own wake phrase to your Android phone, from across the room or with the screen locked,
and talk to it like a person: interrupt it mid-sentence, have it read and answer your texts, turn
the TV on and put Netflix on, pause what's playing, brief you in the morning, keep your to-do list.

You pick its name, its wake phrase, how it talks and what it calls you. It runs on **your** phone
and **your** AWS account with **your** API keys: there is no shared server, nobody else holds your
conversations, and you can read every line of what it does.

## What it does

| | |
|---|---|
| **Always listening, only for you** | A wake word you train yourself, detected on the phone. A voiceprint so it ignores everyone else. |
| **Talk over it** | Say "stop", "wait, that's not what I meant" or "no, open Hulu" while it's talking. It pauses, works out whether you were talking to it or to someone in the room, and either stops or carries on. |
| **Texts** | Reads texts from your contacts aloud. "Reply: running ten minutes late", "text Sarah I'm on my way". It always reads the message back and waits for your yes. |
| **Your home, from your phone** | "Here's what's on your Wi-Fi": it scans your network, shows what it found and what it can drive, and connects with one tap. Then: TV on/off, apps, volume, pause / rewind / skip, directly over Wi-Fi with no hub and no cloud account in between. *(Samsung TVs today; Roku, LG, Hue, Sonos and Home Assistant are recognised and next, see [docs/PLUGINS.md](docs/PLUGINS.md).)* |
| **Daily life** | Calendar, reminders, to-do list, "who called?", sleep mode with Do Not Disturb, a morning briefing. |
| **Car mode** | Connects to your car's Bluetooth and goes hands-free automatically. |
| **Web answers** | Weather, news, scores, opening hours: looked up live, answered in a sentence or two. |
| **Lock-screen orb** | A widget that glows while it listens and pulses while it thinks. |
| **Optional plugins** | Claude Code bridge (above). Email (read, search, draft replies, hourly inbox cleanup, charge alerts). Amazon ("order me paper towels": it finds the product, asks before adding it, puts it in your cart, and remembers your usual one; you place the order). |

Simple home commands are recognised on the phone and answered in under a second; everything else
goes to the brain (Claude) and is spoken back sentence by sentence as it's written.

## What you need

- An **Android phone** (Android 12+) for the full assistant: its own wake word, always listening, talk over it.
- **Or an iPhone**, by voice through Siri: say "Hey Siri, *your assistant's name*", ask, and it answers in its own voice,
  from the lock screen, on AirPods or in CarPlay. iPhone doesn't let any app but Siri listen for a wake word, so
  Siri is the wake word and your assistant does the rest. No talking over it and no announcements on iPhone.
  Building the shortcut needs a Mac. ([docs/SETUP.md](docs/SETUP.md#iphone-hey-siri))
- An **AWS account** (the brain is one Lambda function, a DynamoDB table and a CloudFront site).
- An **Anthropic API key** (the brain) and an **ElevenLabs API key** (the voice; Amazon Polly is the fallback).
- About an hour for the wake-word training, once.

**Cost:** you pay AWS, Anthropic and ElevenLabs directly for your own use. The AWS part is small for one
person; Claude and ElevenLabs depend on how much you talk to it. There is a daily reply cap
(300 by default) so a stuck loop can't run up a bill. Check each provider's current pricing.

## Get started

```bash
python3 scripts/setup.py      # name, wake phrase, voice, plugins, AWS profile; downloads model files
python3 scripts/put-keys.py   # your API keys, typed at a hidden prompt, stored only in your AWS account
./scripts/deploy.sh           # the brain and the web page
```

Then train your wake word, build the phone app and install it: **[docs/SETUP.md](docs/SETUP.md)**
walks through all of it.

## How it's built

**[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**: phone, brain, plugins, and where the time goes
between "hey ..." and the first word back.

**[docs/LESSONS.md](docs/LESSONS.md)**: the things that took longest to get right (echo
cancellation so you can talk over it, why the phone's own speech recognisers couldn't be used for
that, Android traps, an API limit that took every request down). Worth reading even if you never
run this.

## Privacy and safety

- Your keys live in AWS Secrets Manager in your account; the scripts never print them.
- The phone talks only to your own brain (plus your TV on your Wi-Fi).
- It never sends a text without reading it back and hearing yes. It cannot send email at all, only save drafts.
- It cannot buy anything: the Amazon plugin only fills your cart, and you place the order in Amazon yourself.
- Text, email and web content is treated as information, never as instructions.

## License

Apache-2.0. Model files are downloaded at setup from their own projects under their own licenses
(openWakeWord feature models; 3D-Speaker CAM++ via sherpa-onnx). The wake-word model is the one
you train.
