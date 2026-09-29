package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.AiCallTurn;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiCallTurnRepository extends JpaRepository<AiCallTurn, Long> {

    Optional<AiCallTurn> findByUserIdAndClientRequestId(Long userId, String clientRequestId);

    boolean existsBySessionIdAndStatus(Long sessionId, AiCallTurn.Status status);

    List<AiCallTurn> findByStatusAndCreatedAtBefore(AiCallTurn.Status status, LocalDateTime before);

    /**
     * Chuyển trạng thái chỉ khi lượt còn PENDING. Trả về 1 cho đúng một luồng thắng,
     * nên việc hoàn quota/CP chỉ xảy ra một lần dù luồng chat và job dọn dẹp chạy cùng lúc.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiCallTurn t set t.status = :to, t.errorCode = :errorCode, t.responseJson = :responseJson, "
            + "t.completedAt = CURRENT_TIMESTAMP where t.id = :id and t.status = com.jlas.kaiwa.model.AiCallTurn.Status.PENDING")
    int finishIfPending(@Param("id") Long id, @Param("to") AiCallTurn.Status to,
                        @Param("errorCode") String errorCode, @Param("responseJson") String responseJson);
}
