# J-LAS Learning-owned content

`learning-content.json` is the only task-owned data file in this directory. Its `sections` preserve independent LP-OWNED source copies and selection rules; `section_sha256` protects each section. `review_audit_archive` preserves historical Gemini advisory notes and prompt text with their own hashes. Advisory status does not prove source accuracy or teacher approval.

Ten dated Learning review/UAT reports formerly at the root of `outputs/` are also preserved in `review_audit_archive` under their original path keys. Nine are byte-exact; the business-scope report has its local database credential redacted. These are historical snapshots, not current acceptance claims. Their useful continuing gates are reflected below: source copies still require content and audio review, and a passing focused test or pilot does not prove a complete learner journey. The generated N5/N4 content-approval packet remains a separate reviewer output.

The `vocabulary_review_batches` section stores the 159 original review-batch JSON texts with their SHA-256 values and manifest. The paths in `reviewed_vocabulary.review_batch_files` are stable provenance keys inside that section, not files to import separately. These batches are a review queue; only the separately selected and validated vocabulary release may be seeded.

The `grammar_question_candidates` section stores the 40 AI draft questions in 13 original JSON texts, along with the review notes. They remain unscored and require human linguistic review. `build_owned_practice_draft_seed.mjs` reads this section to reproduce the holding-table portion of the consolidated seed.

The four N3 draft-review checkpoints from `outputs/2026-10-12-owned-draft-review/` are preserved verbatim, with hashes, in `review_audit_archive`. They document review progress only and do not change publication status.

The `n1_grammar_review_draft` section preserves the N1 reviewer packets and draft bundle with their checksums. Its scratch-only SQL can be regenerated from the stored bundle with `renderSeed(bundle, { publish: false })`; the section's `draft_seed_sha256` guards the result. This is not an approved N1 release.

The `n2_grammar_review_draft` section preserves the eight N2 v2 reviewer JSON files. Its SQL is included in `sql/learning/learning_data_N2.sql` and is not separately applied from `outputs/`.

The `historical_context_candidates` section preserves the four files from the superseded direct-content experiment byte-for-byte, including its inactive seed SQL. This archive is not part of the import sequence; current content uses `supplement_selection` and the v14 review bundle.

The `historical_supplement_v3` section preserves the four earlier v3 bundle files byte-for-byte, including the canonical dictionary inventory used by regression tests. It is historical evidence, not the current v14 import bundle.

The `owned_draft_source_manifest` section and `review_audit_archive` preserve the N3–N1/goal source holding-table manifest and three review notes. `build_owned_source_draft_seed.mjs` accepts this JSON as its manifest input and reproduces the source-draft portion of the consolidated seed. The 1,016 staged source rows remain drafts, not lessons.

The `source_aligned_pilot_archive` section preserves the unpublished reading/listening source-alignment pilot verbatim. Its `DRAFT_NOT_PUBLISHED` status means the pilot is historical review material, not an activity to import or score.

The `supplement_selection` section is the source of the current v14 Vocabulary/Grammar supplement. The v9–v13 generated bundles were intermediate correction runs: compared with v14, they differ only in version, corrected vocabulary fields, canonical review status, and catalog hold flags. The old expected values and replacements that matter for regeneration are recorded in `supplement_selection.vocabulary_corrections`; the current generated v14 bundle is retained for review and tests. Intermediate seeds must not be applied over the current bundle.

`learning-content.json` is the single task-owned JSON source. On a DB using the current canonical schema, apply `sql/learning/learning_schema.sql`, then `sql/learning/learning_data_common.sql` and `learning_data_N5.sql` … `learning_data_N1.sql` in that order (one schema file; the data split by JLPT level, owner 2026-11-01; see `sql/learning/README.md`) after the normal base seeds on an explicitly selected DB, following a verified backup/restore. The former pre-V2 upgrade (old migration 004) is part B of `learning_schema.sql`, guarded so it is safe on any schema state. These files do not replace the shared `jlas_schema.sql` or source-team seeds. On 2026-10-15 the local web DB received an independent owned Sekai N4 copy and 12 sourced N4 Kanji questions; the source book and learner-progress signature stayed unchanged.

The `availability` field is a gate ledger, not a promise of full coverage. Status rechecked 2026-10-18:

- **N5/N4 source curriculum:** 25 chapters each; 1,240/1,258 vocabulary items and 132/150 grammar patterns in the Sekai source copy. It remains `DRAFT`; the counts prove presence, not linguistic review or publication.
- **N5/N4 Reading/Listening:** the canonical Minna Reading section has 190 passage-bound checks across all 50 chapters (94 N5, 96 N4). The Listening section has 1,282 checks across 204 clips (100 N5 clips, 104 N4). The owner confirmed team-seed distribution rights on 2026-10-18; the source is attributed to VNJPClub and the authorization is recorded in the canonical JSON. The content remains Learning-path practice only (`quiz_eligible=false`, `smart_test_eligible=false`), not Smart Test material. The 204 exact media files (353,255,278 bytes) are packaged separately at `outputs/learning-media/jlas-minna-n5-n4-audio-20261018.zip`; `outputs/` and ZIP files are git-ignored, so share this ZIP through the team's approved file-sharing channel alongside the repository revision. Extract it into the directory configured by `content.audio.path`; it creates `minna/vnjpclub/N5/...` and `minna/vnjpclub/N4/...`. Then verify with the command below. The ZIP contains `MINNA_AUDIO_MANIFEST.json` with each MP3's size and SHA-256. The Windows Japanese Text-to-Speech capability is installed, and the browser accepted a Japanese speech-synthesis request; actual sound output was not independently measured. Verify local media and voice availability on each serving machine.
- **N3:** the source snapshot has 168 vocabulary lessons, 139 grammar patterns and 193 Kanji entries, but is not a complete released textbook path. Reading/Listening crosswalk source is DRAFT with 146 answer-mapping holds and 4 unresolved audio links.
- **Communication goals:** the owned snapshots contain BA 140 vocabulary/14 dialogues, Travel 94 vocabulary/8 dialogues and Daily Life 100 vocabulary/8 dialogues. The deterministic generator derives 20 vocabulary-topic lessons, 30 dialogue lessons and 74 applied grammar points from those snapshots, plus three survey catalogs. The owner approved internal J-LAS supplements after technical checks; they are explicitly not official textbook/JLPT material. The generated block has been applied idempotently to the owner's local `jlas` database. Read-only verification found BA 140 vocabulary/14 dialogue lessons, Travel 94/8, Daily Life 100/8; all three have survey catalogs, lesson scopes and synthetic-audio mappings. Authenticated browser UAT verified all three survey pages and the Travel survey-to-path-to-vocabulary/dialogue/grammar route. The Windows Japanese Text-to-Speech capability is installed, and the browser accepted a Japanese speech-synthesis request. This was manual browser UAT, not Selenium; the full lesson-completion and AI reading-explanation journeys remain unverified. Ledger: `INTERNAL_SEEDED_NOT_FULLY_UAT`. Canonical source/config is `learning-content.json#sections.communication_path_release`; regenerate its block of `sql/learning/learning_data_common.sql` with `tools/learning-data-extract/build_communication_path_seed.mjs` rather than editing it. This release adds no scored quiz or JLPT grading.
- The general source crosswalk for N5–N3 Reading/Listening remains `DRAFT`, with 207 answer-mapping holds and 6 unresolved audio links. Keep those rows out of scored and published content until reviewed.

`build_jlpt_comprehension_draft.mjs` regenerates the source crosswalk section; the consolidated Learning seed includes separate checked Minna Reading/Listening blocks for the local internal demo. A passing generator check does not prove the database has been seeded or that a complete browser journey works. The MySQL/app UAT is still a separate gate.

For local N5/N4 listening playback, extract the separate audio ZIP into the configured `content.audio.path` directory, then run `node tools/learning-data-extract/sync_minna_local_audio.mjs . <path-to-user-supplied-minna-json> --check` on the machine serving the app to verify the 204 source/destination files. Omit `--check` only when intentionally copying the exact verified files; the script refuses conflicting files. The canonical JSON and SQL seed contain 190 Reading checks, but this does not prove they are loaded in the current database; confirm after the owner-approved migration/seed procedure and browser UAT.

Validate the canonical inputs:

```bash
node --test tools/learning-data-extract/learning_content_store.test.mjs
node tools/learning-data-extract/build_owned_sekai_n5_n4_draft.mjs . --check
node tools/learning-data-extract/build_jlpt_comprehension_draft.mjs . --check
node tools/learning-data-extract/build_jlpt_n4_kanji_crosswalk.mjs . --check
node tools/learning-data-extract/verify_learning_sql.mjs
```

These verify data integrity and deterministic copies. Browser study, quiz, phase transition, and path completion are separate acceptance gates.
