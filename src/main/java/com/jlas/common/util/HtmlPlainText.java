package com.jlas.common.util;

import org.springframework.web.util.HtmlUtils;

/** Converts small, trusted content snippets to readable plain text for non-HTML views. */
public final class HtmlPlainText {
    private HtmlPlainText() {
    }

    public static String from(String html) {
        if (html == null || html.isBlank()) return null;
        String text = HtmlUtils.htmlUnescape(html)
                .replaceAll("(?i)<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>", " ")
                .replaceAll("(?s)<[^>]+>", "")
                .replaceAll("\\s+", " ")
                .trim();
        return text.isEmpty() ? null : text;
    }
}
