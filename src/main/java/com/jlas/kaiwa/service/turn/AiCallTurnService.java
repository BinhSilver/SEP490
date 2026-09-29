package com.jlas.kaiwa.service.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.AiCallTurnProperties;
import com.jlas.kaiwa.dto.ChatTurnRequest;
import com.jlas.kaiwa.dto.ChatTurnResponse;
import com.jlas.kaiwa.model.AiCallTurn;
import com.jlas.kaiwa.model.AiCallTurn.ChargeType;
import com.jlas.kaiwa.model.AiCallTurn.Status;
import com.jlas.kaiwa.repository.AiCallTurnRepository;
import com.jlas.kaiwa.service.ai.AIConversationService;
import com.jlas.kaiwa.service.quota.AiCallQuotaService;
import com.jlas.kaiwa.service.quota.AiCallQuotaService.QuotaExceededException;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Tính phí một lượt chat AI Call an toàn (JLAS-W0199):
 * <ol>
 *   <li>Giữ chỗ trong transaction riêng: trừ phút trong hạn mức, hoặc trừ CP nếu người học đã xác nhận.</li>
 *   <li>Xếp hàng (PENDING) chờ tới lượt gọi AI; quá tải thì báo lỗi và hoàn lại.</li>
 *   <li>Gọi AI; thành công thì chốt (COMPLETED), AI lỗi thì hoàn lại (FAILED).</li>
 * </ol>
 * (user_id, client_request_id) duy nhất nên request lặp lại trả kết quả cũ, không trừ lần hai.
 * Giao dịch CP dùng reference AI_CALL_TURN + id lượt; khoá duy nhất của sổ CP chặn trừ/hoàn hai lần.
 */
@Service
public class AiCallTurnService {

    private static final Logger log = LoggerFactory.getLogger(AiCallTurnService.class);
    static final String CREDIT_REFERENCE = "AI_CALL_TURN";

    private final AiCallTurnRepository turns;
    private final AiCallQuotaService quota;
    private final UserCreditService credits;
    private final AIConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final AiCallTurnProperties properties;
    private final Semaphore permits;

    public AiCallTurnService(AiCallTurnRepository turns, AiCallQuotaService quota, UserCreditService credits,
                             AIConversationService conversationService, ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager, AiCallTurnProperties properties) {
        this.turns = turns;
        this.quota = quota;
        this.credits = credits;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.permits = new Semaphore(Math.max(1, properties.maxConcurrentTurns()), true);
    }

