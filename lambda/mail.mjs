// Email for the assistant: search, read, and SAVE DRAFTS across Gmail and Microsoft 365.
// Deliberately has NO send function. Drafts land in each account's Drafts folder for a human to send.
//
// Accounts and refresh tokens live in Secrets Manager (MAIL_SECRET_ID), shaped like:
// { "google_client": {"id","secret"}, "microsoft_client": {"id","secret"?},
//   "accounts": [{ "key":"outlook", "label":"Outlook (work)", "provider":"microsoft",
//                  "email":"...", "refresh_token":"..." }, ...] }
import { SecretsManagerClient, GetSecretValueCommand, PutSecretValueCommand } from "@aws-sdk/client-secrets-manager";

const sm = new SecretsManagerClient({});
const SECRET_ID = process.env.MAIL_SECRET_ID;
let cfg;          // cached secret
let cfgAt = 0;    // when it was read; re-read so a newly connected mailbox shows up without a redeploy
const CFG_TTL_MS = 2 * 60_000;
const tokens = {}; // key -> { access, exp }

async function config() {
  if (!cfg || Date.now() - cfgAt > CFG_TTL_MS) {
    cfgAt = Date.now();
    try {
      const r = await sm.send(new GetSecretValueCommand({ SecretId: SECRET_ID }));
      cfg = JSON.parse(r.SecretString);
    } catch (e) {
      if (e.name === "ResourceNotFoundException") cfg = { accounts: [] };
      else throw e;
    }
  }
  return cfg;
}

export async function listAccounts() {
  return (await config()).accounts.map(({ key, label, provider, email }) => ({ key, label, provider, email }));
}

async function account(key) {
  const c = await config();
  const a = key ? c.accounts.find((x) => x.key === key) : c.accounts[0];
  if (!a) throw new Error(`no connected email account "${key}". Connected: ${c.accounts.map((x) => x.key).join(", ") || "none"}`);
  return a;
}

// ---------- access tokens ----------
async function accessToken(a) {
  const t = tokens[a.key];
  if (t && t.exp > Date.now() + 60_000) return t.access;
  const c = await config();
  let url, form;
  if (a.provider === "google") {
    url = "https://oauth2.googleapis.com/token";
    form = { client_id: c.google_client.id, client_secret: c.google_client.secret, refresh_token: a.refresh_token, grant_type: "refresh_token" };
  } else {
    const ms = a.ms_client || c.microsoft_client;   // personal Outlook.com accounts use their own app
    url = `https://login.microsoftonline.com/${ms.tenant || "common"}/oauth2/v2.0/token`;
    form = { client_id: ms.id, refresh_token: a.refresh_token, grant_type: "refresh_token",
      scope: "offline_access Mail.ReadWrite User.Read" };
    if (ms.secret) form.client_secret = ms.secret;
  }
  const r = await fetch(url, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(form) });
  const j = await r.json();
  if (!r.ok) throw new Error(`${a.label}: sign-in expired or revoked (${j.error || r.status}). Reconnect it with scripts/connect-mail.py.`);
  tokens[a.key] = { access: j.access_token, exp: Date.now() + (j.expires_in || 3000) * 1000 };
  // Microsoft rotates refresh tokens; keep the newest so the connection never lapses.
  if (j.refresh_token && j.refresh_token !== a.refresh_token) {
    a.refresh_token = j.refresh_token;
    await sm.send(new PutSecretValueCommand({ SecretId: SECRET_ID, SecretString: JSON.stringify(c) })).catch((e) => console.warn("could not persist rotated token:", e.message));
  }
  return j.access_token;
}

