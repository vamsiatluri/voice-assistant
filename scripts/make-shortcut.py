#!/usr/bin/env python3
"""Build the iPhone Siri shortcut: "Hey Siri, <name>" -> you speak -> the brain answers in its own voice.

iPhone does not let any app but Siri listen for a wake phrase, so on iPhone Siri is the wake word and the
brain does the thinking. The shortcut asks what you want (Siri listens), posts it to your brain, and plays
the reply in your assistant's voice. It works from the lock screen, with AirPods and in CarPlay.

The access phrase is NOT in the file: the iPhone asks for it once, when the shortcut is added.

Usage:  python3 scripts/make-shortcut.py [--out assistant.shortcut]
        (deploy.sh runs it and publishes the signed file at  <your site>/<name>.shortcut)
Needs macOS (the `shortcuts` command signs the file so an iPhone will import it).
"""
from __future__ import annotations

import argparse, pathlib, plistlib, shutil, subprocess, sys, tempfile, uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import cfg  # noqa: E402

OBJ = "￼"   # where a variable sits inside a text field


def text(s: str) -> dict:
    return {"Value": {"string": s}, "WFSerializationType": "WFTextTokenString"}


def var(uid: str, name: str) -> dict:
    """A text field holding one action's output."""
    return {"Value": {"string": OBJ, "attachmentsByRange": {"{0, 1}": {"OutputUUID": uid, "Type": "ActionOutput", "OutputName": name}}},
            "WFSerializationType": "WFTextTokenString"}


def ref(uid: str, name: str) -> dict:
    """An action's input taken from another action's output."""
    return {"Value": {"OutputUUID": uid, "Type": "ActionOutput", "OutputName": name}, "WFSerializationType": "WFTextTokenAttachment"}


def fields(pairs) -> dict:
    return {"Value": {"WFDictionaryFieldValueItems": [{"WFItemType": 0, "WFKey": text(k), "WFValue": v} for k, v in pairs]},
            "WFSerializationType": "WFDictionaryFieldValue"}


def action(ident: str, uid: str | None = None, **params) -> dict:
    if uid:
        params["UUID"] = uid
    return {"WFWorkflowActionIdentifier": f"is.workflow.actions.{ident}", "WFWorkflowActionParameters": params}


def build(name: str, api_base: str) -> dict:
    phrase, asked, reply, audio, sound = (str(uuid.uuid4()).upper() for _ in range(5))
    actions = [
        # 0: the access phrase, typed once when the shortcut is added (import question below).
        action("gettext", phrase, WFTextActionText=""),
        # 1: Siri asks and listens.
        action("ask", asked, WFAskActionPrompt="Yes?", WFInputType="Text"),
        # 2: to the brain. stream false: one JSON answer with the reply and its voice.
        action("downloadurl", reply, WFURL=api_base.rstrip("/") + "/api/chat", WFHTTPMethod="POST", WFHTTPBodyType="JSON",
               WFHTTPHeaders=fields([("x-assistant-key", var(phrase, "Text"))]),
               WFJSONValues=fields([("text", var(asked, "Provided Input")), ("source", text("siri"))]),
               ShowHeaders=True),
        # 3-4: the reply in the assistant's own voice (base64 MP3 from the brain).
        action("getvalueforkey", audio, WFInput=ref(reply, "Contents of URL"), WFDictionaryKey="audio"),
        action("base64encode", sound, WFInput=ref(audio, "Dictionary Value"), WFEncodeMode="Decode"),
        action("playsound", None, WFInput=ref(sound, "Base64 Encoded")),
    ]
    return {
        "WFWorkflowActions": actions,
        "WFWorkflowName": name,
        "WFWorkflowClientVersion": "2607.0.2",
        "WFWorkflowMinimumClientVersion": 900,
        "WFWorkflowMinimumClientVersionString": "900",
        "WFWorkflowIcon": {"WFWorkflowIconStartColor": 463140863, "WFWorkflowIconGlyphNumber": 59511},
        "WFWorkflowTypes": ["Watch"],
        "WFWorkflowInputContentItemClasses": ["WFStringContentItem"],
        "WFWorkflowHasShortcutInputVariables": False,
        "WFWorkflowImportQuestions": [{
            "ActionIndex": 0, "Category": "Parameter", "ParameterKey": "WFTextActionText", "DefaultValue": "",
            "Text": f"Your {name} access phrase (from .passphrase on your computer, or the web page you unlocked)",
        }],
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--out", help="default: <name>.shortcut here; the file name is the shortcut's name, and what you say to Siri")
    p.add_argument("--base", help="the brain's address (default: apiBase in config.json)")
    a = p.parse_args()
    c = cfg.load()
    name, base = c.get("assistantName") or "Assistant", a.base or c.get("apiBase")
    a.out = a.out or str(cfg.ROOT / f"{name}.shortcut")
    if not base:
        sys.exit("No apiBase in config.json yet: run ./scripts/deploy.sh first.")
    if not shutil.which("shortcuts"):
        sys.exit("The `shortcuts` command is macOS-only; build the shortcut on a Mac.")
    with tempfile.TemporaryDirectory() as d:
        raw = pathlib.Path(d) / f"{name}.shortcut"
        raw.write_bytes(plistlib.dumps(build(name, base), fmt=plistlib.FMT_BINARY))
        r = subprocess.run(["shortcuts", "sign", "--mode", "anyone", "--input", str(raw), "--output", a.out],
                           capture_output=True, text=True)
        if r.returncode or not pathlib.Path(a.out).exists():
            sys.exit(f"Signing failed (the Mac must be signed in to iCloud): {r.stderr.strip()[:300]}")
    print(f"Siri shortcut: {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
