# Kế hoạch công việc 2026-09-22 — Hoàn thiện luồng Smart Test → Lộ trình học → Sách số hóa

> Nhánh làm việc: `feat/JLAS-W0090-smart-test-fix`. Người thực hiện: TNguyen47.
> Mục đích: chốt luồng end-to-end trước khi báo cáo mentor, đủ để review + PR + Jira theo quy trình team.

## 0. Tóm tắt 1 dòng cho mentor

Hoàn thiện luồng **learner làm Smart Test → hệ thống sinh lộ trình học 2 giai đoạn (ôn hổng + học theo mục tiêu) → học bằng bài từ sách số hóa qua lesson player**; phần còn lại hôm nay là **nối 3 mô-đun đã xong** + **validate E2E (W0099)**.

## 1. Hiện trạng (đã xác minh trong repo, không phải đoán)

| Mô-đun | Ticket | Trạng thái | Bằng chứng |
|---|---|---|---|
| Smart Test — review từng câu + giải thích | JLAS-W0090 | ✅ Xong | commit `172d3e1`, `ResultView.review` |
| Smart Test — breakdown theo grammar point + bài | JLAS-W0095 | ✅ Xong | commit `f605220`, `GrammarPointResultView` |
| Smart Test — knowledge vs usage (goal gating) | JLAS-W0103 | ✅ Xong | commit `39dd92c`, `9c36200`, `36cfadc` |
| Learning Path — selector 2 giai đoạn | JLAS2-123 | ✅ Xong | commit `68fb7b6` |
| Learning Path — lưu/đọc + migration | JLAS2-124 | ✅ Xong | commit `427586e`, `cb9466f` |
| Learning Path — QA backend + MySQL | JLAS2-127 | ✅ Xong | commit `7b8ea8b` |
| Learning Path — UI 2 giai đoạn | JLAS2-129 | ✅ Xong | commit `eb58bda` |
| Learning Path — sinh + xem (parent) | JLAS-W0108 | ✅ Xong | commit `8ec3830`, `323de6e` |
| Lesson player + progress | JLAS-W0119 / W0120 / W0121 | ✅ Xong | commit `45ba4ab`, `f9b6fd3`, `c927024` |
| Sách số hóa (nội dung bài) | JLAS2-20 / 22 | ✅ Đã merge | API `/api/dictionary/lessons/{id}/content` |
| **Nối luồng (adapter thật Smart Test → Learning Path)** | *(tích hợp, chưa có ticket riêng)* | ❌ **Chưa** | chỉ có `LocalLearningProfileSource` (demo) |
| **Per-tag aggregation cho Learning Path** | *(gap tích hợp)* | ❌ **Chưa** | Smart Test mới có per-skill + per-grammar-point, thiếu per-tag |
| **Validate E2E toàn luồng** | JLAS-W0099 | ❌ **Chưa chạy** | cần bằng chứng browser + API |
| **integration-contract.md** | (design JLAS2-123 yêu cầu) | ❌ **Thiếu file** | `docs/learning/` chưa có |

**Kết luận:** 3 mô-đun đã xong riêng lẻ. Công việc hôm nay = **đóng 3 gap tích hợp** (adapter, per-tag, trigger) rồi **chạy W0099** để có luồng thật, sau đó commit/PR theo rule.

## 2. Gap tích hợp cần đóng (đây là trọng tâm hôm nay)

1. **Per-tag aggregation.** Learning Path cần `TagAssessmentResult{tagCode, assessedCount, wrongCount}` (theo *knowledge tag* = tag bài, vd `N5-B07`). Smart Test hiện chỉ trả per-skill + per-grammar-point (`GrammarPointResultView.lessonCode` là mã bài của *mẫu*, không phải tag bài tổng hợp). → Cần Smart Test emit thêm breakdown per-tag, hoặc adapter roll-up từ `lessonCode`/`review` về tag. **Đây là việc phải chốt, không phải chỉ nối dây.**
2. **Production profile adapter.** Viết `SmartTestLearningProfileSource` (impl `LearningProfileSource`) đọc `assessmentAttemptId` thật → `LearningProfileSnapshot` (ownership, finalized, assessedLevel, targetLevel, goalCode, minutesPerDay, tagResults). `LocalLearningProfileSource` giữ làm demo local-seed, không dùng production.
3. **Trigger + CTA.** Kết quả Smart Test → nút "Tạo lộ trình" → gọi `POST /api/learning/paths` (endpoint đã có). Idempotency đã chốt: retry cùng identity trả `200` path cũ, attempt mới → version mới.

## 3. Kế hoạch chi tiết theo thời gian

### Buổi sáng (9:00 – 12:00) — ổn định + đối chiếu contract

- [ ] **9:00** Xác nhận baseline: `git status` sạch (chỉ còn 3 file đã biết: `jlas_schema.sql` line-ending, `TagRepository.java` fix bean, `application.yaml` local). App boot qua `ddl-auto: validate`.
- [ ] **9:15** Đồng bộ DB local với `jlas_schema.sql` (chạy câu `ALTER` còn thiếu: `uk_flashcard_session_creation`, `idx_flashcard_session_history`, cập nhật `chk_flashcard_session_type` thêm `SPEED_RECALL`) — xong phần đang dở từ sáng.
- [ ] **9:30** Đối chiếu contract: đọc `SmartTestResponse.ResultView` thực tế ↔ `LearningProfileSnapshot` cần gì. Viết `docs/learning/integration-contract.md` (design JLAS2-123 đã yêu cầu): liệt kê field mapping + 2 ví dụ input/output, ghi rõ **per-tag aggregation lấy từ đâu**.
- [ ] **10:30** Chốt cách lấy per-tag: (a) Smart Test emit thêm `List<TagResultView>` per knowledge tag, hay (b) adapter roll-up từ `grammarPoints.lessonCode` + `review`. Ghi quyết định vào contract. *(Nếu chưa chắc, đưa ra 2 phương án để hỏi mentor/team — không tự chọn ngầm.)*
- [ ] **11:00** Bắt tay viết adapter thật `SmartTestLearningProfileSource` + test contract (ownership, chưa finalized, sai user → 403, thiếu revision).

