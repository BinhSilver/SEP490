package com.jlas.kaiwa.controller.api;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.CurrentUser;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.dto.ChatTurnRequest;
import com.jlas.kaiwa.dto.ChatTurnResponse;
import com.jlas.kaiwa.dto.FuriganaTokenDto;
import com.jlas.kaiwa.model.ConversationSession;
import com.jlas.kaiwa.model.FreeChatSupport;
import com.jlas.kaiwa.repository.ConversationSessionRepository;
import com.jlas.kaiwa.service.ai.AIConversationService;
import com.jlas.kaiwa.service.learning.AnnotationService;
import com.jlas.kaiwa.service.learning.SuggestedReplyService;
import com.jlas.kaiwa.service.quota.AiCallQuotaService;
import com.jlas.kaiwa.service.quota.AiCallQuotaService.QuotaExceededException;
import com.jlas.kaiwa.service.turn.AiCallTurnService;
import com.jlas.kaiwa.service.understanding.JapaneseChatInputValidator;
import com.jlas.moderation.service.UserSanctionService;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversation")
public class ConversationApiController {

    private static final Logger log = LoggerFactory.getLogger(ConversationApiController.class);

    private final AIConversationService conversationService;
    private final AnnotationService annotationService;
    private final SuggestedReplyService suggestedReplyService;
    private final ConversationSessionRepository sessionRepository;
    private final AiCallQuotaService quotaService;
    private final UserSanctionService sanctions;
    private final AiCallTurnService turnService;
    private final JapaneseChatInputValidator inputValidator;

    public ConversationApiController(
            AIConversationService conversationService,
            AnnotationService annotationService,
            SuggestedReplyService suggestedReplyService,
            ConversationSessionRepository sessionRepository,
            AiCallQuotaService quotaService,
            UserSanctionService sanctions,
            AiCallTurnService turnService,
            JapaneseChatInputValidator inputValidator) {
        this.conversationService = conversationService;
        this.annotationService = annotationService;
        this.suggestedReplyService = suggestedReplyService;
        this.sessionRepository = sessionRepository;
        this.quotaService = quotaService;
        this.sanctions = sanctions;
        this.turnService = turnService;
        this.inputValidator = inputValidator;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatTurnResponse> chat(@RequestBody ChatTurnRequest request) {
        if (request.getSessionId() == null || request.getMessage() == null || request.getMessage().isBlank()) {
            ChatTurnResponse err = new ChatTurnResponse();
            err.setError("sessionId and message are required");
            return ResponseEntity.badRequest().body(err);
        }
        UserPrincipal current = currentUser();
        ownedSession(request.getSessionId(), current);
        sanctions.assertCanAct(current.userId());
        inputValidator.validate(request.getMessage());
        // Giữ chỗ hạn mức/CP -> gọi AI -> chốt hoặc hoàn (JLAS-W0199).
        return ResponseEntity.ok(turnService.chat(current, request));
    }

    /** Người học chủ động kết thúc phiên; kịch bản thì chấm điểm và trả link trang kết quả. */
    @PostMapping("/{sessionId}/end")
    public ResponseEntity<Map<String, Object>> end(@PathVariable Long sessionId) {
        ConversationSession session = ownedSession(sessionId, currentUser());
        conversationService.endSession(session.getId());
        String next = FreeChatSupport.isFreeChat(session) ? "/ai-call" : "/conversation/" + sessionId + "/result";
        return ResponseEntity.ok(Map.of("ended", true, "redirectUrl", next,
                "memoryProcessing", FreeChatSupport.isFreeChat(session) ? "QUEUED" : "NOT_APPLICABLE"));
    }

    /** Quota hiện tại để giao diện hiển thị hạn mức còn lại. */
    @GetMapping("/quota")
    public ResponseEntity<AiCallQuotaService.UsageSnapshot> quota() {
        return ResponseEntity.ok(quotaService.snapshot(currentUser()));
    }

    @GetMapping("/{sessionId}/hint")
    public ResponseEntity<Map<String, String>> hint(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "1") int level) {
        ownedSession(sessionId, currentUser());
        return ResponseEntity.ok(Map.of("hint", conversationService.getHint(sessionId, level)));
    }

    @PostMapping("/{sessionId}/furigana")
    public ResponseEntity<Map<String, Object>> furigana(
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> body) {
        ConversationSession session = ownedSession(sessionId, currentUser());
        if (FreeChatSupport.isFreeChat(session)) {
            // Free chat: no learning annotation pipeline
            return ResponseEntity.ok(Map.of("tokens", List.of()));
        }
        String text = body.get("text") == null ? "" : String.valueOf(body.get("text"));
        // allowAi=true only when client asks (nút あ); mặc định chỉ DB vocab + cache
        boolean allowAi = Boolean.TRUE.equals(body.get("allowAi"))
                || "true".equalsIgnoreCase(String.valueOf(body.getOrDefault("allowAi", "false")));
        List<FuriganaTokenDto> tokens = annotationService.annotateFurigana(
                session.getScenario().getId(), text, allowAi);
        long rubyCount = tokens.stream()
                .filter(token -> token.getReading() != null && !token.getReading().isBlank())
                .count();
        log.info("FURIGANA_TRACE sessionId={} scenarioId={} allowAi={} textLength={} tokenCount={} readingCount={}",
                sessionId, session.getScenario().getId(), allowAi, text.length(), tokens.size(), rubyCount);
        return ResponseEntity.ok(Map.of("tokens", tokens));
    }

    @PostMapping("/{sessionId}/translate")
    public ResponseEntity<Map<String, String>> translate(
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> body) {
        ConversationSession session = ownedSession(sessionId, currentUser());
        if (FreeChatSupport.isFreeChat(session)) {
            return ResponseEntity.ok(Map.of("translation", ""));
        }
        String text = body.getOrDefault("text", "");
        String vi = annotationService.translate(session.getScenario().getId(), text);
        return ResponseEntity.ok(Map.of("translation", vi != null ? vi : ""));
    }

    @GetMapping("/{sessionId}/suggestions")
    public ResponseEntity<Map<String, Object>> suggestions(@PathVariable Long sessionId) {
        ConversationSession session = ownedSession(sessionId, currentUser());
        if (FreeChatSupport.isFreeChat(session)) {
            return ResponseEntity.ok(Map.of("items", List.of()));
        }
        return ResponseEntity.ok(Map.of("items", suggestedReplyService.suggestions(session)));
    }

    /** Hết hạn mức: 402 kèm số liệu (data.*) cho modal hết hạn mức trong conversation.js. */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiResponse<AiCallQuotaService.UsageSnapshot>> quotaExceeded(QuotaExceededException exception) {
        ApiResponse<AiCallQuotaService.UsageSnapshot> body = ApiResponse.fail(exception.getMessage(), "QUOTA_EXCEEDED", null);
        body.setData(exception.getSnapshot());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }

    private static UserPrincipal currentUser() {
        return CurrentUser.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    /** Session của người khác trả về 404 như không tồn tại, để không lộ id của người học khác. */
    private ConversationSession ownedSession(Long sessionId, UserPrincipal user) {
        return sessionRepository.findDetail(sessionId)
                .filter(session -> user.userId().equals(session.getUserId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên hội thoại"));
    }
}
