import { test } from "node:test";
import assert from "node:assert/strict";
import { createApi, REMOTE, ApiError, validateServerUrl } from "../src/api.js";

function memoryStorage() {
  const data = new Map();
  return { getItem: (k) => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: (k) => data.delete(k) };
}

/** A fetch that answers from a queue and records what was asked. */
function fakeFetch(...replies) {
  const calls = [];
  const fn = async (url, init = {}) => {
    calls.push({ url, ...init, body: init.body ? JSON.parse(init.body) : undefined });
    const [status, body] = replies.shift() ?? [500, {}];
    return new Response(status === 204 ? null : JSON.stringify(body), { status });
  };
  fn.calls = calls;
  return fn;
}

const BASE = "https://api.example";

const LEGAL = { terms: { version: "2026-10-15", url: "t" }, privacy: { version: "2026-10-16", url: "p" } };

test("a code for a known email signs in as the web client and keeps the tokens", async () => {
  const fetch = fakeFetch([202, { sent: true }], [200, { access_token: "a1", refresh_token: "r1", display_name: "Home" }]);
  const storage = memoryStorage();
  const api = createApi({ base: BASE + "/", storage, fetch });
  await api.requestCode("a@b.co");
  assert.equal(fetch.calls[0].url, `${BASE}/api/auth/email-code/request`);
  assert.deepEqual(fetch.calls[0].body, { email: "a@b.co" });
  await api.verifyCode("a@b.co", "123456");
  assert.deepEqual(fetch.calls[1].body, { email: "a@b.co", code: "123456", client: "web" });
  assert.equal(fetch.calls[1].headers.authorization, undefined);
  assert.equal(api.signedIn, true);
  assert.equal(api.householdName, "Home");
  assert.equal(JSON.parse(storage.getItem("cablegram.session")).refresh_token, "r1");
});

test("a new person is not signed in until Create your account sends the Terms and age", async () => {
  const fetch = fakeFetch(
    [200, { status: "signup_required", signup_ticket: "t-1", email: "new@b.co" }],
    [201, { access_token: "a1", refresh_token: "r1", display_name: "Sam" }],
  );
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  const result = await api.verifyCode("new@b.co", "123456");
  assert.equal(result.status, "signup_required");
  assert.equal(api.signedIn, false);
  await api.signUp("t-1", "Sam", LEGAL);
  assert.equal(fetch.calls[1].url, `${BASE}/api/auth/signup`);
  assert.deepEqual(fetch.calls[1].body, {
    signup_ticket: "t-1", name: "Sam", accepted: { terms_version: "2026-10-15", privacy_version: "2026-10-16" }, age_confirmed: true, client: "web",
  });
  assert.equal(api.signedIn, true);
});

test("Google sends the ID token with its nonce; methods and legal need no sign-in", async () => {
  const fetch = fakeFetch(
    [200, { google: { client_id: "c" }, email_code: true }],
    [200, LEGAL],
    [200, { access_token: "a1", refresh_token: "r1" }],
  );
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  assert.equal((await api.methods()).google.client_id, "c");
  assert.equal((await api.legal()).terms.version, "2026-10-15");
  await api.google("id-token", "nonce-1");
  assert.deepEqual(fetch.calls[2].body, { id_token: "id-token", nonce: "nonce-1", client: "web" });
  assert.equal(api.signedIn, true);
});

test("accepting new Terms sends the current versions with the session", async () => {
  const fetch = fakeFetch([200, { access_token: "a1", refresh_token: "r1" }], [204, null]);
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  await api.acceptTerms(LEGAL);
  assert.equal(fetch.calls[1].url, `${BASE}/api/auth/legal/accept`);
  assert.equal(fetch.calls[1].headers.authorization, "Bearer a1");
  assert.deepEqual(fetch.calls[1].body, { terms_version: "2026-10-15", privacy_version: "2026-10-16" });
});

