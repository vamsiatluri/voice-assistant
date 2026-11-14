#!/usr/bin/env python3
"""Store the assistant's API keys in Secrets Manager, in the AWS account from config.json.

Keys are typed at a hidden prompt, checked against the provider, and written
straight to the secret. They are never printed, logged, or put on a command line.
Leave a prompt blank to keep whatever is already stored for that key.
"""
import getpass, json, os, subprocess, sys, tempfile, urllib.request, urllib.error
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cfg

CFG = cfg.load()
PROFILE, REGION = CFG["awsProfile"], CFG["region"]
SECRET = f"{CFG['prefix']}/api-keys"


def aws(*args, check=True):
    r = subprocess.run(["aws", *args, "--profile", PROFILE, "--region", REGION, "--output", "json"],
                       capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"aws {args[0]} {args[1]} failed: {r.stderr.strip()}")
    return r


def probe(url, headers):
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, ""
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:200]
    except Exception as e:  # network trouble: don't block storing
        return None, str(e)


def check_elevenlabs(k):
    s, body = probe("https://api.elevenlabs.io/v1/user", {"xi-api-key": k})
    if s == 200:
        return True, "valid"
    if s == 401 and "missing_permissions" in body:
        return True, "valid (key is scoped; fine for text-to-speech)"
    return False, f"rejected: HTTP {s} {body}"


def check_anthropic(k):
    s, body = probe("https://api.anthropic.com/v1/models",
                    {"x-api-key": k, "anthropic-version": "2023-06-01"})
    return (s == 200, "valid" if s == 200 else f"rejected: HTTP {s} {body}")


KEYS = [("ELEVENLABS_API_KEY", "ElevenLabs API key", check_elevenlabs),
        ("ANTHROPIC_API_KEY", "Anthropic API key", check_anthropic)]


def main():
    cfg.check_account(CFG)

    existing = {}
    r = aws("secretsmanager", "get-secret-value", "--secret-id", SECRET, check=False)
    exists = r.returncode == 0
    if exists:
        existing = json.loads(json.loads(r.stdout)["SecretString"])

    changed = False
    for name, label, check in KEYS:
        have = " (already stored; blank = keep)" if name in existing else " (blank = skip)"
        while True:
            val = getpass.getpass(f"{label}{have}: ").strip()
            if not val:
                break
            ok, msg = check(val)
            print(f"  {label}: {msg}")
            if ok:
                existing[name] = val
                changed = True
                break
            print("  Not stored. Paste it again, or press Enter to skip.")

    if not changed:
        print("Nothing new to store.")
        return

    fd, path = tempfile.mkstemp()
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(existing, f)
        if exists:
            aws("secretsmanager", "put-secret-value", "--secret-id", SECRET, "--secret-string", f"file://{path}")
        else:
            aws("secretsmanager", "create-secret", "--name", SECRET,
                "--description", "Voice assistant API keys", "--secret-string", f"file://{path}")
    finally:
        os.remove(path)

    stored = json.loads(json.loads(aws("secretsmanager", "get-secret-value", "--secret-id", SECRET).stdout)["SecretString"])
    print(f"Stored in {SECRET}: " + ", ".join(f"{k} ({len(v)} chars)" for k, v in stored.items()))


if __name__ == "__main__":
    main()
