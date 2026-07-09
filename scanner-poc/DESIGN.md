# Amharic Document Understanding Pipeline — Design Document

**Status:** Draft v1 · **Date:** 2026-07-09 · **Branch:** `claude/amharic-english-translation-px95wo`

---

## 1. The Idea

AI tooling for English is mature; AI tooling for Amharic is not. But machine translation
*from* Amharic *to* English is good enough to be useful. This project exploits that asymmetry:

> **Scan an Amharic document, OCR it, translate it to English once, and then do all the
> "smart" work — search, extraction, summarization, Q&A — in the English domain, where
> every capability is a commodity.**

Translation is used **one-way only** (Amharic → English). The weak direction
(English → Amharic generation) is never on the critical path. The original Amharic text is
always preserved and shown alongside; English is the *processing* domain, not the display domain.

### What already exists, and where the gap is

- Google Lens/Translate does camera → OCR → translation, cloud-only, closed, with weak
  Ethiopic OCR.
- Dedicated Amharic OCR apps (Abyssinica OCR, Ethio OCR, MetaAppz) stop at raw text.
- **Nobody offers an open, offline-capable pipeline that goes past translation into document
  understanding**: searching a folder of scanned Amharic documents in English, pulling
  structured data out of receipts, summarizing a letter. That is this project's target.

### Design principles

1. **Offline-first.** Every stage must have an on-device/local backend. Cloud backends
   (better MT, LLM Q&A) are opt-in extras, never requirements.
2. **Amharic is the source of truth.** English artifacts are derived, cached, and
   regenerable. Never destroy or hide the original text.
3. **Each stage is a replaceable module.** OCR, correction, MT, and understanding
   communicate through plain, documented file formats (JSON), so any stage can be swapped
   (e.g., Tesseract → a trained handwriting model) without touching the others.
4. **Measure everything.** Every phase has numeric exit criteria. No stage is "done" on
   vibes; it's done when the metric says so on the shared evaluation set.

---

## 2. Architecture Overview

```
                        ┌────────────────────────────────────────────────┐
                        │              ENGLISH DOMAIN (derived)           │
 photo/scan             │                                                 │
    │                   │   ┌───────────┐   ┌──────────┐   ┌──────────┐  │
    ▼                   │   │ Extraction │   │  Search  │   │ Summary/ │  │
┌─────────┐  ┌──────┐  ┌┴─┐ │ (receipts, │   │  index   │   │   Q&A    │  │
│ Capture/ │→│ OCR  │→│MT│→│  dates,    │   │ (cross-  │   │ (LLM,    │  │
│ Preproc  │  │ +fix │  └┬─┘ │  amounts)  │   │ lingual) │   │  opt-in) │  │
└─────────┘  └──────┘   │   └───────────┘   └──────────┘   └──────────┘  │
                        └────────────────────────────────────────────────┘
     Stage A         Stage B      Stage C              Stage D
```

| Stage | Name | Role (why it exists) |
|-------|------|----------------------|
| A | **Capture & Preprocess** | Camera photos are skewed, shadowed, and noisy. OCR accuracy is decided here more than anywhere else. Cleans the image (deskew, denoise, binarize) so Stage B sees something close to a flatbed scan. |
| B | **OCR & Post-correction** | Turns pixels into Amharic text. Tesseract `amh` for printed text; a lexicon/n-gram rescorer cleans its characteristic errors (Ethiopic has ~350 visually similar glyphs, so raw confusion rates are high). This is the quality bottleneck of the whole system. |
| C | **Machine Translation** | The pivot. Translates the corrected Amharic text to English, one direction, document-at-a-time (no keystroke latency budget). Pluggable backends by quality/size trade-off. |
| D | **Understanding & Storage** | The payoff. Operates entirely on English text using commodity tools: regex/NER extraction, a local full-text search index, optional LLM summarization/Q&A. Stores original image + Amharic text + English text + extracted fields together. |

### The document record — the contract between all stages

Every processed document becomes one JSON record. This format is frozen early (Phase 0)
because every stage reads/writes it:

