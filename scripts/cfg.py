"""Shared settings for the scripts: everything personal lives in ../config.json (written by setup.py, never committed)."""
import json, pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PATH = ROOT / "config.json"


def load() -> dict:
    if not PATH.exists():
        sys.exit("config.json not found: run  python3 scripts/setup.py  first.")
    c = json.loads(PATH.read_text())
    c.setdefault("prefix", "assistant")
    c.setdefault("region", "us-east-1")
    c.setdefault("awsProfile", "default")
    return c


def save(c: dict) -> None:
    PATH.write_text(json.dumps(c, indent=2) + "\n")


def aws(c: dict, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["aws", *args, "--profile", c["awsProfile"], "--region", c["region"], "--output", "json"],
                          capture_output=True, text=True, check=check)


def check_account(c: dict) -> str:
    """The account the profile points at; refuses to go on if config.json pinned a different one."""
    who = json.loads(aws(c, "sts", "get-caller-identity").stdout)["Account"]
    if c.get("awsAccountId") and c["awsAccountId"] != who:
        sys.exit(f"AWS profile '{c['awsProfile']}' is account {who}, but config.json says {c['awsAccountId']}. Stopping.")
    return who


if __name__ == "__main__":   # deploy.sh reads single values this way: python3 scripts/cfg.py prefix
    v = load().get(sys.argv[1], "")
    print(",".join(v) if isinstance(v, list) else v)
