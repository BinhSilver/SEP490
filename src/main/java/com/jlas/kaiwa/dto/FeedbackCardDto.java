package com.jlas.kaiwa.dto;

public class FeedbackCardDto {
    private String originalText;
    private String correctedText;
    private String reasonVi;
    private String mistakeType;

    public FeedbackCardDto() {}

    public FeedbackCardDto(String originalText, String correctedText, String reasonVi, String mistakeType) {
        this.originalText = originalText;
        this.correctedText = correctedText;
        this.reasonVi = reasonVi;
        this.mistakeType = mistakeType;
    }

    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }
    public String getCorrectedText() { return correctedText; }
    public void setCorrectedText(String correctedText) { this.correctedText = correctedText; }
    public String getReasonVi() { return reasonVi; }
    public void setReasonVi(String reasonVi) { this.reasonVi = reasonVi; }
    public String getMistakeType() { return mistakeType; }
    public void setMistakeType(String mistakeType) { this.mistakeType = mistakeType; }
}