async function api(a, url, opts = {}) {
  // Reads are retried when Microsoft/Google's gateway fails (502/503/504 or a hang): Graph does this
  // in bursts, where the same request works seconds later. Writes are never retried.
  const read = !opts.method || opts.method === "GET";
  const tries = read ? (opts.attempts || 2) : 1;
  for (let i = 1; ; i++) {
    let r;
    try {
      r = await fetch(url, {
        ...opts,
        headers: { authorization: `Bearer ${await accessToken(a)}`, ...(opts.body ? { "content-type": "application/json" } : {}), ...(opts.headers || {}) },
        signal: AbortSignal.timeout(opts.timeoutMs || 12_000),
      });
    } catch (e) {
      if (i < tries && e.name === "TimeoutError") continue;
      throw e;
    }
    if (!r.ok && i < tries && [502, 503, 504].includes(r.status)) { await new Promise((ok) => setTimeout(ok, 1000 * i)); continue; }
    if (!r.ok) throw new Error(`${a.label} API ${r.status}: ${(await r.text()).slice(0, 200)}`);
    return r.status === 204 ? {} : r.json();
  }
}

const clip = (s, n) => (s && s.length > n ? `${s.slice(0, n)}…` : s || "");
const b64url = (s) => Buffer.from(s, "utf8").toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const fromB64url = (s) => Buffer.from(s.replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8");
const stripHtml = (h) => h.replace(/<style[\s\S]*?<\/style>|<script[\s\S]*?<\/script>/gi, " ").replace(/<br\s*\/?>|<\/p>|<\/div>/gi, "\n")
  .replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&#39;/g, "'").replace(/&quot;/g, '"')
  .replace(/[ \t]+/g, " ").replace(/\n\s*\n+/g, "\n\n").trim();

// ---------- search ----------
export async function searchEmail({ account: key, query = "", max = 6, unread_only = false }) {
  const keys = key ? [key] : (await config()).accounts.map((a) => a.key);
  const n = Math.min(Math.max(1, max), 10);
  const per = await Promise.all(keys.map(async (k) => {
    const a = await account(k);
    try {
      return a.provider === "google" ? await gmailSearch(a, query, n, unread_only) : await graphSearch(a, query, n, unread_only);
    } catch (e) { return [{ account: a.key, error: e.message }]; }
  }));
  return per.flat();
}

async function gmailSearch(a, query, n, unread) {
  const q = [query, unread ? "is:unread" : "", query ? "" : "in:inbox"].filter(Boolean).join(" ");
  const list = await api(a, `https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=${n}&q=${encodeURIComponent(q)}`);
  return Promise.all((list.messages || []).map(async ({ id }) => {
    const m = await api(a, `https://gmail.googleapis.com/gmail/v1/users/me/messages/${id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date`);
    const h = Object.fromEntries((m.payload?.headers || []).map((x) => [x.name.toLowerCase(), x.value]));
    return { account: a.key, id, from: h.from, subject: h.subject, date: h.date, unread: (m.labelIds || []).includes("UNREAD"), snippet: clip(m.snippet, 160) };
  }));
}

async function graphSearch(a, query, n, unread) {
  const sel = "$select=id,subject,from,receivedDateTime,isRead,bodyPreview";
  let url;
  if (query) url = `https://graph.microsoft.com/v1.0/me/messages?$search=${encodeURIComponent(`"${query.replace(/"/g, "")}"`)}&$top=${n}&${sel}`;
  else url = `https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?$top=${n}&$orderby=receivedDateTime desc&${sel}${unread ? "&$filter=isRead eq false" : ""}`;
  const r = await api(a, url);
  return (r.value || []).filter((m) => !unread || !m.isRead).map((m) => ({
    account: a.key, id: m.id, from: m.from?.emailAddress ? `${m.from.emailAddress.name} <${m.from.emailAddress.address}>` : "",
    subject: m.subject, date: m.receivedDateTime, unread: !m.isRead, snippet: clip(m.bodyPreview, 160),
  }));
}

/** For charge alerts: anything that looks like a receipt, invoice, charge or refund since a time, in every
 *  folder (the cleanup moves mail around, and receipts can land in Other or Promotions). The model decides
 *  which of these are real charges; this is only the net. */
const RECEIPT_WORDS = ["receipt", "invoice", "charged", "billed", "\"payment received\"", "\"payment confirmation\"", "\"payment successful\"",
  "\"payment failed\"", "\"payment declined\"", "\"order confirmation\"", "\"your order\"", "refund", "renewal", "\"amount due\""];
