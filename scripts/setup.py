#!/usr/bin/env python3
"""First-time setup: who your assistant is, which AWS account it lives in, and the model files the phone needs.

Writes config.json (never committed). Re-run any time: your previous answers are the defaults.
Next steps are printed at the end (keys -> deploy -> build the phone app).
"""
import hashlib, json, os, pathlib, sys, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cfg

ASSETS = cfg.ROOT / "android" / "app" / "src" / "main" / "assets"

# Downloaded from their official releases, never committed: each keeps its own license.
MODELS = [
    # openWakeWord feature models (audio -> features the wake-word model reads). github.com/dscripka/openWakeWord
    ("melspectrogram.onnx", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx",
     "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f"),
    ("embedding_model.onnx", "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx",
     "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f"),
    # Voiceprint: 3D-Speaker CAM++ (Apache-2.0), from the sherpa-onnx model release.
    ("speaker_campplus_en.onnx",
     "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
     "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"),
]

DEFAULT_PERSONA = "calm, warm and quietly witty; polite, unflappable, never sycophantic"
DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"   # ElevenLabs stock voice "George"; any voice id from your ElevenLabs library works


def ask(prompt: str, default: str = "") -> str:
    shown = f" [{default}]" if default else ""
    got = input(f"{prompt}{shown}: ").strip()
    return got or default


def yes(prompt: str, default: bool) -> bool:
    got = input(f"{prompt} [{'Y/n' if default else 'y/N'}]: ").strip().lower()
    return default if not got else got.startswith("y")


def local_timezone() -> str:
    try:
        return str(pathlib.Path("/etc/localtime").resolve()).split("zoneinfo/")[1]
    except Exception:
        return "America/New_York"


def fetch_models() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    for name, url, sha in MODELS:
        dest = ASSETS / name
        if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == sha:
            print(f"  {name}: already here")
            continue
        print(f"  {name}: downloading ...", end="", flush=True)
        data = urllib.request.urlopen(url, timeout=120).read()
        got = hashlib.sha256(data).hexdigest()
        if got != sha:
            sys.exit(f"\n{name}: checksum mismatch ({got}); not saved. The release may have changed: check the URL.")
        dest.write_bytes(data)
        print(f" ok ({len(data) // 1024} KB)")


def main() -> None:
    old = json.loads(cfg.PATH.read_text()) if cfg.PATH.exists() else {}
    print("Setting up your assistant. Press Enter to keep the value in [brackets].\n")

    c = dict(old)
    c["ownerName"] = ask("Your first name", old.get("ownerName", ""))
    c["assistantName"] = ask("Your assistant's name (what you'll call it)", old.get("assistantName", "Nova"))
    c["address"] = ask('How should it address you? ("sir", "ma\'am", your name, or leave empty)', old.get("address", ""))
    c["wakePhrase"] = ask("Wake phrase", old.get("wakePhrase", f"hey {c['assistantName'].lower()}"))
    c["timezone"] = ask("Your time zone", old.get("timezone", local_timezone()))
    c["persona"] = ask("Its manner, in a few words", old.get("persona", DEFAULT_PERSONA))
    c["voiceId"] = ask("ElevenLabs voice id", old.get("voiceId", DEFAULT_VOICE))

    print("\nOptional plugins (add or remove later by re-running setup):")
    plugins = set(old.get("plugins", ["tv"]))
    for key, what in [("tv", "Smart TV control on your Wi-Fi (Samsung today; more brands planned)"),
                      ("email", "Email: read, search, draft replies, hourly cleanup and charge alerts (needs your own Google/Microsoft app, see docs/PLUGINS.md)"),
                      ("claude", "Claude Code bridge: hear and answer your Claude Code threads by voice"),
                      ("amazon", "Amazon: find products by voice and put them in your cart (you place the order yourself)")]:
        (plugins.add if yes(f"  {what}?", key in plugins) else plugins.discard)(key)
    c["plugins"] = sorted(plugins)
    if "amazon" in plugins:
        c["amazonSite"] = ask("  Your Amazon store", old.get("amazonSite", "www.amazon.com"))
    if "email" in plugins:
        c["emailKeepAlso"] = ask("  Mail the cleanup must ALWAYS keep, in plain words (optional)", old.get("emailKeepAlso", ""))
        doms = ask("  Your own email domains, comma separated (always kept; optional)", ",".join(old.get("ownDomains", [])))
        c["ownDomains"] = [d.strip().lower() for d in doms.split(",") if d.strip()]

    print("\nAWS (the brain runs in your own account):")
    c["awsProfile"] = ask("  AWS CLI profile", old.get("awsProfile", "default"))
    c["region"] = ask("  Region", old.get("region", "us-east-1"))
    c["prefix"] = ask("  Name prefix for everything it creates", old.get("prefix", "assistant"))
    pin = ask("  Pin an AWS account id (deploys refuse any other; optional)", old.get("awsAccountId", ""))
    if pin: c["awsAccountId"] = pin
    else: c.pop("awsAccountId", None)

    cfg.save(c)
    print(f"\nSaved {cfg.PATH.relative_to(cfg.ROOT)}.\n\nPhone model files:")
    fetch_models()

    wake = ASSETS / "wakeword.onnx"
    print("\nWake word:")
    if wake.exists():
        print(f"  wakeword.onnx is in place ({wake.stat().st_size // 1024} KB).")
    else:
        print(f'  Not trained yet. Train "{c["wakePhrase"]}" with the openWakeWord training notebook (free Google Colab, about an hour;')
        print("  steps in docs/SETUP.md), then copy the .onnx it produces to:")
        print(f"    {wake.relative_to(cfg.ROOT)}")

    print("\nNext:")
    print("  1. python3 scripts/put-keys.py      # Anthropic + ElevenLabs keys, stored only in your AWS account")
    print("  2. ./scripts/deploy.sh              # brain + web UI; writes the brain's address into config.json")
    print("  3. cd android && ./gradlew assembleRelease, then install the APK (docs/SETUP.md)")
    if "email" in plugins:
        print("  4. python3 scripts/connect-mail.py ...   # connect each mailbox (docs/PLUGINS.md)")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped; nothing else changed.")
