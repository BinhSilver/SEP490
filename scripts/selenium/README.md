# Selenium full-spec verification

Read these local documents before running business journeys:

- `docs/testing/2026-10-03-selenium-db-bootstrap.md`
- `docs/testing/2026-10-03-full-spec-selenium-cases.md` (199 base cases, variants listed per case)
- `docs/testing/2026-10-03-selenium-execution-status.md`

These documents currently remain local because `docs/` is ignored. The catalogue defines planned
coverage; it does not imply all cases have automated implementations or have passed.

From the repository root:

```powershell
./scripts/selenium/run-smoke.ps1 -BaseUrl http://localhost:8080
```

Requires local Chrome, Java 21, Maven dependencies, and the running J-LAS app.
Uses Java Selenium/JUnit and the existing driver setup. ChromeDriver may need network access on
its first run. No DB reset or seed import is performed by this runner.

Smoke covers login rendering at desktop/mobile sizes and invalid-form feedback, without an account.
Authenticated suites use dedicated accounts via environment variables. Do not put passwords, JWTs,
cookies, raw request bodies or local configuration in step descriptions or artifacts.

Evidence is under `target/selenium-runs/`; Maven assertions also appear under `target/surefire-reports/`.
Preflight records BLOCKED_ENV if login is unreachable, leaving browser cases NOT_RUN. Setup failures
record a failed driver step; test assertions record action/expected/status/duration and a screenshot.
Each rerun uses a new evidence directory. Do not aggregate setup/teardown PASS entries as passing cases.

The ordinary Maven suite excludes the `e2e` tag. To select the smoke directly:

```powershell
./mvnw.cmd test '-Dtest=FullSpecSmokeE2ETest' '-De2e.excludedGroups='
```