```json
{
  "id": "2026-07-09-0001",
  "source_image": "scans/receipt-0001.jpg",
  "preprocessed_image": "work/receipt-0001.clean.png",
  "ocr": {
    "engine": "tesseract-amh-5.3",
    "raw_text": "…fidel…",
    "corrected_text": "…fidel…",
    "mean_confidence": 0.83,
    "words": [{"text": "ሰላም", "conf": 0.91, "bbox": [10, 12, 64, 40]}]
  },
  "translation": {
    "engine": "nllb-200-distilled-600M",
    "text": "…english…"
  },
  "understanding": {
    "doc_type": "receipt",
    "fields": {"date": "2026-05-02", "total": "1,450.00 ETB", "merchant": "…"}
  }
}
```

---

## 3. Phases

Each phase states: **Goal** (what and why) · **Deliverables** · **Assembly** (what gets
built, from what, and how the parts connect) · **Operation** (how you run it) ·
**Exit criteria** (numbers that gate the next phase) · **Documentation** (what the builder
writes down before moving on).

---

### Phase 0 — Proof of Concept (Python CLI)

**Goal.** Prove the full chain works end-to-end on one machine before investing in
anything: image in → Amharic text → English text → one extraction demo. Kill the idea
cheaply if OCR or MT quality is fatally bad; otherwise produce the baseline numbers every
later phase is measured against.

**Deliverables.**
```
scanner-poc/
├── DESIGN.md              (this file)
├── README.md              (install + run instructions, kept current every phase)
├── requirements.txt
├── pipeline.py            CLI entry point: image(s) in → document record JSON out
├── stages/
│   ├── preprocess.py      grayscale, Otsu binarize, deskew (OpenCV)
│   ├── ocr.py             pytesseract wrapper (lang=amh), emits words + confidences
│   ├── translate.py       pluggable MT: 'opus' | 'nllb' | 'none'
│   └── extract.py         demo extraction: dates, ETB amounts, phone numbers (regex)
├── samples/
│   ├── make_samples.py    renders known Amharic sentences → PNG (Noto Sans Ethiopic)
│   ├── *.png              generated test images (clean + degraded variants)
│   └── ground_truth.json  the sentences used, for scoring
└── eval/
    └── score.py           CER/WER of OCR vs ground truth; timing per stage
```

**Assembly.**
1. *OCR:* install Tesseract ≥ 5 plus the `amh.traineddata` model (from
   `tessdata_best` for accuracy). Wrap with `pytesseract`, request TSV output to get
   per-word confidences — Stage B's correction work in Phase 1 depends on having these.
2. *MT backends:* two, behind one interface in `translate.py`:
   - `opus` → `Helsinki-NLP/opus-mt-mul-en` (~300 MB, MarianMT, fast on CPU). Default.
   - `nllb` → `facebook/nllb-200-distilled-600M` (~2.4 GB, source lang `amh_Ethi`,
     target `eng_Latn`). Quality option, flag-enabled.
   Both loaded lazily via `transformers`, so the CLI starts fast and downloads happen once.
3. *Test data:* real photos are not required to start. `make_samples.py` renders known
   Amharic sentences with Noto Sans Ethiopic at several sizes, then produces degraded
   variants (blur, rotation ±3°, JPEG noise) to simulate camera capture. Because the input
   sentences are known, OCR accuracy is exactly measurable from day one.
4. *Wiring:* `pipeline.py` calls the four stages in order and writes one document-record
   JSON per image. No database, no service — files in, files out.

**Operation.**
```bash
pip install -r requirements.txt
python samples/make_samples.py                 # generate test set
python pipeline.py samples/clean_01.png        # single image → JSON on stdout
python pipeline.py samples/ --out results/     # batch
python pipeline.py photo.jpg --mt nllb         # quality MT backend
python eval/score.py results/ samples/ground_truth.json   # CER/WER + timings
```

**Exit criteria.**
- OCR character error rate (CER) ≤ 10% on clean rendered samples, ≤ 25% on degraded ones.
- MT output judged intelligible by an Amharic speaker on ≥ 8/10 sample sentences
  (subjective gate — the user is the judge here).
- Full pipeline ≤ 30 s per page on CPU with the `opus` backend.
- If clean-sample CER > 25%: **stop and reassess** — the printed-OCR foundation is not
  viable with Tesseract and Phase 1 priorities change (evaluate Abyssinica-style commercial
  OCR APIs or training our own recognizer before anything else).

**Documentation.** `README.md` with exact install steps (incl. tessdata path), a results
table (CER per sample class, seconds per stage), and 3 side-by-side examples:
image → Amharic → English. This is the evidence the idea works.

