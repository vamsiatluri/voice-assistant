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

**CI failures go to the thread that owns the code, to fix.** List your repos in `config.json`:

```json
"ciWatch": [
  {"repo": "you/website", "thread": "Website"},
  {"repo": "you/app", "workflows": ["deploy web", "site"], "thread": "Website"},
  {"repo": "you/app", "thread": "App"}
]
```

Every two minutes the relay checks each repo's GitHub Actions runs with `gh` (signed in on the Mac).
When a workflow turns red on a branch, the thread whose title starts with `thread` gets one message:
the workflow, branch, commit, failed job and step, and a link, never the log text. The thread reads
the logs, fixes the cause and re-runs it to green, then tells you what broke; you hear that like any
thread reply. Promoting to the main branch or deploying to production still waits for you. A
workflow that stays red is reported once until it passes again, a failure that goes green before
it's delivered is dropped, and a repo you add later starts from then, with no backlog. Routes are
tried in order; `workflows` (optional) are words to look for in the workflow's name.

## Amazon (`amazon`)

"Order me paper towels", "get me more AA batteries", "make that my usual coffee".

1. **Find.** If you've saved a usual item whose name is in what you said, it's used straight away.
   Otherwise the brain searches your Amazon store and offers the best match: brand and size,
   "Shall I put that in your cart?" Say no or describe it differently and it offers the next one.
   Among equally close matches it puts a good price per unit and fast (one or two day / Prime)
   delivery first, and never brings up who the seller is. It only mentions delivery when the search
   result shows it; Amazon's page has the real date.
2. **Add.** Only after your yes, the phone shows a notification that opens Amazon's own add-to-cart
   page (`/gp/aws/cart/add.html`). One tap adds it; you check out in Amazon as usual.
3. **Remember.** After adding something new it offers to make it your usual for that name.
   "What are my usual Amazon items?" lists them; "forget my usual coffee" removes one.

**It cannot buy.** There's no Amazon login, no stored card and no ordering code: the only thing the
brain can send the phone is an add-to-cart link, the phone refuses any other link, and the brain only
accepts product ids that came from a real search result or your saved items in this conversation
(so the model can't make one up). Product titles and prices are treated as page content, never as
instructions.

Search uses Claude's web search limited to your Amazon site. Prices come from search results and can be
stale, so the assistant says "about"; Amazon's page shows the real one before you buy.

Other stores: set `amazonSite` in setup (`www.amazon.co.uk`, `www.amazon.de`, ...). You need to be signed
in to Amazon in the app or the browser the link opens in; otherwise Amazon asks you to sign in first and
then carries on to the add-to-cart page.

Android only lets an app open another app from the background in a few cases, so the page opens by
itself only while the assistant's own screen is showing; otherwise it's a notification to tap.

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