test("an expired access token is refreshed once and the request is retried", async () => {
  const fetch = fakeFetch(
    [200, { access_token: "a1", refresh_token: "r1" }],
    [401, { error: "unauthorized" }],
    [200, { access_token: "a2", refresh_token: "r2" }],
    [200, { items: [{ id: "i1" }] }],
  );
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  assert.deepEqual(await api.library(), [{ id: "i1" }]);
  assert.equal(fetch.calls[2].url, `${BASE}/api/auth/refresh`);
  assert.deepEqual(fetch.calls[2].body, { refresh_token: "r1" });
  assert.equal(fetch.calls[3].headers.authorization, "Bearer a2");
});

test("a refused refresh signs the user out instead of looping", async () => {
  const fetch = fakeFetch([200, { access_token: "a1", refresh_token: "r1" }], [401, {}], [401, {}]);
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  await assert.rejects(api.library(), (e) => e instanceof ApiError && e.status === 401);
  assert.equal(api.signedIn, false);
  assert.equal(fetch.calls.length, 3);
});

test("TV login answers are only approved or denied", async () => {
  const fetch = fakeFetch([200, { access_token: "a", refresh_token: "r" }], [200, {}], [200, {}]);
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  await api.answerTvLogin("id-1", true);
  await api.answerTvLogin("id-2", false);
  assert.deepEqual(fetch.calls[1].body, { outcome: "approved" });
  assert.deepEqual(fetch.calls[2].body, { outcome: "denied" });
  assert.equal(fetch.calls[1].url, `${BASE}/api/telegram/tv-logins/id-1/result`);
});

test("account() drops revoked TVs and phones, and says when new Terms must be accepted", async () => {
  const fetch = fakeFetch(
    [200, { access_token: "a", refresh_token: "r" }],
    [200, { legal: { acceptance_required: true }, household: { profile_lock: true }, devices: [
      { id: "1", kind: "tv", display_name: "Den", revoked_at: null },
      { id: "2", kind: "tv", display_name: "Old", revoked_at: "2026-01-01" },
      { id: "3", kind: "phone", display_name: "Phone", revoked_at: null },
    ] }],
  );
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  const account = await api.account();
  assert.deepEqual(account.tvs.map((d) => d.id), ["1"]);
  assert.equal(account.termsRequired, true);
  assert.equal(account.profileLock, true);
  assert.equal(account.sharedTvIdleMinutes, 240);
});

test("remote commands have the shape the control plane and the TV accept", () => {
  const tv = "11111111-1111-4111-8111-111111111111";
  assert.deepEqual(REMOTE.play(tv, "item-1"), { command: "play", target_device_id: tv, payload: { videoId: "item-1" } });
  assert.deepEqual(REMOTE.play(tv), { command: "play", target_device_id: tv, payload: {} });
  assert.deepEqual(REMOTE.seek(tv, -10).payload, { seconds: -10 });
  assert.deepEqual(REMOTE.seek(tv, 9e9).payload, { seconds: 86400 });
  assert.deepEqual(REMOTE.volume(tv, 140).payload, { level: 100 });
  assert.deepEqual(REMOTE.mute(tv, 1).payload, { muted: true });
  assert.deepEqual(REMOTE.move(tv, "left").payload, { direction: "left" });
  assert.throws(() => REMOTE.move(tv, "diagonal"));
  assert.throws(() => REMOTE.pause(null));
});

test("commandStatus reads one command by id", async () => {
  const fetch = fakeFetch([200, { access_token: "a1", refresh_token: "r1" }], [200, { id: "c1", status: "delivered", target_device_id: "tv-1", expires_in_ms: 20000 }]);
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  const status = await api.commandStatus("c1");
  assert.equal(status.status, "delivered");
  assert.equal(fetch.calls[1].url, `${BASE}/api/control/commands/c1`);
  assert.equal(fetch.calls[1].method, "GET");
});

