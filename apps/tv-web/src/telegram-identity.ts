/** `tg:<chat_id>:<message_id>` as the catalog stores it; chat_id is a TDLib chat id (channels are -100<channel id>). */
export interface TelegramRef { chatId: string; messageId: number }

export function parseTelegramIdentity(identity: string | undefined): TelegramRef | null {
  const m = /^tg:(-?\d+):(\d+)$/.exec(identity ?? "");
  if (!m) return null;
  const messageId = Number(m[2]);
  return Number.isSafeInteger(messageId) && messageId > 0 ? { chatId: m[1], messageId } : null;
}

/** MTProto peer ids: TDLib's -100<id> for a channel becomes the plain channel id. */
export function peerOf(chatId: string): { kind: "channel" | "chat" | "user"; id: string } {
  if (chatId.startsWith("-100")) return { kind: "channel", id: chatId.slice(4) };
  if (chatId.startsWith("-")) return { kind: "chat", id: chatId.slice(1) };
  return { kind: "user", id: chatId };
}
