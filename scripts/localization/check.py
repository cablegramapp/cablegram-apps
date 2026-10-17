#!/usr/bin/env python3
"""Validate bundled Android translations without Android tooling or network access."""
from pathlib import Path
from collections import Counter
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
LANGUAGES = {"en", "de", "ar", "fa", "ru", "es", "it"}
PLACEHOLDER = re.compile(r"%(\d+)\$([sd])")
REQUIRED_PLURALS = {
    "en": {"one", "other"}, "de": {"one", "other"},
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "fa": {"one", "other"}, "ru": {"one", "few", "many", "other"},
    "es": {"one", "other"}, "it": {"one", "other"},
}


def resources(directory):
    result = {}
    for path in directory.glob("*.xml"):
        for element in ET.parse(path).getroot():
            if element.tag not in {"string", "plurals"} or element.get("translatable") == "false":
                continue
            key = element.get("name")
            assert key not in result, f"Duplicate resource {key} in {directory}"
            result[key] = element
    return result


def placeholders(element):
    return Counter(PLACEHOLDER.findall("".join(element.itertext())))


for app in ("android-phone", "android-tv"):
    res = ROOT / "apps" / app / "src/main/res"
    declared = {e.text for e in ET.parse(res / "values/languages.xml").getroot().find("string-array")}
    configured = {e.get("{http://schemas.android.com/apk/res/android}name") for e in ET.parse(res / "xml/locales_config.xml").getroot()}
    assert declared == configured == LANGUAGES, f"Supported languages differ in {app}"
    original = resources(res / "values")
    for language in sorted(LANGUAGES):
        directory = res / ("values" if language == "en" else f"values-{language}")
        translated = resources(directory)
        assert original.keys() == translated.keys(), f"Missing/extra resources in {directory}: {original.keys() ^ translated.keys()}"
        for key, element in translated.items():
            source = original[key]
            assert element.tag == source.tag, f"Resource type changed: {directory}/{key}"
            if element.tag == "string":
                assert placeholders(element) == placeholders(source), f"Format arguments changed: {directory}/{key}"
                text = "".join(element.itertext())
                # Screen/list identities and API paths must remain stable across locale changes.
                assert not re.match(r'^"?(?:[a-z][a-z0-9_]*[:/].*%|[a-z][a-z0-9_]*_%\d+\$s)', text), f"Internal identifier in translation catalog: {directory}/{key}"
                assert text.strip('" '), f"Empty translation: {directory}/{key}"
                assert not re.search(r"7370\d\d", text), f"Translation marker: {directory}/{key}"
                if placeholders(element):
                    rest = re.sub(r"%\d+\$[sd]|%%", "", text)
                    assert "%" not in rest, f"Invalid percent escape: {directory}/{key}"
            else:
                forms = {e.get("quantity"): e for e in element}
                assert REQUIRED_PLURALS[language] <= forms.keys(), f"Missing plural forms: {directory}/{key}"
                for quantity, value in forms.items():
                    # Arabic one/two may spell out the number instead of interpolating it.
                    assert placeholders(value) in (Counter(), Counter({("1", "d"): 1})), f"Invalid plural arguments: {directory}/{key}/{quantity}"
    print(f"{app}: {len(original)} resources, all 7 languages complete; format arguments and plural forms valid")
