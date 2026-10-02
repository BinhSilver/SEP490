package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Một lượt chat có tính phí (JLAS-W0199). Table: ai_call_turn.
 * (user_id, client_request_id) là duy nhất nên request lặp lại không bị trừ lần hai.
 */
@Entity
@Table(name = "ai_call_turn")
public class AiCallTurn {

    public enum Status { PENDING, COMPLETED, FAILED }

    /** FREE: không giới hạn (admin); QUOTA: trừ phút trong hạn mức; CREDIT: trả bằng CP. */
    public enum ChargeType { FREE, QUOTA, CREDIT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "client_request_id", nullable = false, length = 64)
    private String clientRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 20)
    private ChargeType chargeType;

    @Column(name = "quota_minutes", nullable = false, precision = 10, scale = 3)
    private BigDecimal quotaMinutes = BigDecimal.ZERO;

    @Column(name = "credit_amount", nullable = false)
    private int creditAmount;

    @Column(name = "usage_date")
    private LocalDate usageDate;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "response_json", columnDefinition = "JSON")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public ChargeType getChargeType() { return chargeType; }
    public void setChargeType(ChargeType chargeType) { this.chargeType = chargeType; }
    public BigDecimal getQuotaMinutes() { return quotaMinutes; }
    public void setQuotaMinutes(BigDecimal quotaMinutes) { this.quotaMinutes = quotaMinutes; }
    public int getCreditAmount() { return creditAmount; }
    public void setCreditAmount(int creditAmount) { this.creditAmount = creditAmount; }
    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
