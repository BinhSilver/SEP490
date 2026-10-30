# N5–N4 learning workbook contract

Status: workbook contract v2 for `JLAS-W0108`.

The workbook is a staging source for reviewed book data. It is not a flat copy of the database. Importers must resolve stable codes to numeric IDs, validate all cross-sheet references before writing, and keep curriculum rows separate from module-owned textbook data.

## Stable identity and ownership

| Workbook key | Persistence key | Scope |
|---|---|---|
| `LP_TEXTBOOK.textbook_code` | `textbook.source_code` | Globally unique |
| `LP_UNIT.unit_code` | `unit.source_code` | Unique within `textbook_id` |
| `LP_LESSON.lesson_code` | `lesson.source_code` | Unique within `unit_id` |
| `COMPREHENSION.question_code` / `EXERCISE.exercise_code` | `assessment_question.source_code` | Globally unique when imported as an assessment question |

All four DB columns are nullable so existing module-owned rows remain valid. Every Learning Path textbook imported from this workbook must use `is_learning_path_source=1`; runtime selection additionally requires both textbook and lesson to be `PUBLISHED`.

## Workbook to normalized schema

| Sheet | Main tables | Required behavior |
|---|---|---|
| `LP_TEXTBOOK` | `textbook` | Resolve by `source_code`; do not use a numeric ID from the workbook. |
| `LP_UNIT` | `unit` | Resolve `textbook_code`, then upsert by `(textbook_id, source_code)`. |
| `LP_LESSON` | `lesson`, `tag` | Resolve unit and knowledge tag; upsert by `(unit_id, source_code)`. |
| `LESSON_VOCAB` | `vocabulary_entry`, `lesson_section`, `content_item`, `content_vocabulary` | Reuse canonical vocabulary where lookup is `MATCHED`; normalization is NFKC plus Katakana→Hiragana while preserving `ー` and `・`. |
| `LESSON_KANJI` | `kanji`, `vocabulary_kanji` | No `lesson_kanji` table is created. A lesson Kanji is usable only through vocabulary linked to that lesson. |
| `KANJI_COMPOUND` | validation/link evidence for `vocabulary_kanji` | Both membership codes must exist in the same lesson; `vocabulary_expression` must contain `character` at the one-based `character_position`. |
| `LESSON_GRAMMAR` | `grammar_point`, optionally `content_item` | See the grammar mapping below. |
| `DIALOGUE` | `content_item`, `dialogue_role`, `dialogue_line_detail` | One row is one dialogue line. |
| `COMPREHENSION` | lesson content and optionally `assessment_question` | `question_type` records the book; `import_question_type` decides DB import. |
| `EXERCISE` | lesson exercise and optionally `assessment_question`, option/pair tables | Answer-book columns are provenance only. |
| `FOUNDATION_CHAR` | foundation textbook → unit → lesson → section → `content_item` | Foundation textbook has `jlpt_level=NULL`; lesson has `situation_tag=FOUNDATION`; character content uses `READING`. |

## Question type conversion

`question_type` must preserve the source exercise form. `import_question_type` must be exactly one of `MULTIPLE_CHOICE`, `FILL_BLANK`, `MATCHING`, or `LESSON_ONLY`.

| Source `question_type` | Allowed `import_question_type` | Rule |
|---|---|---|
| `MCQ` | `MULTIPLE_CHOICE` | Import options and one correct option. |
| `TRUE_FALSE` | `MULTIPLE_CHOICE` | Force option 1=`Đúng`, option 2=`Sai`; resolve the source answer to 1 or 2. |
| `FILL_BLANK` | `FILL_BLANK` | Store the reviewed answer in `answer_text`. |
| `MATCHING` | `MATCHING` | Import explicit left/right pairs. |
| `ORDERING` | `MATCHING` or `LESSON_ONLY` | Use `MATCHING` only when reviewed pair semantics exist; otherwise retain it as lesson practice. |
| `FREE_RESPONSE` | `LESSON_ONLY` | Never create an auto-graded `assessment_question`. |

Rows marked `LESSON_ONLY` remain instructional exercises and do not receive an `assessment_question.source_code` row.

## Grammar, dialogue, and foundation mapping

Grammar mapping:

- `pattern` → `grammar_point.pattern`
- `meaning_vi` → `grammar_point.meaning`
- `example_ja` → `grammar_point.example`
- `connection_formula`, `context_note`, `example_vi`, and `practice_prompt` → `grammar_point.note`, each with a stable label so the text remains auditable

Dialogue mapping:

- `text_ja` → `content_item.content`
- `translation_vi` → `content_item.translated_text`
- `speaker` → `dialogue_role.code` and, when provided by reviewed source data, its display name
- each line → one `LISTENING` `content_item` plus one `dialogue_line_detail`

Foundation mapping:

```text
FOUNDATION_CHAR
  → textbook(jlpt_level NULL, is_learning_path_source=1)
  → unit
  → lesson(situation_tag=FOUNDATION)
  → lesson_section
  → content_item(content_type=READING)
```

The supplied N5/N4 book inventory does not yet provide a dedicated Kana source, so foundation rows stay `NEEDS_SOURCE` until reviewed source material is supplied.

## Audit-only metadata

The following fields are intentionally not new DB columns: `source_book_code`, source page/range/reference, answer source, register, key phrase, Can-do text, and other editorial notes. They remain in the workbook and import report for traceability. Question metadata that must travel with a generated assessment question is serialized into `assessment_question.context_json`; it must not drive scoring or canonical lookup.

## Validation gates

Before any SQL is written, the importer must reject duplicate codes, missing parent codes, canonical state `CONFLICT`, unsafe question mappings, and Kanji without at least one compound vocabulary in the same lesson. A validation error must prevent all output files from being created or modified.
