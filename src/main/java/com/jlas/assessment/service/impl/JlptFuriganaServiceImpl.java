package com.jlas.assessment.service.impl;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import com.jlas.assessment.service.JlptFuriganaService;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class JlptFuriganaServiceImpl implements JlptFuriganaService {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern RUBY_READING = Pattern.compile(
            "<(?:rt|rp)(?:\\s[^>]*)?>.*?</(?:rt|rp)\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RUBY_TAG = Pattern.compile(
            "</?ruby(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final ThreadLocal<Tokenizer> TOKENIZERS = ThreadLocal.withInitial(Tokenizer::new);

    @Override
    public List<String> annotate(List<String> texts) {
        return texts.stream().map(this::annotateFragment).toList();
    }

    private String annotateFragment(String fragment) {
        // Dữ liệu đề có thể đã chứa ruby + rp fallback. Bỏ annotation cũ trước khi
        // phân tích lại để tránh kết quả dạng 画家(がか) hoặc ruby lồng nhau.
        fragment = RUBY_TAG.matcher(RUBY_READING.matcher(fragment).replaceAll("")).replaceAll("");
        StringBuilder result = new StringBuilder();
        Matcher matcher = HTML_TAG.matcher(fragment);
        int cursor = 0;
        while (matcher.find()) {
            result.append(annotateText(fragment.substring(cursor, matcher.start())));
            result.append(allowedTag(matcher.group()));
            cursor = matcher.end();
        }
        result.append(annotateText(fragment.substring(cursor)));
        return result.toString();
    }

    private String annotateText(String text) {
        if (text.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        Matcher whitespace = WHITESPACE.matcher(text);
        int cursor = 0;
        while (whitespace.find()) {
            result.append(annotateChunk(text.substring(cursor, whitespace.start())));
            result.append(escape(whitespace.group()));
            cursor = whitespace.end();
        }
        result.append(annotateChunk(text.substring(cursor)));
        return result.toString();
    }

    private String annotateChunk(String text) {
        if (text.isBlank()) return escape(text);
        List<Token> tokens = new ArrayList<>(TOKENIZERS.get().tokenize(text));
        StringBuilder result = new StringBuilder();
        for (Token token : tokens) {
            String surface = token.getSurface();
            String reading = token.getReading();
            if (containsKanji(surface) && reading != null && !reading.isBlank() && !"*".equals(reading)) {
                result.append("<ruby>").append(escape(surface)).append("<rt>")
                        .append(escape(katakanaToHiragana(reading))).append("</rt></ruby>");
            } else {
                result.append(escape(surface));
            }
        }
        return result.toString();
    }

    private String allowedTag(String tag) {
        String normalized = tag.replaceAll("\\s+", "").toLowerCase();
        return switch (normalized) {
            case "<u>", "</u>", "<br>", "<br/>", "<b>", "</b>", "<strong>", "</strong>" -> normalized;
            default -> "";
        };
    }

    private boolean containsKanji(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String katakanaToHiragana(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint >= 0x30A1 && codePoint <= 0x30F6) result.appendCodePoint(codePoint - 0x60);
            else result.appendCodePoint(codePoint);
        });
        return result.toString();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
