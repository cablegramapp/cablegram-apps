// Lists recent video messages in the signed-in account's chats with the TG_IDENTITY to use for scripts/e2e-telegram.mjs.
// Prints chat names, file names and sizes, never the session. Needs TG_SESSION and the api id/hash (see tg-session.mjs).
import { Api, TelegramClient } from "telegram";
import { StringSession } from "telegram/sessions/index.js";

const apiId = Number(process.env.TG_API_ID || process.env.TELEGRAM_API_ID);
const apiHash = process.env.TG_API_HASH || process.env.TELEGRAM_API_HASH;
if (!apiId || !apiHash || !process.env.TG_SESSION) { console.error("set TG_SESSION and the api id/hash"); process.exit(2); }
const client = new TelegramClient(new StringSession(process.env.TG_SESSION), apiId, apiHash, { connectionRetries: 3, deviceModel: "Smart TV", systemVersion: "1.0", appVersion: "TV Web" });
await client.connect();
const me = await client.getMe();
const dialogs = await client.getDialogs({ limit: 60 });
// Saved Messages first, then chats named like the Cablegram library channel, then everything else.
const places = [{ entity: "me", id: me.id.toString(), title: "Saved Messages" }]
  .concat(dialogs.filter((d) => /cablegram/i.test(d.title ?? "")).map((d) => ({ entity: d.entity, id: d.id.toString(), title: d.title })))
  .concat(dialogs.filter((d) => !/cablegram/i.test(d.title ?? "")).map((d) => ({ entity: d.entity, id: d.id.toString(), title: d.title })));
let shown = 0;
for (const place of places) {
  if (shown >= 25) break;
  let msgs = [];
  try { msgs = await client.getMessages(place.entity, { limit: 5, filter: new Api.InputMessagesFilterVideo() }); } catch { continue; }
  for (const m of msgs) {
    const doc = m.media?.document;
    if (!doc) continue;
    const name = doc.attributes?.find((a) => a.fileName)?.fileName ?? "(no file name)";
    console.log(`tg:${place.id}:${m.id}   ${(Number(doc.size.toString()) / 1048576).toFixed(0)} MB   ${doc.mimeType}   ${name}   [${place.title}]`);
    shown++;
  }
}
await client.disconnect();
process.exit(0);
