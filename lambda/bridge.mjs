// Assistant <-> Claude Code bridge.
// While the owner is "away", Claude Code hooks on their computer post thread events here (a thread finished,
// or asked them a question). The phone announces them in the assistant's voice and their spoken answer is
// stored here; the waiting hook picks it up and hands it to the thread as their instruction.
//
// Items in the memory table:
//   pk "away"            { on, at }
//   pk "sleep"           { until (epoch ms), at }  sleep mode: no announcements before then
//   pk "bridge#open"     { ids: [...] }            small list of not-yet-closed event ids
//   pk "bridge#<id>"     { id, kind, project, text, options, announcement, status, answer, ... }
//   pk "bridge#recent"   { ids: [...] }            events the Mac stopped waiting on (answerable later)
//   pk "threads"         { at, items: [...] }      their threads from the desktop app (relay.py), pinned first
//   pk "outbox"          { items: [...] }          their instructions for a thread, waiting for the relay
//   pk "relay"           { at }                    last time the relay on the computer checked in
//   status: pending -> announced -> answered | closed
import { randomUUID } from "node:crypto";
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient, GetCommand, PutCommand, UpdateCommand } from "@aws-sdk/lib-dynamodb";

const TABLE = process.env.TABLE_NAME;
const ddb = DynamoDBDocumentClient.from(new DynamoDBClient({}));
const TTL_DAYS = 3;

const get = async (pk) => (await ddb.send(new GetCommand({ TableName: TABLE, Key: { pk } }))).Item || null;

/** The phone checks in every ~15 s while idle; if it hasn't lately, nobody would hear an announcement. */
export async function phoneSeen() {
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "phone", at: Date.now() } }));
}
export async function phoneListening(withinMs = 180_000) {
  return Date.now() - ((await get("phone"))?.at || 0) < withinMs;
}

/** Sleep mode: until this time (epoch ms) nothing is announced to them. */
export async function sleepUntil() {
  const u = (await get("sleep"))?.until || 0;
  return u > Date.now() ? u : 0;
}
export async function setSleep(untilMs) {
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "sleep", until: untilMs || 0, at: new Date().toISOString() } }));
}

export async function getAway() {
  return !!(await get("away"))?.on;
}

/** by: "car" when the phone's car Bluetooth set it. The car going away only ends away mode the car started,
 *  so "I'm heading out" followed by a drive stays on after the user parks. */
export async function setAway(on, by = "voice") {
  if (!on && by === "car") {
    const cur = await get("away");
    if (cur?.on && cur.by !== "car") return true;
  }
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "away", on: !!on, by, at: new Date().toISOString() } }));
  if (!on) {
    // Back at the desk: release every waiting thread so it stops normally.
    for (const e of await listOpen()) await closeEvent(e.id, "they are back at the Mac");
  }
  return !!on;
}

async function openIds() {
  return (await get("bridge#open"))?.ids || [];
}

async function saveOpenIds(ids) {
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "bridge#open", ids: ids.slice(-50) } }));
}

export async function createEvent({ kind, project, session, local, pinned, text, options, announcement }) {
  const id = randomUUID().slice(0, 8);
  const item = {
    pk: `bridge#${id}`, id, kind, project: String(project || "a").slice(0, 80), session: String(session || "").slice(0, 80),
    local: String(local || "").slice(0, 80), pinned: !!pinned,
    text: String(text || "").slice(0, 4000), options: Array.isArray(options) ? options.slice(0, 6).map(String) : [],
    announcement, status: "pending", createdAt: new Date().toISOString(),
    expiresAt: Math.floor(Date.now() / 1000) + TTL_DAYS * 86400,
  };
  await ddb.send(new PutCommand({ TableName: TABLE, Item: item }));
  await saveOpenIds([...(await openIds()), id]);
  return item;
}

export async function getEvent(id) {
  return get(`bridge#${id}`);
}

export async function listOpen() {
  const items = await Promise.all((await openIds()).map((id) => getEvent(id)));
  return items.filter((e) => e && (e.status === "pending" || e.status === "announced"));
}

/** Oldest event the phone hasn't announced yet; marks it announced. */
export async function nextForPhone() {
  const open = await listOpen();
  const e = open.find((x) => x.status === "pending");
  if (!e) return null;
  await ddb.send(new UpdateCommand({ TableName: TABLE, Key: { pk: `bridge#${e.id}` },
    UpdateExpression: "SET #s = :a, announcedAt = :t", ConditionExpression: "#s = :p",
    ExpressionAttributeNames: { "#s": "status" }, ExpressionAttributeValues: { ":a": "announced", ":p": "pending", ":t": new Date().toISOString() } }))
    .catch(() => null);
  return e;
}

export async function answerEvent(id, answer) {
  const e = await getEvent(id);
  if (!e || !(e.status === "pending" || e.status === "announced")) throw new Error("that thread is no longer waiting");
  await ddb.send(new UpdateCommand({ TableName: TABLE, Key: { pk: `bridge#${id}` },
    UpdateExpression: "SET #s = :d, answer = :ans, answeredAt = :t",
    ExpressionAttributeNames: { "#s": "status" },
    ExpressionAttributeValues: { ":d": "answered", ":ans": String(answer).slice(0, 2000), ":t": new Date().toISOString() } }));
  await saveOpenIds((await openIds()).filter((x) => x !== id));
  return { ...e, status: "answered", answer };
}

