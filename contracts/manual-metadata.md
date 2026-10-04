# Manual metadata edits (version 1)

Phone editing uses the existing `PATCH /api/catalog/items/:itemId` route. It requires a phone token for the item's household. TV tokens may read the effective metadata but cannot edit it.

The body can contain `title` (trimmed, 1–500 characters), `year` (1–9999 or null), `overview` (at most 10,000 characters or null), and `media_type` (`movie` or `tv`). Omitted fields stay unchanged. Null intentionally clears year or overview. At least one display field is required; unknown properties are refused.

New clients also send `expected_revision` (nonnegative integer) and `edit_id` (UUID), together. The server locks the item, compares its revision, applies only the submitted fields, records their ownership, and increments `metadata_revision`. A retry of the last accepted edit id returns the accepted item without another increment. A stale revision answers 409 with `{"error":"metadata_conflict","current":{...}}`; the phone keeps its draft and requires an explicit review/save before replacing shared values.

The 200 response contains `id`, `title`, `year`, `overview`, `media_type`, `user_metadata_fields`, `metadata_revision`, and `origin_filename`. Catalog list responses include the same ownership and revision fields alongside existing flat metadata. Ownership names are `title`, `year`, `overview`, and `media_type`. TV clients can continue decoding the existing flat fields.

Legacy title-only patches remain accepted without a revision pair; these explicit manual writes retain last-write behavior and acquire title ownership. Ordinary imports, web reanalysis, and automatic matching preserve owned fields. The migration keeps existing metadata/artwork intact and does not schedule rematching.

Saving on the phone performs no enrichment or artwork replacement. Only changed fields become owned. Pending fields and the edit id persist in the atomic library index; sync retries them even when the title's source is not reachable. A failed request or an older server does not clear the local edits. Later shared corrections update acknowledged fields; unacknowledged local fields remain protected. Concurrent local edits are not cleared by an older acknowledgement.

Unchanged behavior: original filenames, source identities, artwork, collections, privacy, and watch progress. The backend must be rolled out before the new phone client. No deployment is part of this change.

