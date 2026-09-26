# Lesson Quiz + Warm-up Gate (JLAS-W0113)

Thiết kế để hoàn thiện yêu cầu "đầu ra của learning path phải cần trải qua nhiều quiz
để đạt target" và "sau khi warm-up xong phải vượt test mới qua giai đoạn theo mục tiêu".

## 1. Lesson Quiz (mỗi bài học có một quiz)

- **Nguồn câu hỏi:** ngân hàng `AssessmentQuestion`, lọc theo `knowledge_tag.code == lesson.knowledgeTag.code`.
  Repository: `AssessmentQuestionRepository.findByIsActiveTrueAndKnowledgeTagCodeOrderByIdAsc`.
- **Giới hạn:** 5 câu (`LESSON_QUIZ_SIZE`).
- **Chấm điểm:** so khớp `selectedOption == correctOption` bằng answer key (AI **không** quyết định đúng/sai).
  `selectedOption == null` = bỏ qua câu, tính là sai.
- **Ngưỡng đạt:** 60% (`LESSON_QUIZ_PASS_PERCENT`). Đạt → gọi `completeLesson` (ghi `lesson_progress`).
- **API:**
  - `GET  /api/learning/lessons/{lessonId}/quiz`
  - `POST /api/learning/lessons/{lessonId}/quiz/submit`
- **DTO:** `LessonQuizResponse` (không lộ đáp án), `LessonQuizSubmitRequest`, `LessonQuizResult` (kèm review + giải thích từng câu).
- **UI:** `/learning/lesson/{lessonId}/quiz` (template `learning/lesson-quiz.html`, JS `lesson-quiz.js`).
  Nút "Kiểm tra" thay cho nút "Hoàn thành" cũ trên trang lộ trình.

## 2. Warm-up Gate (test tổng kết trước khi mở khóa GOAL_LEARNING)

- **Điều kiện xuất hiện:** giai đoạn `WARM_UP` hoàn thành 100% **và** `gate_passed_at IS NULL`.
- **Nguồn câu hỏi:** gom các `matched_tag_codes` của tất cả item `WARM_UP` (chính là các tag yếu đã ôn),
  rồi lấy câu từ ngân hàng theo `knowledgeTagCode IN (...)`.
  Repository: `findByIsActiveTrueAndKnowledgeTagCodeInOrderByIdAsc`. Giới hạn 10 câu (`GATE_QUIZ_SIZE`).
- **Chấm điểm:** giống lesson quiz, ngưỡng 60%. Đạt → ghi `user_learning_path.gate_passed_at = now()`.
- **API:**
  - `GET  /api/learning/paths/gate-quiz`
  - `POST /api/learning/paths/gate-quiz/submit`
- **Response path:** `LearningPathResponse` thêm `warmUpCompleted` + `gatePassed`. Khi `gateRequired`
  (warmUpCompleted && !gatePassed), FE khóa toàn bộ ngày `GOAL_LEARNING` và hiện banner
  "Kiểm tra tổng kết khởi động".
- **Schema:** thêm cột `user_learning_path.gate_passed_at DATETIME(6) NULL`.
  Migration: `sql/migrations/004_learning_path_gate.sql`.

## 3. Chiến lược tag giữa hai giai đoạn (trả lời câu hỏi thiết kế)

- **WARM_UP — điều phối bằng TAG.** Item được chọn theo `weakTags` (tag yếu từ Smart Test), lý do
  `WEAK_TAG`, lưu `matched_tag_codes`. Xem `LearningResourceSelectionServiceImpl.select`.
- **GOAL_LEARNING — điều phối bằng THỨ TỰ GIÁO TRÌNH, KHÔNG dùng tag.** Item `GOAL_TARGET` được sắp
  theo `level → textbook order → unit order → lesson order` (hàm `candidates(...)`).
  Tag chỉ còn dùng để ghi nhận `matched_tag_codes` (chẩn đoán/ôn lại), không phải để xếp lịch.

## 4. Điểm cần chú ý khi chạy local

- `ddl-auto: validate` → phải chạy migration `004_learning_path_gate.sql` trước khi start.
- `application.yaml` là sửa local, không commit; secrets nằm ở `application-local.yaml` (gitignored).
