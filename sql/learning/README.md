# Learning flow SQL

[JLAS-W0108] Owner decisions 2026-11-01: the whole Learning flow (learning survey + Smart Test, the W0108 learning path,
goal curriculum and goal survey, Learning-owned holding tables, and the new jpath) keeps **one schema file**, and its
data is **split by JLPT level** (a common file plus one file per level, each under GitHub's 50 MB warning limit).
Learning tables and data that a later task needs go into these files, never into `sql/migrations/` or `sql/seeds/`, so
they can be merged into the team database later.

| File | Content |
|---|---|
| `learning_schema.sql` | Part A: every Learning table, final shape, `CREATE TABLE IF NOT EXISTS`. Part B: guarded upgrades (columns, indexes, FKs, CHECKs, Learning columns on shared tables) for older databases. Part C: jpath tables. |
| `learning_data_common.sql` | Data tied to no JLPT level: Learning flags on the base textbooks, kana sound rules (FOUNDATION), the communication goals (BA / Travel / Daily Life: goal catalogue, survey, lessons) and the non-JLPT source drafts. |
| `learning_data_N5.sql` … `learning_data_N1.sql` | Every row of one level: Learning-owned supplement books, reviewed vocabulary, practice/source drafts, Reading/Listening checks, the J-LAS compiled book, store pool, Smart Test corrections of that level. Each ends with an empty `-- BEGIN GENERATED jpath` / `-- END GENERATED jpath` region for the jpath data of that level. |

Generated blocks sit between `-- BEGIN <source> SHA256 <hash>` and `-- END <source>`. A block that came from a
multi-level source carries the level in its name: `…json#sections.<name>@N5` (rendered by a builder) or
`<source>.sql#N5` / `#COMMON` (one level's part of a frozen output, hashed over its own body). No file has a `USE` line.
`jlas_schema.sql` still carries the Learning tables for the team schema; both describe the same shape.

## Run order and why

Back up the target database first. Then, on the database you mean to change (after `jlas_schema.sql` and the shared base
seeds such as `seed_data_jlas.sql`):

```bash
mysql -u root -p <db> -e "SELECT DATABASE()"                     # must print <db>
mysql -u root -p <db> < sql/learning/learning_schema.sql          # 1. schema
mysql -u root -p <db> < sql/learning/learning_data_common.sql     # 2. common
for L in N5 N4 N3 N2 N1; do mysql -u root -p <db> < sql/learning/learning_data_$L.sql; done   # 3. levels, low to high
```

- **Schema first**: the data files write Learning tables and Learning columns that the schema creates.
- **Common before the levels**: it sets the Learning flags on the base textbooks (and checks their identities) and holds
  the goal catalogue that every path uses.
- **Levels from N5 up to N1**: a level file may read rows that a lower level file inserted, never a higher one (for
  example the N4 compiled book reuses the Minna/Speed N4 checks and the owned Sekai N4 copy; the N3 reviewed vocabulary
  checks that the N5/N4 reviewed books exist). A level can be skipped only if every higher level is skipped too.
- Every file is re-runnable (upserts by stable keys); a second run changes nothing. Never use `--force`.

## Rebuilding generated blocks

Edit the source (`data/learning-owned/learning-content.json`, or the compiled sources under `data/learning-path/`),
then re-run the builder that owns the block; each builder rewrites only its block(s) in the right file
(`tools/learning-data-extract/learning_data_files.mjs` maps a level to its file) and has a `--check` mode:

```bash
node tools/learning-data-extract/build_communication_path_seed.mjs [--check]     # common
node tools/learning-data-extract/build_sekai_n5_comprehension_sql.mjs . [--check] # N5
node tools/learning-data-extract/build_minna_listening_sql.mjs . [--check]        # N5 + N4 (one block per level)
node tools/learning-data-extract/build_minna_reading_sql.mjs . [--check]          # N5 + N4
node tools/learning-data-extract/build_speed_n5_n4_reading_sql.mjs . [--check]    # N5 + N4
node tools/learning-data-extract/build_speed_n3_comprehension_sql.mjs . [--check] # N3
node tools/learning-data-extract/build_jlas_store_pool_sql.mjs . [--check]        # N4 + N3
node tools/learning-data-extract/build_jlas_compiled_books_sql.mjs . [--check]    # N5, N4, N3
node tools/learning-data-extract/verify_learning_sql.mjs   # headers, markers, safety rules, block hashes, 50 MiB per file
```

`learning_schema.sql` is maintained by hand: add a new table to Part A or C, and add every later column, index, FK or
CHECK change to Part B through its guarded helper so that a second run changes nothing.

## Ghi chú khi gộp với team

- **`lesson.chk_lesson_situation_tag` thêm `DAILY_LIFE`** (B1 của `learning_schema.sql`): mọi DB đang chạy đã có giá trị này
  nhưng `jlas_schema.sql` của team chưa có. B1 thay CHECK khi mệnh đề thiếu `DAILY_LIFE` (thay theo dấu hiệu): nếu sau này
  team thêm giá trị mới vào CHECK này mà chưa có `DAILY_LIFE`, chạy lại B1 sẽ **xoá mất giá trị mới của team**. Khi gộp: đưa
  `DAILY_LIFE` vào CHECK trong `jlas_schema.sql` (hoặc đổi B1 thành chỉ thêm `DAILY_LIFE` vào mệnh đề đang có).
- **Migration trộn 010 đã bỏ một phần**: `010_mock_test_policy_and_completion_attempt.sql` thêm cả
  `user_learning_path.completion_attempt_id` (đã vào B3) lẫn `jlpt_exam.policy_version` và `jlpt_exam_attempt.policy_version`
  (bảng Mock Test). Hai cột Mock không có trong `jlas`/`jlas_mirai`, `jlpt_exam_attempt` không còn tồn tại và không code nào
  đọc, nên không chép sang; nếu nhóm Mock cần thì thêm vào file của nhóm Mock.
- **Ghi chú rollback 014/015 không chép sang**: `014_learning_package_budgeted_segments.rollback.sql` và
  `015_learning_activity_parts.rollback.sql` (truy vấn kiểm tra thủ công trước khi rollback) đã xoá cùng migration; chúng
  không nằm trong chuỗi chạy xuôi. Cần thì lấy lại từ lịch sử git (trước commit `ace5b777`).
- **Sách tổng hợp (compiled book) dùng dải id riêng** (N5 5e9, N4 6e9, N3 7e9). Từ 01/11 bước "cất" (park) chỉ đụng hàng của
  chính sách đó. Nhưng AUTO_INCREMENT của `grammar_point`, `content_item`, `lesson`… trên DB thật đang nằm trong dải N3, nên
  hàng mới của bất kỳ nhóm nào cũng nhận id trong dải đó; nếu bản dựng lại sách N3 có thêm ô mới thì ô đó có thể trùng id
  hàng của nhóm khác. Khi gộp: nâng AUTO_INCREMENT các bảng này lên trên 8e9 hoặc chuyển dải id của sách tổng hợp.
