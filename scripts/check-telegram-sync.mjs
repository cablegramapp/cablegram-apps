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
// The iPhone app keeps its own copy of the allowed Telegram surface (Swift cannot reflect over a protocol): it must name exactly the
// members of the Android allowlist, with Kotlin's `getX` property accessors written as `x`.
function allowlist(file, pattern) {
  const text = readFileSync(join(root, file), "utf8");
  const block = text.match(pattern);
  if (!block) { problems.push(`${file}: allowlist not found`); return new Set(); }
  return new Set([...block[1].matchAll(/"([A-Za-z]+)"/g)].map((m) => m[1]));
}
const lower = (name) => name.replace(/^get([A-Z])/, (_, c) => c.toLowerCase());
const android = new Set([...allowlist("apps/android-phone/src/test/java/app/cablegram/telegram/TelegramApiSurfaceTest.kt", /setOf\(([\s\S]*?)\n    \)/)].map(lower));
const ios = allowlist("apps/ios-phone/Packages/CablegramCore/Tests/CablegramCoreTests/Telegram/TelegramApiSurfaceTests.swift", /Set<String> = \[([\s\S]*?)\n    \]/);
for (const name of android) if (!ios.has(name)) problems.push(`iOS Telegram surface is missing ${name}`);
for (const name of ios) if (!android.has(name)) problems.push(`iOS Telegram surface has ${name}, which Android does not allow`);

if (problems.length) {
  console.error("Telegram package out of sync:\n  " + problems.join("\n  "));
  process.exit(1);
}
console.log(`Telegram package in sync (${pairs.length} directories, iOS surface matches Android: ${android.size} members).`);
