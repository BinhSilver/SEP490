# JLAS2-124 — Learning Path API

Create and read a two-phase learning path (WARM_UP then GOAL_LEARNING). The path is generated
server-side from a finalized assessment profile, scheduled into daily buckets, and persisted with
a monotonically increasing version.

Base path: `/api/learning`. All endpoints require an authenticated `UserPrincipal`.

## Endpoints

### `POST /api/learning/paths`

Create a path for the current user. The request references only the finalized assessment attempt;
the server never trusts a `userId` or assessment evidence from the body.

Request body:

```json
{ "assessmentAttemptId": 91 }
```

Responses:

| Status | Meaning |
|---|---|
| `201 Created` | A new path was generated and persisted. |
| `200 OK` | Idempotent retry: same generation identity, returns the existing path (no new version). |
| `400 Bad Request` | `VALIDATION_FAILED` — missing/blank attempt, invalid levels/budget/revision, or invalid tag evidence. |
| `401 Unauthorized` | No authenticated user. |
| `403 Forbidden` | The profile belongs to a different user. |
| `404 Not Found` | The user does not exist, or the goal curriculum has no published lessons. |
| `409 Conflict` | Survey incomplete or Smart Test not finalized. |
| `503 Service Unavailable` | Profile/scope source not configured, or the goal has no selection policy yet. |

Generation identity: `(userId, assessmentAttemptId, assessmentRevision, policyVersion)`. A retry with
the same identity returns the existing path (`created=false`). A new attempt/revision creates a new
version and marks the previous `ACTIVE` path `SUPERSEDED`.

### `GET /api/learning/paths/current`

Returns the current `ACTIVE` path.

| Status | Meaning |
|---|---|
| `200 OK` | The active path snapshot. |
| `404 Not Found` | No path has been generated yet. |
| `409 Conflict` | The path predates the two-phase schedule (legacy) and must be regenerated. |

## Response shape

```json
{
  "id": 1,
  "userId": 7,
  "goal": "JLPT",
  "targetLevel": "N4",
  "currentLevel": "N4",
  "minutesPerDay": 30,
  "status": "ACTIVE",
  "version": 1,
  "createdAt": "2026-09-15T14:00:00",
  "phases": [
    {
      "phase": "WARM_UP",
      "lessonCount": 1,
      "totalMinutes": 15,
      "dayCount": 1,
      "items": [
        {
          "lessonId": 11,
          "title": "Lesson PARTICLE",
          "textbookId": 1,
          "unitId": 10,
          "level": "N5",
          "reasonCode": "WEAK_TAG",
          "reasonText": "Review weak knowledge tags: PARTICLE.",
          "matchedTagCodes": ["PARTICLE"],
          "dayNumber": 1,
          "orderIndex": 1,
          "estimatedMinutes": 15,
          "completed": false
        }
      ]
    },
    {
      "phase": "GOAL_LEARNING",
      "lessonCount": 1,
      "totalMinutes": 20,
      "dayCount": 1,
      "items": [
        {
          "lessonId": 21,
          "title": "Lesson TARGET",
          "textbookId": 2,
          "unitId": 20,
          "level": "N4",
          "reasonCode": "GOAL_TARGET",
          "reasonText": "Study the selected JLPT curriculum from N4 to N4.",
          "matchedTagCodes": ["TARGET"],
          "dayNumber": 2,
          "orderIndex": 2,
          "estimatedMinutes": 20,
          "completed": false
        }
      ]
    }
  ],
  "coverageGaps": [
    { "tagCode": "UNKNOWN", "reasonCode": "INSUFFICIENT_ASSESSMENT" }
  ],
  "totalLessons": 2,
  "totalMinutes": 35,
  "totalDays": 2,
  "completedLessons": 0
}
```

`dayNumber` is global (phases are sequential); `dayCount` per phase is the number of distinct days
in that phase. `coverageGaps` uses `INSUFFICIENT_ASSESSMENT` (too few questions) and
`MISSING_WARM_UP_RESOURCE` (no published warm-up lesson matches a weak tag).

## Differences from the old bundle

- `POST` no longer takes a goal/level body; it takes the finalized attempt only.
- Creation is idempotent on the generation identity instead of always creating a new version.
- `GET` returns two phases with schedule/reason/coverage snapshots; legacy paths without a schedule
  are rejected with a clear `409`.
