# Post-RC Database Convergence — Manual Owner Checklist

> **Owner:** User/manual database operator after the Interactive Learning V2 RC is accepted.
>
> **Do not execute during RC development.** Freeze schema and seed changes first. This checklist is deliberately
> excluded from the AI RC implementation plan.

## Outcome

After the V2 branch is accepted and prepared for merge:

1. One canonical full-install schema: `jlas_schema.sql`.
2. One V2 rollup upgrade migration from the target branch's last released schema to the final V2 schema.
3. One canonical application-content seed: `db/seed_data_jlas.sql`.
4. One canonical MySQL database used by the merged application.
5. No V2 test-user/runtime data copied from `jlas_v2` into the canonical database.
6. Schema and seed remain separate files, per `AGENTS.md`.

“One database” means one database after code convergence. Continue using `jlas_v2` in isolation while branch schemas
are different.

## Non-negotiable safety gate

Before squashing migrations, prove whether any V2 migration has been applied to a shared/team/staging database.

- If **none** of the feature migrations has escaped the private branch, use Path A below and squash them.
- If **any** feature migration has been applied to a shared environment, use Path B. Never delete or rewrite applied
  migration history.
- Importing the final `jlas_schema.sql` into the disposable local `jlas_v2` database does not count as applying the
  incremental migrations to a shared environment.

Record this decision in the final handoff before changing files.

## Freeze point

Do this only after:

- the RC AI has completed every task and committed the final handoff;
- full tests pass;
- no further entity/schema/seed changes are planned;
- the final target branch and its latest migration list are known;
- a backup of every database containing valuable data exists.

Do not merge branch code and improvise database consolidation simultaneously.

## Step 1 — Inventory and backup

Record:

- target branch/commit;
- V2 branch/commit;
- target branch's last migration number;
- every V2 migration added after that base;
- whether each migration ran anywhere outside a disposable database;
- which database contains user data that must survive.

Create a consistent backup of the database that must survive. Example template:

```powershell
mysqldump --single-transaction --routines --triggers -u <user> -p <database> > <backup-file>.sql
```

Do not place passwords in shell history, committed scripts or documentation.

Current pre-RC reference at `a59eb30`:

- target branch `feat/JLAS-W0108-mp-baseline`: migrations `001–009`;
- V2 branch: migrations `010–015`;
- RC may add `016+`; recalculate after RC rather than relying on this snapshot.

## Step 2 — Choose the convergence database

### Preserve existing user data

Use the current main/team database as the data source of truth:

1. Restore its backup into a temporary integration database.
2. Apply the final rollup upgrade migration to that copy.
3. Validate and run the application against the copy.
4. Only then schedule the same upgrade for the canonical database.

Never merge runtime rows from `jlas_v2` into this database. Accounts, attempts, progress, rewards and certificates in
`jlas_v2` are disposable test evidence.

### No existing data needs preservation

Create a fresh canonical database from:

1. final `jlas_schema.sql`;
2. final `db/seed_data_jlas.sql`;
3. real user registration through the application.

Do not import E2E users/attempts into the canonical database.

## Step 3 — Migration consolidation

### Path A — V2 migrations never ran on a shared database (recommended current path)

1. Determine the next free migration number on the final target branch. Do not assume it remains `010` if the target
   branch gained migrations during RC development.
2. Create one rollup file named like:

```text
sql/migrations/NNN_interactive_learning_v2_rollup.sql
```

3. The rollup upgrades exactly from the target branch's released schema to the final V2 schema.
4. Build it from the reviewed final schema diff and dependency order, not by blindly concatenating files.
5. Preserve this dependency order:
   - parent/version columns and package table;
   - activity membership;
   - study session/card and quiz snapshot changes;
   - grammar progress;
   - dialogue progress;
   - package completion/score;
   - Reading/Listening RC tables/columns/constraints;
   - indexes, checks and foreign keys after required columns/tables exist.
6. Remove the superseded unshared feature migration files from the final tree in the same consolidation commit.
7. Keep Git history; do not rewrite already-shared commits merely to hide that intermediate files once existed.
8. Update docs to say the rollup is the only upgrade artifact supported from that target baseline.

### Path B — One or more V2 migrations already ran on a shared database

1. Keep every applied migration unchanged and in the repository.
2. Add only a new forward migration for differences introduced after the last applied migration.
3. Do not renumber, squash or delete the historical files.
4. “Clean” in this path means `jlas_schema.sql` is canonical and the migration ledger is accurate; it does not mean one
   physical migration file.

Using Path A after a migration escaped to a shared database will make future upgrades ambiguous and is prohibited.

## Step 4 — Canonical full schema

`jlas_schema.sql` is the only schema source for fresh installation. Verify it contains the final definitions after RC:

- all tables and columns;
- enum/check values for every V2 activity and quiz type;
- unique keys, indexes and foreign keys;
- correct nullability/defaults;
- MySQL-compatible types and constraint order;
- no seed inserts or user data.

Do not combine `jlas_schema.sql` and the rollup migration. They serve different starting states:

- `jlas_schema.sql`: empty database → final schema;
- rollup migration: released baseline database → final schema.

## Step 5 — Seed consolidation

Keep exactly one canonical application-content seed:

```text
db/seed_data_jlas.sql
```

Consolidate only real reference/content data needed by the application:

- roles required for registration/auth;
- taxonomy/tag/outcome data;
- textbooks, units, lessons and sections;
- grammar, vocabulary, senses, glosses, Kanji/readings and mappings;
- content/dialogue/audio metadata;
- active assessment questions needed by Smart Test and Reading/Listening;
- required relation tables in foreign-key order.

Do not merge `db/2026-09-27-w0132-e2e-seed.sql` into the canonical seed. It contains synthetic survey/assessment/session
rows for E2E and would violate the real-account rule. Either keep it clearly labeled as a test fixture if tests still
reference it, or remove it only after proving no script/test/docs consumer remains.

Seed cleanup rules:

1. Remove duplicate canonical IDs and duplicate normalized identities.
2. Preserve vocabulary normalization: `katakana → hiragana(NFKC(text))`, retaining `ー` and `・`.
3. Resolve all foreign keys before insertion; order parent tables before child tables.
4. Keep active/published/reviewed status explicit. Do not mass-publish draft AI content.
5. Do not include credentials, real users, auth tokens, attempts, progress, rewards, certificates or PII.
6. Treat the canonical seed as fresh-install/test-content material. Its current delete-and-reinsert behavior makes it
   unsafe for a live database containing user-created or moderated content.
7. For a database with valuable data, deliver content corrections through a reviewed forward data migration/upsert,
   not by rerunning the destructive canonical seed.

## Step 6 — Two-path validation

Both installation paths must produce equivalent schemas.

### Fresh-install validation

1. Create an empty temporary database.
2. Import final `jlas_schema.sql`.
3. Import final `db/seed_data_jlas.sql`.
4. Start the application with `ddl-auto=validate`.
5. Run readiness audit and the manual JLPT V2 smoke flow.

### Upgrade validation

1. Restore a database at the exact target-branch baseline.
2. Apply the single rollup migration (Path A) or remaining forward migrations (Path B).
3. Do not run the destructive canonical seed over valuable data.
4. Start the application with `ddl-auto=validate`.
5. Run the same readiness and smoke checks.

Compare schema dumps after removing volatile properties such as `AUTO_INCREMENT` counters. Tables, columns, types,
defaults, indexes, checks and foreign keys must match.

## Step 7 — Application and data acceptance

With the integrated code and database:

1. Run full Maven and Node suites.
2. Start once with V2 flag `false`; legacy must still work.
3. Start again in the test environment with V2 flag `true`.
4. Register a real account through the application.
5. Complete Smart Test and create a V2 path.
6. Exercise Vocabulary, Kanji, Grammar, Dialogue, Reading and Listening.
7. Verify retries, cooldown, resume, completion popup, summary and certificate.
8. Run the final read-only data-readiness audit.
9. Keep levels without complete canonical coverage blocked.

Do not call the database ready merely because schema import and H2 tests pass.

## Step 8 — Converge local configuration and retire the disposable DB

Only after the integrated database passes acceptance:

1. Point the merged checkout's ignored `application-local.yaml` to the canonical database.
2. Keep credentials local and uncommitted.
3. Take a final backup.
4. Archive the V2 worktree after all commits are integrated and no process uses it.
5. Drop `jlas_v2` only when no debugging or evidence depends on it.

Do not point both divergent pre-merge branches at the canonical database. Database convergence follows code/schema
convergence, not the other way around.

## Acceptance checklist

- [ ] RC schema is frozen and final commit recorded.
- [ ] Shared-environment migration history audited.
- [ ] Correct Path A or Path B selected with evidence.
- [ ] Valuable database backed up and restore tested.
- [ ] One canonical `jlas_schema.sql` imports into empty MySQL.
- [ ] One supported V2 rollup exists if Path A is valid.
- [ ] One canonical application-content seed exists.
- [ ] E2E fake users/attempts are outside the canonical seed.
- [ ] Fresh-install and baseline-upgrade schemas match.
- [ ] Application starts with `ddl-auto=validate`.
- [ ] Full tests and real-account JLPT V2 smoke flow pass.
- [ ] Readiness audit identifies releaseable/blocked levels truthfully.
- [ ] Feature flag stays off by default until acceptance passes.
- [ ] `jlas_v2` is retired only after successful convergence.