---

### Phase 1 — Printed-Document Hardening (real photos)

**Goal.** Go from "works on rendered samples" to "works on phone photos of real paper."
Two levers: better image preprocessing (most OCR failures are image-quality failures) and
Amharic-side post-correction (fixing the OCR errors that remain). This phase is entirely
about Stage A and Stage B; MT and extraction are untouched.

**Deliverables.**
- `stages/preprocess.py` grown into a real pipeline: perspective correction (find document
  quadrilateral, warp flat), adaptive thresholding for shadows, deskew by text-baseline
  angle, and a resolution gate that warns when the image is below ~300 DPI equivalent.
- `stages/correct.py` — new module: OCR post-correction.
- `data/wordlist_am.txt` — Amharic word-frequency list built from a public corpus
  (CC-100 Amharic slice and/or Amharic Wikipedia dump; script + provenance committed).
- A real evaluation set: **30–50 phone photos** of actual printed Amharic material
  (newspapers, books, printed receipts, church calendars — collected by the project owner),
  each with hand-checked ground-truth transcription. This replaces rendered samples as the
  metric that matters.

**Assembly.**
1. *Preprocessing:* pure OpenCV, no ML. Assembled as an ordered list of small functions,
   each togglable by flag, because the eval harness from Phase 0 lets us measure which
   steps actually help (keep) or hurt (drop) on the real-photo set.
2. *Post-correction — the n-gram idea lands here:*
   - Build a frequency dictionary (word → count) from the corpus.
   - For each OCR word below a confidence threshold, generate candidates within edit
     distance 1–2 **weighted by an Ethiopic confusion matrix** (same-consonant different
     vowel-order glyphs are cheap substitutions, e.g. በ/ቡ/ቢ/ባ; unrelated glyphs are
     expensive). The confusion matrix is derived from Phase 0's scored errors.
   - Rescore: `score = OCR confidence × candidate frequency`. Upgrade path (only if the
     metric demands it): word bigrams from the same corpus for context-aware rescoring.
3. *Wiring:* `correct.py` slots between `ocr.py` and `translate.py`; the document record
   keeps both `raw_text` and `corrected_text` so correction quality stays measurable forever.

**Operation.** Same CLI. New flags: `--no-correct`, `--preprocess-only` (emit the cleaned
image for visual inspection), `--debug-correct` (log every substitution made, so bad
corrections are diagnosable). `eval/score.py` now reports CER **before vs. after**
correction — the correction module must prove it helps.

**Exit criteria.**
- CER ≤ 15% on the real-photo evaluation set (median document).
- Post-correction reduces CER (never increases it) on ≥ 80% of eval documents.
- Perspective/shadow preprocessing demonstrably lowers CER on the phone-photo subset.

**Documentation.** A short `docs/ocr-notes.md`: the measured Ethiopic confusion pairs,
what preprocessing steps helped and by how much, corpus provenance and license for the
wordlist. This is the institutional knowledge the handwriting phase (4) will reuse.

---

### Phase 2 — English-Domain Understanding (the payoff)

**Goal.** Deliver the features that justify the whole design — the things no existing
Amharic OCR app does. Everything in this phase operates on the English translation and is
therefore ordinary, well-trodden engineering; that's the point of the pivot.

**Deliverables.**
- `stages/extract.py` grown into per-document-type extractors:
  - **Receipts:** merchant, date (incl. Ethiopian-calendar → Gregorian conversion),
    line items, total. Output: CSV/JSON export.
  - **Letters/general:** people, organizations, dates, amounts (spaCy NER on the English
    text — commodity, which is exactly why we pivoted).
  - Doc-type classifier: rules first (receipts have totals and ETB amounts; letters have
    salutations), ML only if rules prove insufficient.
- `index.py` — local full-text search over all document records using SQLite FTS5:
  both the English *and* Amharic text are indexed, so queries work in either language and
  results always show the original document.
- `summarize.py` — **opt-in** LLM backend (Claude API) for summarization and Q&A over a
  document's English text. Clearly separated: this is the only module that can send data
  off-device, it is off by default, and the pipeline is fully functional without it.
- `pipeline.py` grows subcommands: `scan`, `search`, `export`, `ask`.

