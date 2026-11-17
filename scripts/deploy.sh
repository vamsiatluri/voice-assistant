#!/usr/bin/env bash
# Deploy the brain and the web UI into YOUR AWS account, from config.json. Safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cfg() { python3 -I "$ROOT/scripts/cfg.py" "$1"; }
PROFILE="$(cfg awsProfile)"; REGION="$(cfg region)"; PREFIX="$(cfg prefix)"
STACK="$PREFIX"; SECRET="$PREFIX/api-keys"
export AWS_PAGER=""
aws_() { aws --profile "$PROFILE" --region "$REGION" "$@"; }

ACCOUNT="$(aws_ sts get-caller-identity --query Account --output text)"
PINNED="$(cfg awsAccountId)"
[[ -z "$PINNED" || "$PINNED" == "$ACCOUNT" ]] || { echo "Profile $PROFILE is account $ACCOUNT, config.json says $PINNED. Stopping."; exit 1; }

# 1. Keys must already be stored (scripts/put-keys.py). Make the phone's access phrase if it is missing.
SECRET_ARN="$(aws_ secretsmanager describe-secret --secret-id "$SECRET" --query ARN --output text)"
python3 -I - "$PROFILE" "$REGION" "$SECRET" "$ROOT/.passphrase" <<'PY'
import json, os, re, secrets, subprocess, sys, tempfile
profile, region, sid, out = sys.argv[1:]
aws = lambda *a: subprocess.run(["aws", *a, "--profile", profile, "--region", region, "--output", "json"], capture_output=True, text=True, check=True)
cur = json.loads(json.loads(aws("secretsmanager", "get-secret-value", "--secret-id", sid).stdout)["SecretString"])
if not cur.get("ANTHROPIC_API_KEY"):
    sys.exit(f"No ANTHROPIC_API_KEY in {sid}; run scripts/put-keys.py first.")
# 100 random bits in five groups of four, from letters and digits that can't be mixed up: typed once on the phone.
ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
make = lambda: "-".join("".join(secrets.choice(ALPHABET) for _ in range(4)) for _ in range(5))
# Early versions made a three-word phrase anyone could guess from this file: replace it.
OLD = re.compile(r"^(amber|birch|cedar|delta|ember|fable|grove|harbor|island|juniper|kestrel|lantern|meadow|north|orchard)(-(amber|birch|cedar|delta|ember|fable|grove|harbor|island|juniper|kestrel|lantern|meadow|north|orchard)){2}-\d{2}$")
old = OLD.match(cur.get("ASSISTANT_PASSPHRASE") or "")
if not cur.get("ASSISTANT_PASSPHRASE") or old:
    cur["ASSISTANT_PASSPHRASE"] = make()
    fd, p = tempfile.mkstemp(); os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w") as f: json.dump(cur, f)
    try: aws("secretsmanager", "put-secret-value", "--secret-id", sid, "--secret-string", f"file://{p}")
    finally: os.remove(p)
    print("REPLACED the old, guessable access phrase. Enter the new one in the phone app and the web page." if old else "Created a new access phrase.")
fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f: f.write(cur["ASSISTANT_PASSPHRASE"] + "\n")
print(f"Access phrase (enter it in the phone app once) is in {out}")
PY

# 2. Code bucket.
CODE_BUCKET="$PREFIX-artifacts-$ACCOUNT"
if ! aws_ s3api head-bucket --bucket "$CODE_BUCKET" 2>/dev/null; then
  if [[ "$REGION" == "us-east-1" ]]; then aws_ s3api create-bucket --bucket "$CODE_BUCKET" >/dev/null
  else aws_ s3api create-bucket --bucket "$CODE_BUCKET" --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null; fi
  aws_ s3api put-public-access-block --bucket "$CODE_BUCKET" --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
fi

