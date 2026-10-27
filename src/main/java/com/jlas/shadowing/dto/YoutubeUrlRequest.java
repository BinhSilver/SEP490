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
}
