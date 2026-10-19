/** `tg:<chat_id>:<message_id>` as the catalog stores it, in TDLib's terms: chat_id is a TDLib chat id (channels are
 * -100<channel id>) and message_id is TDLib's, which is the server (MTProto) message id times 2^20. */
export interface TelegramRef { chatId: string; messageId: number }

const TDLIB_SHIFT = 1048576;

export function parseTelegramIdentity(identity: string | undefined): TelegramRef | null {
  const m = /^tg:(-?\d+):(\d+)$/.exec(identity ?? "");
  if (!m) return null;
  const id = Number(m[2]);
  if (!Number.isSafeInteger(id) || id <= 0) return null;
  // TDLib ids are multiples of 2^20; a plain id (a hand-written one) is already the server's.
  return { chatId: m[1], messageId: id % TDLIB_SHIFT === 0 ? id / TDLIB_SHIFT : id };
}

/** MTProto peer ids: TDLib's -100<id> for a channel becomes the plain channel id. */
export function peerOf(chatId: string): { kind: "channel" | "chat" | "user"; id: string } {
  if (chatId.startsWith("-100")) return { kind: "channel", id: chatId.slice(4) };
  if (chatId.startsWith("-")) return { kind: "chat", id: chatId.slice(1) };
  return { kind: "user", id: chatId };
}
