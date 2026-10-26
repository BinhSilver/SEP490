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
    public ApiResponse<TutorChatService.Status> status() {
        return ApiResponse.ok("Trạng thái gia sư", tutor.status());
    }

    public record ChatRequest(String question, List<TutorChatService.Message> history) { }

    @PostMapping("/chat")
    public ApiResponse<TutorChatService.Reply> chat(@AuthenticationPrincipal UserPrincipal user, @RequestBody ChatRequest request) {
        return ApiResponse.ok("Câu trả lời", tutor.ask(user.userId(),
                request == null ? null : request.question(), request == null ? null : request.history()));
    }
}
