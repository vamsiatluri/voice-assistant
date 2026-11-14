"""Your Claude threads as the desktop app knows them: real title, pinned, and what each last said.

The Claude desktop app keeps one small JSON file per thread (title, pinned = isStarred, archived,
the Claude Code transcript id = cliSessionId). Claude Code hooks only know the transcript id and
the folder, and nearly every thread lives in the same folder, so without this every thread is
announced by the folder name. Standard library only.
"""
import glob, json, os

SESSIONS = os.path.expanduser("~/Library/Application Support/Claude/claude-code-sessions/*/*/local_*.json")
PROJECTS = os.path.expanduser("~/.claude/projects")
TAIL_BYTES = 600_000   # transcripts reach hundreds of MB; the last turn is always near the end


def all_threads():
    out = []
    for f in glob.glob(SESSIONS):
        if "/deleted_" in f:   # threads they deleted live on under deleted_<id> folders
            continue
        try:
            d = json.load(open(f))
        except Exception:
            continue
        if not d.get("sessionId") or d.get("isArchived"):
            continue
        out.append({
            "local": d["sessionId"], "cli": d.get("cliSessionId") or "", "title": (d.get("title") or "Untitled thread").strip(),
            "pinned": bool(d.get("isStarred")), "lastActivityAt": int(d.get("lastActivityAt") or 0), "cwd": d.get("cwd") or "",
        })
    out.sort(key=lambda t: (not t["pinned"], -t["lastActivityAt"]))
    return out


def by_cli(cli_id):
    for t in all_threads():
        if t["cli"] == cli_id:
            return t
    return None


def transcript_path(cli_id):
    hits = glob.glob(os.path.join(PROJECTS, "*", f"{cli_id}.jsonl"))
    return hits[0] if hits else ""


def _lines(path, tail=False):
    with open(path, "rb") as f:
        if tail:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - TAIL_BYTES))
            if size > TAIL_BYTES:
                f.readline()   # drop the partial first line
        for raw in f:
            yield raw


def last_assistant_text(path, tail=False):
    """Last thing the thread said, plus the desktop app's next-step question if this turn asked one."""
    text, ask = "", ""
    try:
        for line in _lines(path, tail):
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if e.get("type") == "user":
                c = (e.get("message") or {}).get("content")
                # Any new input (typed, or a voice answer arriving as hook feedback, a plain string)
                # answers the old question; only tool results belong to the same turn.
                if isinstance(c, str) or any(isinstance(b, dict) and b.get("type") == "text" for b in c or []):
                    ask = ""
            if e.get("type") != "assistant":
                continue
            for b in (e.get("message") or {}).get("content") or []:
                if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name", "").endswith("wrap_up") and (b.get("input") or {}).get("ask"):
                    inp = b["input"]
                    labels = [x.get("label") for x in inp.get("actions") or [] if isinstance(x, dict) and x.get("label")]
                    ask = inp["ask"] + (f" (options: {' / '.join(labels)})" if labels else "")
            parts = [b.get("text", "") for b in (e.get("message") or {}).get("content") or [] if isinstance(b, dict) and b.get("type") == "text"]
            if any(p.strip() for p in parts):
                text = "\n".join(parts)
    except Exception:
        pass
    return text.strip(), ask
