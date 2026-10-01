// Assembles dist/: the files the web remote serves, plus version.txt and SHA256SUMS so anyone can compare what
// is live with this repository (see VERIFY.md). No dependencies; run `npm run build`.
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { cpSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");
const SERVED = ["index.html", "styles.css", "config.js", "sw.js", "manifest.webmanifest", "_headers", "src", "icons"];

const git = (...args) => { try { return execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim(); } catch { return "unknown"; } };
const ref = process.env.GITHUB_REF_NAME || "local";
const sha = process.env.GITHUB_SHA || git("rev-parse", "HEAD");

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });
for (const name of SERVED) cpSync(join(root, name), join(dist, name), { recursive: true });
writeFileSync(join(dist, "version.txt"), `${ref}\n${sha}\n`);

const files = [];
(function walk(dir) {
  for (const entry of readdirSync(dir).sort()) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path);
    else files.push(path);
  }
})(dist);
const sums = files.map((f) => `${createHash("sha256").update(readFileSync(f)).digest("hex")}  ./${relative(dist, f)}`);
writeFileSync(join(dist, "SHA256SUMS"), sums.join("\n") + "\n");
console.log(`dist/: ${files.length} files, ${ref} ${sha.slice(0, 7)}`);