### Buổi chiều (13:00 – 17:00) — đóng gap + nối luồng

- [ ] **13:00** Hoàn thiện per-tag aggregation phía Smart Test (theo quyết định 10:30) + test.
- [ ] **14:00** Nối trigger: result page → CTA "Tạo lộ trình" → `POST /api/learning/paths`; verify idempotency (retry không sinh version mới).
- [ ] **14:30** Catalog mapping: xác minh `JpaLearningCatalogSource` chỉ lấy lesson **published** từ sách số hóa (tag, level, duration, order), không lấy DRAFT.
- [ ] **15:00** Lesson link: path item → lesson player (W0119/120/121) + `/api/dictionary/lessons/{id}/content`; không render link chết (rule JLAS2-129).
- [ ] **15:30** Chạy `mvn test` + `node --test src/test/frontend/learning-path.test.cjs` + `git diff --check`.

### Cuối ngày (17:00 – 18:00) — W0099 E2E + bàn giao

- [ ] **17:00** W0099 E2E thật trên browser: survey → Smart Test → result → tạo path → reload giữ nguyên 2 phase → mở lesson → progress; chụp bằng chứng.
- [ ] **17:30** Commit từng phần theo W-code, push nhánh, mở PR (base `develop`).
- [ ] **17:45** Tóm tắt tiến độ + việc cần chốt để báo cáo mentor.

## 4. Phân công theo W-code (bản đồ task → việc hôm nay)

| Ticket | Việc hôm nay | Đầu ra |
|---|---|---|
| JLAS-W0095 | Thêm per-tag aggregation (nếu chọn phương án a) | field + test trong `SmartTestResponse` |
| JLAS2-124 (tích hợp) | `SmartTestLearningProfileSource` + bỏ demo khỏi production | impl + contract test |
| JLAS2-123 (tích hợp) | `integration-contract.md` + mapping | doc + 2 ví dụ |
| JLAS-W0108 (tích hợp) | Trigger CTA + nối result→path | endpoint được gọi thật |
| JLAS2-129 (tích hợp) | Lesson link đến player thật | không link chết |
| JLAS-W0099 | E2E toàn luồng + bằng chứng | browser/API evidence |
| *(mới)* | Adapter thật + per-tag | xem mục 2 |

## 5. Nguyên tắc team / git / chuyên nghiệp (nhắc lại, bắt buộc tuân thủ)

- **Nhánh:** chỉ làm trên feature branch (`feat/JLAS-W0090-smart-test-fix`), **không commit/push/merge vào `develop`**; thay đổi lên develop qua PR do maintainer merge. Pull từ develop thì được.
- **Commit:** tiếng Anh, **1 Jira ID / commit**, dạng `fix(JLAS-W####): ...` hoặc `feat(JLAS2-###): ...`; **không kèm `Co-Authored-By`**.
- **Schema:** sửa trực tiếp `jlas_schema.sql` kèm note `[JLAS-XXXX]`; feature **chưa hoàn thiện → để migration riêng `sql/migrations/NNN_*.sql`**, chỉ gộp vào schema chung khi hoàn thiện; seed data để file riêng.
- **Bí mật:** `application-local.yaml` (password DB/mail/API key), `db/local/` là gitignored — **tuyệt đối không push**. `application.yaml` đang có sửa local (2 dòng trắng) — không commit.
- **Không đụng task người khác** trừ khi cần; làm từng phần nhỏ, chạy web xác nhận từng phần.
- **`ddl-auto: validate`**: mọi entity được `@EntityScan` đều phải có bảng/cột, kể cả tính năng chưa dùng — migration phải apply đủ trước khi start.

## 6. Định nghĩa hoàn thành (Definition of Done — hôm nay)

1. Learner làm survey → Smart Test → kết quả **thật** (không demo) tạo được lộ trình 2 giai đoạn.
2. Adapter production đọc đúng ownership/finalized/revision; sai user → 403; chưa finalized → 409.
3. Retry cùng identity không sinh version mới; attempt mới SUPERSEDE path cũ.
4. Path item trỏ đúng lesson **published**, mở được lesson player + nội dung sách số hóa.
5. `mvn test` + JS test + `git diff --check` xanh; có bằng chứng browser cho W0099.
6. Commit theo W-code, push, PR mở (base `develop`), không có file bí mật nào trong diff.

## 7. Rủi ro & việc cần chốt với mentor/team

- **Per-tag aggregation nguồn ở đâu** (Smart Test emit thêm field vs adapter roll-up) — ảnh hưởng phạm vi W0095, cần chốt sớm.
- **Trigger tự động vs người dùng bấm** — đã chốt "bấm tạo" theo design 2026-09-15, giữ nguyên.
- **Scope chương trình JLPT** (sách nào làm curriculum cho GOAL_LEARNING) — design ghi do `LearningScopeSource` resolve, production impl là dependency ngoài; nếu chưa có, hôm nay dùng local-seed scope cố định và **ghi rõ giới hạn** khi báo cáo.
- **Jira tenant đang `suspended-inactivity`** (`twg` không fetch được) — tiến độ Jira cập nhật thủ công qua web nếu cần, không chặn việc code.
