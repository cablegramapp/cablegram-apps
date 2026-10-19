// Developer helper: signs a Telegram account in once and prints a GramJS session string for the TV app's dev setup
// (set localStorage["cablegram.tv.tgSession"] in the TV's debugger). Treat the output like a password: it gives full
// access to the account. Never commit it or paste it into logs.
// Usage: TG_API_ID=... TG_API_HASH=... node scripts/tg-session.mjs   (use the application's api_id/api_hash)
import readline from "node:readline/promises";
import { TelegramClient } from "telegram";
import { StringSession } from "telegram/sessions/index.js";

const apiId = Number(process.env.TG_API_ID || process.env.TELEGRAM_API_ID);
const apiHash = process.env.TG_API_HASH || process.env.TELEGRAM_API_HASH;
if (!apiId || !apiHash) { console.error("set TG_API_ID and TG_API_HASH"); process.exit(2); }
const rl = readline.createInterface({ input: process.stdin, output: process.stderr });
const ask = (q) => rl.question(q);
const client = new TelegramClient(new StringSession(""), apiId, apiHash, { connectionRetries: 3, deviceModel: "Smart TV", systemVersion: "1.0", appVersion: "TV Web" });
await client.start({
  phoneNumber: () => ask("Phone number (international format): "),
  phoneCode: () => ask("Code Telegram sent you: "),
  password: () => ask("Two-step password (if any): "),
  onError: (e) => console.error(e.message),
});
console.error("Signed in. Session string (keep it secret):");
console.log(client.session.save());
await client.disconnect();
rl.close();
process.exit(0);
