import { createReadStream, existsSync, statSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { homedir } from "node:os";
import { join } from "node:path";
import { build } from "esbuild";
import { chromium, type Browser, type Page } from "playwright-core";
import { FIXTURES } from "../helpers";

const CHROME =
  process.env.CHROME_PATH ??
  join(homedir(), "Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing");

export const chromeAvailable = existsSync(CHROME);

export interface Harness {
  page: Page;
  base: string;
  /** Range requests the static server saw, as "start-end" strings, with the number in flight at the time. */
  requests: Array<{ url: string; range: string; inFlight: number }>;
  close(): Promise<void>;
}

export async function startHarness(): Promise<Harness> {
  const bundle = await build({
    entryPoints: [join(__dirname, "page.ts")],
    bundle: true,
    write: false,
    format: "iife",
    globalName: "tvMedia",
    target: "es2017",
  });
  const js = bundle.outputFiles[0].text;

  const requests: Harness["requests"] = [];
  let inFlight = 0;
  const server: Server = createServer((req, res) => {
    const url = (req.url ?? "/").split("?")[0];
    res.setHeader("Access-Control-Allow-Origin", "*");
    if (url === "/") {
      res.setHeader("Content-Type", "text/html");
      res.end('<!doctype html><video id="v" muted playsinline width="320" height="180"></video><script src="/bundle.js"></script>');
      return;
    }
    if (url === "/bundle.js") {
      res.setHeader("Content-Type", "text/javascript");
      res.end(js);
      return;
    }
    const file = join(FIXTURES, decodeURIComponent(url.replace(/^\/fixtures\//, "")));
    if (!url.startsWith("/fixtures/") || !existsSync(file)) {
      res.statusCode = 404;
      res.end();
      return;
    }
    const size = statSync(file).size;
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? "");
    inFlight++;
    res.on("close", () => inFlight--);
    requests.push({ url, range: m ? `${m[1]}-${m[2]}` : "", inFlight });
    res.setHeader("Content-Type", url.endsWith(".mkv") ? "video/x-matroska" : "video/mp4");
    res.setHeader("Accept-Ranges", "bytes");
    if (!m) {
      res.setHeader("Content-Length", size);
      createReadStream(file).pipe(res);
      return;
    }
    const start = Number(m[1]);
    const end = Math.min(m[2] ? Number(m[2]) : size - 1, size - 1);
    if (start >= size) {
      res.statusCode = 416;
      res.end();
      return;
    }
    res.statusCode = 206;
    res.setHeader("Content-Range", `bytes ${start}-${end}/${size}`);
    res.setHeader("Content-Length", end - start + 1);
    createReadStream(file, { start, end }).pipe(res);
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

  const browser: Browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ["--autoplay-policy=no-user-gesture-required"] });
  const page = await browser.newPage();
  page.on("pageerror", (e) => console.error("[page error]", e.message));
  page.on("console", (m) => {
    if (m.type() === "error") console.error("[page console]", m.text());
  });
  await page.goto(base + "/");
  return {
    page,
    base,
    requests,
    async close() {
      await browser.close();
      await new Promise((r) => server.close(r));
    },
  };
}
