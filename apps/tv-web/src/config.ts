/**
 * Where the Cablegram control plane lives; change it when you host your own. `VITE_API_BASE` overrides it at build
 * time (the dev proxy in scripts/dev-proxy.mjs uses this).
 */
export const API_BASE: string = (import.meta.env?.VITE_API_BASE as string | undefined) || "https://api.cablegram.app";
/** The page the pairing QR opens (the web remote); the PIN also works in the phone app. */
export const WEB_APP_BASE = "https://app.cablegram.app";
