# learning-data-extract

Python extraction used to turn the owner's N5/N4 Word books into the Learning Path data workbook
(`outputs/<run>/jlas-learning-path-n5-n4-data-template.xlsx`). The workbook is then checked and turned into
seed SQL by the Node tooling in [`scripts/learning-data/`](../../scripts/learning-data) (validator and generator).

**This directory is not production tooling.** Its output is a draft that must pass the validator and be reviewed by the
data owner. Nothing here is imported by the application, tests or CI.

## Layout

| Path | What it is |
|---|---|
| `build_v5.py` | **The one entry point.** Latest and only complete extractor (N5 lessons, N4 lessons, N4 kanji book). |
| `MANIFEST.md` | Old root path -> classification -> new path -> sha256 -> reason, for every moved file. |
| `archive/superseded/` | Earlier parser versions and one-off workbook patches (v2-v4, builders, fill/clean/fix scripts). Committed, for diffing only. |
| `archive/debug/` | One-off investigation and dump scripts. Committed. |
| `archive/unsafe/` | `fix_grammar.py` - writes placeholder values into the workbook. **Do not run.** |
| `archive-local/` | Intermediate text dumps. **Not committed** (they contain text of licensed books); listed in the manifest. |
| `audit_mazii_listening.py` | Read-only audit for split Mazii Listening handoffs; resolves standardized and raw-manifest audio and exports fail-closed JSON. |
| `test_audit_mazii_listening.py` | Regression tests for split roots, hash conflicts, raw audio recovery and question parsing. |
| `build_mazii_listening_staging.mjs` | Builds a review-only XLSX from the audit JSON with every candidate locked as not import-ready. |

## Running `build_v5.py` (only with the data owner's go-ahead)

Requirements: Python 3.11+, `python-docx`, `openpyxl`.

```bash
python tools/learning-data-extract/build_v5.py
```

It has the owner's machine paths hard-coded at the top (`SRC_EXCEL`, `DST_EXCEL`, `REPORT_PATH`) and in the three
`parse_*` calls at the bottom (the N5 book, the N4 book and the N4 kanji book `.docx` files). Edit them for your machine first.

Known hazards - read before running:

1. **It overwrites `DST_EXCEL`**: `shutil.copy(SRC_EXCEL, DST_EXCEL)` replaces the workbook in `outputs/` with the blank template,
   discarding any manual edits the owner made. Point `DST_EXCEL` somewhere new, or back the workbook up first.
2. **It writes the placeholder `-`** into required cells it cannot extract (`reading`, `pattern`, `meaning_vi`,
   `vocabulary_reading`). A placeholder passes the validator's required-value check and so hides missing data.
   Treat every `-` as missing, not as content. At cleanup time the current workbook contained 315 `reading`, 81 `meaning_vi`,
   31 `pattern` (LESSON_GRAMMAR) and 187 `vocabulary_reading` placeholders.
3. The parsing is heuristic (style names, tab splits, Vietnamese-character detection). Treat results as candidates for review.
4. All rows are written with `canonical_lookup_status = NEEDS_LOOKUP` and lesson `status = DRAFT`; nothing is published.

## After running

```bash
node scripts/learning-data/validate-learning-workbook.mjs --input <workbook.xlsx>
node --test scripts/learning-data/learning-workbook.test.mjs
```

At cleanup time the validator reported `errors = 40` (all `KANJI_WITHOUT_LESSON_VOCAB`), which keeps the data slice `BLOCKED_DATA`.
Do not generate or commit production seed from an unreviewed workbook.

## Restoring an archived script

`git mv tools/learning-data-extract/archive/<group>/<file>.py ./<file>.py` (original root names are in `MANIFEST.md`).
Files in `archive-local/` are not in Git; move them back by hand using the manifest.

## Auditing a split Mazii Listening handoff

The audit reads every file and never changes the handoff folders, database, seeds or owner workbook. Pass all split roots in one run:

```bash
python tools/learning-data-extract/audit_mazii_listening.py \
  <catalog-root> <media-root-1> <media-root-2> \
  --output outputs/<new-audit-run>
```

The output directory must not already exist. The tool resolves audio in this order: catalog-standardized filename, source-URL basename,
then the raw `listening.csv` URL-to-local-hash mapping. It verifies hashes and MP3 headers but deliberately leaves
`license_verified=false`, `semantic_reviewed=false`, `review_status=NOT_REVIEWED`, `lifecycle_status=DRAFT` and
`ready_for_import=false`.

Run its tests before trusting a report:

```bash
python -m unittest tools/learning-data-extract/test_audit_mazii_listening.py -v
```

Build the human-review workbook only with the bundled `@oai/artifact-tool` runtime:

```bash
node tools/learning-data-extract/build_mazii_listening_staging.mjs \
  outputs/<audit-run>/mazii-listening-audit.json \
  outputs/<audit-run>/jlas-mazii-listening-staging.xlsx \
  outputs/<audit-run>/summary-preview.png
```

This workbook is staging evidence, not a schema-aligned production seed. A source URL is not permission to redistribute content;
the data owner must close license, media, answer-key and semantic-review gates before any import work begins.
