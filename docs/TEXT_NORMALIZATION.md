# Text normalization for narration

How EpubReader rewrites book text into the form a speech engine pronounces
correctly, and the roadmap for extending it.

## Where it runs

```
EPUB resource text
  └─ Readium content service        (Content.Element, source offsets)
       └─ sentence tokenizer         (PublicationSentenceIterator.next)
            └─ TextNormalizer        <-- this document (per sentence)
                 └─ speech engine    (Pocket TTS / Android System TTS)
```

Normalization is applied to the **spoken string only**, after sentence
segmentation and immediately before synthesis. `Sentence.text`, its locator,
the reader highlight, and the persisted position always come from the source
text, so normalization can never shift a resume position or highlight. This is
why the layer is a plain `String -> String` pipeline rather than something
entangled with Readium locators.

Current integration: `PocketNarrationSession.renderSentence` (Pocket path).
Android System TTS still runs through Readium's `TtsUtteranceIterator` and is
not normalized yet; see [Provider seams](#provider-seams).

## Principles

1. **Do no harm.** These are high-precision rules. An abbreviation is expanded
   only when it is effectively unambiguous; ambiguous short forms (`no.`,
   `in.`, `ft.`, `vol.`, `rev.` as "revolution") stay untouched until a safe
   context rule exists. A missed expansion sounds like "etc" (acceptable); a
   wrong one sounds like "Street John" (not acceptable).
2. **Segmentation first.** Normalize per sentence, never before `Content` /
   sentence splitting. Expansion changes string length and would otherwise
   corrupt source offsets.
3. **Pure and ordered.** Each rule is a small, deterministic
   `TextNormalizationRule` (`String -> String`). Order is explicit and
   significant: abbreviations are expanded before numbers so an expansion can
   never be re-scanned as a numeral.
4. **Language-gated.** `TextNormalizer.forLanguage` returns the English pipeline
   for `en*` and `null`, and a pass-through for other languages. Pocket TTS is
   English-only, but the gate keeps other providers safe and is where
   per-language rules will attach.
5. **Provider-agnostic.** Nothing in the package imports Pocket or Android TTS;
   it is unit-testable on the host JVM without `android.icu`.

## Architecture

```
playback/normalization/
  TextNormalizationRule.kt   fun interface String -> String
  TextNormalizer.kt          ordered pipeline + forLanguage()
  AbbreviationNormalizer.kt  abbreviation + St.-context rules
  NumberNormalizer.kt        numeral detection + dispatch
  NumberSpeller.kt           cardinal / year / decimal spelling
```

To add a rule: implement `TextNormalizationRule`, append it to
`TextNormalizer.English` in the intended order, and add a focused test.

## Implemented now

### Abbreviations

Word-boundary, case-insensitive expansion of a curated, near-unambiguous list
distilled from authoritative TTS text-normalization sources:

- Ossian abbreviation map — CSTR Edinburgh (`rules/en/textnorm/rules/abbrevmap`).
- MaryTTS `preprocess/abbrev.dat`.
- MUSE English abbreviations (Oxford-derived).
- Google Kestrel / WeTextProcessing whitespace whitelist.
- Tortoise-TTS `_abbreviations`.

Covered: honorifics/titles/ranks (`Mr.`, `Mrs.`, `Ms.`, `Dr.`, `Drs.`,
`Prof.`, `Rev.`, `Gov.`, `Gen.`, `Col.`, `Lt.`, `Maj.`, `Capt.`, `Sgt.`,
`Jr.`, `Sr.`, `Esq.`, …), Latin/common (`etc.`, `vs.`, `e.g.`, `i.e.`,
`et al.`, `cf.`, `viz.`, `ibid.`, `n.b.`, `p.s.`, `a.m.`, `p.m.`), business
(`Inc.`, `Corp.`, `Ltd.`, `Co.`, `Bros.`), months, and days. Longer forms win
(`Mrs.` over `Mr.`, `Sept.` over `Sep.`).

`St.` is the one context-sensitive entry: a following capitalised word means
Saint (`St. John`), while a preceding word ending the token means Street
(`Main St.`).

### Numbers

`NumberNormalizer` converts, with word boundaries on both sides:

- **Comma-grouped thousands:** `1,234` → "one thousand two hundred thirty-four";
  `12,345,678` → "twelve million three hundred forty-five thousand six hundred
  seventy-eight".
- **Decimals:** `3.14` → "three point one four"; `2.50` → "two point five zero";
  `1,234.56` → "… point five six".
- **Four-digit years:** `1999` → "nineteen ninety-nine"; `1905` → "nineteen oh
  five"; `1900` → "nineteen hundred"; `1000` → "one thousand"; `2005` → "two
  thousand five"; `2013` → "twenty thirteen".
- **Negatives:** `-42` → "minus forty-two".

Deliberately not treated as negatives/ranges: a hyphen that follows a digit
(`3-5` → "three-five"). Numbers attached to letters on both sides (`3D`, `1st`)
are left alone.

Spelling follows CLDR `spellout-numbering` / `spellout-numbering-year`
conventions (hyphenated tens, no "and").

## Planned rules

Ordered roughly by value. Each item lists the intended approach.

### Numbers and quantities

| Rule | Examples | Notes |
| --- | --- | --- |
| Ordinals | `1st`, `2nd`, `23rd` | Needed because `1st` is currently left alone. |
| Percentages | `50%`, `3.5 %` | Append "percent". |
| Currency | `$5`, `£1,234.56`, `€9` | Append the spoken currency noun; handle cents. |
| Ranges | `3-5`, `1990–2000`, `pp. 5–7` | "three to five" / "nineteen ninety to two thousand". |
| Fractions | `3/4`, `½` | "three quarters"; unicode vulgar fractions. |
| Measurements / units | `5 km`, `6 ft.`, `70 mph`, `20°C` | Expand only when a number precedes; `ft.` was excluded from abbreviations for this reason. |
| Dates | `13 Feb. 2007`, `02/03` | Ordinal day, month name; locale-dependent day/month order. |
| Times | `9:30`, `7 p.m.` | "nine thirty", "seven in the evening". |
| Decades | `1990s`, `'80s` | "nineteen nineties", "eighties". |
| Phone numbers | `555-1234` | Digit-by-digit, preserve grouping. |
| Roman numerals | `Chapter IV`, `Henry VIII` | Context-gated; risky in isolation. |
| Scientific / large | `1.5e9`, `10^6` | "one point five times ten to the ninth". |
| Version / dotted | `1.2.3`, `v2.0` | Currently mis-handled as a partial decimal; emit "one point two point three". |
| Number spans | `100-200` vs `-5` | Disambiguate range hyphen from negative sign by surrounding context. |

### Symbols and punctuation

- `&` → "and"; `@` → "at"; `#` → "number"/"hashtag"; `+` → "plus"; `=` →
  "equals"; `×`/`x` → "times"; `÷` → "divided by"; `°` → "degrees"; `©`, `®`,
  `™`.
- Smart quotes/dashes/ellipsis → plain pauses; collapse repeated punctuation
  (`!!`, `?!`) to a single sentence terminator; strip or verbalize stray
  brackets and footnote markers/superscripts.

### Text hygiene

- Normalize all Unicode whitespace (NBSP, narrow NBSP, ideographic space) and
  zero-width characters; drop control characters; collapse runs of spaces.
- Handle line-break hyphenation (`exam-\nple` → "example").
- Guard against leftover HTML entities and markup fragments.
- All-caps words and initialisms: decide when to spell out (`NATO` vs "Nato"),
  ties into the planned lexicon.
- Possessives and trailing apostrophes (`'90s`, `Smiths'`).

### Language and content classification

- Per-language rule sets behind `TextNormalizer.forLanguage` (currently only
  `en`); add number/date/currency rules per supported language.
- Detect embedded foreign-language spans (Readium supplies `Language` per
  segment) and route them to the matching rule set.
- Skip normalization inside obvious non-prose spans (code blocks, math).

### User control and pronunciation

- User lexicon / pronunciation substitutions and regex replacements (already a
  post-MVP backlog item; this pipeline is the natural host).
- "Silence this text" rules for running headers, footers, and page numbers.
- Per-book overrides for abbreviations, dates, and number style (cardinal vs
  year reading).
- Optional engine hints (SSML `<say-as>` / `<sub>`) when an engine supports
  them; Pocket TTS does not, so keep hints behind a capability flag.

## Provider seams

- **Pocket (app-owned loop):** normalize in `PocketNarrationSession.renderSentence`
  before `session.stream(...)`. Done.
- **Android System TTS (Readium-owned loop):** the tokenizer cannot rewrite
  text (it only returns `IntRange`s that `TextContentTokenizer` slices), so the
  seam is a `TtsEngineProvider` decorator whose `TtsEngine.speak` normalizes the
  utterance before delegating to `AndroidTtsEngine`. Note the range callback
  then indexes the normalized string; normalize `location.utterance` in
  `ReadiumNarrationController` as well so highlight offsets stay consistent.
- **Future providers:** normalize in the single place each provider receives
  utterance text; never in a shared layer that also feeds locators.

## Testing strategy

- Pure host JVM JUnit tests per rule and for the combined pipeline
  (`app/src/test/.../normalization/`); no Android/ICU dependency.
- Table-driven cases for number spellout (cardinals, years, decimals,
  negatives) and abbreviations, including near-misses that must **not** change
  (`drum`, `MS`, `3D`, `1st`, `3-5`).
- Contract tests: idempotence (`normalize(normalize(x))` does not double-expand),
  non-English pass-through, and "no character disappears silently" for known
  non-prose inputs.
- When a rule changes segmentation assumptions, add the sentence through the
  real `PublicationSentenceIterator` on a small fixture so the integration is
  covered, not just the string rule.

## References

- Ossian text-normalisation abbreviation map — CSTR Edinburgh.
- MaryTTS `preprocess/abbrev.dat`.
- MUSE English abbreviations (Oxford-derived).
- Google Kestrel / WeTextProcessing whitespace whitelist.
- Tortoise-TTS `_abbreviations`, `expand_numbers`, `expand_abbreviations`.
- Unicode CLDR RBNF (`common/rbnf/en.xml`) for `spellout-numbering` and
  `spellout-numbering-year`.
- Sproat et al., "Hippocratic Abbreviation Expansion" — high-precision,
  do-no-harm rationale for TTS abbreviation expansion.
