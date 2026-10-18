# App languages

The Android phone and TV apps bundle English, German (`de`), Arabic (`ar`), Farsi
(`fa`), Russian (`ru`), Spanish (`es`) and Italian (`it`). Strings, dialogs, setup,
player controls, accessibility labels and notifications use Android resources.
Arabic and Farsi use RTL layout. Numeric keypads, pairing codes and transport
controls retain their physical left/right ordering.

## Phone and profiles

The phone uses the first supported language in its Android language preferences.
Unsupported languages fall back to English, including English layout direction.
Android 13+ also exposes these languages in the system's per-app language settings.
No translation service is called at runtime.

After sign-in and on foreground refresh, the phone reports its language to
`PUT /api/me/language`: the full tag of the device preference it shows (for example
`de-AT`), or the first preference when no bundled translation matches, so TVs that
ship more languages than the phone can still use it. Failed updates retry on the
next refresh. The account
owner's language is inherited by profiles without an override. In **Settings →
Profiles → Language**, choose another language for a profile or **Follow phone
language** to restore inheritance. These preferences do not change subtitle or
audio languages. Media titles, filenames and user-provided names are preserved.

`GET /api/profiles` returns effective `ui_language` and nullable
`ui_language_override`. A phone can change the override through
`PATCH /api/profiles/{profileId}/language`. TV credentials cannot write either
preference, and updates are restricted to the authenticated account/household.

## TV behavior

The TV uses the selected profile's language, independently of the TV's own device
language. Before profile selection, all available profiles must agree on a
supported language to use it; otherwise the interface stays in English. This
includes profiles from different accounts paired to one TV. PIN entry and phone
approval keep that preselection language until a profile opens. Initial pairing
without known profiles uses English.

While in the foreground, the TV refreshes known profile languages from the
`profile_languages` list in its session check (`GET /api/me`, every 30 seconds),
without an extra request, preserving playback and profile selection.
Returning to the picker restores the preselection language. Regional variants,
such as `de-DE` and `de-AT`, share the same bundled base translation.

Existing accounts and older servers that omit the language field default to
English. Deploy control-plane migration `046_ui_language` and the language routes
before distributing these app builds for cross-device synchronization.

## Maintaining translations

English source strings live in each app's `res/values/strings.xml` and
`ui_strings.xml`; translations live in `values-de`, `values-ar`, `values-fa`,
`values-ru`, `values-es` and `values-it`. Phone episode/season counts use Android
plurals, including Arabic and Russian quantity categories. Keep positional format
arguments intact. `values/languages.xml` and `xml/locales_config.xml` must agree in
both apps.

The initial longer-message drafts were generated locally with Argos translation
models; common controls were edited manually. The phone catalogs subsequently
received a full source-to-translation review in Farsi, Arabic, German, Spanish,
Italian and Russian, correcting misleading playback, sign-in, storage, subtitle,
permission and privacy wording. This review does not replace native-speaker
release review, and the TV catalogs have not received the same wording pass.
No model assets or translation
runtime dependency ship in the apps. See the [Argos documentation](https://argos-translate.readthedocs.io/en/latest/source/examples.html)
for the offline generation approach.

Run `python3 scripts/localization/check.py` from the apps repository to verify
language coverage, XML, format arguments, required plural forms and untranslated
leftovers. A translation identical to English fails unless its key and language are
listed in `SAME_AS_ENGLISH` (names and words the language shares).

Messages exist once, in the resource files. Functions that build user-facing text take
a required `Resources`; unit tests that check those messages run under Robolectric
and read the bundled English strings. Diagnostic logs stay in English. Android unit
tests cover language selection, regional fallback, profile mixing and API calls;
control-plane contract tests cover inheritance, overrides, invalid tags and account
isolation. Build and lint both apps after changing translations.

## Physical-device acceptance

Verify the sign-in and profile language picker in every language; Arabic/Farsi
text and number fields; a mixed-language TV's English picker and unlock/approval
screens; selection/switching back; and language changes during actual playback.
Maestro currently reports no connected Android phone or TV, so physical layout,
remote-focus behavior and end-to-end propagation have not been verified for this
change. Automated checks do not establish physical-device coverage.
