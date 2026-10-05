import { test } from "node:test";
import assert from "node:assert/strict";
import { ApiError, REMOTE } from "../src/api.js";
import { trackCommand, OUTCOME } from "../src/outcome.js";

const TV = "11111111-1111-4111-8111-111111111111";
const OTHER_TV = "22222222-2222-4222-8222-222222222222";
const FAST = { pollMs: 1, graceMs: 20, newId: () => "cmd-1" };
const settle = (ms = 30) => new Promise((resolve) => setTimeout(resolve, ms));

/** An api whose status answers come from a queue; an Error in the queue is thrown. The last answer repeats. */
function fakeApi({ command = async () => ({ id: "cmd-1" }), statuses = [] } = {}) {
  const sent = [];
  let polls = 0;
  return {
    sent,
    get polls() { return polls; },
    command: async (body) => { sent.push(body); return command(body); },
    commandStatus: async () => {
      polls++;
      const next = statuses.length > 1 ? statuses.shift() : statuses[0];
      if (next instanceof Error) throw next;
      return { target_device_id: TV, expires_in_ms: 30_000, reason: null, ...next };
    },
  };
}

/** Collects the updates and resolves once a terminal one arrives. */
function watch(api, body, options = FAST) {
  const updates = [];
  let done;
  const terminal = new Promise((resolve) => { done = resolve; });
  const tracker = trackCommand(api, body, (u) => {
    updates.push(u);
    if (u.outcome !== OUTCOME.sent) done(u);
  }, options);
  return { tracker, updates, terminal };
}

for (const [name, build] of [["play", () => REMOTE.play(TV, "v1")], ["pause", () => REMOTE.pause(TV)], ["stop", () => REMOTE.stop(TV)]]) {
  test(`${name} shows Sent first and TV confirmed only when the TV completes it`, async () => {
    const api = fakeApi({ statuses: [{ status: "pending" }, { status: "delivered" }, { status: "completed" }] });
    const { updates, terminal } = watch(api, build());
    assert.equal((await terminal).outcome, OUTCOME.confirmed);
    assert.deepEqual(updates.map((u) => u.outcome), [OUTCOME.sent, OUTCOME.confirmed]);
    assert.equal(api.polls, 3);
  });
}

test("the command goes to the selected TV with a client-generated id", async () => {
  const api = fakeApi({ statuses: [{ status: "completed" }] });
  await watch(api, REMOTE.pause(TV)).terminal;
  assert.deepEqual(api.sent, [{ command: "pause", target_device_id: TV, payload: {}, id: "cmd-1" }]);
});

test("a command the server accepted is not confirmed while the TV hasn't answered", async () => {
  const api = fakeApi({ statuses: [{ status: "delivered", expires_in_ms: 5 }] });
  const { updates, terminal } = watch(api, REMOTE.pause(TV));
  const last = await terminal;
  assert.deepEqual([last.outcome, last.reason], [OUTCOME.unconfirmed, "timeout"]);
  assert.ok(!updates.some((u) => u.outcome === OUTCOME.confirmed));
});

test("a delayed completion inside the wait still counts", async () => {
  const api = fakeApi({ statuses: [...Array(5).fill({ status: "delivered", expires_in_ms: 5_000 }), { status: "completed" }] });
  assert.equal((await watch(api, REMOTE.stop(TV)).terminal).outcome, OUTCOME.confirmed);
});

test("a TV rejection is shown as rejected, with its reason", async () => {
  const api = fakeApi({ statuses: [{ status: "rejected", reason: "no_profile_selected" }] });
  const last = await watch(api, REMOTE.play(TV, "v1")).terminal;
  assert.deepEqual([last.outcome, last.reason], [OUTCOME.rejected, "no_profile_selected"]);
});

