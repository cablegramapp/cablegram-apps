// Cablegram web remote: follows one command from "the server accepted it" to what the TV did with it.
// HTTP 201 from POST /api/control/commands only means accepted; the TV's receipt shows up in
// GET /api/control/commands/:id as `completed` or `rejected` (docs/remote-command-delivery.md, "Confirmed commands").

import { ApiError } from "./api.js";

/** The four things the page can say about a command. Only `confirmed` is ever shown as success. */
export const OUTCOME = { sent: "sent", confirmed: "confirmed", rejected: "rejected", unconfirmed: "unconfirmed" };

const POLL_MS = 700;
/** The server expires a command itself, but a TV may still answer a moment later. */
const GRACE_MS = 2_000;
const MAX_STATUS_FAILURES = 3;

const wait = (ms, signal) => new Promise((resolve) => {
  const timer = setTimeout(resolve, ms);
  signal.addEventListener("abort", () => { clearTimeout(timer); resolve(); }, { once: true });
});

/**
 * Sends `body` to its target TV and reports each step through `onChange({ outcome, reason? })`.
 * Polling stops at a terminal outcome, at expiry, and on `cancel()` (teardown or a different TV).
 * Nothing is ever re-sent here: retrying is the caller's explicit decision.
 *
 * @param {{ command: (body: object) => Promise<any>, commandStatus: (id: string) => Promise<any> }} api
 * @param {object} body from REMOTE.*, with `target_device_id`
 * @param {(update: { outcome: string, reason?: string, error?: Error }) => void} onChange
 * @param {{ id?: string, pollMs?: number, graceMs?: number, newId?: () => string }} [options]
 *   `id` reuses a command id so that re-sending after a lost response cannot run the command twice.
 * @returns {{ id: string, accepted: Promise<boolean>, cancel: () => void }} `accepted` settles when the POST does
 */
export function trackCommand(api, body, onChange, { id, pollMs = POLL_MS, graceMs = GRACE_MS, newId = () => crypto.randomUUID() } = {}) {
  const commandId = id || newId();
  const control = new AbortController();
  const { signal } = control;
  const report = (update) => { if (!signal.aborted) onChange(update); };
  const finish = (update) => { report(update); control.abort(); };

  let accept;
  const accepted = new Promise((resolve) => { accept = resolve; });

  async function run() {
    try {
      await api.command({ ...body, id: commandId });
    } catch (error) {
      // A rejected request never reached a TV. No answer at all might have: the caller may re-send with the same id.
      finish(error instanceof ApiError
        ? { outcome: OUTCOME.rejected, reason: error.code || `http_${error.status}`, error }
        : { outcome: OUTCOME.unconfirmed, reason: "network", error });
      accept(false);
      return;
    }
    accept(true);
    report({ outcome: OUTCOME.sent });

    let deadline = null;
    let failures = 0;
    while (!signal.aborted) {
      await wait(pollMs, signal);
      if (signal.aborted) break;
      let status;
      try {
        status = await api.commandStatus(commandId);
        failures = 0;
      } catch (error) {
        // An older server has no status route: nothing can be confirmed. Anything else gets a few tries.
        const missing = error instanceof ApiError && error.status === 404;
        if (missing || error instanceof ApiError && error.status === 401 || ++failures >= MAX_STATUS_FAILURES) {
          finish({ outcome: OUTCOME.unconfirmed, reason: missing ? "unsupported" : "status_unavailable", error });
          break;
        }
        continue;
      }
      if (signal.aborted) break;
      if (status.target_device_id && status.target_device_id !== body.target_device_id) {
        finish({ outcome: OUTCOME.unconfirmed, reason: "other_device" });
        break;
      }
      if (status.status === "completed") { finish({ outcome: OUTCOME.confirmed }); break; }
      if (status.status === "rejected") {
        // The server's own expiry means the TV never answered; a TV's rejection is its answer.
        finish(status.reason === "expired" ? { outcome: OUTCOME.unconfirmed, reason: "expired" } : { outcome: OUTCOME.rejected, reason: status.reason || "rejected" });
        break;
      }
      // `deadline` follows the server's own countdown, so a skewed phone clock cannot cut the wait short or stretch it.
      deadline ??= Date.now() + (Number(status.expires_in_ms) || 0) + graceMs;
      if (Date.now() >= deadline) { finish({ outcome: OUTCOME.unconfirmed, reason: "timeout" }); break; }
    }
  }

  void run();
  return { id: commandId, accepted, cancel: () => control.abort() };
}