test("profile lock: list and answer TV profile requests, read and change the lock", async () => {
  const fetch = fakeFetch(
    [200, { access_token: "a1", refresh_token: "r1" }],
    [200, { items: [{ request_id: "r-1", profile_name: "Kids", tv_name: "Den" }] }],
    [200, { ok: true, status: "approved" }],
    [200, { household: { profile_lock: true } }],
    [200, { household: { profile_lock: false } }],
  );
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  assert.deepEqual(await api.pendingProfileRequests(), [{ request_id: "r-1", profile_name: "Kids", tv_name: "Den" }]);
  assert.equal(fetch.calls[1].url, `${BASE}/api/profiles/switch-requests/pending`);
  await api.answerProfileRequest("r-1", true);
  assert.equal(fetch.calls[2].url, `${BASE}/api/profiles/switch-requests/r-1/approve`);
  assert.equal(fetch.calls[2].method, "POST");
  assert.equal(await api.profileLock(), true);
  assert.equal(await api.setProfileLock(false), false);
  assert.equal(fetch.calls[4].method, "PATCH");
  assert.deepEqual(fetch.calls[4].body, { profile_lock: false });
});

test("renaming a TV patches its display name, trimmed and capped", async () => {
  const fetch = fakeFetch([200, { access_token: "a", refresh_token: "r" }], [200, { ok: true }]);
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.verifyCode("a@b.co", "123456");
  await api.renameTv("tv-1", `  ${"x".repeat(100)}  `);
  assert.equal(fetch.calls[1].url, `${BASE}/api/devices/tv-1`);
  assert.equal(fetch.calls[1].method, "PATCH");
  assert.equal(fetch.calls[1].body.display_name.length, 80);
});

test("validateServerUrl accepts HTTPS and private LAN HTTP, rejects public HTTP", () => {
  assert.deepEqual(validateServerUrl("https://tv.example.com/"), { ok: true, url: "https://tv.example.com", insecureLan: false });
  assert.deepEqual(validateServerUrl("http://192.168.1.50:3000/"), { ok: true, url: "http://192.168.1.50:3000", insecureLan: true });
  assert.deepEqual(validateServerUrl("http://cablegram.local:3000"), { ok: true, url: "http://cablegram.local:3000", insecureLan: true });
  assert.equal(validateServerUrl("http://tv.example.com").ok, false);
  assert.equal(validateServerUrl("ftp://tv.example.com").ok, false);
});

test("device sign-in: server capabilities, setup code claim, join requests and server switching", async () => {
  const fetch = fakeFetch(
    [200, { api_version: 1, edition: "community", auth: { mode: "device" }, features: { artwork: false } }],
    [200, { access_token: "a-setup", refresh_token: "r-setup", display_name: "Home" }],
    [201, { id: "jr-1", poll_secret: "sec-1", short_code: "123-456" }],
    [200, { status: "approved", access_token: "a-join", refresh_token: "r-join", display_name: "Home" }],
    [200, { requests: [{ id: "jr-2", device_name: "Phone 2", short_code: "654-321" }] }],
    [200, { status: "approved" }],
  );
  const storage = memoryStorage();
  const api = createApi({ base: BASE, storage, fetch });
  const caps = await api.server();
  assert.equal(caps.edition, "community");
  assert.equal(caps.auth.mode, "device");

  await api.claimSetup("ABCD-EFGH", "My iPhone");
  assert.equal(fetch.calls[1].url, `${BASE}/api/auth/setup/claim`);
  assert.deepEqual(fetch.calls[1].body, { code: "ABCD-EFGH", device_name: "My iPhone", client: "web" });
  assert.equal(api.signedIn, true);

  // Switching server clears session tokens and updates target URL
  api.setBaseUrl("https://self.example.com/");
  assert.equal(api.baseUrl, "https://self.example.com");
  assert.equal(api.signedIn, false);
  assert.equal(storage.getItem("cablegram.server"), "https://self.example.com");

  const jr = await api.createJoinRequest("iPad");
  assert.equal(fetch.calls[2].url, "https://self.example.com/api/auth/join-requests");
  assert.equal(jr.short_code, "123-456");

  await api.pollJoinRequest("jr-1", "sec-1");
  assert.equal(fetch.calls[3].url, "https://self.example.com/api/auth/join-requests/jr-1?secret=sec-1");
  assert.equal(api.signedIn, true);

  const pending = await api.pendingJoinRequests();
  assert.equal(pending.length, 1);
  await api.answerJoinRequest("jr-2", true);
  assert.equal(fetch.calls[5].url, "https://self.example.com/api/auth/join-requests/jr-2/approve");
});