**Assembly.** Each extractor is a function `(english_text, ocr_words) → fields` registered
per doc type; the classifier picks which ones run. The search index is a single SQLite file
next to the document records — no server, portable, trivially backed up. The LLM module
reads the same document record JSON; nothing upstream knows it exists.

**Operation.**
```bash
python pipeline.py scan ~/scans/*.jpg          # process + index
python pipeline.py search "rent contract 2024" # English query over Amharic docs
python pipeline.py search "ደረሰኝ"               # Amharic query works too
python pipeline.py export --type receipt --csv receipts.csv
python pipeline.py ask 2026-07-09-0001 "what is this letter asking me to do?"  # opt-in LLM
```

**Exit criteria.**
- Receipt extraction: date + total correct on ≥ 80% of printed receipts in the eval set.
- Search: relevant document in top 3 results for 10 predefined English queries against a
  50-document corpus.
- A user who reads no Amharic can determine what a scanned document is about using only
  this tool (the demo that sells the project).

**Documentation.** `docs/formats.md` freezing the document-record schema and index layout;
README gains a worked end-to-end example with real (redacted) documents.

---

### Phase 3 — Android App

**Goal.** Put the pipeline where the documents are: a phone. Everything from Phases 0–2
is re-hosted on-device; the Python code remains the reference implementation and eval
harness (models are converted, logic is ported, metrics must match).

**Deliverables.** A standalone Android app (separate repo — this is not keyboard code):
camera capture with live edge detection → on-device OCR → on-device MT → local index,
mirroring the `scan`/`search`/`export` operations with a UI. Views always show
Amharic original with English alongside.

**Assembly.**
1. *Capture:* CameraX + the Phase 1 OpenCV preprocessing ported via OpenCV-Android
   (same functions, same order — the eval harness already proved which steps matter).
2. *OCR:* [Tesseract4Android] with the same `amh` traineddata; post-correction ported to
   Kotlin (wordlist ships as an asset, same file as Phase 1).
3. *MT:* NLLB-600M quantized to int8 via **CTranslate2** (~600 MB on disk, ~1–2 s per
   paragraph on a modern phone) as the quality option; the smaller Marian model similarly
   converted as the default. Model files downloaded on first run, not bundled in the APK.
4. *Index/records:* same SQLite FTS5 schema as Phase 2 — the document record JSON is the
   portability contract between desktop and mobile.

**Operation.** Install app → point at document → auto-crop → processed record appears in
the library → search/export from the library screen. Settings: MT backend choice, opt-in
cloud features (off by default), storage location.

**Exit criteria.**
- Photo-to-English ≤ 15 s on a mid-range phone (Snapdragon 6-series class).
- OCR CER on-device within 2 points of the Python reference on the same eval images.
- Total app + models ≤ 1 GB with the default backend.
- Fully functional in airplane mode.

**Documentation.** Architecture README in the app repo; a `PORTING.md` mapping each
Python stage to its Kotlin counterpart so the two implementations stay in sync.

---

### Phase 4 — Handwriting (research track)

**Goal.** Extend OCR to handwritten Amharic — personal notes, filled-in forms, handwritten
portions of receipts. This is a genuine research effort (no off-the-shelf engine for
handwritten Ethiopic exists), so it runs as a parallel track that must not block
Phases 1–3, and it plugs into the existing pipeline as just another Stage-B backend.

**Deliverables.**
- A trained handwritten-Ethiopic recognizer exposed with the same interface as `ocr.py`
  (text + per-word confidence), selectable via `--ocr-engine handwriting`.
- A modern-handwriting dataset: collected + labeled samples of contemporary pen-on-paper
  Amharic (notes, forms), because the public dataset is historical manuscripts.
- An updated confusion matrix for the Phase 1 corrector (handwriting confuses different
  glyph pairs than print does; the corrector matters *more* here, not less).

