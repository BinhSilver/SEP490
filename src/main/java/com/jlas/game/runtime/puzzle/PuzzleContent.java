package com.jlas.game.runtime.puzzle;

import com.jlas.game.enums.GameType;
import com.jlas.game.util.GameRules;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Học liệu chỉ đọc từ classpath. Đáp án và ảnh chưa mở nằm ngoài tài nguyên công khai. */
@Component
public class PuzzleContent {
    private final Map<GameType, List<Entry>> bank = new EnumMap<>(GameType.class);

    public PuzzleContent(ObjectMapper mapper) throws IOException {
        for (GameType type : List.of(GameType.SPHINX_RIDDLE, GameType.PICTURE_REBUS, GameType.DRAW_GUESS)) {
            try (var input = new ClassPathResource("game-content/" + type.slug() + ".json").getInputStream()) {
                List<Entry> entries = mapper.readValue(input, new TypeReference<List<Entry>>() { });
                validate(type, entries);
                bank.put(type, List.copyOf(entries));
            }
        }
    }

    public List<Entry> forLevel(GameType type, String jlpt) {
        return bank.get(type).stream().filter(entry -> entry.jlpt().equals(jlpt)).toList();
    }

    private void validate(GameType type, List<Entry> entries) {
        Set<String> ids = new HashSet<>();
        for (Entry entry : entries) {
            if (entry.id() == null || !ids.add(entry.id()) || !GameRules.PUZZLE_LEVELS.contains(entry.jlpt())
                    || empty(entry.answer()) || empty(entry.reading()) || empty(entry.meaning())
                    || !entry.accepted().contains(entry.answer()) || !entry.accepted().contains(entry.reading())
                    || entry.accepted().stream().anyMatch(PuzzleContent::empty)) {
                throw new IllegalStateException("Dữ liệu đáp án không hợp lệ: " + entry.id());
            }
            if (type == GameType.SPHINX_RIDDLE && (empty(entry.prompt()) || empty(entry.explanation()))) {
                throw new IllegalStateException("Thiếu câu đố hoặc lời giải: " + entry.id());
            }
            if (type == GameType.PICTURE_REBUS) {
                if (entry.images().size() != 2 || entry.hints().size() < 2) {
                    throw new IllegalStateException("Thiếu ảnh hoặc gợi ý: " + entry.id());
                }
                entry.images().forEach(PuzzleContent::validateImage);
                for (Hint hint : entry.hints()) {
                    if (empty(hint.image()) == empty(hint.text())) {
                        throw new IllegalStateException("Gợi ý phải chỉ chứa ảnh hoặc chữ: " + entry.id());
                    }
                    if (hint.image() != null) validateImage(hint.image());
                }
            }
        }
        int minimumPerLevel = type == GameType.PICTURE_REBUS ? 1 : 5;
        for (String level : GameRules.PUZZLE_LEVELS) {
            if (entries.stream().filter(entry -> entry.jlpt().equals(level)).count() < minimumPerLevel) {
                throw new IllegalStateException("Thiếu học liệu " + type + " " + level);
            }
        }
    }

    private static boolean empty(String value) {
        return value == null || value.isBlank();
    }

    private static void validateImage(String image) {
        if (image == null || !image.matches("PR-[0-9]{2}-[1-4]\\.webp")
                || !new ClassPathResource("game-content/rebus/" + image).exists()) {
            throw new IllegalStateException("Thiếu ảnh Ghép hình: " + image);
        }
    }

    public record Hint(String image, String text) { }

    public record Entry(String id, String jlpt, String prompt, String answer, String reading, String meaning,
                        String explanation, List<String> accepted, List<String> images, List<Hint> hints) {
        public Entry {
            accepted = accepted == null ? List.of() : List.copyOf(accepted);
            images = images == null ? List.of() : List.copyOf(images);
            hints = hints == null ? List.of() : List.copyOf(hints);
        }
    }
}
