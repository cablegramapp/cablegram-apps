#!/usr/bin/env python3
"""Generate the iPhone app's Localizable.xcstrings from the Android string resources (the single source).

Usage: android-to-xcstrings.py [--check]   (--check fails when the committed catalog is stale)
Android key names are kept as the String Catalog keys; %1$s becomes %1$@, %d stays.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "apps/android-phone/src/main/res"
OUT = ROOT / "apps/ios-phone/Cablegram/Resources/Localizable.xcstrings"
LANGUAGES = ["en", "de", "ar", "fa", "ru", "es", "it"]
ESCAPES = {"n": "\n", "t": "\t", "'": "'", '"': '"', "\\": "\\", "@": "@", "?": "?"}


def unescape(raw: str) -> str:
    text = raw or ""
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    out, i = [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            n = text[i + 1]
            if n == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", text[i + 2:i + 6]):
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(ESCAPES.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def to_apple(text: str) -> str:
    # Positional Android specifiers: %1$s -> %1$@. Bare %s -> %@. %d / %1$d are kept.
    text = re.sub(r"%(\d+)\$s", r"%\1$@", text)
    return re.sub(r"%s", "%@", text)


def unit(text: str) -> dict:
    return {"stringUnit": {"state": "translated", "value": to_apple(unescape(text))}}


def load(lang: str) -> dict:
    directory = RES / ("values" if lang == "en" else f"values-{lang}")
    result = {}
    for path in sorted(directory.glob("*.xml")):
        for el in ET.parse(path).getroot():
            if el.tag not in {"string", "plurals"} or el.get("translatable") == "false":
                continue
            key = el.get("name")
            if el.tag == "string":
                result[key] = unit(el.text or "")
            else:
                forms = {i.get("quantity"): unit(i.text or "") for i in el.findall("item")}
                result[key] = {"variations": {"plural": forms}}
    return result


def build() -> str:
    per_lang = {lang: load(lang) for lang in LANGUAGES}
    strings = {}
    for key in sorted(per_lang["en"]):
        loc = {lang: per_lang[lang][key] for lang in LANGUAGES if key in per_lang[lang]}
        strings[key] = {"extractionState": "manual", "localizations": loc}
    catalog = {"sourceLanguage": "en", "strings": strings, "version": "1.0"}
    return json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    generated = build()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != generated:
            print(f"{OUT.relative_to(ROOT)} is stale: run `make strings` in apps/ios-phone", file=sys.stderr)
            return 1
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(generated)
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
