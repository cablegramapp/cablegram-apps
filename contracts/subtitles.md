# Personal-provider subtitles (CAB-28)

This contract requires the coordinated CAB-28 control-plane release. Provider search, authentication,
download, parsing and activity matching run on the phone. Provider credentials, login tokens,
activity fingerprints, technical analysis and media bytes are never sent to the control plane.

## Source context and selection

Authenticated `POST /api/subtitles/source-context` accepts the catalog identity and optional exact
`sourceId`. The service checks household ownership and returns `sourceId`, `sourceIdentity` (the
source version), title/file name and available catalog/episode/duration metadata for local discovery.

Authenticated `POST /api/subtitles/selected-track` accepts the exact `sourceId` and `sourceIdentity`,
parsed `cues`, two-letter `language`, `provider` (`subdl` or `opensubtitles`), numeric `providerRef`,
`offsetSeconds`, `scale`, `forced`, `confidence` and `automatic`. Confidence is `Likely Match`,
`Unverified` or `Local Activity Match`; local matching is a heuristic. The service rechecks ownership,
availability and version under a source lock before saving. A changed source must be rediscovered.

Requests reject unknown fields, URLs, credentials, activity/technical/media payloads, invalid cue
text/timing and out-of-range corrections. Limits: 2 MB serialized cues, 20,000 chronological cues,
4,000 characters per cue, 120-second cue duration, times within 0–86,400 seconds, offset within
±600 seconds and scale 0.9–1.1. Source context carries no credentials.

Only the selected parsed track and its provenance/timing enter household storage. Authenticated
`DELETE /api/subtitles/:id` removes a saved track belonging to the phone's household.

## Compatibility and lifecycle

The TV playback/content and manual correction contracts remain compatible. Existing selected tracks
and preferences survive migration 037; legacy discovery rows are deleted. Retired backend discovery
endpoints return `410 phone_upgrade_required`. There is no shared-provider-key fallback.

Personal provider credentials are stored only in the phone's account-scoped Keystore-encrypted,
backup-excluded storage. Replace/remove cancels local discovery; sign-out/account deletion clears
credentials. Removing a key preserves saved household tracks. Source/account removal deletes those
tracks. Provider permissions and live device acceptance remain release gates.
