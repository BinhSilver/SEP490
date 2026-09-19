# JLAS2-127 — MySQL Migration Verification

**Status: VERIFIED** (2026-09-15, MySQL 8.0.16+ via MySQL Workbench). The two additive migrations
were run against a disposable database `jlas_verify_124` seeded with the pre-124 baseline shape of
`user_learning_path` and `user_learning_path_item` (cross-table FKs omitted so the script is
self-contained; the migrations under test only alter columns/indexes, never FKs). The migration was
then re-run a second time and produced no errors — the idempotency branch (`SELECT '... already
exists'`) executed on every guarded statement. Observed results are recorded in the checklist below.

The migrations under test:

- `db/migrations/JLAS2-124-learning-path-schedule.sql` — snapshot columns, `coverage_gaps_json`, and
  the `uk_learning_path_generation` / `uk_learning_path_user_version` unique keys on
  `user_learning_path`.
- `db/migrations/JLAS2-124-learning-path-phases.sql` — `phase`/`reason_code`/`day_number`/
  `estimated_minutes`/`matched_tag_codes` on `user_learning_path_item`, and making legacy `kind`
  nullable.

Both files are additive and idempotent: every `ALTER` is guarded by an `information_schema` check, so
they are safe to run twice.

## Preconditions

- A disposable MySQL 8.0.16+ server, isolated from the team database.
- A copy of the shared `jlas_schema.sql` baseline with any `CREATE DATABASE` / `USE` statement pointed
  at the disposable name (the plan requires this swap; never run the original `USE jlas` against a
  real database).
- The baseline schema is loaded first, so `user_learning_path` and `user_learning_path_item` exist in
  their pre-124 shape (legacy `kind`, no snapshot/phase columns).

## Commands

```bash
# 1) Disposable database, consistently targeted.
DB=jlas_verify_124
mysql -u root -p -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4;"

# 2) Load baseline with its CREATE/USE target swapped to $DB, then apply both migrations.
sed "s/^USE jlas;/USE $DB;/; s/CREATE DATABASE IF NOT EXISTS jlas/CREATE DATABASE IF NOT EXISTS $DB/" \
    jlas_schema.sql | mysql -u root -p "$DB"
mysql -u root -p "$DB" < db/migrations/JLAS2-124-learning-path-schedule.sql
mysql -u root -p "$DB" < db/migrations/JLAS2-124-learning-path-phases.sql

# 3) Idempotency: run each migration a second time and expect no errors.
mysql -u root -p "$DB" < db/migrations/JLAS2-124-learning-path-schedule.sql
mysql -u root -p "$DB" < db/migrations/JLAS2-124-learning-path-phases.sql
```

## Verification queries

```sql
-- New columns exist on the path table.
SELECT COLUMN_NAME, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'jlas_verify_124' AND TABLE_NAME = 'user_learning_path'
  AND COLUMN_NAME IN ('survey_id','assessment_attempt_id','assessment_revision',
                      'policy_version','minutes_per_day','coverage_gaps_json');

-- New columns exist on the item table.
SELECT COLUMN_NAME, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'jlas_verify_124' AND TABLE_NAME = 'user_learning_path_item'
  AND COLUMN_NAME IN ('phase','reason_code','day_number','estimated_minutes','matched_tag_codes');

-- Unique keys exist.
SELECT INDEX_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'jlas_verify_124' AND TABLE_NAME = 'user_learning_path'
  AND INDEX_NAME IN ('uk_learning_path_generation','uk_learning_path_user_version');

-- Legacy `kind` became nullable.
SELECT IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'jlas_verify_124' AND TABLE_NAME = 'user_learning_path_item'
  AND COLUMN_NAME = 'kind';
```

## Results checklist

| Step | Expected | Actual |
|---|---|---|
| Schema columns present (`user_learning_path`) | 6 rows, all `IS_NULLABLE = YES` (additive, legacy rows stay null) | ✅ 6 columns added, all `NULL` (query returned 11 rows = 6 path + 5 item) |
| Schema columns present (`user_learning_path_item`) | 5 rows, all `IS_NULLABLE = YES` | ✅ 5 columns added, all `NULL` |
| Unique keys | `uk_learning_path_generation` + `uk_learning_path_user_version` present | ✅ both present (6 `STATISTICS` rows = 4 columns + 2 columns) |
| `kind` nullable | `YES` | ✅ `MODIFY kind VARCHAR(20) NULL` applied |
| Second migration run | no error, no duplicate columns/keys | ✅ all statements returned `1 row(s) returned` = "already exists" branch |
| Legacy row read | pre-existing path/item rows keep `NULL` snapshot/phase columns | ✅ legacy `id=1` row has `survey_id`/`assessment_attempt_id`/`coverage_gaps_json` = NULL |
| Duplicate version precheck | inserting `(user_id, version)` duplicate is rejected by `uk_learning_path_user_version` | ✅ `Error 1062 Duplicate entry '1-2' for key 'uk_learning_path_user_version'` |
| New path round-trip | insert a path with both phases + coverage JSON, read it back intact | ✅ path `id=2` with `WARM_UP` (day 1) + `GOAL_LEARNING` (day 2) read back via JOIN (2 rows) |

## What "verified" requires

The checklist above is the recorded result of running the two migrations against the disposable
`jlas_verify_124` database: the columns and unique keys are present, a legacy row keeps `NULL`
snapshot/phase columns, the version unique key rejects a duplicate `(user_id, version)`, and a new
two-phase path round-trips (insert → read) with `phase`, `day_number`, `estimated_minutes`,
`matched_tag_codes`, and `coverage_gaps_json`.

Not exercised manually: the `uk_learning_path_generation` duplicate precheck. Its existence is
confirmed via `information_schema.STATISTICS`, and its enforcement is covered by the backend
idempotency and concurrency tests (`LearningPathApiTest.duplicateSameGenerationIdentityReusesExistingPathWithoutNewVersion`,
`LearningPathConcurrencyTest.concurrentCreateWithSameIdentityProducesOnePath`), which run on H2.

Applying these migrations to the shared team `jlas` database remains a team decision and is outside
this verification.
