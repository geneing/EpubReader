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

Current integration: `PocketNarrationSession.renderSentence` (Pocket path) and
`NormalizingTtsEngineProvider` (Android System TTS path); see
[Provider seams](#provider-seams).

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
   significant. A rule claims source-shaped input and emits final spoken words;
   only a named downstream consumer may re-scan its intermediate output
   (`DigitGroupNormalizer` hands dotted versions to `DottedNumberNormalizer`).
   All other later rules must not treat emitted words as fresh source tokens or
   expand them again. The combined contract is idempotence:
   `normalize(normalize(text)) == normalize(text)`.
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

### Hygiene

Decodes HTML entities, normalizes Unicode whitespace and zero-width/control
characters, converts smart quotes/dashes/ellipsis to ASCII, repairs line-break
hyphenation, and collapses repeated sentence punctuation. Runs first so the
numeric and symbol rules see clean ASCII.

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

- **Comma-grouped thousands:** `1,234` → "one thousand two hundred thirty-four";
  `12,345,678` → "twelve million three hundred forty-five thousand six hundred
  seventy-eight".
- **Decimals:** `3.14` → "three point one four"; `2.50` → "two point five zero";
  `1,234.56` → "… point five six".
- **Four-digit years:** bare four-digit values in the common year range are
  explicitly read with year style: `1999` → "nineteen ninety-nine";
  `1905` → "nineteen oh five"; `1900` → "nineteen hundred"; `1000` → "one
  thousand"; `2005` → "two thousand five"; `2013` → "twenty thirteen".
- **Negatives:** `-42` → "minus forty-two"; a hyphen after a digit (`3-5`) is a
  range, not a sign.
- **Digits and versions:** phone numbers (`415-555-1234`, `(415) 555-1234`,
  `415.555.1234`, `415 555 1234`, `… ext. 12`) read as "area code four one five
  five five five one two three four"; dotted versions (`1.2.3`) and long digit
  runs read digit by digit.

The caller selects a `YEAR` or `QUANTITY` reading explicitly. Unmarked bare
four-digit numerals and date/range years use year style; explicitly marked
amounts, percentages, and measurements use cardinal quantity style. Thus
`1999` → "nineteen ninety-nine", but `1500%`, `$1500`, and `1500 kg` use
"one thousand five hundred". Spelling follows CLDR `spellout-numbering` /
`spellout-numbering-year` conventions (hyphenated tens, no "and").

### Roman numerals

Roman numerals are expanded only with an explicit context signal; isolated
Roman-shaped words and initialisms are too ambiguous to rewrite safely. A
case-insensitive title label immediately before the numeral is sufficient;
the labels are `Chapter`, `Book`, `Part`, `Volume`, `Vol`/`Vol.`, `Section`,
`Act`, `Scene`, `Article`, `Appendix`, `Psalm`, `Canto`, and `Lesson`.
(`Chapter IV` → "Chapter four", `Vol. XII` → "Vol. twelve"). For regnal
readings, the immediately preceding capitalized word must exactly match this
allowlist of established regnal names: `Henry`, `Elizabeth`, `Louis`, `George`,
`Edward`, `Charles`, `Richard`, `James`, `William`, `John`, `Philip`, `Peter`,
`Alexander`, `Frederick`, `Alfonso`, `Ferdinand`, `Francis`, `Nicholas`, `Paul`,
`Leo`, `Benedict`, `Gregory`, `Innocent`, `Pius`, `Sixtus`, `Urban`, `Adrian`,
`Clement`, `Victor`, `Stephen`, `Martin`, `Constantine`, `Augustus`, `Titus`,
`Vespasian`, `Trajan`, `Hadrian`, `Antoninus`, `Marcus`, `Lucius`, or
`Maximilian` (`Henry VIII` → "Henry the Eighth", `Louis XIV` → "Louis the
Fourteenth"). The allowlist avoids treating ordinary capitalized nouns such as
`Vitamin` as names (`Vitamin C` stays unchanged). Roman syntax must be canonical
subtractive notation in the range I–MMMCMXCIX; malformed forms are left
untouched. Numerals in titles use cardinals, while regnal names use ordinals
prefixed by "the". Lowercase Roman letters are accepted in either context
because EPUB text is inconsistently cased. Numerals attached to a following
hyphenated word are not claimed.

The rule runs after non-prose isolation and hygiene, but before structured
number, abbreviation, symbol, and lexicon rules. This lets it see the original
`Vol.` label and prevents a later word rule from reconsidering its spoken
output. With no qualifying title or allowlisted name, examples such as `I think`,
`a V-shaped valley`, `50 M`, `CD player`, `the MI5`, `V for Vendetta`, `XIV`,
and standalone `L`, `D`, or `C` remain unchanged.

### Dates, times and quantities

- **Month dates:** `Feb. 13, 2007`, `February 13th, 2007`, `Feb 2007`.
- **Numeric dates:** `02/03/2007` and `2007-03-02` (day-before-month), with
  range checks so `99/99/2007` is left alone.
- **Ordinals:** `21st` → "twenty-first"; `100th` → "one hundredth".
- **Decades:** `1990s` → "nineteen nineties"; `'80s`/`80s` → "eighties".
- **Currency:** `$5`, `£9`, `€20`, `$1500`, `$12.34` (→ "… and thirty-four
  cents"), `£3.50` (→ "… and fifty pence"), `5 dollars`.
- **Percentages:** `50%`, `3.5 %`; `1500%` → "one thousand five hundred
  percent" (quantity style, never year style).
- **Ranges:** `3-5` → "three to five"; `1990-2000` → "nineteen ninety to two
  thousand".
- **Times:** `9:30`, `09:05` (→ "nine oh five"), `9:30 p.m.`, `7:00 a.m.`.
- **Fractions:** `3/4` → "three quarters"; `1/2` → "one half".
- **Units:** `5 km`, `6 ft.`, `70 mph`, `20 °C`, `10 kg`, `25°`, `2 x 3`, plus
  short units `5 m`, `3 in.`, `2 l`, `4 g`, and `6 h`. Short symbols are only
  claimed immediately after a numeral (with at most one space); a following
  numeral makes `in` the prose preposition, as in `1,234 in 1999`.

### Symbols, non-prose and lexicon

- Symbols: `&` → "and", `+` → "plus", `=` → "equals", `©`/`®`/`™`, `%`, `°`.
- Inline code, URLs and emails are spoken as letter sequences (`user@example.com`
  → "u s e r at e x a m p l e dot c o m"). The scanner runs first and claims
  host-shaped spans such as `example.com/path` and `https://a.b`; numeric
  lookalikes (`1,234.50`, `1.2.3`, `02/03/2007`) and abbreviations (`a.m.`)
  remain available to their prose rules.
- All-caps initialisms: known acronyms use a pronunciation (`NASA` → "Nasa");
  unlisted vowel-less caps are spelled out.

## Planned rules

Remaining work, roughly by value.

### Numbers and quantities

| Rule | Examples | Notes |
| --- | --- | --- |
| Scientific / large | `1.5e9`, `10^6` | "one point five times ten to the ninth". |
| Measurements with word units | `5 kilometres`, `70 miles per hour` | Already spelled; expansion of symbols only. |

### Symbols and punctuation

- `×`/`x` between words (not numbers), `÷` when spelled out, `§`, `¶`.
- Footnote markers/superscripts and stray brackets: strip or verbalize.

### Text hygiene

- All-caps words and initialisms beyond the small acronym list (needs a lexicon).
- Possessives and trailing apostrophes (`Smiths'`).

### Language and content classification

- Per-language rule sets behind `TextNormalizer.forLanguage` (currently only
  `en`); add number/date/currency rules per supported language.
- Detect embedded foreign-language spans (Readium supplies `Language` per
  segment) and route them to the matching rule set.
- Skip normalization inside obvious non-prose spans (code blocks, math).

### User control and pronunciation

- User lexicon / pronunciation substitutions and regex replacements (already a
  post-MVP backlog item; `LexiconNormalizer(lexicon)` is the host).
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
  seam is `NormalizingTtsEngineProvider` — a `TtsEngineProvider` decorator whose
  `TtsEngine.speak` normalizes the utterance before delegating to
  `AndroidTtsEngine`. `BookPlaybackService` builds it with
  `ttsEngineProvider = NormalizingTtsEngineProvider(AndroidTtsEngineProvider(…))`.
  The range callback indexes the normalized string, so `ReadiumNarrationController`
  normalizes `location.utterance` the same way to keep highlight offsets
  consistent.
- **Future providers:** normalize in the single place each provider receives
  utterance text; never in a shared layer that also feeds locators.

## Testing strategy

- Pure host JVM JUnit tests per rule and for the combined pipeline
  (`app/src/test/.../normalization/`); no Android/ICU dependency.
- The pipeline's re-scanning invariant is covered by idempotence over a mixed
  corpus and overlap checks for structured-number, abbreviation, symbol,
  measurement, and non-prose rule pairs.
- Table-driven cases for number spellout (cardinals, years, decimals,
  negatives) and abbreviations, including near-misses that must **not** change
  (`drum`, `MS`, `3D`, `1st`, `3-5`).
- Contract tests: idempotence (`normalize(normalize(x))` does not double-expand),
  non-English pass-through, and "no character disappears silently" for known
  non-prose inputs.
- The Android System TTS seam is covered by
  `NormalizingTtsEngineProviderTest` with a fake engine: the utterance is
  rewritten before delegation and every other operation passes through.
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
