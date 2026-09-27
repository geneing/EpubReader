# Text normalization — hard issues for investigation

This file tracks the difficult, unresolved problems in the narration text
normalization pipeline (`app/src/main/java/com/geneing/epubreader/playback/normalization`).
Each item should be investigated and fixed with tests before the rule is
considered done. Background and design principles live in
`docs/TEXT_NORMALIZATION.md`.

## Open issues

### 1. `1500%` reads as "fifteen hundred percent"

`PercentageNormalizer` currently delegates to `NumberSpeller.quantity`, which
uses the cardinal reading, so `1500` becomes "fifteen hundred". The want is a
reading decision shared with other quantity contexts: years (`1999` -> nineteen
ninety-nine) versus quantities (`1500` -> one thousand five hundred). Audit
every `spellToken`/`quantity` call site and make the year-vs-quantity policy
explicit and tested, so `1500%`, `$1500`, `1500 kg` and `in 1500` each read
correctly.

### 2. Unit symbol ambiguity

`m`, `in`, `l`, `g`, `h` were removed from `UnitAndSymbolNormalizer` because
they collide with prose ("1,234 in 1999" -> "… inches …"). A context rule is
needed so real measurements (`5 m`, `3 in.`) are still spoken while prose is
untouched. Decide the acceptance criteria (attached numeral, explicit unit
word, or a following unit-like token) and test both directions.

### 3. Pipeline ordering and re-scanning

The pipeline is an ordered list of `String -> String` rules. Later rules can
re-scan output of earlier ones; `SymbolNormalizer` currently owns a
numeral catch-all for this reason. Define the invariant precisely ("a rule may
not match text that another rule just emitted unless it is the designated
consumer") and add a test that walks a corpus and asserts idempotence
(`normalize(normalize(x)) == normalize(x)`) plus no double expansion for every
rule pair that overlaps.

### 4. URL/email vs numeric spans

`NonProseNormalizer` and the numeric rules both consume dotted/bracketed
sequences. Ensure the URL/email scanner wins for real hosts (`example.com/path`,
`user@example.com`, `https://a.b`) while `1,234.50`, `1.2.3`, `02/03/2007` and
`a.m.` still reach their numeric/abbreviation rules. Add a table-driven
regression set covering near-misses on both sides.
