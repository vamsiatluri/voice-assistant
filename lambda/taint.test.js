// The "only you give instructions" guard: after a turn reads other people's text, these tools must refuse.
import test from "node:test";
import assert from "node:assert/strict";

globalThis.awslambda = { streamifyResponse: (f) => f, HttpResponseStream: { from: (s) => s } };
process.env.PLUGINS = "email,tv,claude,amazon";
const { guarded, readsOthers, REFUSED_TAINTED } = await import("./index.mjs");

test("tools that act for the owner are guarded", () => {
  for (const t of ["reply_to_claude", "send_text", "remember", "save_draft", "add_calendar_event", "set_away", "car_mode", "feature_switch", "sleep_mode"])
    assert.equal(guarded(t), true, t);
  assert.equal(guarded("amazon", { action: "add" }), true);
});

test("reading and harmless tools are not guarded", () => {
  for (const t of ["read_email", "search_email", "claude_inbox", "tv", "go_quiet", "todo_list"]) assert.equal(guarded(t), false, t);
  assert.equal(guarded("amazon", { action: "find" }), false);
});

test("reading other people's text taints the turn", () => {
  for (const t of ["read_email", "search_email", "claude_inbox", "morning_briefing", "amazon"]) assert.equal(readsOthers(t), true, t);
  assert.equal(readsOthers("tv"), false);
  assert.match(REFUSED_TAINTED, /NOT done/);
});
