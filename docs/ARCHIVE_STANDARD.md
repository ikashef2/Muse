# Archive Standard v0.1

This document is the contract between the scanner, the review UI, and the human curator. A visually tidy screen is not evidence of a tidy archive.

## Admission rule

A file is discovered into the Inbox. It enters the Library only after its canonical record reaches a health score of 90 or higher and the curator explicitly verifies it. Remote metadata never bypasses this confirmation.

## Canonical identity

The canonical identity of a recording is not its filename. It is the combination of:

- recording title;
- primary artist and structured credits;
- release and edition;
- disc and track position;
- duration and, later, acoustic fingerprint;
- stable external identifiers when available.

The same recording on an original release and a remaster may share an acoustic lineage but must remain distinct archive editions.

## Required fields

| Field | Rule |
| --- | --- |
| Title | Official recording title; no source-site suffixes |
| Primary artist | Canonical artist entity; never `Unknown Artist` |
| Album artist | Required even when equal to primary artist |
| Release | Exact album, EP, single, or compilation edition |
| Track/disc | Positive position when the release defines one |
| Date | Original and edition dates will be stored separately |
| Artwork | Release-specific, square, and provenance-aware |
| Genre | Controlled vocabulary with optional secondary genres |
| File quality | Codec, bitrate, sample rate, channels, and integrity |

## Naming rules

- Strip `Official Audio`, `Official Video`, `Lyrics`, download-site names, and bitrate claims from titles.
- Model featured performers as credits rather than title text, unless the official release title itself includes them.
- Keep display name, sort name, and aliases separate. This is mandatory for Persian/Latin interoperability.
- Never merge artists only because their normalized text is similar.
- Never treat two similarly named releases as the same edition without supporting evidence.

## File mutation policy

Physical tag writing is atomic: create a temporary replacement, write tags, reopen and validate audio, preserve previous metadata, then replace. If a format cannot be rewritten safely, update only the canonical database and disclose that the source file is unchanged.

If another app changes a source file after a manual canonical edit, Archive preserves the curator's record, marks the source as externally changed, and returns it to review instead of silently choosing either version.

Deletion is recoverable Trash by default. Permanent deletion requires a second, explicit action.

## Duplicate policy

The prototype marks only near-certain local duplicates: equal byte size, equal duration, and equal normalized title and artist. Acoustic duplicate and alternate-edition detection will be separate concepts in the next milestone.
