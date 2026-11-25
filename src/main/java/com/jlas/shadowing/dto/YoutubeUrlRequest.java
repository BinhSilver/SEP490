package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class YoutubeUrlRequest {
    private String url;
    private String category;
    /** Genre codes (ACTION, SCHOOL…), several allowed. */
    private java.util.List<String> genres;
    private String targetLanguage;
    /** When false (default), skip AI furigana to speed up long imports. */
    private Boolean generateFurigana;
    /** Age rating 13, 16 or 18; 0 or null = all ages. Never lower than YouTube's own age restriction. */
    private Integer minAge;
    /** The user confirmed paying CP for a video past this week's free allowance. */
    private boolean useCredit;
}
