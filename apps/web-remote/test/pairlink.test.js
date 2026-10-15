import { test } from "node:test";
import assert from "node:assert/strict";
import { capturePairLink, clearPendingPair, pendingPair, readPairLink } from "../src/pairlink.js";

function memoryStorage() {
  const data = new Map();
  return { getItem: (k) => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: (k) => data.delete(k) };
}

test("a TV's pairing link gives the PIN and the TV's name", () => {
  assert.deepEqual(readPairLink("?pair=042891&tv=Living+room+TV"), { pin: "042891", tv: "Living room TV" });
  assert.deepEqual(readPairLink("?pair=042891"), { pin: "042891", tv: "your TV" });
  assert.equal(readPairLink("?pair=4289"), null);
  assert.equal(readPairLink("?pair=abcdef&tv=x"), null);
  assert.equal(readPairLink(""), null);
});

test("the link is kept for after sign-in and removed from the address, other parameters stay", () => {
  const storage = memoryStorage();
  const cleaned = capturePairLink({ search: "?pair=123456&tv=Den&x=1", pathname: "/", hash: "#library" }, storage);
  assert.equal(cleaned, "/?x=1#library");
  assert.deepEqual(pendingPair(storage), { pin: "123456", tv: "Den" });
  clearPendingPair(storage);
  assert.equal(pendingPair(storage), null);
});

test("no pairing link: nothing is kept and the address is left alone", () => {
  const storage = memoryStorage();
  assert.equal(capturePairLink({ search: "?x=1", pathname: "/", hash: "" }, storage), null);
  assert.equal(pendingPair(storage), null);
});

test("when the browser refuses storage, the link lasts as long as the page", () => {
  const refusing = { getItem() { throw new Error("denied"); }, setItem() { throw new Error("denied"); }, removeItem() { throw new Error("denied"); } };
  capturePairLink({ search: "?pair=654321&tv=Hotel", pathname: "/", hash: "" }, refusing);
  assert.deepEqual(pendingPair(refusing), { pin: "654321", tv: "Hotel" });
  clearPendingPair(refusing);
  assert.equal(pendingPair(refusing), null);
});
