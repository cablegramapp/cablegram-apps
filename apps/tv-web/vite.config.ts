import legacy from "@vitejs/plugin-legacy";
import { defineConfig } from "vite";

// Old TV browsers (Tizen 5 ~ Chromium 69, webOS 4 ~ Chromium 53) lack <script type="module">, so the build ships a
// legacy (nomodule, polyfilled) bundle and relative asset paths.
export default defineConfig({
  base: "./",
  plugins: [legacy({ targets: ["chrome >= 53"], modernTargets: ["chrome >= 80"] })],
  build: { outDir: "dist", assetsInlineLimit: 0 },
  test: { environment: "node" },
});