export async function listReceipts(sinceIso, max = 25) {
  const keys = (await config()).accounts.map((a) => a.key);
  const per = await Promise.all(keys.map(async (k) => {
    const a = await account(k);
    try {
      if (a.provider === "google") {
        return await gmailSearch(a, `after:${Math.floor(Date.parse(sinceIso) / 1000)} (${RECEIPT_WORDS.join(" OR ")}) -in:spam -in:trash`, max, false);
      }
      // Graph's $search returns newest first and won't mix with $filter; single words only, dates checked here.
      const kql = "receipt OR invoice OR charged OR billed OR payment OR order OR refund OR renewal";
      const url = `https://graph.microsoft.com/v1.0/me/messages?$search=${encodeURIComponent(`"${kql}"`)}&$top=${Math.max(max, 40)}&$select=id,subject,from,receivedDateTime,bodyPreview`;
      const r = await api(a, url, { timeoutMs: 20_000, attempts: 3 });
      return (r.value || []).filter((m) => m.receivedDateTime >= sinceIso).map((m) => ({ account: a.key, id: m.id,
        from: m.from?.emailAddress ? `${m.from.emailAddress.name} <${m.from.emailAddress.address}>` : "", subject: m.subject, date: m.receivedDateTime, snippet: clip(m.bodyPreview, 160) }));
    } catch (e) { return [{ account: a.key, error: e.message.slice(0, 160) }]; }
  }));
  return per.flat();
}


/** For the morning briefing: inbox mail since a time, minus the low-priority tabs, across every mailbox. */
export async function listImportant(sinceIso, max = 15) {
  const keys = (await config()).accounts.map((a) => a.key);
  // A spoken briefing can't wait on a mailbox that's down: 5 s each, and one that failed is skipped
  // for 15 minutes rather than costing every briefing the full wait (Outlook outage, 11-12).
  const budget = (p, k) => Promise.race([p, new Promise((_, no) => setTimeout(() => no(new Error(`${k} did not answer in time`)), 5_000))]);
  const per = await Promise.all(keys.map(async (k) => {
    const a = await account(k);
    if ((mailboxDownUntil[k] || 0) > Date.now()) return [{ account: k, error: "not answering (skipped; it failed a few minutes ago)" }];
    try {
      if (a.provider === "google") return await budget(gmailSearch(a, `in:inbox after:${Math.floor(Date.parse(sinceIso) / 1000)} -category:promotions -category:social -in:chats`, max, false), k);
      const sel = "$select=id,subject,from,receivedDateTime,isRead,bodyPreview";
      const url = `https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?$filter=${encodeURIComponent(`receivedDateTime ge ${sinceIso} and inferenceClassification eq 'focused'`)}&$top=${max}&${sel}`;
      const r = await budget(api(a, url, { timeoutMs: 4_500, attempts: 1 }), k);
      return (r.value || []).map((m) => ({ account: a.key, id: m.id, from: m.from?.emailAddress ? `${m.from.emailAddress.name} <${m.from.emailAddress.address}>` : "",
        subject: m.subject, date: m.receivedDateTime, unread: !m.isRead, snippet: clip(m.bodyPreview, 160) }));
    } catch (e) { mailboxDownUntil[k] = Date.now() + 15 * 60_000; return [{ account: a.key, error: e.message.slice(0, 120) }]; }
  }));
  return per.flat();
}
const mailboxDownUntil = {};   // per container; the keep-warm ping keeps one container alive

