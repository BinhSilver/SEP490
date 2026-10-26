package com.jlas.kaiwa.controller.api;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.rag.RagIndexService;
import com.jlas.kaiwa.rag.RagRetriever;
import com.jlas.kaiwa.rag.RagTutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản trị RAG của gia sư AI. Chỉ ADMIN.
 * Base path: /api/admin/system/ai/rag
 */
@RestController
@RequestMapping("/api/admin/system/ai/rag")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RagAdminController {
    private static final int MAX_QUESTION_CHARS = 500;

    private final RagIndexService index;
    private final RagRetriever retriever;
    private final RagTutorService tutor;

    @GetMapping("/status")
    public ApiResponse<RagIndexService.Status> status() {
        return ApiResponse.ok("Trạng thái RAG", index.status());
    }

    /** Đồng bộ Qdrant theo MySQL; chỉ embed đoạn mới/đổi. {@code lessonId} để đồng bộ riêng một bài. */
    @PostMapping("/reindex")
    public ApiResponse<RagIndexService.Report> reindex(@RequestParam(required = false) Long lessonId) {
        return ApiResponse.ok("Đã đồng bộ index", index.reindex(lessonId));
    }

    /** Xem nguồn mà gia sư sẽ nhận cho một câu hỏi, không gọi model chat. {@code minScore} để hiệu chỉnh ngưỡng. */
    @GetMapping("/search")
    public ApiResponse<RagRetriever.Context> search(@RequestParam("q") String question,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) Double minScore) {
        return ApiResponse.ok("Kết quả tìm", retriever.retrieve(validQuestion(question), limit, minScore));
    }

    public record AskRequest(String question) { }

    /** Hỏi gia sư có nguồn, dùng để đánh giá chất lượng trước khi đưa vào AI Call. */
    @PostMapping("/ask")
    public ApiResponse<RagTutorService.Answer> ask(@RequestBody AskRequest request) {
        return ApiResponse.ok("Câu trả lời", tutor.ask(validQuestion(request == null ? null : request.question())));
    }

    private static String validQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_CHARS)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu hỏi phải có từ 1 đến " + MAX_QUESTION_CHARS + " ký tự.");
        return question.strip();
    }
}
