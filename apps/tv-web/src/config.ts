/**
 * Where the Cablegram control plane lives; change it when you host your own. `VITE_API_BASE` overrides it at build
 * time (the dev proxy in scripts/dev-proxy.mjs uses this).
 */
export const API_BASE: string = (import.meta.env?.VITE_API_BASE as string | undefined) || "https://api.cablegram.app";
/** The page the pairing QR opens (the web remote); the PIN also works in the phone app. */
export const WEB_APP_BASE = "https://app.cablegram.app";
/** Built with VITE_TG_DIRECT=1: Telegram titles play over MTProto from this TV (needs a Telegram session, see src/telegram.ts). */
export const TG_DIRECT: boolean = import.meta.env?.VITE_TG_DIRECT === "1";
/** Sentry DSN for Smart TV crash/error reporting (empty when disabled). */
export const SENTRY_DSN: string = (import.meta.env?.VITE_SENTRY_DSN as string | undefined) || "";
