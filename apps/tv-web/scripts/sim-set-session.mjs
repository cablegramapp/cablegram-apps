// Puts the Telegram session string from $TG_SESSION into the Tizen simulator's app storage (the dev setup for direct
// Telegram playback). The string never leaves your shell and is not printed. Needs the simulator on CDP port 9333.
import { chromium } from "playwright-core";

const session = process.env.TG_SESSION;
if (!session) { console.error("set TG_SESSION first (read -s TG_SESSION; export TG_SESSION)"); process.exit(2); }
const browser = await chromium.connectOverCDP(process.env.CDP ?? "http://localhost:9333");
const page = browser.contexts()[0].pages().find((p) => p.url().includes("ripple.html"));
const app = page?.frames().find((f) => f.url().includes("CblGramTV1"));
if (!app) { console.error("the Cablegram app is not open in the simulator"); process.exit(1); }
await app.evaluate((s) => localStorage.setItem("cablegram.tv.tgSession", s), session);
console.log(`session stored in the simulator app (${session.length} characters)`);
await browser.close();
