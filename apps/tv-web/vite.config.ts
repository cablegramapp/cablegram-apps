import legacy from "@vitejs/plugin-legacy";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";

// Old TV browsers (Tizen 5 ~ Chromium 69, webOS 4 ~ Chromium 53) lack <script type="module">, so the build ships a
// legacy (nomodule, polyfilled) bundle and relative asset paths.
export default defineConfig(({ mode }) => ({
  base: "./",
  // plugin-legacy's inline module detector checks up to Chrome 64 features (import.meta.url, dynamic import, async
  // generator), so modernTargets must be chrome >= 64 (otherwise webOS 5 / Chromium 68 and webOS 6 / Chromium 79 pass
  // the detector and then fail with SyntaxError on Chrome 80's `??` / `?.`).
  plugins: [legacy({ targets: ["chrome >= 53"], modernTargets: ["chrome >= 64"] })],
  // GramJS (loaded only in VITE_TG_DIRECT builds) refers to Node's `global`.
  // An explicit constant, so builds without VITE_TG_DIRECT=1 drop the Telegram chunk instead of shipping it unused.
  define: {
    global: "window",
    "import.meta.env.VITE_TG_DIRECT": JSON.stringify(loadEnv(mode, process.cwd(), "VITE_").VITE_TG_DIRECT ?? ""),
    "import.meta.env.VITE_SENTRY_DSN": JSON.stringify(loadEnv(mode, process.cwd(), "VITE_").VITE_SENTRY_DSN ?? ""),
  },
  resolve: {
    alias: [
      ...["util", "events"].map((name) => ({ find: new RegExp(`^${name}$`), replacement: fileURLToPath(new URL(`./src/shims/${name}.ts`, import.meta.url)) })),
      // GramJS ships its own pure-JS crypto (AES, plus WebCrypto for SHA and PBKDF2) for browsers; Node's `crypto` is not there.
      { find: /^crypto$/, replacement: fileURLToPath(new URL("./node_modules/telegram/crypto/crypto.js", import.meta.url)) },
    ],
  },
  build: { outDir: "dist", assetsInlineLimit: 0 },
  test: { environment: "node" },
}));
