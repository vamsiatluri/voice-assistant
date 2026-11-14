#!/usr/bin/env python3
"""Connect a mailbox to the assistant (one-time sign-in per account).

  Store the Google OAuth client (the JSON you download from Google Cloud):
    python3 scripts/connect-mail.py google-client ~/Downloads/client_secret_XXXX.json

  Store the Microsoft app's client id (from the Entra app registration):
    python3 scripts/connect-mail.py microsoft-client <application-client-id>

  Connect an account (opens your browser; you sign in and approve there):
    python3 scripts/connect-mail.py google    --key gmail   --label "personal Gmail"           [--hint you@gmail.com]
    python3 scripts/connect-mail.py google    --key work    --label "Work Gmail"               [--hint ...]
    python3 scripts/connect-mail.py microsoft --key outlook --label "Outlook"                  [--hint ...]

  List / remove:
    python3 scripts/connect-mail.py list
    python3 scripts/connect-mail.py remove --key gmail

Access granted: READ mail + SAVE DRAFTS. The assistant has no send function (and the Microsoft
permission it asks for cannot send at all). Tokens go straight to AWS Secrets Manager
(<prefix>/mail-accounts in your AWS account); nothing is printed or written to disk.
"""
import argparse, base64, hashlib, http.server, json, os, secrets, subprocess, sys, tempfile, threading
import urllib.parse, urllib.request, webbrowser

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cfg
CFG = cfg.load()
PROFILE, REGION, SECRET = CFG["awsProfile"], CFG["region"], f"{CFG['prefix']}/mail-accounts"
NAME = CFG.get("assistantName", "the assistant")
GOOGLE_SCOPES = ("https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.compose "
                 "https://www.googleapis.com/auth/gmail.modify")  # modify = labels/archive for the morning cleanup; never permanent delete
MS_SCOPES = "offline_access Mail.ReadWrite User.Read"


def aws(*args, check=True):
    r = subprocess.run(["aws", *args, "--profile", PROFILE, "--region", REGION, "--output", "json"], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"aws {' '.join(args[:2])} failed: {r.stderr.strip()}")
    return r


def load():
    cfg.check_account(CFG)
    r = aws("secretsmanager", "get-secret-value", "--secret-id", SECRET, check=False)
    if r.returncode != 0:
        return None
    return json.loads(json.loads(r.stdout)["SecretString"])


def save(cfg, existed):
    fd, path = tempfile.mkstemp(); os.fchmod(fd, 0o600)
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(cfg, f)
        if existed:
            aws("secretsmanager", "put-secret-value", "--secret-id", SECRET, "--secret-string", f"file://{path}")
        else:
            aws("secretsmanager", "create-secret", "--name", SECRET, "--description", "Assistant mailbox sign-ins (read + drafts)",
                "--secret-string", f"file://{path}")
    finally:
        os.remove(path)


def post_form(url, form):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(form).encode(), headers={"content-type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"Token exchange failed: HTTP {e.code} {e.read()[:300].decode(errors='replace')}")


def get_json(url, token):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"authorization": f"Bearer {token}"}), timeout=20) as r:
        return json.load(r)


def browser_consent(auth_url_for):
    """Loopback OAuth: listen on 127.0.0.1, open the browser, return (code, redirect_uri)."""
    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "code" in q or "error" in q:
                result.update({k: v[0] for k, v in q.items()})
                ok = "code" in q
                self.send_response(200); self.send_header("content-type", "text/html"); self.end_headers()
                self.wfile.write((f"<body style='font-family:sans-serif;background:#02060c;color:#cfefff;text-align:center;padding-top:20vh'>"
                                  f"<h2>{'Connected to ' + NAME if ok else 'Not connected'}</h2><p>You can close this tab.</p></body>").encode())
            else:
                self.send_response(404); self.end_headers()

        def log_message(self, *a):
            pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://localhost:{srv.server_address[1]}"
    url = auth_url_for(redirect)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("Opening your browser to sign in. If it doesn't open, paste this URL into a browser:\n" + url + "\n")
    webbrowser.open(url)
    try:
        while not result:
            threading.Event().wait(0.5)
    except KeyboardInterrupt:
        sys.exit("Cancelled.")
    finally:
        srv.shutdown()
    if "error" in result:
        sys.exit(f"Sign-in was not completed: {result.get('error')} {result.get('error_description', '')}")
    return result["code"], redirect