// ---------- read ----------
export async function readEmail({ account: key, id }) {
  const a = await account(key);
  if (a.provider === "google") {
    const m = await api(a, `https://gmail.googleapis.com/gmail/v1/users/me/messages/${id}?format=full`);
    const h = Object.fromEntries((m.payload?.headers || []).map((x) => [x.name.toLowerCase(), x.value]));
    const parts = []; const walk = (p) => { if (!p) return; if (p.body?.data) parts.push({ type: p.mimeType, text: fromB64url(p.body.data) }); (p.parts || []).forEach(walk); };
    walk(m.payload);
    const plain = parts.find((p) => p.type === "text/plain")?.text;
    const body = plain || stripHtml(parts.find((p) => p.type === "text/html")?.text || m.snippet || "");
    return { account: a.key, id, from: h.from, to: h.to, subject: h.subject, date: h.date, body: clip(body.trim(), 5000) };
  }
  const m = await api(a, `https://graph.microsoft.com/v1.0/me/messages/${id}?$select=subject,from,toRecipients,receivedDateTime,body`,
    { headers: { Prefer: 'outlook.body-content-type="text"' } });
  return {
    account: a.key, id, from: m.from?.emailAddress?.address, to: (m.toRecipients || []).map((r) => r.emailAddress.address).join(", "),
    subject: m.subject, date: m.receivedDateTime, body: clip((m.body?.content || "").trim(), 5000),
  };
}

// ---------- drafts (never send) ----------
export async function saveDraft({ account: key, reply_to_id, to, subject, body }) {
  const a = await account(key);
  if (!body) throw new Error("draft body is empty");
  if (a.provider === "google") {
    let threadId, headers = [];
    if (reply_to_id) {
      const m = await api(a, `https://gmail.googleapis.com/gmail/v1/users/me/messages/${reply_to_id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Message-ID&metadataHeaders=References`);
      const h = Object.fromEntries((m.payload?.headers || []).map((x) => [x.name.toLowerCase(), x.value]));
      threadId = m.threadId;
      to = to || h.from;
      subject = subject || (h.subject?.startsWith("Re:") ? h.subject : `Re: ${h.subject || ""}`);
      if (h["message-id"]) headers.push(`In-Reply-To: ${h["message-id"]}`, `References: ${[h.references, h["message-id"]].filter(Boolean).join(" ")}`);
    }
    if (!to) throw new Error("a new draft needs a recipient");
    const raw = [`To: ${to}`, `Subject: ${subject || ""}`, ...headers, "Content-Type: text/plain; charset=UTF-8", "", body].join("\r\n");
    const d = await api(a, "https://gmail.googleapis.com/gmail/v1/users/me/drafts", { method: "POST", body: JSON.stringify({ message: { raw: b64url(raw), ...(threadId ? { threadId } : {}) } }) });
    return { saved: true, account: a.label, draft_id: d.id, to, subject, note: "Saved in Drafts. Not sent." };
  }
  if (reply_to_id) {
    const d = await api(a, `https://graph.microsoft.com/v1.0/me/messages/${reply_to_id}/createReply`, { method: "POST", body: JSON.stringify({ comment: body }) });
    return { saved: true, account: a.label, draft_id: d.id, subject: d.subject, note: "Saved in Drafts. Not sent." };
  }
  if (!to) throw new Error("a new draft needs a recipient");
  const d = await api(a, "https://graph.microsoft.com/v1.0/me/messages", {
    method: "POST",
    body: JSON.stringify({ subject: subject || "", body: { contentType: "Text", content: body }, toRecipients: to.split(",").map((x) => ({ emailAddress: { address: x.trim() } })) }),
  });
  return { saved: true, account: a.label, draft_id: d.id, to, subject, note: "Saved in Drafts. Not sent." };
}

// ---------- cleanup helpers (Outlook "Other" tab / Gmail Promotions, Social, Updates) ----------
const GRAPH = "https://graph.microsoft.com/v1.0/me";
const GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";

