# Personal-provider subtitles (CAB-28)

This contract requires the coordinated CAB-28 control-plane release. Provider search, authentication,
download, parsing and activity matching run on the phone. Provider credentials, login tokens,
activity fingerprints, technical analysis and media bytes are never sent to the control plane.

## Source context and selection

Authenticated `POST /api/subtitles/source-context` accepts the catalog identity and optional exact
`sourceId`. The service checks household ownership and returns `sourceId`, `sourceIdentity` (the
source version), title/file name and available catalog/episode/duration metadata for local discovery.

Authenticated `POST /api/subtitles/selected-track` accepts the exact `sourceId` and version `identity`,
parsed `cues`, two-letter `language`, `provider` (`subdl`, `opensubtitles` or `local`), `providerRef`,
`offset`, `scale`, `forced`, `confidence` and `automatic`. Confidence is `Likely Match`,
`Unverified` or `Local Activity Match`; local matching is a heuristic. The service rechecks ownership,
availability and version under a source lock before saving. A changed source must be rediscovered.

Requests reject unknown fields, URLs, credentials, activity/technical/media payloads, invalid cue
text/timing and out-of-range corrections. Limits: 2 MB serialized cues, 20,000 chronological cues,
4,000 characters per cue, 120-second cue duration, times within 0–86,400 seconds, offset within
±600 seconds and scale 0.9–1.1. Source context carries no credentials.

Only the selected parsed track and its provenance/timing enter household storage. Authenticated
`DELETE /api/subtitles/:id` removes a saved track belonging to the phone's household.

## User-attached SRT/VTT files

Video details offers **Attach subtitle file**, independently of personal provider setup. Android's
document picker grants access to one file for this visit; broad storage permission and persistent
access are unnecessary. The phone reads at most 2,000,000 bytes, validates the `.srt`/`.vtt` extension
and content, and shows language, cue count and a text preview before confirmation. UTF-8, BOM-marked
UTF-16 and language-specific legacy encodings use the existing timeline decoder. Archives are refused.
WebVTT styling/positioning metadata is not retained; the shared track contains plain timed text.

Confirmation explicitly uploads parsed cues to the existing selected-track endpoint with `provider`
`local`, `providerRef` equal to the lowercase SHA-256 of the file bytes, `confidence` `Unverified`,
`automatic` false, initial `offset` 0 and `scale` 1. No filename or document URI is uploaded. Provider
references keep their existing numeric-only validation; local references require exactly 64 hex digits.
Existing cue, household, source-version and correction checks apply unchanged. One selected track per
user/source is replaced only after a valid save. Picker cancellation and validation errors preserve it.

After attachment, the existing timing-adjustment/preview screen is available, including when the video
cannot be previewed on this phone. Paired TVs receive the same normalized VTT contract on next playback;
an already playing title needs restarting to load a newly attached track. The existing remove action
and source/account deletion lifecycle apply. No TV code or database migration is needed. The coordinated
backend change must be deployed before releasing the phone feature.

## Compatibility and lifecycle

The TV playback/content and manual correction contracts remain compatible. Existing selected tracks
and preferences survive migration 037; legacy discovery rows are deleted. Retired backend discovery
endpoints return `410 phone_upgrade_required`. There is no shared-provider-key fallback.

Personal provider credentials are stored only in the phone's account-scoped Keystore-encrypted,
backup-excluded storage. Replace/remove cancels local discovery; sign-out/account deletion clears
credentials. Removing a key preserves saved household tracks. Source/account removal deletes those
tracks. Provider permissions and live device acceptance remain release gates.
