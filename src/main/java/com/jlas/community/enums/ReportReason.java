package com.jlas.community.enums;

public enum ReportReason {
    SPAM("Spam"),
    INAPPROPRIATE("Nội dung không phù hợp"),
    COPYRIGHT("Vi phạm bản quyền"),
    WRONG_CONTENT("Nội dung sai"),
    OTHER("Khác");

    private final String label;

    ReportReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static String labelOf(String code) {
        for (ReportReason reason : values()) {
            if (reason.name().equals(code)) return reason.label;
        }
        return code;
    }
}
