package com.jlas.shadowing.dto;

import lombok.Data;

@Data
public class YoutubeAnalyzeResultDto {
    private String youtubeVideoId;
    private String url;
    private String title;
    private int duration;
    private String thumbnail;
    private String channel;
    private boolean hasSubtitle;
    /** False when the video owner does not allow embedding; such videos are downloaded and played from file. */
    private boolean embeddable = true;
    /** Age rating YouTube itself applies (yt-dlp age_limit): 18 for age-restricted videos, null otherwise. */
    private Integer youtubeMinAge;
    private boolean hasAutoSubtitle;
    private boolean viManual;
    private boolean viAuto;
    private boolean enManual;
    private boolean enAuto;
    private String japaneseSubtitleLang;
    private boolean japaneseSubtitleAuto;
    private String viSubtitleLang;
    private boolean viSubtitleAuto;
    private String enSubtitleLang;
    private boolean enSubtitleAuto;
    private String language;
    private String detectedLanguage = "unknown";
    private String detectedLanguageLabel = "Chưa xác định";
    private double languageConfidence;
    private boolean japaneseDetected;
    private boolean blockedNonJapanese;
    private String languageMessage;
    private boolean requiresAiStt;
    private boolean blockedNoSubtitleTooLong;
    private String aiCostLabel;
    private String message;
}
