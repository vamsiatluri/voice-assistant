#!/usr/bin/env python3
"""Refuse to commit anything personal. Reads .private-denylist (one string per line, gitignored, never
published: a public list of what to hide would publish it) and checks every staged file, case-insensitive.

Install once:  ln -sf ../../scripts/check-private.py .git/hooks/pre-commit
Check the whole tree:  python3 scripts/check-private.py --all
"""
import pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LIST = ROOT / ".private-denylist"


def files(all_files: bool) -> list[str]:
    cmd = ["git", "ls-files", "--cached", "--others", "--exclude-standard"] if all_files else ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"]
    return [f for f in subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=True).stdout.split("\n") if f]


def staged(path: str, all_files: bool) -> bytes:
    if all_files:
        return (ROOT / path).read_bytes()
    return subprocess.run(["git", "show", f":{path}"], cwd=ROOT, capture_output=True, check=True).stdout


def main() -> int:
    if not LIST.exists():
        print("check-private: no .private-denylist here, nothing to check against (create one before publishing).")
        return 0
    words = [w.strip().lower() for w in LIST.read_text().splitlines() if w.strip() and not w.startswith("#")]
    all_files = "--all" in sys.argv
    bad = []
    for f in files(all_files):
        try:
            text = staged(f, all_files).decode("utf-8", errors="ignore").lower()
        except Exception:
            continue
        for n, line in enumerate(text.split("\n"), 1):
            hit = next((w for w in words if w in line), None)
            if hit:
                bad.append(f"{f}:{n}: matches deny-list entry #{words.index(hit) + 1}")
    if bad:
        print("Personal strings found (entries shown by number so this output is safe to share):")
        print("\n".join(bad[:50]))
        return 1
    print(f"check-private: {len(words)} entries, clean.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
