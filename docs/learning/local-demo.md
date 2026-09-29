# Local demo — two-phase learning path

The `local-seed` profile activates demo adapters that let the flow run end-to-end before the survey
and Smart Test modules are delivered. It is **explicitly local-only**; the profile source fabricates a
finalized assessment profile from the authenticated user instead of reading a real assessment.

## What `local-seed` wires

- `LocalLearningProfileSource` — builds a demo profile: `goalCode=JLPT`, assessed level from the
  user's `japanese_level` (default `N4`), target one level up, `minutesPerDay=30`, and two demo weak
  tags (`PARTICLE` 4/5 wrong, `VERB` 2/5 wrong). Attempt id comes from the request, revision is
  `local-demo-v1`.
- `LocalLearningScopeSource` — reads the curriculum scope from configuration:

  ```yaml
  jlas:
    learning:
      local-seed:
        warm-up-book-ids: 1        # published textbook(s) for warm-up lessons
        goal-book-ids: 2           # published textbook(s) for goal lessons
  ```

The textbooks must already be seeded (published, with `jlpt_level`, units and published lessons whose
knowledge tags include `PARTICLE`/`VERB` for warm-up matching). The demo does **not** seed the DB.

## Run

1. Start the app against a separate demo database with the demo profile:

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local-seed,local
   ```

   `application-local.yaml` holds the datasource URL/credentials (git-ignored); point it at a
   disposable DB, never the team's shared `jlas_schema.sql` database.

2. Log in with a real account (any existing auth flow), then create the path:

   ```bash
   curl -X POST http://localhost:8080/api/learning/paths \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -d '{"assessmentAttemptId": 1}'
   ```

3. Read it back:

   ```bash
   curl http://localhost:8080/api/learning/paths/current -H "Authorization: Bearer <token>"
   ```

Repeating the same `POST` returns `200` with the same path (idempotent); a different
`assessmentAttemptId` creates a new version and supersedes the old one.

## Limits

- The demo profile is fabricated — it is not proof that the flow works with a real survey or
  Smart Test. Production adapters stay a dependency outside this task.
- Without `local-seed`, `POST /api/learning/paths` returns `503` (no profile/scope source) rather
  than failing to start, so the rest of the app boots normally.
