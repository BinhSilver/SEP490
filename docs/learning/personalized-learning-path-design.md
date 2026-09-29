# Thiết kế lộ trình cá nhân hóa theo hai giai đoạn

Ngày: 2026-09-15. Parent: JLAS-W0108. Child: JLAS2-123, JLAS2-124, JLAS2-127, JLAS2-129.

## Nguồn yêu cầu

Mô tả mới của người dùng trong cuộc hội thoại là nguồn yêu cầu nghiệp vụ. Tài liệu JLAS2-28 cũ chỉ tham khảo rule, cách làm và thông tin task. Đây là thiết kế đề xuất để lập kế hoạch, chưa phải code đã triển khai hoặc trạng thái Jira đã được xác nhận.

## Yêu cầu đã xác nhận

- Survey đi trước Smart Test, cung cấp nhu cầu và target.
- Khi Smart Test có kết quả, tạo một lộ trình gồm khởi động ôn kiến thức hổng rồi học kiến thức phục vụ nhu cầu/target.
- Khởi động ưu tiên tag có kết quả sai nhiều.
- Du lịch cần cấu trúc theo tình huống, câu và mẫu câu đơn giản; sách chỉ là một phần nguồn học. Thiết kế chi tiết và triển khai du lịch ngoài đợt này.
- Smart Test/survey và số hóa sách chưa hoàn thiện. Module phải kiểm thử độc lập và có ranh giới tích hợp rõ ràng.

## Cách tiếp cận

Chọn quy tắc xác định, kiểm thử được: profile -> xác định tag yếu -> chọn tài nguyên hai giai đoạn -> xếp lịch -> lưu -> hiển thị. Tái sử dụng phần phù hợp của bundle. Không chép lại selector cũ như toàn bộ nghiệp vụ mới.

Hai phương án khác chưa chọn: viết lại toàn bộ module làm mất phần persistence đã kiểm thử; dùng AI quyết định toàn bộ path làm tăng phụ thuộc và khó chứng minh tag/target được bao phủ. Đợt này không cần hai phương án đó.

## Ranh giới tích hợp

1. `LearningProfileSource` tải snapshot server-side theo user/attempt, kiểm tra ownership, survey và kết quả đã chấm xong. Smart Test chịu trách nhiệm chấm điểm và quy tắc tổng hợp theo tag, gồm câu bỏ trống, câu nhiều tag và điểm một phần.
2. `LearningCatalogSource` trả ứng viên cho tag/level và chương trình đích. Implementation hiện tại đọc JPA. Số hóa sách chịu trách nhiệm nội dung, trạng thái xuất bản, tag, thời lượng và thứ tự.
3. `LearningResourceSelectionService` tạo danh sách bài có giai đoạn và lý do; không ghi DB.
4. `LearningService` điều phối, chia ngày, lưu version và đọc DTO; không tự chấm Smart Test.

Thêm catalog port vì selector mới cần truy vấn kiến thức nền và chương trình mục tiêu độc lập, không còn giới hạn mọi thứ vào một textbook ID do Smart Test cung cấp. Không tạo microservice, HTTP catalog riêng hoặc plugin framework.

`SmartTestSkillResult` hiện chỉ tổng hợp theo skill, chưa đủ cho tag yếu. Adapter thật phải nhận tổng hợp theo tag từ Smart Test hoặc mapping được phía assessment thống nhất. Không lấy điểm skill làm điểm tag.

## Dữ liệu nội bộ đề xuất

- `LearningProfileSnapshot`: userId, assessmentAttemptId, surveyReference, assessmentRevision, surveyCompleted, assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay, tagResults.
- `TagAssessmentResult`: tagCode, assessedCount, wrongCount. Mẫu này áp dụng bài được chấm đúng/sai; điểm một phần phải thống nhất trước khi kết nối, không làm tròn ngầm.
- `LearningResourceCandidate`: lessonId, textbookId, unitId, tagCodes, level, estimatedMinutes, unitOrder, lessonOrder, published.
- `SelectedLearningResource`: candidate, phase, reasonCode, reasonText, matchedTagCodes.
- `LearningSelectionResult`: orderedResources, coverageGaps, policyVersion.
- `LearningPhase`: WARM_UP, GOAL_LEARNING. Phase không đồng nghĩa với lesson_type hoặc kind.

Mã mục tiêu IT không được tự đồng nhất với BA/WORK trong bundle. Đợt đầu đề xuất hoàn thiện policy JLPT; mục tiêu chưa được hỗ trợ trả lỗi rõ ràng, không tự rơi về JLPT. Contract giữ goalCode để bổ sung policy IT/du lịch sau khi có đặc tả.

## Chính sách đề xuất cần chốt trước khi code selector

Các giá trị sau là đề xuất bản đầu, không phải yêu cầu đã được người dùng xác nhận:

- Tag yếu: assessedCount >= 3 và wrongCount / assessedCount >= 0.5. Ưu tiên tỷ lệ sai giảm dần, số sai giảm dần, tagCode tăng dần. Đưa ngưỡng vào cấu hình có validation.
- Tag không đủ bằng chứng được ghi là thiếu bằng chứng, không gắn nhãn đã thành thạo. Cần phân biệt snapshot hoàn toàn thiếu kết quả tag với kết quả đầy đủ nhưng không có tag yếu.
- Khởi động lấy một bài nền phù hợp cho mỗi tag yếu trước; lesson có thể đáp ứng nhiều tag. Chọn theo level phù hợp rồi thứ tự catalog ổn định, cho phép thấp hơn assessed level. Không đặt trần số tag/bài tùy ý.
- Giai đoạn mục tiêu lấy chương trình JLPT được catalog xác định cho khoảng assessed-to-target; có thể gồm kiến thức cùng level mà Smart Test chưa kiểm tra. Không coi chưa kiểm tra là đã biết.
- Không lặp lessonId trong cùng path. Nếu bài đã ở khởi động thì loại khỏi phần mục tiêu; ghi coverage để tránh hiểu là bị bỏ sót. Thứ tự tiên quyết có dữ liệu sẽ được ưu tiên hơn thứ tự mức yếu; hiện dùng thứ tự chương trình của catalog, chưa xây prerequisite graph.
- Đề xuất tự tạo sau khi kết quả được lưu/chấm hoàn tất. Không tự mang cờ pathAccepted từ tài liệu cũ sang. Trigger và yêu cầu xác nhận cần được chốt trước khi nối upstream.
- Đề xuất chống tạo trùng theo (userId, attemptId, assessmentRevision, policyVersion); retry cùng input trả cùng path. Replan và tạo lại theo catalog thay đổi nằm ngoài đợt này.
- Lộ trình thiếu bài cho một tag yếu vẫn có thể lưu nếu còn chương trình mục tiêu, nhưng phải lưu/hiện coverageGaps và không tuyên bố bao phủ đầy đủ. Thiếu toàn bộ chương trình mục tiêu trả NOT_FOUND, giữ path cũ.
- Không có tag yếu sau đánh giá đầy đủ: bỏ qua khởi động, hiển thị lý do. Nếu mọi bài mục tiêu đã nằm ở khởi động, không lặp bài chỉ để có đủ hai danh sách; hiện rõ kiến thức mục tiêu đã được bố trí.
- Bắt đầu giai đoạn mục tiêu vào ngày mới. Bài dài hơn budget chiếm riêng một ngày. Đây là lịch dự kiến, không phải cơ chế khóa/mở bài hoặc bắt buộc hoàn thành khởi động.

## Lưu trữ và API

Giữ POST /api/learning/paths với assessmentAttemptId làm tham chiếu; nhận user từ CurrentUser. GET /api/learning/paths/current chỉ đọc path của user hiện tại. Khi có upstream, gọi cùng service sau khi kết quả đã commit; mở trang GET hoặc refresh không tạo path.

Path lưu tham chiếu survey/assessment revision, policyVersion, budget, version và trạng thái. Item lưu phase, thứ tự, ngày, thời lượng, reasonCode/reasonText và tag đã khớp. Coverage gaps được lưu cùng path bằng field JSON có định dạng được tài liệu hóa để reload không tự mất cảnh báo. LessonProgress vẫn là nguồn tiến độ duy nhất.

DTO trả phases, totals, items và coverageGaps từ dữ liệu đã lưu. Phase không có bài phải có lý do rõ ràng. Không hardcode số liệu, không lấy lại kết quả assessment hiện tại để tái tạo path khi GET.

Migration additive: bổ sung schedule theo file cũ và các field mới. Legacy item chưa có phase không được gán thành khởi động bằng suy đoán; API trả trạng thái legacy cần tạo lộ trình mới. Không tự migrate dữ liệu trên DB dùng chung.

## Kiểm thử và mức hoàn thành

Mỗi task có test trước triển khai. Test profile dùng URL H2 in-memory, create-drop và secret test riêng, không phụ thuộc application-local. Demo sử dụng local-seed rõ ràng và database riêng. Adapter demo không chứng minh ownership/assessment thật.

Hoàn thiện module: selector hai giai đoạn, API/persistence, UI, test H2/JS và demo MySQL có bằng chứng. Hoàn thiện tích hợp parent: thêm adapter thật, catalog thật, trigger kết quả và browser end-to-end. Definition of Done theo team còn gồm push/PR/review/Jira; kết quả local không tự thay thế các bước đó.

## Ngoài phạm vi

OCR/số hóa; xây survey/Smart Test; policy du lịch; chương trình IT chi tiết chưa đặc tả; lesson player/quiz; cập nhật progress; unlock bài; replan; rewards; thay thiết kế shared shell.
