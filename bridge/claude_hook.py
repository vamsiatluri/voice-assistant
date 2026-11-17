#!/usr/bin/env python3
"""Claude Code hook: hand a waiting thread to the assistant when the owner is away from the Mac.

Wired in ~/.claude/settings.json for:
  Stop                       -> the thread finished its turn and is waiting for them
  PreToolUse (AskUserQuestion) -> the thread is asking them a question

"Away" = they told the assistant they're heading out, OR the Mac has had no keyboard/mouse for IDLE_AWAY seconds.
(Quiet hours: idle alone doesn't count overnight, so a late thread never wakes the house.)
While away the event is posted to the the assistant brain, the phone announces it, and their spoken answer
comes back here and is handed to the thread as their instruction. Touch the Mac while it waits and the
thread is handed straight back to the screen. Standard library only; any failure = behave as if
the hook wasn't there.
"""
import json, os, subprocess, sys, time, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import threads  # noqa: E402  (the desktop app's thread titles)
from datetime import datetime

# The brain's address, written into config.json by scripts/deploy.sh.
BASE = json.load(open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json"))).get("apiBase", "")
KEY_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".passphrase")
IDLE_AWAY = 180          # seconds without input before the Mac counts as "they stepped away"
IDLE_BACK = 20           # input within this many seconds = they're back at the keyboard
QUIET = (23, 7)          # 11 PM - 7 AM: only an explicit "I'm heading out" counts
MAX_WAIT = 3300          # give the thread back before the hook's own timeout (3600)


def idle_seconds():
    try:
        out = subprocess.run(["ioreg", "-c", "IOHIDSystem"], capture_output=True, text=True, timeout=5).stdout
        for line in out.splitlines():
            if "HIDIdleTime" in line:
                return int(line.split("=")[-1].strip()) / 1e9
    except Exception:
        pass
    return 0.0


def quiet_hours():
    h = datetime.now().hour
    return h >= QUIET[0] or h < QUIET[1]


def call(method, path, body=None):
    key = open(KEY_FILE).read().strip()
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"x-assistant-key": key, "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read() or b"{}")


def last_assistant_text(transcript_path):
    text, ask = threads.last_assistant_text(transcript_path)
    if ask:
        text += f"\n\nThe thread is asking them: {ask}"
    return text


def wait_for_answer(event_id, voice_away):
    start = time.time()
    while time.time() - start < MAX_WAIT:
        time.sleep(3)
        try:
            r = call("GET", f"/api/bridge/poll?id={event_id}")
        except Exception:
            continue
        if r.get("status") == "answered":
            return r.get("answer")
        if r.get("status") in ("closed", "missing"):
            return None
        # Back at the Mac (and not in an explicit "I'm heading out"): give the thread back to the screen.
        if idle_seconds() < IDLE_BACK and not r.get("away"):
            call("POST", "/api/bridge/close", {"id": event_id, "reason": "they is back at the Mac"})
            return None
    call("POST", "/api/bridge/close", {"id": event_id, "reason": "timed out"})
    return None


def main():
    hook = json.load(sys.stdin)
    name = hook.get("hook_event_name")
    # The thread's own title from the desktop app ("Website — Checkout"), not the folder: nearly every
    # thread runs in the same folder, so the folder name says nothing about which one it is.
    t = threads.by_cli(hook.get("session_id", "")) if hook.get("session_id") else None
    project = (t or {}).get("title") or os.path.basename(hook.get("cwd") or os.getcwd()) or "Claude"
    local = (t or {}).get("local", "")

    idle = idle_seconds()
    mac_idle = idle >= IDLE_AWAY and not quiet_hours()
    voice_away = False
    if not mac_idle:
        try:
            voice_away = bool(call("GET", "/api/away").get("away"))
        except Exception:
            return
        if not voice_away:
            return

    if name == "Stop":
        text = last_assistant_text(hook.get("transcript_path", ""))
        if not text:
            return
        ev = {"kind": "stop", "project": project, "local": local, "pinned": bool((t or {}).get("pinned")), "session": hook.get("session_id", ""), "text": text[-3500:], "options": []}
    elif name == "PreToolUse" and hook.get("tool_name") == "AskUserQuestion":
        qs = (hook.get("tool_input") or {}).get("questions") or []
        if not qs:
            return
        q = qs[0]
        opts = [o.get("label", "") for o in q.get("options", [])]
        more = f"\n(+{len(qs) - 1} more question(s) after this one)" if len(qs) > 1 else ""
        ev = {"kind": "question", "project": project, "local": local, "pinned": bool((t or {}).get("pinned")), "session": hook.get("session_id", ""), "text": q.get("question", "") + more, "options": opts}
    else:
        return

    ev["macIdle"] = mac_idle
    r = call("POST", "/api/bridge/event", ev)
    if not r.get("id"):
        return
    answer = wait_for_answer(r["id"], voice_away)
    if not answer:
        return

    said = f"The owner answered by voice through the assistant (they are away from the computer): {answer}"
    if name == "Stop":
        print(json.dumps({"decision": "block", "reason": said + "\nCarry on with that. Keep your reply short: it will be read aloud to them."}))
    else:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                                 "permissionDecisionReason": said + "\nTreat that as their answer to the question and proceed; do not ask it again."}}))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # never break a thread because the assistant is unreachable
        print(f"assistant hook: {e}", file=sys.stderr)
    sys.exit(0)
