// CAB-62: a TV's pairing QR code opens this page as /?pair=<PIN>&tv=<name>. The code is kept for this browser tab
// while the person signs in or creates an account, then offered as "Connect <TV>?". It is never sent anywhere but the
// pairing request, and it leaves the address bar at once.

const KEY = "cablegram.pendingPair";
/** Fallback when the browser refuses storage (private mode): the link then lasts as long as the page. */
let memory = null;

/** The PIN and TV name from a pairing link's query string, or null when it isn't one. */
export function readPairLink(search) {
  const params = new URLSearchParams(search);
  const pin = params.get("pair")?.trim() ?? "";
  if (!/^\d{6}$/.test(pin)) return null;
  const tv = (params.get("tv") ?? "").trim().slice(0, 80);
  return { pin, tv: tv || "your TV" };
}

/** Takes a pairing link from the address, keeps it in [storage] and returns the address without it. */
export function capturePairLink(location, storage) {
  const link = readPairLink(location.search);
  if (!link) return null;
  memory = link;
  try { storage.setItem(KEY, JSON.stringify(link)); } catch { /* kept in memory instead */ }
  const params = new URLSearchParams(location.search);
  params.delete("pair");
  params.delete("tv");
  const rest = params.toString();
  return `${location.pathname}${rest ? `?${rest}` : ""}${location.hash}`;
}

export function pendingPair(storage) {
  try {
    const link = JSON.parse(storage.getItem(KEY) || "null");
    if (link && /^\d{6}$/.test(link.pin)) return link;
  } catch { /* fall back to memory */ }
  return memory;
}

export function clearPendingPair(storage) {
  memory = null;
  try { storage.removeItem(KEY); } catch { /* nothing to clear */ }
}
