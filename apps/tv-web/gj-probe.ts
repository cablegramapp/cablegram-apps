import "./src/telegram-env";
import { TelegramClient } from "telegram";
import { StringSession } from "telegram/sessions";
(window as any).probe = async () => {
  const c = new TelegramClient(new StringSession(""), 1, "0".repeat(32), { useWSS: true, connectionRetries: 1, floodSleepThreshold: 0 });
  try { await c.connect(); return "connected"; } catch (e: any) { return "error: " + (e.errorMessage ?? e.message); }
};
