package com.jlas.game.runtime.memory;

import com.jlas.game.enums.DeckField;

/**
 * Thẻ trên bàn Memory. {@code pairId} là bí mật phía server, không bao giờ đưa vào DTO gửi client.
 */
public class MemoryCard {

    public static final String HIDDEN = "HIDDEN";
    public static final String TEMP_REVEALED = "TEMP_REVEALED";
    public static final String MATCHED = "MATCHED";

    private final String cardInstanceId;
    private final int pairId;
    private final int position;
    private final DeckField face;
    private final String content;

    private String state = HIDDEN;
    private Long matchedByUserId;

    public MemoryCard(String cardInstanceId, int pairId, int position, DeckField face, String content) {
        this.cardInstanceId = cardInstanceId;
        this.pairId = pairId;
        this.position = position;
        this.face = face;
        this.content = content;
    }

    public String getCardInstanceId() {
        return cardInstanceId;
    }

    public int getPairId() {
        return pairId;
    }

    public int getPosition() {
        return position;
    }

    public DeckField getFace() {
        return face;
    }

    public String getContent() {
        return content;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Long getMatchedByUserId() {
        return matchedByUserId;
    }

    public void setMatchedByUserId(Long matchedByUserId) {
        this.matchedByUserId = matchedByUserId;
    }

    /** Nội dung chỉ gửi cho client khi thẻ đang mở hoặc đã ghép. */
    public boolean isContentVisible() {
        return !HIDDEN.equals(state);
    }
}
