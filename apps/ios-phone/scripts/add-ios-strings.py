#!/usr/bin/env python3
"""Adds (or replaces) iPhone-only strings in Cablegram/Resources/Strings-ios.xcstrings.

Usage: scripts/add-ios-strings.py strings.json
where strings.json is {"ios_key": {"en": "...", "de": "...", "es": "...", "it": "...", "ru": "...", "ar": "...", "fa": "..."}}
Keys stay sorted so diffs are small."""
import json, sys, pathlib

LANGS = ["ar", "de", "en", "es", "fa", "it", "ru"]
path = pathlib.Path(__file__).resolve().parent.parent / "Cablegram/Resources/Strings-ios.xcstrings"
catalog = json.loads(path.read_text())
for key, values in json.loads(pathlib.Path(sys.argv[1]).read_text()).items():
    missing = [l for l in LANGS if l not in values]
    if missing:
        sys.exit(f"{key}: missing {missing}")
    catalog["strings"][key] = {
        "extractionState": "manual",
        "localizations": {l: {"stringUnit": {"state": "translated", "value": values[l]}} for l in LANGS},
    }
catalog["strings"] = dict(sorted(catalog["strings"].items()))
path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
print("ok", len(catalog["strings"]))
