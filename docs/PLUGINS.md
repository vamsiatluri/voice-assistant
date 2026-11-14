# Plugins

Turn plugins on or off by re-running `python3 scripts/setup.py`, then `./scripts/deploy.sh`.
A plugin that's off has no tools in the brain and no commands in the list.

## Smart TV (`tv`)

Samsung Tizen TVs (2016 and later) on the same Wi-Fi as the phone. Nothing to set up: the first
command finds the TV, the TV asks you to allow the app, and the token it returns is kept on the
phone. To turn the TV on from standby, enable the TV's network standby / "Power on with mobile".

**Home devices** (button on the main screen) scans your Wi-Fi the way devices announce themselves
(mDNS and SSDP/UPnP), reads each one's own description, and sorts what it finds into *ready to
connect*, *support coming* and *found, no open control* (Cast, Fire TV/Echo, AirPlay, HomeKit,
Spotify Connect speakers). Computers and routers are left out. Nothing connects until you tap
Connect; for a Samsung TV that's one Allow on the TV.

Next adapters, already recognised by the scan:

| Device | Pairing |
|---|---|
| Roku | none (local ECP API) |
| LG webOS TV | accept a prompt on the TV |
| Philips Hue | press the bridge button |
| Sonos | none (local UPnP) |
| Home Assistant | paste a long-lived token: then anything Home Assistant controls |

## Email (`email`)

Read, search and draft replies across Gmail and Microsoft 365 / Outlook; an hourly cleanup that
moves clear junk out of the low-priority tabs into a "<name> cleaned" folder (undo any day by
voice); spoken alerts for new charges and "what did I spend this week?".

It never sends email: the Google scopes are read + compose (drafts) + modify (labels), and there
is no send code anywhere.

You need your own OAuth app, because a published app with Gmail scopes needs Google's paid
security assessment:

- **Gmail:** in Google Cloud Console create a project, enable the Gmail API, create an OAuth client
  of type *Desktop app*, and add yourself as a test user on the consent screen (testing mode works
  indefinitely for your own account). Then:
  `python3 scripts/connect-mail.py google --key gmail --label "Gmail" --client-id <id> --client-secret <secret>`
- **Microsoft 365 / Outlook:** register an app in Entra ID with the `Mail.ReadWrite` delegated
  permission and a `http://localhost` redirect, then
  `python3 scripts/connect-mail.py microsoft --key outlook --label "Outlook" --client-id <id> [--tenant consumers]`

Run `python3 scripts/connect-mail.py --help` for every option. Sign-ins are stored in
`<prefix>/mail-accounts` in Secrets Manager.

## Claude Code bridge (`claude`)

When you step away from your computer, Claude Code threads that finish or ask you something are
announced on the phone by name, and you answer by voice. You can also tell any thread anything:
"tell the Growth thread to pause the ads".

1. Add `bridge/claude_hook.py` as a Claude Code `Stop` and `Notification` hook.
2. Run `python3 bridge/relay.py` in a Claude Code session to deliver instructions to threads that
   aren't currently waiting.

Only you can instruct a thread: text read from emails, web pages or the threads themselves is never
relayed as an instruction.

## Writing a plugin

A plugin is usually two halves:

1. **Brain tool** (`lambda/index.mjs`): a tool definition (`strict: false` unless it needs strict
   schemas; the API allows 20 strict tools per request), a `case` in `runTool`, and a line in
   `toolsFor()` behind `PLUGINS.has("yourplugin")`. If the phone has to do the work, push a phone
   action: `phoneActions.push({ type: "yourplugin", ... })`.
2. **Phone action** (`AssistantService.kt`): handle `type == "yourplugin"` where the other actions
   are handled. For local-network devices, put the device code in its own file like `SamsungTv.kt`.

Add the commands to `shared/commands.json` with `"plugin": "yourplugin"` so they only show when the
plugin is on. The TV plugin is the worked example: `TV_TOOL` in the brain, the `tv` action and
`SamsungTv.kt` on the phone, and its plain commands in `FastPath.kt` for instant answers.
