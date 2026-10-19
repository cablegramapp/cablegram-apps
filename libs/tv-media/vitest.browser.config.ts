import { defineConfig } from "vitest/config";

// Needs Chrome for Testing (CHROME_PATH or the Playwright cache) and the generated fixtures.
export default defineConfig({ test: { environment: "node", include: ["test/browser/*.spec.ts"], testTimeout: 60000, hookTimeout: 60000 } });
