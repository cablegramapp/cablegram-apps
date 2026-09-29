#!/usr/bin/env node
// The Telegram package is copied into both apps until a shared Gradle module exists.
// Fails when the phone and TV copies differ, so a fix never lands in only one app.
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "../");
const pairs = [
  ["apps/android-phone/src/main/java/app/cablegram/telegram", "apps/android-tv/src/main/java/app/cablegram/telegram"],
  ["apps/android-phone/src/test/java/app/cablegram/telegram", "apps/android-tv/src/test/java/app/cablegram/telegram"],
];
const problems = [];
for (const [a, b] of pairs) {
  const names = new Set([...readdirSync(join(root, a)), ...(existsSync(join(root, b)) ? readdirSync(join(root, b)) : [])]);
  for (const name of names) {
    const pa = join(root, a, name), pb = join(root, b, name);
    if (!existsSync(pa) || !existsSync(pb)) problems.push(`${name}: only in ${existsSync(pa) ? a : b}`);
    else if (readFileSync(pa, "utf8") !== readFileSync(pb, "utf8")) problems.push(`${name}: differs between ${a} and ${b}`);
  }
}
if (problems.length) {
  console.error("Telegram package out of sync:\n  " + problems.join("\n  "));
  process.exit(1);
}
console.log(`Telegram package in sync (${pairs.length} directories).`);
