# Interactive Learning Path V2 — RC manual UAT checklist [JLAS-W0193]

> For the user to run by hand, after their own MySQL migration/reseed and with
> `learning.interactive-v2.enabled=true` in a non-production environment only. This checklist assumes the
> content-readiness audit (`sql/audits/2026-09-29-interactive-v2-content-readiness-audit.sql`) has already been
> run against the real database and the target JLPT level is marked `RELEASABLE` — running this checklist
> against a level with `QUOTA_AT_RISK_*` or `GOAL_TARGET_CONTENT_MISSING` will surface content gaps, not
> feature bugs. Use the tester's own real account (never a demo/seed account) per this project's own standing
> rule. Report the exact step, URL and screenshot/console error for anything that does not match "Expected".

## 0. Setup

1. Confirm `learning.interactive-v2.enabled=true` is set (env `LEARNING_INTERACTIVE_V2_ENABLED=true`) in the
   environment under test only — never in a shared/production config.
2. Confirm the tester's account has a **finalized** Smart Test attempt (`assessment_attempt.status = 'FINALIZED'`
   or the app's finalize flow completed) for a JLPT level/goal the readiness audit marked `RELEASABLE`.
3. Open browser dev tools (Network + Console tabs) before starting, and keep them open throughout — several
   steps below ask you to check for unexpected requests or console errors, not just visible UI state.

## 1. Create a real V2 path from a finalized Smart Test

- Steps: log in as the tester → complete/finalize a Smart Test attempt if not already done → navigate to
  `/learning/path` (or wherever the app offers "Tạo lộ trình học") → create a path.
- Expected: a new `INTERACTIVE_V2` path is created; the roadmap page (`/learning/path` or the V2 roadmap view)
  shows an ordered list of day packages, the first one `AVAILABLE` or `IN_PROGRESS`, every later one `LOCKED`.
- Expected: creating a path a second time immediately after (refresh, or click "create" again if reachable)
  does not create a second path — the same path/id is returned (idempotent by generation identity).

## 2. Observe correct 0/4/6/8 comprehension allocation

- Steps: inspect the created path's early warm-up packages (Warm-up B). If the tester's Smart Test showed a
  Reading or Listening skill score in one of the bands (`>70%→0`, `(50,70]→4`, `(40,50]→6`, `[0,40]→8`
  exercises), a Reading and/or Listening warm-up activity should appear with that many exercise groups.
- Expected: **at most two comprehension exercise groups per day**, combining Reading and Listening.
- Expected: if the tester's Smart Test had **no** Reading/Listening questions at all (or too few to be
  reliable), no comprehension activity is silently invented — the roadmap must not show a 0%-derived weakness
  where the real state is "not enough evidence yet" (`NEEDS_VERIFICATION`).
- Expected: opening the activity, "Vì sao tôi học nội dung này" (or equivalent provenance text) names the
  skill, the evidence band, and the exercise count — never a bare, unexplained number.

## 3. Complete Reading and Listening exercises, including fail 1/2 and fail 3 cooldown/review

- Steps: open a Reading activity (`/learning/v2/reading?activity={id}`) → read the passage → open the
  micro-check → answer to intentionally score **below 70%** on the first attempt.
- Expected: the result screen shows which questions were wrong (server-graded), and lets you retry
  immediately (attempt 2 of 3) — no cooldown yet.
- Steps: fail attempt 2 as well (still below 70%).
- Expected: still no cooldown after the 2nd failure; retry (attempt 3 of 3) is still immediately available.
- Steps: fail attempt 3 (the 3rd consecutive failure in this cycle).
- Expected: a **60-minute cooldown** now appears (a countdown or an explicit "come back after HH:MM"), and
  a **review requirement** — re-open the passage and scroll/read to the end (or, for Listening, play the audio)
  before a new cycle can start. Confirm attempting to submit again immediately (if the UI allows it) is refused
  server-side, not merely hidden by the UI.
- Steps: perform the review action (reach the end of the passage / play the audio), then either wait out the
  60 minutes or come back later.
- Expected: after both the review evidence AND the cooldown expire, a fresh cycle (3 more attempts) opens.
- Steps: repeat for a Listening activity (`/learning/v2/listening?activity={id}`), same expectations, using
  "play the audio" as the review evidence instead of "reach the end of the passage".
- Steps: on a fresh attempt, answer every question correctly (≥70%).
- Expected: the activity passes; if it was the only remaining unresolved activity in its exercise group, the
  whole activity moves toward `PASSED`; the next unlocked activity in the day package becomes available.

## 4. Resume after reload and verify no duplicate activities/attempts/rewards

- Steps: mid-way through a Vocabulary or Kanji study session (some cards flipped/remembered, not all), reload
  the page (`F5`/refresh).
- Expected: the same open study session resumes — previously flipped/remembered cards are still marked, not
  reset; no new session/attempt row is silently created.
