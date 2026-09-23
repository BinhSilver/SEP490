# Integration Contract — Smart Test → Learning Path

> Mục đích: chốt mapping dữ liệu **thật** từ Smart Test (module `assessment`) sang
> `LearningProfileSnapshot` (module `learning`), để adapter production
> `SmartTestLearningProfileSource` được viết không mơ hồ, thay cho
> `LocalLearningProfileSource` (demo).
> Nguồn chuẩn: `docs/learning/personalized-learning-path-design.md` mục "Ranh giới tích hợp".

## 1. Ranh giới trách nhiệm (theo design)

- **Smart Test** chịu trách nhiệm chấm điểm và **quy tắc tổng hợp theo tag** (gồm câu bỏ trống, câu nhiều tag, điểm một phần).
- **Learning Path** đọc snapshot server-side, kiểm tra ownership, survey + kết quả đã chấm xong; không tự chấm lại Smart Test.
- Adapter **không** lấy điểm skill làm điểm tag; không nhận userId/assessment evidence từ request body.

## 2. Field mapping

`LearningProfileSnapshot(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted, assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay, tagResults)`

| Field snapshot | Nguồn Smart Test | Ghi chú |
|---|---|---|
| `userId` | `AssessmentAttempt.user.id` | ownership |
| `assessmentAttemptId` | `AssessmentAttempt.id` | |
| `surveyId` | `UserLearningSurvey.userId` | 1:1 với user (PK) |
| `assessmentRevision` | hằng `"smart-test-v1"` (xem §4) | |
| `surveyCompleted` | `UserLearningSurvey.completedAt != null` | |
| `assessmentFinalized` | `AssessmentAttempt.status == "SUBMITTED"` | `finishAttempt` set SUBMITTED |
| `assessedLevel` | `SmartTestSession.estimatedOverallLevel` | chỉ set khi SUBMITTED |
| `targetLevel` | `SmartTestSession.targetLevel` | fallback `UserLearningSurvey.targetLevel` |
| `goalCode` | `SmartTestSession.goal` | `JLPT`/`BA`/`TRAVEL`/`DAILY_LIFE` |
| `minutesPerDay` | `SmartTestSession.dailyStudyMinutes` | |
| `tagResults` | per-tag aggregation (§3) | `List<TagAssessmentResult>` |

## 3. Per-tag aggregation

**Nguồn:** `AssessmentAnswer` (câu GRAMMAR) → `AssessmentQuestion.grammarPoint` (`grammar_point_id`) → `GrammarPoint.lesson` → `Lesson.knowledgeTag` (tag bài `N5-Bxx`). Lưu ý `AssessmentQuestion.knowledgeTag` giữ tag **mục tiêu** (JLPT/BA/TRAVEL/DAILY_LIFE) — **không** dùng để gom tag bài.

**Quy tắc:**
- Group theo tag bài đằng sau grammar point: `answer.question.grammarPoint.lesson.knowledgeTag.code` (vd `N5-B14`).
- Chỉ câu GRAMMAR đã gắn `grammar_point_id` vào đúng lesson mới đóng góp; câu GRAMMAR chưa map (NULL) và câu skill khác (VOCAB/KANJI/READING/LISTENING chưa có link lesson) **không** vào per-tag.
- `assessedCount` = số đáp án đã trả lời của tag đó.
- `wrongCount` = số đáp án `isCorrect == false`.
- Chỉ tính câu đã trả lời (`selectedOption != null`); câu bỏ trống **không** tính vào assessed/wrong.
- Mỗi câu góp vào đúng 1 tag bài (qua grammar point chính).

**Quyết định — chọn (a):**
- **(a) Smart Test sở hữu aggregation.** Thêm method vào assessment service, vd
  `AssessmentService.tagResults(Long attemptId) : List<TagAssessmentResult>`, adapter
  `SmartTestLearningProfileSource` gọi method này rồi ghép với session/survey thành snapshot.
  Ưu: một nguồn chấm điểm duy nhất, edge case xử lý một lần.
- **(b) Adapter tự roll-up** từ `AssessmentAnswer` — **không chọn**: vi phạm ranh giới
  (design giao quy tắc tổng hợp cho Smart Test), dễ duplicate logic chấm điểm.

## 4. `assessmentRevision`

Smart Test **không re-score cùng một attempt** (kết quả tính một lần lúc SUBMITTED). Do
`attemptId` đã nằm trong generation identity `(userId, attemptId, assessmentRevision,
policyVersion)`, revision đặt hằng `"smart-test-v1"`. Attempt mới → attemptId mới → version mới,
SUPERSEDE path cũ (đã chốt 2026-09-15).

## 5. Rules ownership / state (mã HTTP)

| Tình huống | Kết quả |
|---|---|
| `attempt.user.id != currentUser` | `403 Forbidden` |
| survey chưa complete (`completedAt == null`) | `409 Conflict` |
| attempt chưa `SUBMITTED` | `409 Conflict` |
| không có attempt/session | `404 Not Found` |
| `goalCode` ngoài policy được hỗ trợ (đợt đầu chỉ JLPT) | `503 Service Unavailable` (do selector) |

## 6. Ví dụ

**Input:** attemptId=91 (user 7), goal=JLPT, target=N4, selfReported=N5,
dailyStudyMinutes=30, 10 câu đã trả lời (không bỏ trống).

Aggregation theo tag:
- PARTICLE: 3 câu, 2 sai → `{PARTICLE, 3, 2}`
- VERB: 2 câu, 1 sai → `{VERB, 2, 1}`
- VOCAB: 5 câu, 0 sai → `{VOCAB, 5, 0}`

**Output snapshot:**
```json
{
  "userId": 7, "assessmentAttemptId": 91, "surveyId": 7,
  "assessmentRevision": "smart-test-v1",
  "surveyCompleted": true, "assessmentFinalized": true,
  "assessedLevel": "N4", "targetLevel": "N4", "goalCode": "JLPT",
  "minutesPerDay": 30,
  "tagResults": [
    { "tagCode": "PARTICLE", "assessedCount": 3, "wrongCount": 2 },
    { "tagCode": "VERB", "assessedCount": 2, "wrongCount": 1 },
    { "tagCode": "VOCAB", "assessedCount": 5, "wrongCount": 0 }
  ]
}
```

**Ví dụ 2 (bỏ trống):** 10 câu, 2 bỏ trống → chỉ 8 câu tính. Tag X: 8 câu, 1 sai
→ `{X, 8, 1}`. Bỏ trống không xuất hiện trong aggregation, không gây chia sai.

## 7. Ghi chú ngoài phạm vi

- `FROM_ZERO` → không test → **không có attempt** → không tạo path từ Smart Test; cần trigger
  riêng (đã ngoài đợt này, ghi trong design "Ngoài phạm vi").
- `TRAVEL`/`DAILY_LIFE` goal-gating đã có ở Smart Test (W0103), nhưng selector learning path
  đợt đầu chỉ hỗ trợ JLPT → contract giữ `goalCode` nguyên để thêm policy sau.