**Assembly.**
1. *Pretrain* on [HHD-Ethiopic](https://github.com/bdu-birhanu/HHD-Ethiopic)
   (~80k labeled handwritten text-lines, 18th–20th-c. manuscripts; baselines near
   human-level CER) — architecture: CRNN+CTC or fine-tuned TrOCR encoder-decoder.
2. *Close the domain gap:* historical manuscripts ≠ modern ballpoint. Collect ~100+ pages
   of modern handwriting from volunteer writers, label with a bootstrap loop (model
   pre-labels, human corrects — the pipeline's own tooling helps here). Augment
   (elastic distortion, stroke-width variation) to stretch the small modern set.
3. *Integrate:* drop in as an alternative OCR engine; line segmentation (splitting a page
   into text-lines, which Tesseract did for print) needs its own step — use a standard
   text-line detector (e.g., Kraken's or a projection-profile baseline) ahead of the recognizer.

**Operation.** `python pipeline.py scan note.jpg --ocr-engine handwriting` — everything
downstream (correction, MT, extraction, index) is unchanged. That downstream reuse is the
reward for the module-boundary discipline of Phases 0–1.

**Exit criteria.**
- CER ≤ 30% on the modern-handwriting test split (usable-with-correction threshold;
  handwriting at print-level accuracy is not a realistic near-term bar).
- End-to-end demo: a handwritten note searchable in English.

**Documentation.** Model card (training data, license, metrics), dataset datasheet for the
collected samples (consent, licensing), training-reproduction script.

---

## 4. Phase Sequence & Effort

```
Phase 0  PoC (CLI)               ~days        gate: OCR viable at all?
Phase 1  Real photos + correction ~weeks       gate: CER ≤ 15% on real photos
Phase 2  Understanding features   ~weeks       gate: the demo that sells it
Phase 3  Android app              ~weeks–months (needs 1; parallel with 2 after schema freeze)
Phase 4  Handwriting              ~months      (research track, parallel from Phase 2 on)
```

Dependencies: 0 → 1 → 2 → 3, with 4 branching off after 1 (it reuses the corrector and
eval harness). Phases 2 and 3 can overlap once the document-record schema is frozen.

## 5. Risks (ranked)

1. **Printed-OCR quality** — the whole stack sits on Stage B. Mitigated by Phase 0's
   kill-gate and Phase 1's correction layer; fallback is a commercial Ethiopic OCR API
   behind the same interface (compromises offline-first — a decision point, not a default).
2. **MT quality on informal/domain text** — receipts and notes aren't news prose (MT
   training data mostly is). Mitigated by keeping extraction tolerant (dates/amounts are
   read from the *Amharic/numeric* side where possible, translation used for semantics).
3. **Handwriting domain gap** — historical training data vs. modern pens. Contained by
   running Phase 4 as a non-blocking research track with its own modest accuracy bar.
4. **Model size on mobile** — 600 MB MT model. Mitigated by int8 quantization, first-run
   download, and the small Marian default.

## 6. Keyboard Track: Amharic Transliteration Input (FlorisBoard)

The original discussion started from FlorisBoard (this repo). This track is independent of
the scanner phases above and can be built by a separate builder at any time.

### 6.1 The English-pivot prediction idea: known structural risks

Translation-pivot *suggestions* — translating typed Amharic context to English, predicting
the next English word, translating it back — face four structural problems in a keyboard.
They are documented here because any builder attempting Phase K2 (§6.4) must design its
evaluation around them:

- **Word-order misalignment:** Amharic is SOV, English is SVO; the "next English word"
  continues a differently-ordered sentence, so it frequently isn't the next Amharic word.
- **Morpheme-vs-word mismatch:** the most common English predictions (*the, to, of, not*)
  surface in Amharic as affixes on other words — there is no standalone token to suggest.
- **Inflection loss:** single-word English→Amharic back-translation can't pick agreement
  (predicted "goes" ↛ ይሄዳል vs. ትሄዳለች), so even semantically right suggestions arrive in
  a form the user won't tap.
- **Cost:** two on-device MT directions + an English LM, in an offline-only app with a
  per-keystroke latency budget, to fill one low-precision suggestion slot.

The native path (K0/K1) is cheaper and correct by construction, so it comes first. The
pivot idea is not discarded: it is staged as a measured experiment in K2, *after* K1
exists as the baseline it must beat.

### 6.2 Keyboard Phase K0 — Transliteration composer ("type Amharic in English")

**Goal.** Let users type romanized Amharic on a QWERTY layout and get fidel directly:
`selam` → ሰላም. This is the mainstream way Amharic is typed (Gboard's Amharic mode and
Google Input Tools work exactly this way, as did Power Ge'ez conventions before them), so
users already know the scheme. Gboard's version is closed and cloud-connected; this is the
open, offline equivalent — and it is the **cheapest real Amharic feature this codebase can
ship**, because the mechanism already exists.

**Key discovery (verified in this repo).** FlorisBoard has a generic rules-based composer:
`WithRules` in `app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/composing/Composer.kt`.
It rewrites the tail of committed text from a plain JSON map of rules and is already used
in production for Vietnamese Telex
(`app/src/main/assets/ime/keyboard/org.florisboard.composers/extension.json`, composer id
`telex`). **An Amharic transliteration mode is therefore a data file, not new Kotlin code.**

**How the rule mechanism works (builder must understand this before writing rules).**
`WithRules.getActions(precedingText, toInsert)` sees the text *already committed* to the
editor plus the incoming character, matches the longest rule key against the tail, and
returns (chars-to-delete, replacement). Consequence: rules are keyed on **already-transformed
output**, not on the raw keystroke sequence. The fidel table is written like this:

```
"s"   → "ስ"     (bare consonant commits the 6th-order/sadis form immediately)
"ስe"  → "ሰ"     (typing e after committed ስ replaces it with the 1st-order form)
"ስu"  → "ሱ"     "ስi" → "ሲ"    "ስa" → "ሳ"    "ስE" → "ሴ"    "ስo" → "ሶ"
"ሰe"  → "ሴ"     (double-e convention for the 5th order, if adopted)
"ስw"  → "ሷ"     (labialized forms follow the same replace pattern)
```

This is exactly the pattern the Telex rules already use (their keys contain transformed
characters like `"ăf": "ằ"`), so the mechanism is proven in this codebase.

**Deliverables.**
1. `generate_ethiopic_rules.py` (or Kotlin script) — generates the full rules map from the
   Unicode Ethiopic block structure. Do not hand-write ~350+ rules: fidel is systematic
   (base consonant + 7 vowel orders at fixed codepoint offsets, plus labialized series),
   so the table is generated from a consonant→romanization list (~34 entries) and a
   vowel-order→suffix list (7 entries). Handwritten exceptions only where romanization
   conventions collide (e.g. `h` families ሀ/ሐ/ኀ, `s` ሰ/ሠ, `ts` ጸ/ፀ — pick one primary per
   sound; the alternates get digraph escapes like `hh`, `ss2`, or long-press keys).
2. A new composer entry `{ "$": "with-rules", "id": "ethiopic-translit", … }` in a keyboard
   extension (either added to `org.florisboard.composers` or a separate
   `org.florisboard.composers.ethiopic` extension).
3. A characters layout JSON (QWERTY key arrangement, Ethiopic long-press popups for the
   ambiguous consonant alternates) + subtype preset wiring `am` locale → this layout +
   composer, following how existing layouts/presets are registered in
   `app/src/main/assets/ime/keyboard/org.florisboard.layouts/`.
4. A rules test: feed romanization strings through `WithRules.getActions` in a unit test
   and assert the fidel output for a golden list of ~50 words (ሰላም, እንጀራ, አዲስ አበባ…).

**Decisions the builder must make (document them in the extension README):**
- Romanization scheme: follow Gboard/Google Input Tools conventions where possible
  (users' muscle memory), SERA as the tie-breaker reference.
- 5th-order (ሴ) and 7th-order (ሶ) suffixes, glottal/pharyngeal consonant escapes, and how
  to type a *bare Latin word* mid-text (an escape key or the language-switch key — decide
  and document).
- Uppercase behavior: `WithRules` lowercases for matching and re-uppercases output — for
  Ethiopic (no case) verify this path is a no-op; add `E`-style distinct-case rule keys
  only if the scheme uses them deliberately.

**Operation.** User selects the Amharic (transliteration) subtype; types `selam betam
des yilal`; sees ሰላም በጣም ደስ ይላል committed as they type; long-presses for rare glyph
alternates; hits the language switch to type raw English.

**Exit criteria.** The 50-word golden test passes; an Amharic-speaking tester types 5
everyday sentences without consulting documentation; no regression to the Telex composer
(shared code path — run its existing behavior before/after).

### 6.3 Keyboard Phase K1 — Dictionary-assisted variants (later, needs suggestion engine)

The composer is deterministic: it requires *one* canonical romanization. Gboard's added
value is variant tolerance — `injera`, `enjera`, `ingera` all surface እንጀራ **as a
suggestion candidate**. That requires the suggestion pipeline, and FlorisBoard's
`SuggestionProvider` for Latin is currently a stub (`suggest()` returns `emptyList()` in
`ime/nlp/latin/LatinLanguageProvider.kt`). When suggestions are rebuilt upstream (or by
us), the assembly is:

1. Amharic wordlist with frequencies — **the same asset Scanner Phase 1 builds** (corpus →
   `wordlist_am.txt`); the two tracks share this foundation.
2. Reverse-transliterate each dictionary word to its romanization set (run the K0 rule
   table backwards + known variant patterns: e→i alternations, doubled consonants,
   epenthetic vowels).
3. A provider that matches the user's raw Latin composing text against the romanization
   index and offers fidel words ranked by frequency — same architectural slot as the
   existing `HanShapeBasedLanguageProvider` (pinyin-style: type Latin, suggest script).

Do not start K1 before K0 ships: K0 is useful alone, K1 without K0 is not, and K1's
reverse-transliteration table is generated *from* K0's rule table.

### 6.4 Keyboard Phase K2 — English-pivot prediction experiment (gated on K1)

**Goal.** Test the hypothesis that English-domain semantics can add prediction value that
Amharic n-gram statistics miss. The pivot: after the user commits a word (space), translate
the Amharic sentence-so-far to English, predict the next English word with an English
language model, map it back to Amharic candidates, and offer the best one as **one extra
slot** in the suggestion row — never replacing the K1 n-gram candidates, never
auto-committing.

**Why it is gated, not scheduled.** The structural risks in §6.1 mean this may add nothing;
the K1 baseline is what makes that measurable instead of arguable. The experiment exists
because the semantic upside is real: n-grams have zero semantics, and an English LM could
occasionally surface a contextually apt word a sparse Amharic n-gram cannot. K2's job is to
find out whether "occasionally" is often enough to pay for its machinery.

**Assembly.**
1. *Trigger:* on word commit (space/punctuation) only — never per keystroke. Runs async;
   if the result arrives after the user types again, it is discarded (stale context).
2. *Am→En leg:* the same MT backend family as the scanner (Marian/NLLB via CTranslate2
   int8); shared model files if both apps are installed is a non-goal for v1.
3. *English prediction:* a small on-device English LM or n-gram model over the translated
   prefix. Skip function-word predictions outright (*the, to, of, a, not…* — §6.1's
   morpheme problem) via a stoplist; only content-word predictions proceed.
4. *En→Am mapping — NOT free-text back-translation:* map the predicted English lemma to
   Amharic candidates through a **bilingual lexicon** (English lemma → Amharic lemma set),
   then choose the surface form by asking the **K1 n-gram model** which inflected form of
   that lemma is most likely after the current Amharic context. This sidesteps §6.1's
   inflection-loss problem: English supplies the *lemma* (semantics), Amharic statistics
   supply the *form* (morphology). If the lexicon has no entry or the n-gram has no form
   preference, show nothing — silence beats noise in a suggestion row.
5. *Placement:* candidate appears in the last suggestion slot, visually identical to other
   suggestions. Log (locally, opt-in) impressions and taps for both K1 and K2 candidates.

**Evaluation — the experiment's entire point.**
- Offline first: on a held-out Amharic corpus, measure next-word hit@1/hit@3 for K1 alone
  vs. K1+K2. If K2 does not improve hit@3 by a pre-registered margin (suggest ≥2 points),
  stop — do not ship.
- On-device second: pivot-candidate tap-through rate vs. the n-gram candidates in the same
  slot position, plus added latency and battery cost per commit.
- Kill criteria are as important as ship criteria: K2 is removed if it degrades suggestion
  latency past 150 ms per commit or its tap-through is below half the n-gram candidates'.

**Dependencies.** K1 shipped (supplies the candidate baseline, the n-gram form-picker, and
the tap-through instrumentation); a bilingual En–Am lexicon (buildable from public
dictionaries or extracted from parallel corpora — document provenance and license).

### 6.5 Relationship to the scanner project

Scanner Phase 1 (Amharic corpus, wordlist, n-gram counts) and Keyboard Phase K1 consume
the same data assets. Whichever track is built first should place these under a shared,
documented format so the other track can reuse them unchanged.
