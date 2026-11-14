#!/usr/bin/env python3
"""Mac side of "tell any Claude thread anything, any time".

Runs in the background inside the desktop app's assistant thread (the only place that can post a
message into another thread). Every 30 s it:
  1. sends the brain a snapshot of their threads (title, pinned, what each last said / is asking),
     so the assistant knows them by name;
  2. asks the brain for an instruction they gave by voice for a thread. If there is one it prints it
     as JSON and exits: the assistant thread wakes, delivers it into that thread, acks it, and starts
     this again.
Standard library only.
"""
import hashlib, json, os, sys, time, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import threads  # noqa: E402

# The brain's address, written into config.json by scripts/deploy.sh.
BASE = json.load(open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config.json"))).get("apiBase", "")
KEY_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".passphrase")
EVERY = 30
OTHERS = 20            # unpinned threads sent, most recently active first (all pinned ones go)
cache = {}             # cli id -> (lastActivityAt, said, ask)


def call(method, path, body=None):
    key = open(KEY_FILE).read().strip()
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"x-assistant-key": key, "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read() or b"{}")


def snapshot():
    ts = threads.all_threads()
    pinned = [t for t in ts if t["pinned"]]
    others = [t for t in ts if not t["pinned"]][:OTHERS]
    out = []
    for t in pinned + others:
        said, ask = "", ""
        if t["cli"]:
            hit = cache.get(t["cli"])
            if hit and hit[0] == t["lastActivityAt"]:
                said, ask = hit[1], hit[2]
            else:
                p = threads.transcript_path(t["cli"])
                if p:
                    said, ask = threads.last_assistant_text(p, tail=True)
                    said = said[-600:]
                cache[t["cli"]] = (t["lastActivityAt"], said, ask)
        out.append({"local": t["local"], "title": t["title"], "pinned": t["pinned"], "lastActivityAt": t["lastActivityAt"],
                    "said": said, "asking": ask})
    return out


def main():
    last_hash = ""
    while True:
        try:
            snap = snapshot()
            h = hashlib.sha256(json.dumps(snap, sort_keys=True).encode()).hexdigest()
            if h != last_hash:
                call("POST", "/api/bridge/threads", {"threads": snap})
                last_hash = h
            r = call("GET", "/api/bridge/outbox")
            if r.get("item"):
                print(json.dumps(r["item"]))
                return
        except Exception as e:
            print(f"relay: {e}", file=sys.stderr)
        time.sleep(EVERY)


if __name__ == "__main__":
    main()