/** Low-priority inbox mail received since `sinceIso`: Outlook's "Other" tab, or Gmail's category tabs. */
export async function listClutter(key, sinceIso, max = 300) {
  const a = await account(key);
  if (a.provider === "microsoft") {
    const sel = "$select=id,subject,from,receivedDateTime,bodyPreview,hasAttachments,importance,flag";
    // Graph requires the sort property to come first in the filter.
    let url = `${GRAPH}/mailFolders/inbox/messages?$filter=${encodeURIComponent(`receivedDateTime ge ${sinceIso} and inferenceClassification eq 'other' and isRead eq false`)}&$orderby=receivedDateTime desc&$top=50&${sel}`;
    const out = [];
    // A filtered, sorted inbox query can take Graph well over 12 s on a big mailbox.
    while (url && out.length < max) { const r = await api(a, url, { timeoutMs: 30_000, attempts: 4 }); out.push(...(r.value || [])); url = r["@odata.nextLink"]; }
    return out.slice(0, max).map((m) => ({
      id: m.id, subject: m.subject || "", from: m.from?.emailAddress?.address || "", fromName: m.from?.emailAddress?.name || "",
      received: m.receivedDateTime, preview: clip((m.bodyPreview || "").replace(/\s+/g, " "), 240),
      hasAttachments: m.hasAttachments, importance: m.importance, flagged: m.flag?.flagStatus === "flagged",
    }));
  }
  const after = Math.floor(Date.parse(sinceIso) / 1000);
  const q = `in:inbox is:unread after:${after} (category:promotions OR category:social OR category:updates)`;   // the user only cares about unread
  const ids = [];
  let token = "";
  do {
    const r = await api(a, `${GMAIL}/messages?maxResults=100&q=${encodeURIComponent(q)}${token ? `&pageToken=${token}` : ""}`);
    ids.push(...(r.messages || []).map((m) => m.id));
    token = r.nextPageToken || "";
  } while (token && ids.length < max);
  const out = [];
  for (let i = 0; i < Math.min(ids.length, max); i += 20) {
    out.push(...await Promise.all(ids.slice(i, i + 20).map(async (id) => {
      const m = await api(a, `${GMAIL}/messages/${id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date&metadataHeaders=Content-Type`);
      const h = Object.fromEntries((m.payload?.headers || []).map((x) => [x.name.toLowerCase(), x.value]));
      const fm = /^(.*?)\s*<([^>]+)>/.exec(h.from || "") || [null, "", h.from || ""];
      return {
        id, subject: h.subject || "", from: fm[2].trim(), fromName: fm[1].replace(/"/g, "").trim(), received: h.date,
        preview: clip((m.snippet || "").replace(/\s+/g, " "), 240),
        hasAttachments: /multipart\/mixed/i.test(h["content-type"] || ""), importance: "normal",
        flagged: (m.labelIds || []).includes("STARRED"),
      };
    })));
  }
  return out;
}

/** Where cleaned mail goes: an Outlook folder or a Gmail label (created if missing). Returns its id. */
export async function ensureCleanPlace(key, name) {
  const a = await account(key);
  if (a.provider === "microsoft") {
    const r = await api(a, `${GRAPH}/mailFolders?$filter=${encodeURIComponent(`displayName eq '${name.replace(/'/g, "''")}'`)}&$select=id`);
    if (r.value?.[0]) return r.value[0].id;
    return (await api(a, `${GRAPH}/mailFolders`, { method: "POST", body: JSON.stringify({ displayName: name }) })).id;
  }
  const labels = await api(a, `${GMAIL}/labels`);
  const hit = (labels.labels || []).find((l) => l.name === name);
  if (hit) return hit.id;
  return (await api(a, `${GMAIL}/labels`, { method: "POST", body: JSON.stringify({ name, labelListVisibility: "labelShow", messageListVisibility: "show" }) })).id;
}

/** Move out of the inbox into the clean place. Returns the id needed to restore it. */
export async function moveToClean(key, id, placeId) {
  const a = await account(key);
  if (a.provider === "microsoft")
    return (await api(a, `${GRAPH}/messages/${id}/move`, { method: "POST", body: JSON.stringify({ destinationId: placeId }) })).id;
  await api(a, `${GMAIL}/messages/${id}/modify`, { method: "POST", body: JSON.stringify({ addLabelIds: [placeId], removeLabelIds: ["INBOX"] }) });
  return id;
}

/** Undo moveToClean. */
export async function restoreToInbox(key, id, placeId) {
  const a = await account(key);
  if (a.provider === "microsoft")
    return api(a, `${GRAPH}/messages/${id}/move`, { method: "POST", body: JSON.stringify({ destinationId: "inbox" }) });
  return api(a, `${GMAIL}/messages/${id}/modify`, { method: "POST", body: JSON.stringify({ addLabelIds: ["INBOX"], removeLabelIds: placeId ? [placeId] : [] }) });
}
