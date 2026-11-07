# Learning flow SQL

[JLAS-W0108] Owner decisions 2026-11-01 and 2026-11-07: the whole Learning flow (learning survey + Smart Test, the W0108
learning path, goal curriculum and goal survey, Learning-owned holding tables, the JLPT path and the communication path)
ships as **one schema file and six data files**: `learning_data_N5.sql` (the common data first, then N5), `learning_data_N4.sql`, `learning_data_N3.sql`, `learning_data_N2.sql`, `learning_data_N1.sql` and `learning_data_comm.sql` (communication path). All are stored with Git LFS (`sql/learning/*.sql` in
`.gitattributes`), so run `git lfs install` once and `git lfs pull` after pulling. Learning tables and data that a later
task needs go into these files, never into `sql/migrations/` or `sql/seeds/`.

| File | Content |
|---|---|
| `learning_schema.sql` | Part A: every Learning table, final shape, `CREATE TABLE IF NOT EXISTS`. Part B: guarded upgrades (columns, indexes, FKs, CHECKs, Learning columns on shared tables) for older databases. Part C: jpath and cpath tables. |
| `learning_data_N5.sql` | Common data (Learning flags on the base textbooks, kana rules, goal catalogue, jpath base data), then every N5 row. |
| `learning_data_N4.sql` … `learning_data_N1.sql` | Every row of one JLPT level. A level may read rows of a lower level, never of a higher one. |
| `learning_data_comm.sql` | The communication path: goals, topics, situations, lines, words and phrases, AI call scenarios. |

Inside each data file each part sits between `-- ==== PART <name> BEGIN ====` and `-- ==== PART <name> END ====`.
Generated blocks inside a part sit between `-- BEGIN <source> SHA256 <hash>` and `-- END <source>`. No file has a `USE`
line. `jlas_schema.sql` still carries the Learning tables for the team schema; both describe the same shape.

## Run order and why

Back up the target database first. Then, on the database you mean to change (after `jlas_schema.sql`, the migrations
and the shared base seeds such as `seed_data_jlas.sql`):

```bash
git lfs pull                                                   # the two files are in Git LFS
mysql -u root -p <db> -e "SELECT DATABASE()"                   # must print <db>
mysql -u root -p --default-character-set=utf8mb4 <db> < sql/learning/learning_schema.sql   # 1. schema
for F in N5 N4 N3 N2 N1 comm; do mysql -u root -p --default-character-set=utf8mb4 <db> < sql/learning/learning_data_$F.sql; done   # 2. data, in this order
```

- **Schema first**: the data writes Learning tables and Learning columns that the schema creates.
- **Data order**: N5 carries the common data first (it sets the Learning flags on the base textbooks and holds
  the goal catalogue) and the levels run from N5 up to N1 (a level may read rows that a lower level inserted, never a
  higher one).
- Every file is re-runnable (upserts by stable keys); a second run changes nothing. Never use `--force`.
- If a file starts with `version https://git-lfs.github.com/spec/v1`, LFS has not downloaded it: run `git lfs pull`.

## Rebuilding generated blocks

The builders write into the per-part working files under `sql/learning/parts/` (not tracked by git). Split the shipped
file into parts first, run the builder(s), then bundle again:

```bash
node tools/learning-data-extract/bundle_learning_data.mjs --split   # the six data files -> sql/learning/parts/
# ... run the builders below ...
node tools/learning-data-extract/bundle_learning_data.mjs           # parts -> the six data files
node tools/learning-data-extract/bundle_learning_data.mjs --check   # the data files match the parts
```

Edit the source (`data/learning-owned/learning-content.json`, or the compiled sources under `data/learning-path/`),
then re-run the builder that owns the block; each builder rewrites only its block(s) in the right file
(`tools/learning-data-extract/learning_data_files.mjs` maps a level to its part file under `sql/learning/parts/`) and has a `--check` mode:

```bash
node tools/learning-data-extract/build_communication_path_seed.mjs [--check]     # common
node tools/learning-data-extract/build_sekai_n5_comprehension_sql.mjs . [--check] # N5
node tools/learning-data-extract/build_minna_listening_sql.mjs . [--check]        # N5 + N4 (one block per level)
node tools/learning-data-extract/build_minna_reading_sql.mjs . [--check]          # N5 + N4
node tools/learning-data-extract/build_speed_n5_n4_reading_sql.mjs . [--check]    # N5 + N4
node tools/learning-data-extract/build_speed_n3_comprehension_sql.mjs . [--check] # N3
node tools/learning-data-extract/build_jlas_store_pool_sql.mjs . [--check]        # N4 + N3
node tools/learning-data-extract/build_jlas_compiled_books_sql.mjs . [--check]    # N5, N4, N3
node tools/learning-path-source/seed/seed-sql.mjs [--check]                       # common: jpath levels + kana
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
