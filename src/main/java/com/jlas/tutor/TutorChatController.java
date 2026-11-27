package com.jlas.tutor;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chatbot gia sư (nút nổi trên các trang học). Mọi tài khoản đã đăng nhập đều dùng được.
 * Base path: /api/tutor
 */
@RestController
@RequestMapping("/api/tutor")
@RequiredArgsConstructor
public class TutorChatController {

    private final TutorChatService tutor;

    @GetMapping("/status")
    public ApiResponse<TutorChatService.Status> status(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Trạng thái gia sư", tutor.status(user == null ? null : user.userId()));
    }

    public record ChatRequest(String question, List<TutorChatService.Message> history) { }

    @PostMapping("/chat")
    public ApiResponse<TutorChatService.Reply> chat(@AuthenticationPrincipal UserPrincipal user, @RequestBody ChatRequest request) {
        return ApiResponse.ok("Câu trả lời", tutor.ask(user.userId(),
                request == null ? null : request.question(), request == null ? null : request.history()));
    }

    private final com.fasterxml.jackson.databind.ObjectMapper json;

    /**
     * Như /chat nhưng trả từng dòng JSON (NDJSON) để trình duyệt hiện chữ dần: nhiều dòng {@code {"delta": "..."}},
     * rồi một dòng cuối {@code {"done": true, "answer", "sources"}} (câu trả lời đã lọc, thay cho phần đã hiện)
     * hoặc {@code {"error": "..."}}. Lỗi cũng đi trong luồng vì lúc đó phản hồi 200 đã bắt đầu.
     */
    @PostMapping(value = "/chat/stream", produces = "application/x-ndjson")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> chatStream(
            @AuthenticationPrincipal UserPrincipal user, @RequestBody ChatRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        long userId = user.userId();
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
            java.util.function.Consumer<java.util.Map<String, Object>> send = line -> {
                try {
                    out.write((json.writeValueAsString(line) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    // Spring đưa cho StreamingResponseBody một luồng bỏ qua flush(); phải đẩy thẳng trên response thì chữ mới hiện dần.
                    response.flushBuffer();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e); // người học đóng trang giữa chừng
                }
            };
            try {
                TutorChatService.Reply reply = tutor.ask(userId, request == null ? null : request.question(),
                        request == null ? null : request.history(), delta -> send.accept(java.util.Map.of("delta", delta)));
                send.accept(java.util.Map.of("done", true, "answer", reply.answer(), "sources", reply.sources(), "tier", reply.tier()));
            } catch (com.jlas.common.exception.ApiException e) {
                send.accept(java.util.Map.of("error", e.getMessage(), "code", e.getErrorCode().name()));
            } catch (java.io.UncheckedIOException e) {
                // kết nối đã đóng, không còn ai để báo
            }
        };
        return org.springframework.http.ResponseEntity.ok().header("Content-Type", "application/x-ndjson;charset=UTF-8").header("X-Accel-Buffering", "no").header("Cache-Control", "no-cache").body(body);
    }
}
