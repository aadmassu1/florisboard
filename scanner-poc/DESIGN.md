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
5. **Alignment by construction, never by aligner.** Word-level Amharic↔English alignment
   is unreliable for this pair (Amharic packs multiple English words into one token —
   አልሄድኩም ↔ "I did not go" — and SOV↔SVO reordering breaks positional heuristics; NMT
   models emit no alignments, and external aligners are research-grade here). Therefore:
   MT is always run **per sentence/per line, recording which source segment produced
   which output segment** — that mapping is exact by construction and is stored in the
   document record (`translation.segments: [{am_range, en_range}]`). No feature may
   depend on alignment finer than these segments. Anything needing a word-level bridge
   uses a bilingual lexicon (as K2 does), not an alignment model.

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
  "pages": [
    {
      "source_image": "scans/receipt-0001.jpg",
      "preprocessed_image": "work/receipt-0001.clean.png",
      "ocr": {
        "engine": "tesseract-amh-5.3",
        "raw_text": "…fidel…",
        "corrected_text": "…fidel…",
        "mean_confidence": 0.83,
        "words": [{"text": "ሰላም", "conf": 0.91, "bbox": [10, 12, 64, 40]}]
      }
    }
  ],
  "translation": {
    "engine": "nllb-200-distilled-600M",
    "text": "…english…",
    "segments": [{"page": 0, "am_range": [0, 42], "en_range": [0, 38]}]
  },
  "understanding": {
    "doc_type": "receipt",
    "fields": {"date": "2026-05-02", "total": "1,450.00 ETB", "merchant": "…"}
  }
}
```

A document is an **array of pages** from day one, even though Phases 0–2 only ever create
single-page records. Capture and OCR are per-page; translation and understanding are
per-document (segments carry a page index). This costs nothing now and is load-bearing
later: multi-page documents are a paid feature (§7), and the free/pro boundary must be a
flag on `len(pages)`, not a schema migration.

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

Phase 2 is organized as one capability per section (2.1–2.7), each with the full
role/assembly/operation/exit-criteria treatment. Build order is the section order:
each capability builds on the ones before it. Two more capabilities that depend on a
phone (read-aloud, document actions) are specified the same way inside Phase 3
(§3.1, §3.2), and handwritten notes join the index in Phase 4 with no changes here.

Shared infrastructure for all of Phase 2: `pipeline.py` grows subcommands
(`scan`, `search`, `export`, `ask`, `pdf`), and every capability reads/writes the
document record — none of them talk to each other directly.

#### 2.1 Cross-lingual search

**Role.** The headline feature: search a folder of scanned Amharic documents in English —
"rent contract 2024" finds a contract that never contained a word of English. Also the
foundation the library UI (Phase 3) sits on. Amharic queries must work equally, because
the original text is the source of truth.

**Assembly.** SQLite FTS5, one database file next to the document records — no server,
portable, trivially backed up. One row per document with two indexed columns
(`text_am`, `text_en`) plus stored metadata (id, doc_type, date). A query runs against
both columns; ranking is BM25 (FTS5 built-in) with a small boost for matches in extracted
fields (a hit on a receipt's merchant name outranks a hit in body text). The indexer is
idempotent: `scan` upserts by document id, so re-processing a document (e.g., after an
improved OCR model) refreshes the index without duplicates. Amharic tokenization note for
the builder: FTS5's default unicode61 tokenizer splits Ethiopic on the word-space
character (፡) and whitespace correctly, but test with and without Ethiopic punctuation —
if real documents mix ASCII spaces and ፡, normalize spacing at index time.

**Operation.**
```bash
python pipeline.py scan ~/scans/*.jpg           # process + upsert into index
python pipeline.py search "rent contract 2024"  # English query
python pipeline.py search "ደረሰኝ"                # Amharic query
python pipeline.py search --type receipt "meat" # filtered by doc type
```
Results print: document id, matched snippet (in whichever language matched), source image
path.

**Exit criteria.** Relevant document in top 3 for 10 predefined English queries and 5
Amharic queries against a 50-document corpus; index rebuild from records ≤ 10 s.

#### 2.2 Structured extraction

**Role.** Turns a photo of paper into data you can act on — the capability no existing
Amharic OCR app has. Receipts are the flagship type (highest value, most regular
structure); a general extractor covers everything else.

**Assembly.** Each extractor is a function `(english_text, amharic_text, ocr_words) → fields`
registered per doc type. Two rules keep it honest:
- **Numerics come from the source side.** Dates, amounts, and phone numbers are read from
  the Amharic/numeric text via patterns, not from the translation — MT can reorder or
  reformat numbers, and a wrong total is worse than no total. The English text is used for
  *semantics*: which number is the total, which line is the merchant.
- **Ethiopian calendar is first-class.** A deterministic Ethiopian↔Gregorian converter
  (13-month calendar, ~7–8 year offset, own leap rule) ships as a utility; every extracted
  date is stored in both calendars plus a flag for which was printed.

Receipt extractor: merchant (top-of-page lines + largest-font heuristic from OCR bboxes),
date, line items (rows where a right-aligned number follows text), total (keyword match on
English side — "total/sum" — then the *amount* taken from the Amharic side of the **same
MT segment**). This works only because of design principle 5: receipts are translated
line-by-line, so the English line that says "total" maps exactly to the Amharic line that
carries the amount — no word aligner involved. General extractor: spaCy NER (people, orgs, dates, money) over the English
text — commodity, which is exactly why the pivot exists.

**Operation.** Runs automatically inside `scan`; fields land in the document record's
`understanding.fields` and are searchable (§2.1) and exportable (§2.5).
`python pipeline.py show <id> --fields` prints extraction for one document;
`--debug-extract` shows which side (Amharic/English) supplied each field.

**Exit criteria.** Date + total correct on ≥ 80% of printed receipts in the eval set;
calendar converter passes a golden test of 20 known date pairs; zero fields silently
guessed — every field carries a confidence and extractors emit nothing rather than
low-confidence junk.

#### 2.3 Auto-organization (document-type classification)

**Role.** Scans sort themselves — receipts, letters, IDs, forms, other — so the library
stays usable at hundreds of documents, and so extraction (§2.2) knows which extractor to
run. Sits *before* extraction in the flow.

**Assembly.** Rules first, ML only if rules prove insufficient on the eval set: receipts
have ETB amounts + a total line; letters have salutation/closing patterns (both sides
checked — ውድ… on the Amharic side, "Dear…" on the English side); IDs are card-aspect-ratio
images with short text and many proper nouns; forms have high ratio of short labeled
fields. Classifier output = type + confidence; below threshold → `other` (never a wrong
confident bucket). The document record stores type + confidence so misclassifications are
findable and re-runnable later.

**Operation.** Automatic inside `scan`; `python pipeline.py scan --type receipt …`
overrides per batch; `search --type` filters (§2.1). Reclassify-all:
`python pipeline.py reclassify` (cheap — no OCR/MT re-run, reads existing records).

**Exit criteria.** ≥ 90% precision on `receipt` (extraction depends on it), ≥ 75% accuracy
overall on the labeled eval set; every misclassification lands in `other`, not in a wrong
type.

#### 2.4 Summarization & Q&A (opt-in LLM)

**Role.** "What is this letter asking me to do?" — free-form understanding that rules
can't cover. Also the accessibility path for a user who reads no Amharic at all. Kept
opt-in because it is the only capability that can send data off-device.

**Assembly.** `summarize.py` reads a document record, sends the *English* text (never the
image, never the Amharic original — smaller payload, no script-handling risk) to the
Claude API, returns summary or answer. Hard separation: off by default, enabled by
explicit config with a visible warning, no other module imports it, and the pipeline is
fully functional without it. Prompt templates live in the repo (versioned, reviewable).
When a capable on-device LLM becomes practical, it slots in behind the same interface —
the opt-in then becomes a backend choice.

**Operation.**
```bash
python pipeline.py ask <id> "what is this letter asking me to do?"
python pipeline.py summarize <id>
python pipeline.py summarize <id> --batch --type letter   # all letters, one line each
```

**Exit criteria.** On 10 eval letters, summaries judged correct/useful by the project
owner for ≥ 8; refusal path verified (clear error when not opted in); documented privacy
note in README stating exactly what leaves the device and when.

#### 2.5 CSV / spreadsheet export

**Role.** The extracted data has to land where people actually use it — a spreadsheet.
"All receipts for June as a table" is the single most demoable output of the project.

**Assembly.** Thin and boring by design: a query over document records (reusing §2.1's
filters) → flat table → CSV (UTF-8 with BOM, so Excel renders fidel correctly — test
this, it is the classic failure). Columns per doc type defined next to each extractor so
they can't drift apart. Dates export in both calendars (two columns).

**Operation.** `python pipeline.py export --type receipt --from 2026-06-01 --to 2026-06-30 --csv june.csv`

**Exit criteria.** Exported CSV opens correctly (fidel intact, columns aligned) in Excel,
Google Sheets, and LibreOffice; row count matches `search --type receipt` for the same
filter.

#### 2.6 Searchable PDF export (stretch)

**Role.** Makes the scan itself a first-class document: a PDF with the original image and
an invisible text layer, so any PDF reader can select/copy fidel text and OS-level search
indexes it. Valuable beyond this tool — it upgrades the user's archive in place.

**Assembly.** The document record already stores per-word bounding boxes from OCR
(Phase 0 deliverable); a PDF writer (e.g., `pikepdf`/`reportlab`) places each corrected
Amharic word invisibly at its bbox over the page image. English translation goes on an
appended text page (visible), not overlaid. Requires an embedded Ethiopic font subset —
document the font license.

**Operation.** `python pipeline.py pdf <id> --out doc.pdf` (or `--all --type letter`).

**Exit criteria.** Text selection in two mainstream PDF readers selects the visually
corresponding fidel; file size ≤ 2× the source image.

#### 2.7 Digitized Amharic text (already free)

**Role.** Stated so it isn't overlooked: after Phase 1, corrected Amharic plain text is
available with no further work — copy-paste, editing, archiving. This is the entire
product of the existing commercial Amharic OCR apps, and here it is a by-product.
`python pipeline.py show <id> --text` prints it; no further build needed.

**Phase 2 exit criteria (overall).** Each capability's own criteria met, plus the
integration demo: a user who reads no Amharic scans 10 mixed documents, finds a specific
one by English search, exports the receipts among them to CSV, and (opted-in) gets a
correct summary of one letter — using nothing but this tool.

**Documentation.** `docs/formats.md` freezing the document-record schema and index layout;
README gains a worked end-to-end example with real (redacted) documents; each capability
section above gets a short usage entry in the README as it lands.

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

#### 3.1 Read-aloud (accessibility)

**Role.** The phone reads a scanned document out loud in English. English TTS is a
commodity (bundled in Android); Amharic TTS is scarce and poor — one more place the
English pivot converts a missing Amharic capability into a solved English one. Primary
audiences: low-vision users, and eyes-busy situations (document in one hand, listening).

**Assembly.** Android's platform `TextToSpeech` engine over the document record's English
text — no new models. Sentence-by-sentence playback with the matching region of the
*original image* highlighted as it reads: the English sentence maps to its Amharic source
segment via `translation.segments` (design principle 5), and that segment's OCR bboxes
give the image region. Segment granularity is exactly why this works — no word aligner. If an Amharic TTS voice is installed on the
device, offer it as a choice; never require it.

**Operation.** Speaker icon on any document view → plays; tap a paragraph to start from
there; standard media controls (pause, speed).

**Exit criteria.** Playback works offline with the stock Google TTS voice; sentence
highlighting stays in sync on the 10-document eval set; TalkBack (Android screen reader)
can drive the whole flow.

#### 3.2 Document-driven actions

**Role.** Close the loop from *understanding* a document to *acting* on it: a bill's due
date becomes a calendar reminder, a letterhead phone number becomes a contact, an address
opens in maps. This is where extraction (§2.2) stops being a data table and starts saving
the user real steps.

**Assembly.** Pure Android intents over already-extracted fields — no new extraction
work: `Intent.ACTION_INSERT` for calendar events (both-calendar dates from §2.2 make the
Gregorian value trivially available) and contacts, `tel:`/`geo:` URIs for dial and maps.
Each action is a chip on the document screen, generated by a small
`field type → intent` registry. Actions are always user-tapped, never automatic — the
extractor's confidence is shown on the chip, and a wrong suggestion costs one glance, not
a wrong calendar entry.

**Operation.** Open a scanned utility bill → chips appear: "Due 12 Jul — add reminder",
"Call 011-…", "Open address in Maps" → tap → the target app opens pre-filled; nothing is
saved without the user confirming inside the target app.

**Exit criteria.** For the eval receipts/bills with extracted dates and phone numbers,
≥ 90% of generated chips open the correct pre-filled intent; zero automatic writes to
calendar/contacts (verified by test).

**Operation (app overall).** Install app → point at document → auto-crop → processed
record appears in the library → search/export/read-aloud/actions from the library screen.
Settings: MT backend choice, opt-in cloud features (off by default), storage location.

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

---

## 7. Monetization

Monetization is by **limits and gates**, never by degrading what free users already have.
It applies to the Android app (Phase 3 onward); the Python pipeline and desktop tooling
stay free.

### 7.1 Principles (non-negotiable)

1. **The free tier must be genuinely useful forever.** Unlimited single-page scan +
   translate + search is the product's word-of-mouth engine; an Amharic tool with a
   crippled free tier never reaches the community it serves.
2. **Accessibility is never paywalled.** Read-aloud (§3.1) and anything a low-vision user
   depends on stays free at every tier.
3. **Privacy is not a paid feature.** Offline processing is the default for everyone;
   nobody is pushed to the cloud to save money.
4. **Gates never corrupt or hold data hostage.** A free user scanning a 5-page contract
   gets 5 clean single-page documents — never a blocked scan, never a locked file. On
   upgrade, a "merge pages into document" tool retroactively combines them: the upgrade
   *adds* order, it doesn't ransom content.
5. **A gate is one sentence.** If a tier boundary needs a paragraph to explain, it's the
   wrong boundary. No usage counters on offline features ("8 of 10 scans used" is hostile
   and, being client-side, fake anyway).

### 7.2 The ladder

| Tier | Price shape | What it unlocks | Enforcement |
|---|---|---|---|
| **Free** | — | Unlimited single-page scans; OCR + translation; cross-lingual search; copy/share digitized text; read-aloud; document actions (§3.2); single-page PDF export | n/a |
| **Pro** | One-time unlock | **Multi-page documents** (the flagship gate: scan N pages → one document, one translation, one searchable PDF); batch scanning; multi-page searchable-PDF export; CSV export; auto-organization folders | Client-side entitlement flag |
| **Cloud credits** | Metered / small subscription | LLM summaries & Q&A (§2.4); premium cloud OCR fallback for scans local OCR can't handle; later: encrypted backup/sync | **Server-side** (real enforcement, real marginal cost) |
| **Institutional / API** | Contract, per-page | Hosted pipeline API for NGOs, banks, courts, archives digitizing at volume — same stages, server deployment | Server-side (future track, not scheduled) |

**Why multi-page is the right Pro gate:** it self-selects exactly the users with
willingness to pay (contracts, filings, reports, books — professional use), while the
person scanning one receipt or one letter never hits it. It is a *feature* gate with no
counter, no reset date, and a one-sentence explanation: *"Free scans one page at a time;
Pro combines pages into documents."*

### 7.3 Enforcement honesty

- Pro is enforced **client-side** (the feature runs on-device); that is ordinary
  app-level protection — crackable in principle, effective in practice, provided the
  **app** ships closed or open-core. Strategy: the pipeline, models, and formats stay
  open (this document's phases); the polished Android app is the commercial artifact.
  A determined fork can rebuild the app — that fork also has no brand, no store
  presence, and no cloud tier, which is the actual moat.
- Cloud credits and the API are enforced **server-side**, where enforcement is real and
  price tracks marginal cost. Over time the durable revenue is expected to come from
  this column, with Pro as the high-margin early revenue.
- The single technical prerequisite is already in the schema: documents are `pages[]`
  from Phase 0, so the Pro boundary is literally `len(pages) > 1 requires entitlement` at
  one enforcement point (document assembly). No other module checks tiers — search,
  export, and read-aloud work identically on any document they're handed.

### 7.4 Payment rails (decide at Phase 3, not before)

- **Diaspora first:** Google Play Billing works for the US/EU diaspora — who
  disproportionately need the translation direction and can pay. This is the launch
  market for Pro.
- **Ethiopia:** Play Billing coverage is limited; local monetization realistically means
  telebirr or similar integration, worth doing only after demand is proven. Until then,
  the free tier *is* the Ethiopian offering — see principle 1.
- Cloud credits require a payment processor + metering service regardless of store;
  scope it with the §2.4 opt-in work, since that's the first paid cloud surface.
