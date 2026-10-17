package com.jlas.kaiwa.service.learning;

import com.atilika.kuromoji.ipadic.Tokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.dto.FuriganaTokenDto;
import com.jlas.kaiwa.util.JapaneseText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Offline readings, including inflected verbs. Reviewed sample readings resolve homographs. */
@Service
public class JapaneseReadingService {
    private final Tokenizer tokenizer = new Tokenizer();
    private final List<Reading> compounds;
    private final Map<String, List<Reading>> sentences;

    public JapaneseReadingService(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("ai-call/furigana-readings.json").getInputStream()) {
            var definitions = mapper.readValue(input, Readings.class);
            compounds = longestFirst(definitions.compounds());
            sentences = definitions.sentences();
        }
    }

    public List<FuriganaTokenDto> annotate(String text) {
        List<Reading> overrides = new ArrayList<>(compounds);
        List<Reading> contextual = sentences.getOrDefault(text, List.of());
        overrides.addAll(contextual);
        overrides = longestFirst(overrides);
        var base = tokenize(text);
        // Match compounds only at morphological boundaries; never split a known longer word.
        var boundaries = new java.util.HashSet<Integer>();
        boundaries.add(0);
        int position = 0;
        for (var token : base) {
            position += token.getSurface().length();
            boundaries.add(position);
        }
        List<FuriganaTokenDto> result = new ArrayList<>();
        int cursor = 0;
        int plainStart = 0;
        while (cursor < text.length()) {
            Reading match = null;
            for (var reading : overrides) {
                if (text.startsWith(reading.surface(), cursor)
                        && (contextual.contains(reading)
                            || (boundaries.contains(cursor) && boundaries.contains(cursor + reading.surface().length())
                                && !continuesNumber(text, cursor, reading.surface())))) {
                    match = reading;
                    break;
                }
            }
            if (match == null) {
                cursor += Character.charCount(text.codePointAt(cursor));
                continue;
            }
            result.addAll(slice(base, plainStart, cursor));
            result.add(new FuriganaTokenDto(match.surface(), JapaneseText.hiraganaReading(match.reading())));
            cursor += match.surface().length();
            plainStart = cursor;
        }
        result.addAll(slice(base, plainStart, text.length()));
        return result;
    }

    private List<FuriganaTokenDto> tokenize(String text) {
        List<FuriganaTokenDto> result = new ArrayList<>();
        int cursor = 0;
        for (var token : tokenizer.tokenize(text)) {
            if (token.getPosition() > cursor) {
                result.add(new FuriganaTokenDto(text.substring(cursor, token.getPosition()), ""));
            }
            result.add(new FuriganaTokenDto(token.getSurface(), JapaneseText.hasKanji(token.getSurface())
                    ? JapaneseText.hiraganaReading(token.getReading()) : ""));
            cursor = token.getPosition() + token.getSurface().length();
        }
        if (cursor < text.length()) result.add(new FuriganaTokenDto(text.substring(cursor), ""));
        return result;
    }

    private List<FuriganaTokenDto> slice(List<FuriganaTokenDto> tokens, int start, int end) {
        List<FuriganaTokenDto> result = new ArrayList<>();
        int offset = 0;
        for (var token : tokens) {
            int next = offset + token.getSurface().length();
            if (next > start && offset < end) {
                if (offset >= start && next <= end) result.add(token);
                else result.addAll(tokenize(token.getSurface().substring(
                        Math.max(0, start - offset), Math.min(token.getSurface().length(), end - offset))));
            }
            offset = next;
        }
        return result;
    }

    private static List<Reading> longestFirst(List<Reading> readings) {
        return readings.stream().sorted(Comparator.comparingInt((Reading r) -> r.surface().length()).reversed()).toList();
    }

    private static boolean continuesNumber(String text, int offset, String surface) {
        // 十二人/二十三日 must not inherit the standalone 二人/三日 reading.
        return offset > 0 && List.of("一人", "二人", "二日", "三日").contains(surface)
                && "0123456789０１２３４５６７８９〇零一二三四五六七八九十百千万億兆".indexOf(text.charAt(offset - 1)) >= 0;
    }

    public record Reading(String surface, String reading) {}
    private record Readings(List<Reading> compounds, Map<String, List<Reading>> sentences) {}
}
