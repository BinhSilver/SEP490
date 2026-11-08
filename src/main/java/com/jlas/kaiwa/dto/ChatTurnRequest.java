package com.jlas.kaiwa.dto;

public class ChatTurnRequest {
    private Long sessionId;
    private String message;
    /** Free-chat modular prompt options (ignored for scenario mode). */
    private FreeChatSettings freeChatSettings;
    /** Mã duy nhất mỗi lần gửi (trình duyệt sinh); gửi lại cùng mã thì không bị tính phí lần hai. */
    private String clientRequestId;
    /** Người học đã xác nhận dùng CP cho lượt này khi hết hạn mức. */
    private boolean useCredit;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public FreeChatSettings getFreeChatSettings() { return freeChatSettings; }
    public void setFreeChatSettings(FreeChatSettings freeChatSettings) {
        this.freeChatSettings = freeChatSettings;
    }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public boolean isUseCredit() { return useCredit; }
    public void setUseCredit(boolean useCredit) { this.useCredit = useCredit; }
}