# 3. Package the Lambda (content-addressed key, so every code change is a real update).
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
# The API rejects any request carrying more than 20 strict tools.
STRICT=$(grep -c "strict: true" "$ROOT/lambda/index.mjs")
if [ "$STRICT" -gt 20 ]; then echo "STOP: $STRICT strict tools in index.mjs; the API allows 20. Mark simple ones strict: false." >&2; exit 1; fi
cp "$ROOT"/lambda/*.mjs "$ROOT/lambda/package.json" "$ROOT/lambda/package-lock.json" "$ROOT/shared/commands.json" "$BUILD/"
(cd "$BUILD" && npm ci --omit=dev --silent && zip -qr brain.zip ./*.mjs commands.json package.json node_modules)
HASH="$(shasum -a 256 "$BUILD/brain.zip" | cut -c1-16)"
CODE_KEY="brain/$HASH.zip"
aws_ s3 cp "$BUILD/brain.zip" "s3://$CODE_BUCKET/$CODE_KEY" --only-show-errors

# 4. Infrastructure. Every personal setting comes from config.json.
PLUGINS="$(cfg plugins)"
EMAIL=false; [[ ",$PLUGINS," == *",email,"* ]] && EMAIL=true
aws_ cloudformation deploy --stack-name "$STACK" --template-file "$ROOT/infra/template.yaml" \
  --capabilities CAPABILITY_NAMED_IAM --no-fail-on-empty-changeset --tags "app=$PREFIX" \
  --parameter-overrides Prefix="$PREFIX" CodeBucket="$CODE_BUCKET" CodeKey="$CODE_KEY" SecretArn="$SECRET_ARN" \
    AssistantName="$(cfg assistantName)" OwnerName="$(cfg ownerName)" Address="$(cfg address)" \
    Timezone="$(cfg timezone)" Persona="$(cfg persona)" Plugins="$PLUGINS" EmailPlugin="$EMAIL" \
    KeepAlso="$(cfg emailKeepAlso)" OwnDomains="$(cfg ownDomains)" VoiceId="$(cfg voiceId)" \
    AmazonSite="$(cfg amazonSite)"

out() { aws_ cloudformation describe-stacks --stack-name "$STACK" --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
URL="$(out Url)"; DIST="$(out DistributionId)"; SITE="$(out SiteBucket)"

# Prove the live function runs the code we just built (CloudFormation can report success and change nothing).
LIVE_SHA="$(aws_ lambda get-function --function-name "$PREFIX-brain" --query 'Configuration.CodeSha256' --output text)"
LOCAL_SHA="$(openssl dgst -sha256 -binary "$BUILD/brain.zip" | base64)"
[[ "$LIVE_SHA" == "$LOCAL_SHA" ]] || { echo "Lambda is NOT running the new build ($LIVE_SHA vs $LOCAL_SHA)"; exit 1; }

# 5. UI.
# The page is static: put the assistant's name in at upload time.
WEB="$BUILD/web"; cp -R "$ROOT/web" "$WEB"
NAME="$(cfg assistantName)"; NAME_UP="$(printf '%s' "$NAME" | tr '[:lower:]' '[:upper:]')"
python3 -I - "$WEB" "$NAME" "$NAME_UP" <<'PY'
import html, pathlib, sys
web, name, up = sys.argv[1:]
for f in pathlib.Path(web).glob("*.*"):
    if f.suffix in (".html", ".json"):
        f.write_text(f.read_text().replace("__ASSISTANT_NAME_UPPER__", html.escape(up)).replace("__ASSISTANT_NAME__", html.escape(name)))
PY
# The iPhone's Siri shortcut, published next to the page (needs a Mac; skipped elsewhere).
if command -v shortcuts >/dev/null 2>&1; then
  # Named after the assistant: the file name becomes the shortcut's name, which is what you say to Siri.
  python3 -I "$ROOT/scripts/make-shortcut.py" --base "$URL" --out "$WEB/$NAME.shortcut" || echo "Siri shortcut not built (see above); the rest of the deploy carries on."
fi
aws_ s3 sync "$WEB/" "s3://$SITE/" --delete --only-show-errors
aws_ cloudfront create-invalidation --distribution-id "$DIST" --paths '/*' --query Invalidation.Id --output text >/dev/null

# 6. The phone app builds against this address.
python3 -I - "$ROOT/config.json" "$URL" <<'PY'
import json, sys
p, url = sys.argv[1:]; c = json.load(open(p)); c["apiBase"] = url; open(p, "w").write(json.dumps(c, indent=2) + "\n")
PY
echo "Live: $URL  (saved to config.json as apiBase for the phone build)"
SHORTCUT_URL="$URL/$(python3 -I -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$NAME").shortcut"
if [[ -f "$WEB/$NAME.shortcut" ]]; then
  echo "iPhone: open $SHORTCUT_URL on the phone, tap Download, then open it to add \"Hey Siri, $NAME\"."
fi