export async function closeEvent(id, reason) {
  const e = await getEvent(id);
  if (e && (e.status === "pending" || e.status === "announced")) {
    await ddb.send(new UpdateCommand({ TableName: TABLE, Key: { pk: `bridge#${id}` },
      UpdateExpression: "SET #s = :c, closedReason = :r", ExpressionAttributeNames: { "#s": "status" },
      ExpressionAttributeValues: { ":c": "closed", ":r": String(reason || "").slice(0, 200) } }));
  }
  await saveOpenIds((await openIds()).filter((x) => x !== id));
  // Still answerable later: the user may not get to it before the Mac stops waiting.
  if (e) {
    const recent = ((await get("bridge#recent"))?.ids || []).filter((x) => x !== id);
    await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "bridge#recent", ids: [...recent, id].slice(-20) } }));
  }
}

/** Questions and finished turns the Mac stopped waiting on in the last day, not yet answered. */
export async function listRecent(withinMs = 24 * 3600_000) {
  const ids = (await get("bridge#recent"))?.ids || [];
  const items = await Promise.all(ids.map((id) => getEvent(id)));
  return items.filter((e) => e && e.status === "closed" && Date.now() - Date.parse(e.createdAt) < withinMs);
}

export async function markAnsweredLater(id, answer) {
  await ddb.send(new UpdateCommand({ TableName: TABLE, Key: { pk: `bridge#${id}` },
    UpdateExpression: "SET #s = :d, answer = :ans, answeredAt = :t, answeredLater = :y", ExpressionAttributeNames: { "#s": "status" },
    ExpressionAttributeValues: { ":d": "answered", ":ans": String(answer).slice(0, 2000), ":t": new Date().toISOString(), ":y": true } }));
}

// ---------- their threads + the outbox (delivered by bridge/relay.py on the computer) ----------
export async function saveThreads(items) {
  const clean = (Array.isArray(items) ? items : []).slice(0, 60).map((t) => ({
    local: String(t.local || "").slice(0, 80), title: String(t.title || "Untitled thread").slice(0, 120), pinned: !!t.pinned,
    lastActivityAt: Number(t.lastActivityAt) || 0, said: String(t.said || "").slice(0, 600), asking: String(t.asking || "").slice(0, 400),
  })).filter((t) => t.local);
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "threads", at: Date.now(), items: clean } }));
  return clean.length;
}
export async function getThreads() {
  return (await get("threads"))?.items || [];
}

export async function relaySeen() {
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "relay", at: Date.now() } }));
}
export async function relayAlive(withinMs = 120_000) {
  return Date.now() - ((await get("relay"))?.at || 0) < withinMs;
}

export async function queueOutbox({ local, title, message }) {
  const items = (await get("outbox"))?.items || [];
  const item = { id: randomUUID().slice(0, 8), local, title, message: String(message).slice(0, 3000), at: Date.now() };
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "outbox", items: [...items, item].slice(-20) } }));
  return item;
}
export async function nextOutbox() {
  return ((await get("outbox"))?.items || [])[0] || null;
}
export async function ackOutbox(id) {
  const items = ((await get("outbox"))?.items || []).filter((x) => x.id !== id);
  await ddb.send(new PutCommand({ TableName: TABLE, Item: { pk: "outbox", items } }));
}

/** A thread from their spoken reference: its id, its title, or most of its words ("growth"). */
export function matchThread(list, ref) {
  const norm = (x) => String(x).toLowerCase().replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
  const r = norm(ref);
  if (!r) return { hit: null, candidates: [] };
  const exact = list.find((t) => t.local === ref || norm(t.title) === r);
  if (exact) return { hit: exact, candidates: [] };
  const words = r.split(" ").filter((w) => w.length > 1 && !["the", "thread", "my", "a", "an", "and", "to"].includes(w));
  const scored = list.map((t) => {
    const tw = norm(t.title).split(" ");
    return { t, score: words.filter((w) => tw.some((x) => x === w || x.startsWith(w))).length };
  }).filter((x) => x.score > 0).sort((a, b) => b.score - a.score || b.t.pinned - a.t.pinned || b.t.lastActivityAt - a.t.lastActivityAt);
  if (!scored.length) return { hit: null, candidates: [] };
  const best = scored.filter((x) => x.score === scored[0].score);
  // One clear winner, or a tie broken by pin (pinned threads are the priority).
  if (best.length === 1 || (best[0].t.pinned && !best[1].t.pinned)) return { hit: best[0].t, candidates: [] };
  return { hit: null, candidates: best.slice(0, 4).map((x) => x.t.title) };
}

/** Resolve a thread from the brain's reference: an id, a project name, or "the latest". */
export async function findOpen(ref) {
  const open = await listOpen();
  if (!open.length) return null;
  if (!ref) return open[open.length - 1];
  const r = String(ref).toLowerCase();
  return open.find((e) => e.id === r) || open.reverse().find((e) => e.project.toLowerCase().includes(r)) || null;
}
