# Setup

About 20 minutes of your time, plus an hour of wake-word training that runs on its own.

## 0. Tools

- AWS CLI v2, signed in to the account you want to use (`aws sts get-caller-identity` should work).
  Any profile name is fine; setup asks for it.
- Python 3.10+, Node.js 20, and for the phone app: JDK 17 and the Android SDK (Android Studio installs both).
- `adb` and an Android phone with USB debugging on (Settings, About phone, tap Build number seven times,
  then Developer options, USB debugging).

## 1. Who your assistant is

```bash
python3 scripts/setup.py
```

It asks for your first name, the assistant's name, how it should address you ("sir", your name,
or nothing), the wake phrase, your time zone, its manner in a few words, an ElevenLabs voice id,
which plugins you want, and the AWS profile and region. Answers go to `config.json`, which is
never committed. It also downloads three model files the phone needs and checks their checksums.

Re-run it any time; your previous answers are the defaults.

## 2. Keys

```bash
python3 scripts/put-keys.py
```

Paste your Anthropic and ElevenLabs keys at the hidden prompts. Each is tested against the provider
before it's stored in Secrets Manager (`<prefix>/api-keys`). Nothing is printed or written to disk.

## 3. Deploy the brain

```bash
./scripts/deploy.sh
```

Creates (or updates) one CloudFormation stack named after your prefix: the Lambda brain, a
DynamoDB table for memory, a CloudFront site with a small web version, and the schedules (keep
warm, and the hourly mail cleanup if you enabled email). It writes:

- `.passphrase`: the access phrase the phone uses. You'll type it into the app once.
- `apiBase` in `config.json`: the brain's address, which the phone build reads.

Re-running deploys whatever changed. It refuses to continue if the live function isn't running
the code it just built.

## 4. Train your wake word

The phone listens with [openWakeWord](https://github.com/dscripka/openWakeWord). Its ready-made
models are licensed for non-commercial use only and none of them say your phrase anyway, so you
train your own:

1. Open openWakeWord's **automatic model training** notebook (`notebooks/automatic_model_training.ipynb`
   in that repository) in Google Colab. A free Colab GPU is enough.
2. Set the target phrase to your wake phrase, exactly as in `config.json`. Two or three syllables
   or more work best ("hey nova" rather than "nova").
3. Run all cells. It generates synthetic speech of your phrase and trains a small model; expect
   about an hour.
4. Download the `.onnx` it produces and save it as `android/app/src/main/assets/wakeword.onnx`.

Later, the phone can also learn **your voice** (step 7), so other people saying the phrase don't wake it.

## 5. Build and install the phone app

```bash
cd android
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n org.voiceassistant/.MainActivity
```

The release build is signed with your local debug key, which is fine for your own phone.

> After every reinstall, open the app once. Android stops the listening service when an app is
> updated, and a microphone service can only be started again from the foreground.

## 6. First run on the phone

1. Type the access phrase from `.passphrase`.
2. Allow the permissions it asks for: microphone (required), notifications, contacts and SMS (texts),
   calendar, call log ("who called?"), nearby devices (car mode, TV).
3. **Texts:** Settings, Notifications, Device and app notifications, allow notification access for
   "<name> texts".
4. **Battery:** tap "Keep running in background" in the app and allow it, so the phone doesn't put it to sleep.
5. **Lock-screen orb:** long-press the lock screen or home screen, add the "<name> orb" widget.

## 7. Talk to it

- Say your wake phrase, then a question. It answers, then listens once more without the wake phrase.
- "Learn my voice", then say the wake phrase five times after the chime. From then on it only wakes for you.
- "What can I say?" or the list button in the app shows every command.
- TV: the first time you say "turn the TV on", the TV asks to allow the app. Press Allow on the remote.

## Plugins

Email and the Claude Code bridge need a few extra steps each: see [PLUGINS.md](PLUGINS.md).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Wake phrase ignored | Service not running (open the app), or your voiceprint is too strict: say "forget my voice", then "learn my voice" again in a quiet room. |
| "I can't reach my brain" | Phone offline, wrong passphrase, or the brain's daily cap was reached. Check the Lambda logs (`/aws/lambda/<prefix>-brain`). |
| TV doesn't respond | Phone and TV must be on the same Wi-Fi. For turning it on from off, enable the TV's "Power on with mobile" / network standby setting. |
| Replies are slow | The first reply after a few idle minutes is slower; the keep-warm schedule makes that rare. Long answers stream sentence by sentence. |
