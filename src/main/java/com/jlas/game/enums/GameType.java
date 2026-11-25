package com.jlas.game.enums;

/** Loại game trong hệ thống. Tên enum trùng giá trị cột {@code game_match.game_type}. */
public enum GameType {
    NEKO_CATCH("neko"),
    MEMORY_MATCH("memory"),
    AIR_DEFENSE("air-defense"),
    SPHINX_RIDDLE("sphinx"),
    PICTURE_REBUS("picture-rebus"),
    DRAW_GUESS("draw-guess");

    private final String slug;

    GameType(String slug) {
        this.slug = slug;
    }

    /** Đoạn URL của trang chơi: /games/{slug}/{sessionId}. */
    public String slug() {
        return slug;
    }

    public String playUrl(String sessionId) {
        return "/games/" + slug + "/" + sessionId;
    }

    public boolean usesDeck() {
        return this == NEKO_CATCH || this == MEMORY_MATCH || this == AIR_DEFENSE;
    }

    public boolean adminOnly() {
        return this == SPHINX_RIDDLE || this == PICTURE_REBUS || this == DRAW_GUESS;
    }

    public boolean supportsSolo() {
        return this != DRAW_GUESS;
    }
}
