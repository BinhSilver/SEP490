package com.jlas.tutor;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Duyệt nội dung do gia sư AI tạo ra (câu trả lời tầng 2, gợi ý từ trái nghĩa). Chỉ CONTENT_CREATOR và ADMIN;
 * SecurityConfig cũng chặn /api/admin/tutor-review/** cho hai vai này.
 * Base path: /api/admin/tutor-review
 */
@RestController
@RequestMapping("/api/admin/tutor-review")
@PreAuthorize("hasAnyRole('CONTENT_CREATOR','ADMIN')")
public class TutorReviewController {

    private final TutorReviewQueue queue;

    public TutorReviewController(TutorReviewQueue queue) {
        this.queue = queue;
    }

    @GetMapping("/items")
    public ApiResponse<TutorReviewQueue.Page> items(@RequestParam(defaultValue = "ANSWER") String kind,
                                                    @RequestParam(defaultValue = "PENDING") String status,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok("OK", queue.list(kind(kind), status(status), q, Math.max(0, page), Math.clamp(size, 1, 50)));
    }

    /** @param answer null = giữ nguyên nội dung, chỉ đổi trạng thái */
    public record AnswerUpdate(String status, String answer) { }

    @PutMapping("/answers/{id}")
    public ApiResponse<Void> updateAnswer(@PathVariable long id, @RequestBody AnswerUpdate body) {
        String answer = body.answer() == null ? null : body.answer().strip();
        if (answer != null && (answer.isEmpty() || answer.length() > TutorReviewQueue.MAX_ANSWER_CHARS)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Câu trả lời phải có từ 1 đến " + TutorReviewQueue.MAX_ANSWER_CHARS + " ký tự.");
        }
        if (!queue.updateAnswer(id, status(body.status()), answer)) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mục này.");
        return ApiResponse.ok("Đã lưu", null);
    }

    /** @param antonym chữ tiếng Nhật của từ trái nghĩa thay thế (phải có trong từ điển J-LAS); trống = giữ nguyên */
    public record AntonymUpdate(String status, String antonym) { }

    @PutMapping("/antonyms/{id}")
    public ApiResponse<Void> updateAntonym(@PathVariable long id, @RequestBody AntonymUpdate body) {
        Long antonymId = null;
        if (body.antonym() != null && !body.antonym().isBlank()) {
            antonymId = queue.dictionaryEntryId(body.antonym());
            if (antonymId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Từ 「" + body.antonym().strip() + "」 không có trong từ điển J-LAS.");
            }
        }
        if (!queue.updateAntonym(id, status(body.status()), antonymId)) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mục này.");
        return ApiResponse.ok("Đã lưu", null);
    }

    private static String kind(String kind) {
        String k = kind == null ? "" : kind.strip().toUpperCase();
        if (!k.equals(TutorReviewQueue.KIND_ANSWER) && !k.equals(TutorReviewQueue.KIND_ANTONYM)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Loại không hợp lệ.");
        }
        return k;
    }

    private static String status(String status) {
        String s = status == null ? "" : status.strip().toUpperCase();
        if (!TutorReviewQueue.STATUSES.contains(s)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Trạng thái không hợp lệ.");
        return s;
    }
}
