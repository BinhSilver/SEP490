package com.jlas.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
    EMAIL_UNVERIFIED(HttpStatus.UNAUTHORIZED, "Email is not verified"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    AICALL_JAPANESE_REQUIRED(HttpStatus.BAD_REQUEST, "AI Call chỉ nhận tiếng Nhật. Vui lòng nhập bằng Hiragana/Katakana hoặc viết câu Kanji kèm kana."),
    AICALL_STAGE_LOCKED(HttpStatus.FORBIDDEN, "Bạn cần hoàn thành các màn trước để mở màn này."),
    AICALL_FREE_CHAT_NEW_DAY(HttpStatus.CONFLICT, "Đã sang ngày mới, chuyển sang cuộc trò chuyện hôm nay."),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa. Vui lòng liên hệ quản trị viên."),
    /** Video Shadowing có nhãn độ tuổi mà tài khoản chưa có ngày sinh: trang hiện hộp nhập ngày sinh. */
    BIRTH_DATE_REQUIRED(HttpStatus.FORBIDDEN, "Vui lòng nhập ngày sinh để xem video có giới hạn độ tuổi."),
    /** Người xem chưa đủ tuổi tối thiểu của video Shadowing (video.min_age). */
    AGE_RESTRICTED(HttpStatus.FORBIDDEN, "Bạn chưa đủ tuổi để xem video này."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    PENDING_VERIFICATION(HttpStatus.CONFLICT, "Email is pending verification"),
    CONTENT_BLOCKED(HttpStatus.BAD_REQUEST, "Content does not meet community guidelines"),
    CONTENT_PENDING_REVIEW(HttpStatus.BAD_REQUEST, "Content requires review before publishing"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INSUFFICIENT_CREDIT(HttpStatus.PAYMENT_REQUIRED, "Not enough credits"),
    /** Hết lượt tra từ nhanh: mua thêm lượt bằng CP hoặc nâng cấp Pro. */
    QUICK_LOOKUP_EXHAUSTED(HttpStatus.PAYMENT_REQUIRED, "Bạn đã hết lượt tra từ nhanh."),
    /** Hết lượt chấm phát âm miễn phí hôm nay: dùng CP hoặc nâng cấp Pro. */
    PRONUNCIATION_QUOTA_EXCEEDED(HttpStatus.PAYMENT_REQUIRED, "Bạn đã hết lượt chấm phát âm miễn phí hôm nay."),
    /** [JLAS-W0108] F36: Hết lượt AI giải thích câu hỏi Learning: mua thêm bằng CP hoặc nâng cấp Pro. */
    JPATH_AI_EXPLAIN_EXHAUSTED(HttpStatus.PAYMENT_REQUIRED, "Bạn đã hết lượt AI giải thích câu hỏi."),
    CPATH_WEEK_TEST_REQUIRED(HttpStatus.CONFLICT, "Bạn đã học xong phần của tuần này. Làm kiểm tra tuần (đạt từ 80%) để học sang tuần sau."),
    CPATH_AHEAD_LIMIT(HttpStatus.CONFLICT, "Bạn đã học trước trọn 1 tuần. Sang tuần mới học tiếp và kiểm tra phần đã học trước nhé."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload quá lớn"),
    /** [JLAS2-XX] Ảnh tải lên sai định dạng hoặc nội dung không phải ảnh thật (magic bytes không khớp). */
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "Ảnh chỉ nhận định dạng JPG, PNG hoặc WEBP"),
    /** [JLAS2-17] Ảnh bài đọc: DB chưa chạy migration 022 (chưa có bảng reading_image_override). */
    READING_IMAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Chức năng ảnh bài đọc chưa được bật trên cơ sở dữ liệu này"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "External service error"),
    TARGET_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Mục tiêu này đang được phát triển"),
    TARGET_LEVEL_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY,
            "Mục tiêu vượt quá khoảng cách phù hợp với trình độ hiện tại"),
    KANA_CHALLENGE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Chưa có từ vựng đã duyệt phù hợp để tạo thử thách. Bạn hãy quay lại sau nhé."),
    KANA_CHALLENGE_LEVEL_INVALID(HttpStatus.BAD_REQUEST, "Cấp độ thử thách chỉ nhận N5, N4, N3, N2, N1 hoặc MIX."),
    KANA_HINT_NOT_READY(HttpStatus.TOO_MANY_REQUESTS, "Gợi ý tiếp theo chưa mở. Bạn chờ thêm chút nhé."),
    KANA_HINT_UNAVAILABLE(HttpStatus.CONFLICT, "Không còn chữ nào để gợi ý cho bàn này."),
    KANA_CHALLENGE_EXPIRED(HttpStatus.CONFLICT, "Thử thách đã sang ngày mới. Hãy tải thử thách hôm nay để tiếp tục."),
    QUIZ_ATTEMPT_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Bạn đã dùng hết lượt quiz hôm nay, hãy thử lại vào ngày mai"),
    /** [JLAS-W0108] P4/P5 — học liệu/đề chưa đủ để phát quiz (BLOCKED_CONTENT); không phải người học trượt. */
    QUIZ_NOT_READY(HttpStatus.UNPROCESSABLE_ENTITY, "Chưa có đủ học liệu để tạo bài kiểm tra"),
    /** [JLAS-W0108] P5 — chưa hoàn thành hoạt động bắt buộc trước đó (gate/prerequisite); không phải trượt quiz. */
    PREREQUISITE_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY, "Bạn cần hoàn thành các hoạt động trước đó trước"),
    /** [JLAS-W0108] P6 — cấp trong lộ trình chưa đủ học liệu đã duyệt (readiness BLOCKED_CONTENT);
     *  bị chặn minh bạch, KHÔNG fallback demo và KHÔNG tính là người học trượt. */
    BLOCKED_CONTENT(HttpStatus.UNPROCESSABLE_ENTITY, "Chưa có đủ học liệu cho cấp độ này"),
    /** [JLAS-W0108] A legacy issued quiz has no immutable passage/audio snapshot and cannot be replayed safely. */
    SOURCE_SNAPSHOT_UNAVAILABLE(HttpStatus.CONFLICT, "Lượt học cũ không có bản nguồn để ôn lại an toàn"),
    // [JLAS-W0108] Lộ trình JLPT theo chủ đề (module jlptpath).
    JPATH_DISABLED(HttpStatus.NOT_FOUND, "Lộ trình JLPT mới chưa được bật."),
    JPATH_NO_ACTIVE_PATH(HttpStatus.NOT_FOUND, "Bạn chưa có lộ trình JLPT."),
    JPATH_ACTIVE_PATH_EXISTS(HttpStatus.CONFLICT, "Bạn đã có một lộ trình JLPT đang học."),
    JPATH_LEVEL_NOT_RELEASED(HttpStatus.CONFLICT, "Cấp này sắp có, hiện chưa mở."),
    JPATH_LEVEL_LOCKED(HttpStatus.FORBIDDEN, "Cấp này chưa mở. Hãy hoàn thành cấp hiện tại và Thi thử JLPT."),
    JPATH_PREMIUM_REQUIRED(HttpStatus.FORBIDDEN, "Cấp N4–N1 dành cho tài khoản Premium. Nâng cấp để học tiếp."),
    CPATH_PREMIUM_REQUIRED(HttpStatus.FORBIDDEN, "Lộ trình Giao tiếp dành cho tài khoản Premium. Nâng cấp để học."),
    CPATH_CORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Máy chủ chưa có bài nền Giao tiếp. Bạn vẫn học tình huống như thường."),
    CPATH_DISPLAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Máy chủ chưa sẵn sàng lưu cách hiển thị. Bạn vẫn bật/tắt furigana và bản dịch ngay trong bài học."),
    JPATH_TARGET_TOO_SOON(HttpStatus.BAD_REQUEST, "Ngày thi quá gần để kịp mục tiêu này."),
    JPATH_ADJUSTMENTS_EXHAUSTED(HttpStatus.CONFLICT, "Bạn đã dùng hết 2 lần chỉnh của cấp này."),
    JPATH_SLIDER_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "Số mục mỗi ngày nằm ngoài giới hạn cho phép."),
    JPATH_SKILL_DAYS_INVALID(HttpStatus.BAD_REQUEST,
            "Hãy chọn đúng 2 ngày kỹ năng và tối đa 2 ngày nghỉ, không trùng nhau."),
    JPATH_SMART_TEST_IN_PROGRESS(HttpStatus.CONFLICT, "Bạn đang làm dở Smart Test."),
    JPATH_RESTART_CONFIRM_MISMATCH(HttpStatus.BAD_REQUEST, "Hãy gõ đúng BẮT ĐẦU LẠI để xác nhận."),
    JPATH_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy mục học."),
    JPATH_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy lượt học."),
    JPATH_CHECK_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy bài kiểm tra."),
    JPATH_CHECK_CLOSED(HttpStatus.CONFLICT, "Bài kiểm tra này đã nộp hoặc đã hết hạn."),
    JPATH_CHECK_INCOMPLETE(HttpStatus.BAD_REQUEST, "Bạn chưa trả lời hết các câu."),
    JPATH_REPORT_DUPLICATE(HttpStatus.CONFLICT, "Bạn đã báo sai mục này, người biên soạn đang xem."),
    JPATH_GRAMMAR_NO_NOTEBOOK(HttpStatus.BAD_REQUEST, "Mẫu ngữ pháp không đưa vào sổ tay."),
    JPATH_REVIEW_DAY_NOT_ALLOWED(HttpStatus.CONFLICT,
            "Hôm nay bạn đã bắt đầu học mới, chỉ đổi sang ngày ôn được khi chưa học mục mới nào."),
    JPATH_EXTRA_NOT_READY(HttpStatus.CONFLICT, "Xong phần hôm nay của tab này trước khi học thêm."),
    JPATH_EXTRA_CHECK_REQUIRED(HttpStatus.CONFLICT, "Bạn đã học vượt khá nhiều. Làm bài kiểm tra tuần (đạt từ 80%) để học thêm tiếp."),
    JPATH_CONTENT_NOT_LEARNABLE(HttpStatus.CONFLICT, "Mục này chưa sẵn sàng để học."),
    // [JLAS-W0108] Kế hoạch 3: Đọc · Nghe, Thi thử.
    JPATH_SKILL_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy dạng câu này."),
    JPATH_SKILL_NO_SETS(HttpStatus.CONFLICT, "Dạng câu này chưa có bài luyện."),
    JPATH_SKILL_LESSON_REQUIRED(HttpStatus.CONFLICT, "Hãy hoàn thành phần lý thuyết trước."),
    JPATH_SKILL_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy buổi luyện."),
    JPATH_SKILL_NOT_SKILL_DAY(HttpStatus.CONFLICT, "Hôm nay không phải ngày kỹ năng — hãy dùng Luyện tập tự do."),
    JPATH_SECTION_LOCKED(HttpStatus.CONFLICT, "Luyện thi theo phần mở ở chặng Rèn kỹ năng."),
    JPATH_MOCK_NOT_APPLICABLE(HttpStatus.CONFLICT, "Lượt thi thử này không dùng được cho cấp đang học."),
    JPATH_MOCK_UNAVAILABLE(HttpStatus.CONFLICT, "Thi thử JLPT chưa sẵn sàng."),
    JPATH_MOCK_ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy lượt thi thử."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() { return status; }
    public String getDefaultMessage() { return defaultMessage; }
}
