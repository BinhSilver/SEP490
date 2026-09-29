# JLAS2-127 — Backend QA Test Matrix

Requirement → test → expected → actual. Every row is a backend behavior of the two-phase
learning path (selector, scheduler, API, transaction, concurrency) that has an automated test.

**Verified on 2026-09-15** with `mvn test` on the isolated H2 test profile (`application-test.yaml`),
which cancels the `local` MySQL include. Result: **57 tests run, 0 failures, 0 errors** across the
learning module plus the application context-load test. No real assessment or catalog data is used;
all input is synthetic fixtures, which the plan requires not to be presented as real evidence.

## 1. Selection — weak-tag boundaries and evidence

| # | Requirement | Test (file) | Expected | Actual |
|---|---|---|---|---|
| 1 | Weak tag = `assessedCount >= 3` AND `wrongCount/assessedCount >= 0.5` | `respectsSampleAndRatioBoundaries` (`LearningResourceSelectionServiceTest`) | `2,2`/`3,1`/`0,0` → not weak; `4,2`/`3,2` → weak | ✅ |
| 2 | Minimum sample `0/0` is not weak and is reported as insufficient evidence | `respectsSampleAndRatioBoundaries` | `0,0` → GOAL only, `INSUFFICIENT_ASSESSMENT` gap | ✅ |
| 3 | Fully assessed tag with no wrong answers is not weak | `fullyAssessedProfileWithoutWeakTagsStartsAtGoalLearning` | `5,0` → GOAL_LEARNING only, no gaps | ✅ |
| 4 | Thresholds configurable and part of policy identity | `thresholdsAreConfigurableAndIncludedInPolicyIdentity` | relaxed vs stricter yield different selection and `policyVersion` | ✅ |
| 5 | Invalid policy config rejected | `rejectsInvalidPolicyConfiguration` | `0`/`3,0`/`3,-0.1`/`3,1.1` → `IllegalArgumentException` | ✅ |
| 6 | Priority = error ratio, then wrong count, then stable tag code | `prioritizesErrorRatioThenWrongCountThenStableTagCode` | order `13,12,11,21` | ✅ |
| 7 | Warm-up chosen below assessed level is allowed | `selectsWeakFoundationsBeforeGoalLessonsEvenBelowAssessedLevel` | weak `PARTICLE` (N5) precedes goal (N4) | ✅ |
| 8 | One lesson covering multiple weak tags appears once | `oneLessonCanCoverMultipleWeakTagsWithoutDuplicatingIt` | single warm item, `matchedTagCodes` = both tags | ✅ |
| 9 | Lesson shared by both phases appears only in WARM_UP | `lessonSharedByBothPhasesAppearsOnlyInWarmUp` | shared lesson → WARM_UP, no goal duplicate | ✅ |
| 10 | Deterministic order regardless of source order | `catalogOrderIsDeterministicRegardlessOfSourceOrder` | reversed input still yields `21,22,23` | ✅ |
| 11 | Unassessed / unknown tags become coverage gaps, not invented lessons | `missingWarmUpResourcesAreReportedWithoutInventingLessons` | `MISSING` → `MISSING_WARM_UP_RESOURCE` gap | ✅ |
| 12 | Partial coverage: weak tag with no warm-up lesson | `missingWarmUpResourcesAreReportedWithoutInventingLessons` | gap recorded, goal still scheduled | ✅ |
| 13 | Unknown/above-target levels ignored | `ignoresUnknownLevelsAndLessonsAboveTarget` | N3 / `UNKNOWN` / null level excluded | ✅ |
| 14 | Missing goal curriculum → NOT_FOUND | `noGoalResourcesIsNotFoundEvenWhenWarmUpExists` | `NOT_FOUND` even with a valid warm-up | ✅ |
| 15 | Invalid lesson duration fails rather than scheduling impossible | `selectedLessonWithInvalidDurationFailsInsteadOfMakingAnImpossibleSchedule` | `CONFLICT` for `estimatedMinutes <= 0` | ✅ |
| 16 | Unsupported goal never falls back to JLPT | `unsupportedGoalsNeverFallBackToJlpt` | TRAVEL / IT / BA → `SERVICE_UNAVAILABLE` | ✅ |
| 17 | Explicit goal curriculum scope required | `requiresExplicitGoalCurriculumScope` | empty/`0`/duplicate scope → `VALIDATION_FAILED` | ✅ |

## 2. Selection — ownership and validation

| # | Requirement | Test (file) | Expected | Actual |
|---|---|---|---|---|
| 18 | Profile owned by another user is rejected | `rejectsForeignProfile` (`LearningResourceSelectionServiceTest`) | `FORBIDDEN` | ✅ |
| 19 | Anonymous caller rejected | `rejectsAnonymousCaller` | `UNAUTHORIZED` | ✅ |
| 20 | Survey incomplete or results not finalized | `requiresCompletedSurveyAndFinalResults` | `CONFLICT` for either | ✅ |
| 21 | Missing tag results are not treated as mastery | `rejectsMissingTagResultsInsteadOfTreatingThemAsMastery` | empty / null tags → `CONFLICT` | ✅ |
| 22 | Invalid / duplicate tag evidence | `rejectsInvalidAndDuplicateTagEvidence` | wrong>assessed, negative, blank, duplicate → `VALIDATION_FAILED` | ✅ |
| 23 | Budget / levels / revision validated | `validatesBudgetLevelsAndRevision` | `0`/`181` min, unknown level, blank revision → `VALIDATION_FAILED` | ✅ |
| 24 | Result lists are not mutable by callers | `resultDoesNotExposeMutableSelectionLists` | `UnsupportedOperationException` on `clear()` | ✅ |

