import { test } from "node:test";
import assert from "node:assert/strict";
import { createApi, REMOTE, ApiError } from "../src/api.js";

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

test("login identifies this app as the web client and keeps the tokens", async () => {
  const fetch = fakeFetch([200, { access_token: "a1", refresh_token: "r1", display_name: "Home" }]);
  const storage = memoryStorage();
  const api = createApi({ base: BASE + "/", storage, fetch });
  await api.login("a@b.co", "password1");
  assert.deepEqual(fetch.calls[0].body, { email: "a@b.co", password: "password1", client: "web" });
  assert.equal(fetch.calls[0].url, `${BASE}/api/auth/login`);
  assert.equal(fetch.calls[0].headers.authorization, undefined);
  assert.equal(api.signedIn, true);
  assert.equal(JSON.parse(storage.getItem("cablegram.session")).refresh_token, "r1");
});

test("an expired access token is refreshed once and the request is retried", async () => {
  const fetch = fakeFetch(
    [200, { access_token: "a1", refresh_token: "r1" }],
    [401, { error: "unauthorized" }],
    [200, { access_token: "a2", refresh_token: "r2" }],
    [200, { items: [{ id: "i1" }] }],
  );
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.login("a@b.co", "password1");
  assert.deepEqual(await api.library(), [{ id: "i1" }]);
  assert.equal(fetch.calls[2].url, `${BASE}/api/auth/refresh`);
  assert.deepEqual(fetch.calls[2].body, { refresh_token: "r1" });
  assert.equal(fetch.calls[3].headers.authorization, "Bearer a2");
});

test("a refused refresh signs the user out instead of looping", async () => {
  const fetch = fakeFetch([200, { access_token: "a1", refresh_token: "r1" }], [401, {}], [401, {}]);
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.login("a@b.co", "password1");
  await assert.rejects(api.library(), (e) => e instanceof ApiError && e.status === 401);
  assert.equal(api.signedIn, false);
  assert.equal(fetch.calls.length, 3);
});

test("TV login answers are only approved or denied", async () => {
  const fetch = fakeFetch([200, { access_token: "a", refresh_token: "r" }], [200, {}], [200, {}]);
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.login("a@b.co", "password1");
  await api.answerTvLogin("id-1", true);
  await api.answerTvLogin("id-2", false);
  assert.deepEqual(fetch.calls[1].body, { outcome: "approved" });
  assert.deepEqual(fetch.calls[2].body, { outcome: "denied" });
  assert.equal(fetch.calls[1].url, `${BASE}/api/telegram/tv-logins/id-1/result`);
});

test("tvs() drops revoked TVs and phones", async () => {
  const fetch = fakeFetch(
    [200, { access_token: "a", refresh_token: "r" }],
    [200, { devices: [
      { id: "1", kind: "tv", display_name: "Den", revoked_at: null },
      { id: "2", kind: "tv", display_name: "Old", revoked_at: "2026-01-01" },
      { id: "3", kind: "phone", display_name: "Phone", revoked_at: null },
    ] }],
  );
  const api = createApi({ base: BASE, storage: memoryStorage(), fetch });
  await api.login("a@b.co", "password1");
  assert.deepEqual((await api.tvs()).map((d) => d.id), ["1"]);
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