- Steps: mid-way through a micro-check (issued but not yet submitted), reload the page.
- Expected: re-opening returns to a sane state — either the same issued attempt is still valid, or the app
  cleanly re-issues without ever double-crediting a previous attempt.
- Steps: immediately after a package completes (its 3-action popup — "Trở về lộ trình" / "Học vượt" /
  "Nghỉ ngơi" — appears), reload the page or navigate back to the same package.
- Expected: the popup does **not** reappear automatically on a normal revisit (it is a one-time,
  request-scoped `justCompleted` signal) — the completed package instead shows its own settled/completed view.
- Expected (credits/streak): check the credit/streak UI (or ask for the ledger) before and after this reload —
  the package-completion reward must be granted exactly once, not once per reload/duplicate request.

## 5. Finish target package and verify popup actions

- Steps: complete all four activities (Vocabulary, Kanji, Grammar, Dialogue) of a `GOAL_LEARNING` package.
- Expected: the 3-action completion popup appears exactly once, showing the real computed score
  (`round((vocabularyScore + kanjiScore + grammarScore) / 3)`, never a hard-coded number) — cross-check against
  the actual micro-check results if in doubt.
- Steps: click "Học vượt" (skip ahead).
- Expected: only navigates to the immediate next package if it is already unlocked (`AVAILABLE`); it must
  never open a package further ahead, and must never appear enabled when there is no next package.
- Steps: click "Trở về lộ trình" and "Nghỉ ngơi" (on separate visits) instead.
- Expected: both simply return to / stay on the roadmap without side effects; neither claims a reward twice.

## 6. Finish path and verify summary/certificate wording

- Steps: complete every `GOAL_LEARNING` package in the path (warm-up packages are prerequisites only and do
  not block this).
- Expected: the path itself transitions to completed; the last package's popup routes to
  `GET /learning/path/v2/summary` instead of the normal 3-action popup ("Xem tổng kết chặng").
- Steps: open the summary page.
- Expected: shows first/latest/best per skill (Vocabulary/Kanji/Grammar), a Dialogue completed/total count
  (never a percentage), and both a Smart-Test-baseline comprehension card AND (if any Reading/Listening was
  actually attempted in-path) a separate in-path comprehension card — the two must never be merged or averaged.
- Expected: readiness confidence shown is `LOW` or `MEDIUM`, **never `HIGH`** (this RC's disclosed, deliberate
  cap) — if you see `HIGH` anywhere, that is a bug, report it.
- Steps: open `GET /learning/v2/certificate`.
- Expected: the certificate is titled literally **"Chứng nhận hoàn thành lộ trình J-LAS"** and at no point
  claims or implies an official JLPT pass/certification.

## 7. Verify Mock Test button enabled only when the owner destination exists

- Steps: on the summary page, locate the Mock Test ("Thi thử JLPT") card/button.
- Expected (default, no real Mock Test module wired up): the button is **visibly disabled** with an honest
  label (e.g. "Thi thử JLPT (sắp có)") — it must not link anywhere, must not be clickable, and must not throw a
  broken-link/404 experience if clicked.
- Expected: if `learning.mock-test.destination-url` / `approved-origins` are deliberately configured for this
  test to a real internal route or an approved origin, the button becomes enabled and its `href`/target exactly
  matches the server-provided URL — the browser must never compose or guess this URL itself. Do not configure
  this against an arbitrary external URL; only an internal route or a pre-approved origin is accepted, and
  anything else is silently rejected back to disabled by the server.

## 8. Desktop/mobile, keyboard, focus, reduced motion, missing-media behavior

- Desktop: verify the Reading/Listening/Dialogue/Vocabulary/Kanji/Grammar pages render with the approved
  light-tech purple/lime, spacious, mostly borderless layout at a normal desktop width.
- Mobile: resize to a phone width (or use device emulation). Verify no horizontal scroll, touch targets are
  comfortably tappable (~44px), and the passage/audio controls remain usable.
- Keyboard only: tab through a micro-check (issue → answer each question → submit) using only Tab/Shift+Tab/
  Enter/Space — verify every control is reachable, has a visible focus ring, and nothing requires a mouse.
- Reduced motion: enable "prefers-reduced-motion" (OS-level or browser dev tools emulation) and reload a study
  page — verify any animation/transition is removed or minimized, without breaking layout or functionality.
- Missing-media: if reachable, find or construct a Listening activity whose canonical audio URL is temporarily
  unavailable (e.g. blocked at the network level) — verify the player shows an explicit "audio unavailable"
  state and does **not** allow marking the exercise complete without ever having had real audio.
- Screen reader spot-check (optional but recommended): with a screen reader on, verify the passage, question
  options, and result feedback are all announced meaningfully (not just "button", "button", "button").

---

Report results as a plain pass/fail list against the numbered steps above, with the exact URL, account, and
screenshot/console output for any step that did not match its "Expected" line. See the final RC completion
handoff (`2026-09-29-interactive-learning-v2-rc-final-completion-handoff.md`) for what remains before the
feature flag can be safely turned on for real users.
