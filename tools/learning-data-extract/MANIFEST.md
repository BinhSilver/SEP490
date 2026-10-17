# Cleanup manifest - root Python extraction scripts

Date: 2026-10-02. Every file was moved, never deleted; `sha256` is the digest before the move and was re-checked after it.
To restore a file: `git mv <new path> <old path>` (committed files) or move it back by hand (`archive-local/`, which is not committed).

| Old path | Classification | New path | sha256 | Reason |
|---|---|---|---|---|
| `build_v5.py` | KEEP_OFFICIAL | `tools/learning-data-extract/build_v5.py` | `299457432f0097b7` | Latest and only complete extractor (N5 + N4 + Kanji book -> workbook). Moved unmodified; see README for its unsafe behaviours. |
| `build_v2.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/build_v2.py` | `44e8c37cdbbb6f9a` | Parser v2; superseded by v5 (same inputs/outputs, v5 header: full rewrite of v4). |
| `build_v3.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/build_v3.py` | `0abeb83de35028e1` | Parser v3 (TOC page map); superseded by v5. |
| `build_v4.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/build_v4.py` | `042b295128127519` | Parser v4; superseded by v5. Kept for diffing the parsing heuristics. |
| `build_clean_excel.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/build_clean_excel.py` | `8eb74561d02932e2` | Early builder (docx -> template); superseded by the versioned parsers. |
| `build_perfect_excel.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/build_perfect_excel.py` | `09b15f09f782e00f` | Early builder; superseded by the versioned parsers. |
| `clean_excel.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/clean_excel.py` | `7c6db5b9ae9ae87b` | One-off removal of SAMPLE- rows from the workbook; already applied, destructive if rerun. |
| `extract_all.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/extract_all.py` | `3ad983efd767edc3` | First all-in-one extractor; superseded by v5. |
| `extract_vocab_n5.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/extract_vocab_n5.py` | `953700105c3306d1` | N5 vocab-only prototype writing to Downloads; superseded by v5. |
| `fill_n4_data.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/fill_n4_data.py` | `152b85fc2577931a` | Prototype with a hard-coded N4 table of contents and sample rows; superseded by v5. |
| `fill_sample_data.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/fill_sample_data.py` | `2403b28d4a0b3faa` | Prototype that wrote SAMPLE rows from PDF names; sample data must never be treated as curriculum. |
| `fix_excel.py` | ARCHIVE_SUPERSEDED | `tools/learning-data-extract/archive/superseded/fix_excel.py` | `dc64b10af051dc85` | One-off workbook patch (SAMPLE row cleanup); already applied, destructive if rerun. |
| `debug_lessons.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/debug_lessons.py` | `b80128fd711855c2` | One-off structure debug. |
| `dump_content.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/dump_content.py` | `e92155eab663af8c` | One-off block dump of the N4 docx. |
| `dump_raw.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/dump_raw.py` | `eb227821095d5090` | One-off raw block-type dump. |
| `explore_n5.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/explore_n5.py` | `20c4a2ddd4b773c2` | One-off first look at the N5 docx (wrote n5_doc_sample.txt). |
| `get_schema.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/get_schema.py` | `8138631e162ab95c` | One-off dump of workbook sheet headers (wrote excel_schema.txt); the contract lives in scripts/learning-data/lib. |
| `inspect_content.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/inspect_content.py` | `723f5da30af850cf` | One-off structure inspection. |
| `inspect_docs.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/inspect_docs.py` | `5ea3406362a80330` | One-off heading-pattern inspection. |
| `investigate_around_174.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_around_174.py` | `c3be3b47a6374e83` | One-off investigation of one grammar table. |
| `investigate_headers.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_headers.py` | `eddf5f281d501da5` | One-off N4 header investigation. |
| `investigate_headers_n5.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_headers_n5.py` | `2b55eacecf0a64c9` | One-off N5 header investigation. |
| `investigate_missing.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_missing.py` | `e4f08cdb3cd0d15a` | One-off investigation of two N4 lesson titles. |
| `investigate_ngu_phap.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_ngu_phap.py` | `26e569a6e6108a77` | One-off N5 grammar investigation. |
| `investigate_patterns.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_patterns.py` | `aff0a7485c7beab2` | One-off N5 grammar pattern investigation. |
| `investigate_start.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_start.py` | `353c6326a35a39f8` | One-off N4 start-of-content investigation. |
| `investigate_structure.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_structure.py` | `11e0511e2d60f676` | One-off N4 structure investigation. |
| `investigate_titles.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_titles.py` | `3687e0bd1a86a256` | One-off lesson title search. |
| `investigate_v4.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/investigate_v4.py` | `06d3cb352105cbdb` | One-off deep N5 investigation that fed parser v4. |
| `read_n4_toc.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/read_n4_toc.py` | `4d523d4b01f2a7dd` | One-off PDF TOC dump (wrote n4_toc.txt). |
| `test_grammar.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/test_grammar.py` | `c99fcc04e0406b45` | Throw-away grammar extraction experiment. |
| `test_grammar_2.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/test_grammar_2.py` | `c263e453e439bb47` | Throw-away grammar extraction experiment. |
| `test_grammar_3.py` | ARCHIVE_DEBUG | `tools/learning-data-extract/archive/debug/test_grammar_3.py` | `1ecff3566a3b4376` | Throw-away grammar extraction experiment. |
| `fix_grammar.py` | ARCHIVE_UNSAFE_DO_NOT_RUN | `tools/learning-data-extract/archive/unsafe/fix_grammar.py` | `8c87a5ca598eac1f` | Writes the placeholder '-' into required cells (reading, pattern, meaning_vi) and 0 into is_review of the owner workbook in place. Placeholders hide missing data from the validator; never rerun. |
| `content2.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/content2.txt` | `309750399967c3fa` | Console/dump output of a debug script. Kept locally, not committed (may contain licensed book text). |
| `content_output.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/content_output.txt` | `9ad26bbb7d733385` | Console/dump output of a debug script. Kept locally, not committed (may contain licensed book text). |
| `excel_schema.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/excel_schema.txt` | `3824d5fa64afe667` | Output of get_schema.py. Kept locally, not committed (may contain licensed book text). |
| `inspect_output.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/inspect_output.txt` | `57e02c73f2c1fd20` | Output of inspect_*.py. Kept locally, not committed (may contain licensed book text). |
| `invest_output.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/invest_output.txt` | `fec601e754519d2e` | Output of investigate_*.py. Kept locally, not committed (may contain licensed book text). |
| `n4_toc.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/n4_toc.txt` | `0e16198990d03839` | Output of read_n4_toc.py (text of the licensed N4 book). Kept locally, not committed (may contain licensed book text). |
| `n5_doc_sample.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/n5_doc_sample.txt` | `2c2f27ee8dd75d37` | Output of explore_n5.py (text of the licensed N5 book). Kept locally, not committed (may contain licensed book text). |
| `raw_output.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/raw_output.txt` | `640eb6b3cd0dcacf` | Output of dump_raw.py. Kept locally, not committed (may contain licensed book text). |
| `v3_output.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/v3_output.txt` | `da7a0beb2e9e6d8b` | Console output of build_v3.py. Kept locally, not committed (may contain licensed book text). |
| `v4_output.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/v4_output.txt` | `a5891ac975e10ffb` | Console output of build_v4.py. Kept locally, not committed (may contain licensed book text). |
| `v5_output.txt` | INTERMEDIATE_OUTPUT_LOCAL_ONLY | `tools/learning-data-extract/archive-local/v5_output.txt` | `7d48fa9da44da696` | Console output of build_v5.py. Kept locally, not committed (may contain licensed book text). |

## Not touched (out of cleanup scope)

- `outputs/` (workbook `jlas-learning-path-n5-n4-data-template.xlsx`, `extraction_report_v*.txt`) - data owner files and evidence.
- Source documents (`.docx`/`.pdf` in the owner's Downloads/Drive), SQL, DB backups, `.factory/`, `.kilocode/`, `tools/artifact-build/previews-n5-n4/`.
- Already tracked `tools/*.py` (dictionary/seed generators) and `scripts/learning-data/` (validator and seed generator).