    public ChatTurnResponse chat(UserPrincipal user, ChatTurnRequest request) {
        String requestId = StringUtils.hasText(request.getClientRequestId())
                ? request.getClientRequestId().trim()
                : UUID.randomUUID().toString();
        if (requestId.length() > 64) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "clientRequestId quá dài");
        }
        Optional<AiCallTurn> existing = turns.findByUserIdAndClientRequestId(user.userId(), requestId);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        AiCallTurn turn;
        try {
            turn = tx.execute(status -> begin(user, request, requestId));
        } catch (DataIntegrityViolationException duplicate) {
            // Hai request cùng mã tới cùng lúc: request tới sau nhận kết quả của request tới trước.
            return turns.findByUserIdAndClientRequestId(user.userId(), requestId).map(this::replay)
                    .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "Lượt này đang được xử lý."));
        }

        if (!acquirePermit()) {
            fail(turn.getId(), "OVERLOADED", null);
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "AI Call đang quá tải. Lượt này chưa bị tính phí, bạn thử lại sau ít phút nhé.");
        }
        try {
            ChatTurnResponse response = conversationService.chat(
                    request.getSessionId(), request.getMessage().trim(), request.getFreeChatSettings());
            response.setChargeType(turn.getChargeType().name());
            if (StringUtils.hasText(response.getError())) {
                // AI/mạng lỗi: câu trả lời dự phòng vẫn hiện, nhưng lượt này không bị tính.
                boolean refunded = fail(turn.getId(), "AI_ERROR", null);
                response.setChargeRefunded(refunded);
                response.setCreditCharged(0);
                storeResponse(turn.getId(), response);
            } else {
                response.setCreditCharged(turn.getCreditAmount());
                finish(turn.getId(), Status.COMPLETED, null, json(response));
            }
            return response;
        } catch (RuntimeException exception) {
            fail(turn.getId(), "AI_EXCEPTION", null);
            throw exception;
        } finally {
            permits.release();
        }
    }

    /** Lượt PENDING quá lâu (máy chủ dừng giữa chừng): đánh FAILED và hoàn lại. */
    @Scheduled(fixedDelayString = "${aicall.turn.cleanup-interval-ms:60000}")
    public void failStalePendingTurns() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(properties.pendingTimeoutSeconds());
        for (AiCallTurn stale : turns.findByStatusAndCreatedAtBefore(Status.PENDING, before)) {
            if (fail(stale.getId(), "TIMEOUT", null)) {
                log.warn("AI Call turn {} timed out while PENDING; charge refunded", stale.getId());
            }
        }
    }

    private AiCallTurn begin(UserPrincipal user, ChatTurnRequest request, String requestId) {
        if (turns.existsBySessionIdAndStatus(request.getSessionId(), Status.PENDING)) {
            throw new ApiException(ErrorCode.CONFLICT, "AI đang trả lời lượt trước, bạn đợi một chút nhé.");
        }
        AiCallTurn turn = new AiCallTurn();
        turn.setUserId(user.userId());
        turn.setSessionId(request.getSessionId());
        turn.setClientRequestId(requestId);

        if (quota.isUnlimited(user)) {
            turn.setChargeType(ChargeType.FREE);
            return turns.save(turn);
        }
        double minutes = quota.minutesPerTurn();
        Optional<LocalDate> reservedOn = quota.tryReserve(user.userId(), minutes);
        if (reservedOn.isPresent()) {
            turn.setChargeType(ChargeType.QUOTA);
            turn.setQuotaMinutes(AiCallQuotaService.minutes(minutes));
            turn.setUsageDate(reservedOn.get());
            return turns.save(turn);
        }
        if (!request.isUseCredit()) {
            // Hết hạn mức: chờ hạn mức mới hoặc xác nhận dùng CP; chưa tạo lượt, chưa trừ gì.
            throw new QuotaExceededException("Bạn đã hết hạn mức AI Call. Hãy chờ hạn mức mới, dùng "
                    + quota.creditPerTurn() + " CP cho lượt này, hoặc nâng cấp Premium.", quota.snapshot(user));
        }
        turn.setChargeType(ChargeType.CREDIT);
        turn.setCreditAmount(quota.creditPerTurn());
        turns.saveAndFlush(turn);
        // Không đủ CP thì ném INSUFFICIENT_CREDIT (402) và transaction huỷ luôn dòng lượt vừa tạo.
        credits.spend(user.userId(), quota.creditPerTurn(), CreditReason.AI_CALL_TURN,
                CREDIT_REFERENCE, turn.getId(), "AI Call: 1 lượt hội thoại");
        return turn;
    }

    private boolean acquirePermit() {
        try {
            return permits.tryAcquire(properties.queueWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void finish(Long turnId, Status status, String errorCode, String responseJson) {
        tx.executeWithoutResult(s -> {
            if (turns.finishIfPending(turnId, status, errorCode, responseJson) == 0) {
                // Job dọn dẹp đã đánh FAILED và hoàn lại trước khi AI kịp trả lời: người học được lợi, chỉ ghi log.
                log.warn("AI Call turn {} was no longer PENDING when it finished", turnId);
            }
        });
    }

    /** @return true nếu lượt này vừa được hoàn (chỉ một luồng làm được việc này). */
    private boolean fail(Long turnId, String errorCode, String responseJson) {
        Boolean refunded = tx.execute(s -> {
            if (turns.finishIfPending(turnId, Status.FAILED, errorCode, responseJson) == 0) {
                return false;
            }
            turns.findById(turnId).ifPresent(this::refund);
            return true;
        });
        return Boolean.TRUE.equals(refunded);
    }

    private void refund(AiCallTurn turn) {
        switch (turn.getChargeType()) {
            case QUOTA -> quota.release(turn.getUserId(), turn.getUsageDate(), turn.getQuotaMinutes());
            case CREDIT -> credits.earn(turn.getUserId(), turn.getCreditAmount(), CreditReason.REFUND,
                    CREDIT_REFERENCE, turn.getId(), "Hoàn CP: lượt AI Call bị lỗi");
            default -> { }
        }
    }

    private void storeResponse(Long turnId, ChatTurnResponse response) {
        String body = json(response);
        tx.executeWithoutResult(s -> turns.findById(turnId).ifPresent(turn -> {
            turn.setResponseJson(body);
            turns.save(turn);
        }));
    }

    private ChatTurnResponse replay(AiCallTurn turn) {
        if (turn.getStatus() == Status.PENDING) {
            throw new ApiException(ErrorCode.CONFLICT, "Lượt này đang được xử lý.");
        }
        if (turn.getResponseJson() == null) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Lượt này đã lỗi và đã được hoàn lại. Hãy gửi lại tin nhắn.");
        }
        try {
            return objectMapper.readValue(turn.getResponseJson(), ChatTurnResponse.class);
        } catch (Exception exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không đọc được kết quả lượt trước.");
        }
    }

    private String json(ChatTurnResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception exception) {
            return null;
        }
    }
}