test("a command the server expired is Couldn't confirm, never a rejection or success", async () => {
  const api = fakeApi({ statuses: [{ status: "rejected", reason: "expired" }] });
  const last = await watch(api, REMOTE.pause(TV)).terminal;
  assert.deepEqual([last.outcome, last.reason], [OUTCOME.unconfirmed, "expired"]);
});

test("a command the server refuses never reaches a TV and is never polled", async () => {
  const api = fakeApi({ command: async () => { throw new ApiError(400, "unknown_target_device"); } });
  const { updates, terminal } = watch(api, REMOTE.pause(TV));
  assert.deepEqual([(await terminal).outcome, updates[0].reason], [OUTCOME.rejected, "unknown_target_device"]);
  assert.deepEqual(updates.map((u) => u.outcome), [OUTCOME.rejected]);
  assert.equal(api.polls, 0);
});

test("offline while sending is Couldn't confirm, and the id can be reused so a retry can't run twice", async () => {
  const api = fakeApi({ command: async () => { throw new TypeError("Failed to fetch"); } });
  const last = await watch(api, REMOTE.pause(TV)).terminal;
  assert.deepEqual([last.outcome, last.reason], [OUTCOME.unconfirmed, "network"]);
  const retry = fakeApi({ statuses: [{ status: "completed" }] });
  const { tracker, terminal } = watch(retry, REMOTE.pause(TV), { ...FAST, id: "cmd-1" });
  assert.equal((await terminal).outcome, OUTCOME.confirmed);
  assert.equal(tracker.id, "cmd-1");
  assert.equal(retry.sent[0].id, "cmd-1");
});

test("a status that names another TV is never reported as success", async () => {
  const api = fakeApi({ statuses: [{ status: "completed", target_device_id: OTHER_TV }] });
  const last = await watch(api, REMOTE.pause(TV)).terminal;
  assert.deepEqual([last.outcome, last.reason], [OUTCOME.unconfirmed, "other_device"]);
});

test("a server without the status route cannot confirm", async () => {
  const api = fakeApi({ statuses: [new ApiError(404, null)] });
  const last = await watch(api, REMOTE.pause(TV)).terminal;
  assert.deepEqual([last.outcome, last.reason], [OUTCOME.unconfirmed, "unsupported"]);
});

test("a flaky status request is tried again, but not forever", async () => {
  const flaky = fakeApi({ statuses: [new TypeError("offline"), new TypeError("offline"), { status: "completed" }] });
  assert.equal((await watch(flaky, REMOTE.pause(TV)).terminal).outcome, OUTCOME.confirmed);
  const down = fakeApi({ statuses: [new TypeError("offline")] });
  const last = await watch(down, REMOTE.pause(TV)).terminal;
  assert.deepEqual([last.outcome, last.reason], [OUTCOME.unconfirmed, "status_unavailable"]);
  assert.equal(down.polls, 3);
});

test("polling stops at a terminal state", async () => {
  const api = fakeApi({ statuses: [{ status: "completed" }] });
  await watch(api, REMOTE.pause(TV)).terminal;
  const polls = api.polls;
  await settle();
  assert.equal(api.polls, polls);
});

test("cancel (teardown or a different TV) stops polling and reports nothing more", async () => {
  const api = fakeApi({ statuses: [{ status: "delivered" }] });
  const { tracker, updates } = watch(api, REMOTE.pause(TV));
  await tracker.accepted;
  tracker.cancel();
  await settle();
  const polls = api.polls;
  await settle();
  assert.equal(api.polls, polls);
  assert.deepEqual(updates.map((u) => u.outcome), [OUTCOME.sent]);
});

test("cancelling before the server answers the send reports nothing and never polls", async () => {
  let release;
  const api = fakeApi({ command: () => new Promise((resolve) => { release = resolve; }), statuses: [{ status: "completed" }] });
  const { tracker, updates } = watch(api, REMOTE.pause(TV));
  tracker.cancel();
  release({ id: "cmd-1" });
  await settle();
  assert.deepEqual(updates, []);
  assert.equal(api.polls, 0);
});