def pkce():
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def connect_google(cfg, key, label, hint):
    c = cfg.get("google_client") or sys.exit("No Google client stored yet. Run: connect-mail.py google-client <client_secret.json>")
    verifier, challenge = pkce(); state = secrets.token_urlsafe(16)
    code, redirect = browser_consent(lambda r: "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": c["id"], "redirect_uri": r, "response_type": "code", "scope": GOOGLE_SCOPES, "access_type": "offline",
        "prompt": "consent", "code_challenge": challenge, "code_challenge_method": "S256", "state": state,
        **({"login_hint": hint} if hint else {})}))
    tok = post_form("https://oauth2.googleapis.com/token", {"client_id": c["id"], "client_secret": c["secret"], "code": code,
                                                           "redirect_uri": redirect, "grant_type": "authorization_code", "code_verifier": verifier})
    if "refresh_token" not in tok:
        sys.exit("Google did not return a refresh token. Remove the app at myaccount.google.com/permissions and try again.")
    email = get_json("https://gmail.googleapis.com/gmail/v1/users/me/profile", tok["access_token"])["emailAddress"]
    return {"key": key, "label": label, "provider": "google", "email": email, "refresh_token": tok["refresh_token"]}


def connect_microsoft(cfg, key, label, hint, own_client=None):
    c = own_client or cfg.get("microsoft_client") or sys.exit("No Microsoft client stored yet. Run: connect-mail.py microsoft-client <client-id>")
    verifier, challenge = pkce()
    tenant = c.get("tenant") or "common"
    code, redirect = browser_consent(lambda r: f"https://login.microsoftonline.com/{tenant}/oauth2/v2.0/authorize?" + urllib.parse.urlencode({
        "client_id": c["id"], "redirect_uri": r, "response_type": "code", "scope": MS_SCOPES, "response_mode": "query",
        "prompt": "select_account", "code_challenge": challenge, "code_challenge_method": "S256", **({"login_hint": hint} if hint else {})}))
    form = {"client_id": c["id"], "code": code, "redirect_uri": redirect, "grant_type": "authorization_code",
            "code_verifier": verifier, "scope": MS_SCOPES}
    tok = post_form(f"https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token", form)
    if "refresh_token" not in tok:
        sys.exit("Microsoft did not return a refresh token (offline_access was not granted).")
    me = get_json("https://graph.microsoft.com/v1.0/me", tok["access_token"])
    acct = {"key": key, "label": label, "provider": "microsoft", "email": me.get("mail") or me.get("userPrincipalName"),
             "refresh_token": tok["refresh_token"]}
    if own_client:
        acct["ms_client"] = own_client
    return acct


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["google-client", "microsoft-client", "google", "microsoft", "list", "remove"])
    ap.add_argument("value", nargs="?")
    ap.add_argument("--key"); ap.add_argument("--label"); ap.add_argument("--hint"); ap.add_argument("--tenant")
    ap.add_argument("--client-id", help="Microsoft: this mailbox uses its own app (e.g. personal Outlook.com accounts, --tenant consumers)")
    a = ap.parse_args()

    cur = load(); existed = cur is not None
    cfg = cur or {"accounts": []}

    if a.cmd == "list":
        print("Google client:", "set" if cfg.get("google_client") else "missing")
        print("Microsoft client:", "set" if cfg.get("microsoft_client") else "missing")
        for x in cfg["accounts"]:
            print(f"  {x['key']:<10} {x['provider']:<10} {x['email']:<35} {x['label']}")
        return
    if a.cmd == "google-client":
        path = os.path.expanduser(a.value or "")
        j = json.load(open(path)); j = j.get("installed") or j.get("web") or sys.exit("Not a Google OAuth client file.")
        cfg["google_client"] = {"id": j["client_id"], "secret": j["client_secret"]}
        save(cfg, existed); print(f"Stored the Google client. You can now delete {path}."); return
    if a.cmd == "microsoft-client":
        if not a.value: sys.exit("Pass the Application (client) ID.")
        cfg["microsoft_client"] = {"id": a.value.strip(), **({"tenant": a.tenant.strip()} if a.tenant else {})}
        save(cfg, existed); print("Stored the Microsoft client id."); return
    if not a.key:
        sys.exit("--key is required (e.g. gmail, vsa, outlook).")
    if a.cmd == "remove":
        cfg["accounts"] = [x for x in cfg["accounts"] if x["key"] != a.key]
        save(cfg, existed); print(f"Removed {a.key} from {NAME}. Also revoke it in the account's security settings."); return

    if a.cmd == "google":
        acct = connect_google(cfg, a.key, a.label or a.key, a.hint)
    else:
        own = {"id": a.client_id.strip(), "tenant": (a.tenant or "common").strip()} if a.client_id else None
        acct = connect_microsoft(cfg, a.key, a.label or a.key, a.hint, own)
    cfg["accounts"] = [x for x in cfg["accounts"] if x["key"] != a.key] + [acct]
    save(cfg, existed)
    print(f"Connected {acct['email']} as '{a.key}' ({acct['label']}): read + drafts. {NAME} cannot send.")


if __name__ == "__main__":
    main()
