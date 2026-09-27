# Text normalization — hard issues

This file records the hard issues investigated in the narration text
normalization pipeline (`app/src/main/java/com/geneing/epubreader/playback/normalization`).
Background and design principles live in `docs/TEXT_NORMALIZATION.md`.

## Resolved issues (investigated with GPT-6 Luna)

### 1. Year versus quantity readings — resolved

`NumberSpeller.spellToken` now requires an explicit year/quantity reading.
Unmarked four-digit numbers and date/range years use year style, while
percentages, currency, symbols, and measurements use cardinal quantity style.
Regression tests cover `1999`, `1500%`, `$1500`, and `1500 kg`.

### 2. Unit symbol ambiguity — resolved

The short units are restored only immediately after a numeral, with at most one
space. A following numeral specifically disambiguates `in` as the preposition,
so `1,234 in 1999` remains prose. Tests cover all five short units and both
measurement/prose directions.

### 3. Pipeline ordering and re-scanning — resolved

The invariant is documented: rules consume source-shaped tokens and emit final
spoken words, except for an explicit intermediate handoff to a designated
downstream consumer (`DigitGroupNormalizer` → `DottedNumberNormalizer`). Other
later rules must not reinterpret emitted words as source tokens. The test suite
checks idempotence across a mixed corpus and verifies overlapping later rule
pairs do not re-expand output.

### 4. URL/email vs numeric spans — resolved

The first-stage non-prose scanner claims real hosts, URLs, and email addresses;
the table-driven regressions verify numeric/abbreviation near-misses continue
to their prose rules instead.
