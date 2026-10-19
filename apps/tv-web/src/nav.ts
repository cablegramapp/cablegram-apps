export type Key = "up" | "down" | "left" | "right" | "enter" | "back" | "play" | "pause" | "playpause" | "stop" | "ff" | "rw" | "digit" | "delete";

/** Remote key codes on Samsung Tizen and LG webOS, plus keyboard equivalents for desktop testing. */
const KEY_CODES: Record<number, Key> = {
  38: "up", 40: "down", 37: "left", 39: "right", 13: "enter",
  10009: "back", 461: "back", 27: "back", 8: "back", // Tizen, webOS, keyboard (Backspace deletes a PIN digit)
  46: "delete",
  415: "play", 19: "pause", 10252: "playpause", 179: "playpause", 413: "stop", 417: "ff", 412: "rw",
};

export function keyFromEvent(e: { keyCode: number }): Key | null {
  if (e.keyCode >= 48 && e.keyCode <= 57) return "digit";
  return KEY_CODES[e.keyCode] ?? null;
}

/** Tizen only delivers media keys after they are registered. No-op elsewhere. */
export function registerPlatformKeys() {
  try {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const tv = (window as any).tizen?.tvinputdevice;
    const names = ["MediaPlay", "MediaPause", "MediaPlayPause", "MediaStop", "MediaFastForward", "MediaRewind"];
    for (const name of names.concat("0123456789".split(""))) tv?.registerKey(name);
  } catch { /* not Tizen */ }
}

export interface Rect { left: number; top: number; right: number; bottom: number }

/** The closest rect in [dir] from [from]; overlap on the cross axis beats distance. Pure, so it is testable. */
export function pickNext(from: Rect, candidates: Rect[], dir: "up" | "down" | "left" | "right"): number {
  const cx = (r: Rect) => (r.left + r.right) / 2;
  const cy = (r: Rect) => (r.top + r.bottom) / 2;
  let best = -1;
  let bestScore = Infinity;
  candidates.forEach((c, i) => {
    const dx = cx(c) - cx(from);
    const dy = cy(c) - cy(from);
    const ahead = dir === "up" ? dy < -1 : dir === "down" ? dy > 1 : dir === "left" ? dx < -1 : dx > 1;
    if (!ahead) return;
    const main = dir === "up" || dir === "down" ? Math.abs(dy) : Math.abs(dx);
    const cross = dir === "up" || dir === "down" ? Math.abs(dx) : Math.abs(dy);
    const score = main + cross * 3;
    if (score < bestScore) { bestScore = score; best = i; }
  });
  return best;
}

export function focusables(root: ParentNode = document): HTMLElement[] {
  return (Array.prototype.slice.call(root.querySelectorAll(".focusable")) as HTMLElement[])
    .filter((el) => el.offsetWidth > 0 || el.offsetHeight > 0);
}

/** Focus without the browser's own scroll jump; rows and shelves scroll themselves. */
export function focusEl(el: HTMLElement) {
  try { el.focus({ preventScroll: true }); } catch { el.focus(); }
}

export function focusFirst(root: ParentNode = document) {
  const el = (root.querySelector(".focusable[data-autofocus]") as HTMLElement | null) ?? focusables(root)[0];
  if (el) focusEl(el);
}

export function moveFocus(dir: "up" | "down" | "left" | "right", root: ParentNode = document): boolean {
  let all = focusables(root);
  const current = document.activeElement as HTMLElement | null;
  if (!current || all.indexOf(current) < 0) { focusFirst(root); return true; }
  // Up and Down stay inside a vertical menu (the library's rail), like a Compose Column.
  const group = (dir === "up" || dir === "down") && current.closest ? current.closest("[data-nav-group]") : null;
  if (group) all = all.filter((el) => group.contains(el));
  // An explicit target wins: the first card in a row goes Left to the menu's Home, as on Android TV.
  const explicit = current.getAttribute(`data-${dir}`);
  const forced = explicit ? document.getElementById(explicit) : null;
  if (forced && all.indexOf(forced) >= 0) { focusEl(forced); return true; }
  const rects = all.map((el) => el.getBoundingClientRect());
  const next = pickNext(current.getBoundingClientRect(), rects.filter((_, i) => all[i] !== current), dir);
  if (next < 0) return false;
  const target = all.filter((el) => el !== current)[next];
  focusEl(target);
  return true;
}
