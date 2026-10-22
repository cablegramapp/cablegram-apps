#!/usr/bin/env python3
"""Regenerate Cablegram/Theme/MaestroIds.swift from the Android MaestroIds.kt (same strings on both platforms)."""
import re
from pathlib import Path

here = Path(__file__).resolve().parents[1]
src = (here.parent / "android-phone/src/main/java/app/cablegram/phone/MaestroIds.kt").read_text()
lines = ["// Generated from MaestroIds.kt (Android) by scripts/gen-maestro-ids.py: keep the strings identical so the",
         "// Maestro flows run on both platforms. Do not edit by hand.", "enum MaestroIds {"]
for name, value in re.findall(r'const val ([A-Z0-9_]+) = "([^"]*)"', src):
    head, *rest = name.lower().split("_")
    lines.append(f'    static let {head}{"".join(p.capitalize() for p in rest)} = "{value}"')
lines.append("}")
(here / "Cablegram/Theme/MaestroIds.swift").write_text("\n".join(lines) + "\n")
