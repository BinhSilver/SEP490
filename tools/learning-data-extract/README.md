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
