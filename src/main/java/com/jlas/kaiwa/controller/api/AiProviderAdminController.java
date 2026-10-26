package com.jlas.kaiwa.controller.api;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.kaiwa.service.ai.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chẩn đoán nhà cung cấp LLM của AI Call (OpenRouter hoặc Ollama cục bộ). Chỉ ADMIN.
 * Base path: /api/admin/system/ai
 */
@RestController
@RequestMapping("/api/admin/system/ai")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AiProviderAdminController {
    private final OpenRouterClient llm;

    /** Gọi model đang cấu hình một lượt rất ngắn và trả độ trễ đo được. */
    @GetMapping("/health")
    public ApiResponse<OpenRouterClient.ProbeResult> health() {
        OpenRouterClient.ProbeResult result = llm.probe();
        return ApiResponse.ok(result.ok() ? "Model AI đang phản hồi" : "Model AI không phản hồi", result);
    }
}