## 3. Catalog

| # | Requirement | Test (file) | Expected | Actual |
|---|---|---|---|---|
| 25 | Only published lessons in published textbooks are read | `readsOnlyPublishedLessonsInsideTheRequestedPublishedTextbooks` (`LearningCatalogSourceTest`) | DRAFT lesson/textbook excluded | ✅ |
| 26 | Secondary knowledge tags included; situation tags and duplicates excluded | `includesSecondaryKnowledgeTagsButNotSituationTagsOrDuplicates` | exact tag set, no dupes | ✅ |
| 27 | Empty scope returns no catalog | `emptyScopeReturnsNoCatalog` | empty list | ✅ |
| 28 | Full flow from persisted catalog yields two phases | `selectsBothPhasesFromPersistedCatalogAndSecondaryTagEvidence` | WARM_UP then GOAL_LEARNING | ✅ |
| 29 | Spring config overrides weak-tag defaults | `springConfigurationOverridesWeakTagDefaults` | `3,3` at threshold 0.75/4 → not weak | ✅ |

## 4. Scheduling

| # | Requirement | Test (file) | Expected | Actual |
|---|---|---|---|---|
| 30 | Budget 30 packs warm-up `[10,20,45]` + goal `[15,15]` into days `1,1,2,3,3` | `packsWarmUpThenGoalIntoNewDayAndKeepsLongLessonsWhole` (`LearningScheduleServiceTest`) | days `1,1,2,3,3` | ✅ |
| 31 | Goal starts a new day even when warm-up day has room | `goalLearningStartsANewDayEvenWhenWarmUpDayHasRoom` | days `1,2` | ✅ |
| 32 | No warm-up starts at day 1 | `noWarmUpStartsAtDayOne` | days `1,1` | ✅ |
| 33 | Empty resources → empty schedule | `emptyResourcesProduceEmptySchedule` | empty | ✅ |
| 34 | Phase / reason / matched tags preserved | `preservesPhaseReasonAndMatchedTags` | WARM_UP + WEAK_TAG + tags | ✅ |
| 35 | Non-positive budget rejected | `rejectsNonPositiveBudget` | `VALIDATION_FAILED` | ✅ |

## 5. API, ownership, and snapshot semantics

| # | Requirement | Test (file) | Expected | Actual |
|---|---|---|---|---|
| 36 | Create + read two phases with correct schedule/reasons/totals | `createsAndReadsTwoPhasesWithDailySchedule` (`LearningPathApiTest`) | WARM_UP day 1, GOAL day 2, totals match | ✅ |
| 37 | Duplicate generation identity reuses path without a new version | `duplicateSameGenerationIdentityReusesExistingPathWithoutNewVersion` | `created=false`, same id, still v1 | ✅ |
| 38 | New attempt supersedes previous ACTIVE path | `newAttemptSupersedesPreviousActivePath` | v2 ACTIVE, old path SUPERSEDED | ✅ |
| 39 | Foreign profile rejected | `rejectsProfileOwnedByAnotherUser` | `FORBIDDEN` | ✅ |
| 40 | Non-finalized assessment rejected | `rejectsNonFinalizedAssessment` | `CONFLICT` | ✅ |
| 41 | **Ownership**: user A's real path is not leaked to user B (`GET` → 404) | `currentDoesNotLeakAnotherUsersPath` | user B GET → `NOT_FOUND` | ✅ |
| 42 | Stored schedule is a snapshot — catalog duration changes do not rewrite it | `reloadKeepsStoredScheduleWhenCatalogDurationChanges` | reload still 15 min after catalog changed to 99 | ✅ |
| 43 | New version does not reset lesson progress | `newVersionDoesNotResetLessonProgress` | completed flag survives v2 | ✅ |
| 44 | Missing profile/scope source degrades to 503 | `LearningSourceAvailabilityTest` (2 tests) | `SERVICE_UNAVAILABLE` | ✅ |

## 6. Concurrency and transaction integrity

| # | Requirement | Test (file) | Expected | Actual |
|---|---|---|---|---|
| 45 | Two concurrent creates with the same identity produce exactly one path | `concurrentCreateWithSameIdentityProducesOnePath` (`LearningPathConcurrencyTest`) | 2 results, exactly one `created=true`, 1 path, 1 ACTIVE | ✅ |
| 46 | Item-persist failure rolls back the whole path and keeps the old ACTIVE path | `itemFailureRollsBackWholePathAndKeepsOldActivePath` (`LearningPathRollbackTest`) | no partial path/items, old ACTIVE survives | ✅ |

## 7. Profile source contract (production adapter hook)

| # | Requirement | Assertion (`LearningProfileSourceContractTest`) | Expected |
|---|---|---|---|
| 47 | Adapter returns an owned, complete snapshot for a known attempt | `assertLoadsOwnAttempt` | non-null, `userId`/`attemptId` match, all fields populated |
| 48 | Adapter never leaks a foreign attempt | `assertDoesNotLeakForeignAttempt` | null or owned-by-requested-user |
| 49 | Adapter reports unknown attempts as absent | `assertUnknownAttemptAbsent` | null |

Rows 47–49 are the reusable contract that the production assessment adapter (wired in when Smart Test
hands off) will run against real data; there is no production adapter to exercise yet, so they carry no
"actual" column.

## Notes

- The old baseline of 39 tests is **not** reused as new evidence; each row above is a fresh assertion
  against the two-phase design.
- Browser evidence (empty/error/retry, session expiry, responsive layout, cross-user isolation) is added
  in JLAS2-129 after the UI exists.
- MySQL migration verification is tracked separately in `mysql-verification.md`.
