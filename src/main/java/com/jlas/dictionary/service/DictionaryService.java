package com.jlas.dictionary.service;

import com.jlas.dictionary.dto.LessonContentResponse;
import com.jlas.dictionary.dto.LessonSummaryResponse;
import com.jlas.dictionary.dto.TextbookResponse;
import com.jlas.dictionary.dto.TextbookUnitsResponse;
import com.jlas.dictionary.dto.TopicLessonsResponse;
import com.jlas.dictionary.dto.TopicResponse;

import java.util.List;

/**
 * Service xử lý nghiệp vụ tra cứu từ điển và duyệt sách đã số hoá.
 * <p>
 * Quyền xem nội dung số hoá được quyết định ở tầng service qua {@code CurrentUser}:
 * CONTENT_CREATOR (vai biên soạn, không phải ADMIN) thấy mọi trạng thái; mọi vai khác và người chưa đăng nhập chỉ thấy status = PUBLISHED.
 * Nội dung learner không được xem trả NOT_FOUND (không lộ tồn tại).
 * TODO: Thêm các phương thức search, lookup, favorite...
 */
public interface DictionaryService {

    /**
     * Danh sách giáo trình kèm số unit; learner chỉ thấy giáo trình PUBLISHED.
     */
    List<TextbookResponse> listTextbooks();

    /**
     * Giáo trình kèm toàn bộ unit và bài học; learner chỉ thấy giáo trình PUBLISHED
     * và trong đó chỉ các bài PUBLISHED.
     *
     * @param textbookId id giáo trình
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu không có hoặc learner không được xem
     */
    TextbookUnitsResponse getTextbookUnits(Long textbookId);

    /**
     * Danh sách chủ đề học (tag tag_type = SITUATION) kèm số bài người dùng hiện tại được xem.
     * Chủ đề 0 bài vẫn có mặt trong danh sách (lessonCount = 0).
     */
    List<TopicResponse> listTopics();

    /**
     * Bài học của một chủ đề, lọc theo quyền xem; kèm ngữ cảnh giáo trình/unit.
     * Chủ đề tồn tại nhưng không có bài được xem → trả về lessons rỗng (200), KHÔNG phải NOT_FOUND.
     *
     * @param code mã tag (ví dụ TOPIC-DAILY)
     * @throws com.jlas.common.exception.ApiException NOT_FOUND chỉ khi tag không tồn tại hoặc không phải SITUATION
     */
    TopicLessonsResponse getTopicLessons(String code);

    /**
     * Đổi trạng thái duyệt của giáo trình (CONTENT_CREATOR — quyền chặn ở controller bằng @PreAuthorize).
     * Chuyển về DRAFT KHÔNG đổi trạng thái các bài bên trong: bật lại giáo trình là mọi thứ như cũ.
     *
     * @param status DRAFT / PUBLISHED / ARCHIVED (đã validate ở DTO)
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu không có giáo trình
     */
    TextbookResponse updateTextbookStatus(Long textbookId, String status);

    /**
     * Đổi trạng thái duyệt của bài học (CONTENT_CREATOR — quyền chặn ở controller bằng @PreAuthorize).
     * Không cho PUBLISHED một bài khi giáo trình chứa nó chưa PUBLISHED (bài mồ côi learner không vào được).
     *
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu không có bài;
     *         CONFLICT nếu công bố bài trong giáo trình chưa công bố
     */
    LessonSummaryResponse updateLessonStatus(Long lessonId, String status);

    /**
     * Nội dung đầy đủ một bài học: từ vựng kèm audio 6 giọng, câu ví dụ kèm audio,
     * mẫu ngữ pháp, hội thoại kèm vai diễn và cụm tách nghĩa.
     * Learner chỉ xem được bài PUBLISHED thuộc giáo trình PUBLISHED.
     *
     * @param lessonId id bài học
     * @return nội dung bài học
     * @throws com.jlas.common.exception.ApiException NOT_FOUND nếu không có bài hoặc learner không được xem
     */
    LessonContentResponse getLessonContent(Long lessonId);
}
