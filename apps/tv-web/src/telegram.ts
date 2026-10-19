// Direct Telegram playback for TVs that cannot run TDLib: a GramJS client reads the video's bytes over MTProto and the
// tv-media engine plays them. Loaded lazily (import("./telegram")) and only when the app was built with VITE_TG_DIRECT=1.
// Credentials: the application's api_id/api_hash come from the control plane at run time (GET /api/telegram/client-config,
// allowed per TV), never from this repository. The account session is a GramJS StringSession; for now a developer
// provides it in localStorage["cablegram.tv.tgSession"] (a TV sign-in screen is a later milestone). Never log it.
import "./telegram-env";
import { SourceError, TelegramRpcError, TelegramSource } from "tv-media";
import type { TelegramFileApi } from "tv-media";
import bigInt from "big-integer";
import { Api, TelegramClient, errors } from "telegram";
import { StringSession } from "telegram/sessions";
import { peerOf } from "./telegram-identity";
import type { TelegramRef } from "./telegram-identity";

export const SESSION_KEY = "cablegram.tv.tgSession";

/** Progress markers for debugging on a TV or in the live check (never ids, names or credentials). */
const note = (step: string) => console.info(`[tg] ${step}`);

/** A step that never answers must end in an error the TV can show, not an endless "Starting playback". */
function within<T>(step: string, ms: number, work: Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new SourceError("telegram_error", `Telegram did not answer (${step})`, undefined, `timeout_${step}`)), ms);
    work.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); reject(e); });
  });
}

export class TelegramUnavailable extends Error {
  constructor(readonly reason: "no_session" | "no_video" | "not_found") {
    super(reason);
  }
}

const WEB_DC_HOSTS: Record<number, string> = {
  1: "pluto.web.telegram.org",
  2: "venus.web.telegram.org",
  3: "aurora.web.telegram.org",
  4: "vesta.web.telegram.org",
  5: "flora.web.telegram.org",
};

let shared: { client: TelegramClient; key: string } | null = null;

async function connect(config: { apiId: number; apiHash: string }, session: string): Promise<TelegramClient> {
  const key = `${config.apiId}:${session.length}`;
  if (shared && shared.key === key) return shared.client;
  const stored = new StringSession(session);
  // A session string made on a computer (TCP) remembers the data centre's IP address, but a browser can only reach
  // Telegram through its WebSocket hosts: wss://<name>.web.telegram.org. Same auth key, different address.
  const webHost = WEB_DC_HOSTS[stored.dcId];
  if (webHost) stored.setDC(stored.dcId, webHost, 443);
  const client = new TelegramClient(stored, config.apiId, config.apiHash, {
    connectionRetries: 3,
    useWSS: true,
    floodSleepThreshold: 0, // TelegramSource decides how long to wait
    deviceModel: "Smart TV",
    systemVersion: "1.0",
    appVersion: "TV Web",
  });
  note("connecting");
  await within("connect", 30_000, client.connect());
  note("connected");
  shared = { client, key };
  return client;
}

/** Turns a GramJS failure into the RPC message TelegramSource understands. */
export function rpcMessage(e: unknown): string | null {
  const err = e as { errorMessage?: string; seconds?: number; newDc?: number };
  if (e instanceof errors.FloodWaitError) return `FLOOD_WAIT_${err.seconds ?? 5}`;
  if (e instanceof errors.FileMigrateError) return `FILE_MIGRATE_${err.newDc}`;
  return typeof err.errorMessage === "string" ? err.errorMessage : null;
}

export async function openTelegramSource(
  ref: TelegramRef,
  config: { apiId: number; apiHash: string },
  session: string | null,
): Promise<TelegramSource> {
  if (!session) throw new TelegramUnavailable("no_session");
  const client = await connect(config, session);

  const peer = peerOf(ref.chatId);
  const resolve = async () => {
    const id = peer.kind === "channel" ? Number(`-100${peer.id}`) : Number(ref.chatId);
    // A fresh session does not know the chat's access hash. Reading the dialog list finds it (and fills GramJS's cache);
    // stop at the chat instead of loading every dialog. getInputEntity alone can wait on the network for a long time.
    note("looking for the chat in the dialog list");
    const found = within("dialogs", 60_000, client.getDialogs({ limit: 300 }).then((dialogs) => {
      const hit = dialogs.find((d) => d.id !== undefined && d.id.toString() === ref.chatId);
      return hit ? hit.inputEntity : null;
    }));
    const entity = await found;
    if (entity) return entity;
    note("chat not in the dialog list, asking Telegram directly");
    return within("find_chat", 20_000, client.getInputEntity(id));
  };
  const inputPeer = await resolve();
  note("chat found");

  const fetchDocument = async (): Promise<Api.Document> => {
    const [message] = await within("message", 30_000, client.getMessages(inputPeer, { ids: [ref.messageId] }));
    if (!message) throw new TelegramUnavailable("not_found");
    const media = message.media;
    if (!(media instanceof Api.MessageMediaDocument) || !(media.document instanceof Api.Document)) throw new TelegramUnavailable("no_video");
    return media.document;
  };

  let doc = await fetchDocument();
  note(`video message found (${Math.round(doc.size.toJSNumber() / 1048576)} MB, ${doc.mimeType})`);
  const location = () =>
    new Api.InputDocumentFileLocation({ id: doc.id, accessHash: doc.accessHash, fileReference: doc.fileReference, thumbSize: "" });
  let dc: number | null = null;

  const api: TelegramFileApi = {
    async getFile(offset, limit) {
      const request = new Api.upload.GetFile({ location: location(), offset: bigInt(offset), limit, precise: false, cdnSupported: false });
      try {
        const result = await within("download", 45_000, dc === null ? client.invoke(request) : client.getSender(dc).then((sender) => sender.send(request)));
        if (!(result instanceof Api.upload.File)) throw new TelegramRpcError("CDN_REDIRECT_UNSUPPORTED");
        const b = result.bytes;
        if (offset === 0 || offset % (16 * 1048576) === 0) note(`downloaded a chunk at ${Math.round(offset / 1048576)} MB`);
        return new Uint8Array(b.buffer, b.byteOffset, b.length);
      } catch (e) {
        const rpc = rpcMessage(e);
        if (rpc) throw new TelegramRpcError(rpc);
        throw e;
      }
    },
    async refreshReference() {
      doc = await fetchDocument();
    },
    async migrate(newDc) {
      dc = newDc;
    },
  };
  return new TelegramSource(api, doc.size.toJSNumber());
}
