# Learning flow SQL

[JLAS-W0108] Owner decision 2026-11-01: the whole Learning flow (learning survey + Smart Test, the W0108 learning
path, goal curriculum and goal survey, Learning-owned holding tables, and the new jpath) keeps **one schema file and
one data file**. Learning tables that a later task needs go into these files, never into `sql/migrations/` or
`sql/seeds/`, so they can be merged into the team database later.

| File | Content |
|---|---|
| `learning_schema.sql` | Part A: every Learning table, final shape, `CREATE TABLE IF NOT EXISTS`. Part B: guarded upgrades (columns, indexes, FKs, CHECKs, Learning columns on shared tables) for older databases. Part C: jpath tables. |
| `learning_data.sql` | Every Learning seed row, upserted. Generated blocks sit between `-- BEGIN <source> SHA256 <hash>` and `-- END <source>`; the jpath block sits between `-- BEGIN GENERATED jpath` and `-- END GENERATED jpath`. |

Neither file has a `USE` line. `jlas_schema.sql` still carries the Learning tables for the team schema; both
describe the same shape.

## Run order

Back up the target database first. Then, on the database you mean to change:

```bash
mysql -u root -p <db> -e "SELECT DATABASE()"                 # must print <db>
mysql -u root -p <db> < sql/learning/learning_schema.sql      # 1. schema (safe to repeat)
mysql -u root -p <db> < sql/learning/learning_data.sql        # 2. data, after the shared base seeds (safe to repeat)
```

Run the schema before the data, and run the data after `jlas_schema.sql` and the shared base seeds
(`seed_data_jlas.sql` and the other base seeds; the data checks the base textbook identities and stops if they differ).
Never use `--force`.

## Rebuilding generated blocks

Edit the source (`data/learning-owned/learning-content.json`, or the compiled sources under `data/learning-path/`),
then re-run the builder that owns the block; each builder rewrites only its block of `learning_data.sql` and has a
`--check` mode:

```bash
node tools/learning-data-extract/build_communication_path_seed.mjs [--check]
node tools/learning-data-extract/build_jlas_compiled_books_sql.mjs [--check]
node tools/learning-data-extract/build_jlas_store_pool_sql.mjs [--check]
node tools/learning-data-extract/build_minna_listening_sql.mjs . [--check]
node tools/learning-data-extract/build_minna_reading_sql.mjs [--check]
node tools/learning-data-extract/build_sekai_n5_comprehension_sql.mjs [--check]
node tools/learning-data-extract/build_speed_n3_comprehension_sql.mjs [--check]
node tools/learning-data-extract/build_speed_n5_n4_reading_sql.mjs [--check]
node tools/learning-data-extract/verify_learning_sql.mjs          # header, markers, safety rules, block hashes, size cap
```

`learning_schema.sql` is maintained by hand: add a new table to Part A or C, and add every later column, index, FK or
CHECK change to Part B through its guarded helper so that a second run changes nothing.
