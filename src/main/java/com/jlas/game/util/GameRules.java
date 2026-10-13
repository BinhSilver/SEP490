package com.jlas.game.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.dto.GameDtos.CatalogItem;
import com.jlas.game.dto.GameDtos.GameOptions;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.PlayMode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Danh mục game và luật chuẩn hoá tuỳ chọn. Cả phòng đối kháng lẫn chơi đơn đều đi qua đây,
 * nên client không thể gửi một giá trị ngoài danh sách cho phép.
 */
public final class GameRules {

    public static final List<String> NEKO_DIFFICULTIES = List.of("EASY", "NORMAL", "HARD");
    public static final List<Integer> NEKO_DURATIONS = List.of(60, 90, 120);
    public static final List<Integer> NEKO_SOLO_DURATIONS = List.of(60, 90, 120, 180, 300, 600, 900, 1200, 1800, 3600);
    public static final List<Integer> MEMORY_BOARD_SIZES = List.of(12, 14, 16, 18, 20);
    /** Luyện tập: bàn dựng theo số thẻ đã chọn (mỗi thẻ một cặp), trong khoảng này. */
    public static final int MEMORY_MIN_PAIRS = 6;
    public static final int MEMORY_MAX_PAIRS = 10;
    public static final List<Integer> MEMORY_TURN_SECONDS = List.of(10, 15, 20);
    public static final List<String> MEMORY_OBJECTIVES = List.of("CLASSIC", "TIME_ATTACK");
    public static final List<Integer> MEMORY_TOTAL_SECONDS = List.of(180, 300, 600);
    public static final List<Integer> AIR_VERSUS_DURATIONS = List.of(120, 180, 300);
    /** Chơi đơn là chế độ vô tận (0 = không giới hạn giờ). */
    public static final List<Integer> AIR_SOLO_DURATIONS = List.of(0);

    public static final int NEKO_MIN_WORDS = 4;
    public static final int AIR_MIN_WORDS = 5;

    private static final List<DeckField> ALL_FIELDS = Arrays.asList(DeckField.values());

    private GameRules() { }

    public static List<CatalogItem> catalog() {
        return List.of(
                new CatalogItem(GameType.NEKO_CATCH, GameType.NEKO_CATCH.slug(), "Bắt cá",
                        "Mèo bắt cá · Đua điểm",
                        "/arena/images/games/neko-catch.webp", NEKO_MIN_WORDS, ALL_FIELDS,
                        new GameOptions("NORMAL", 60, null, null, null, null, null, null),
                        choices("difficulty", NEKO_DIFFICULTIES, "durationSeconds", NEKO_DURATIONS)),
                new CatalogItem(GameType.MEMORY_MATCH, GameType.MEMORY_MATCH.slug(), "Lật thẻ",
                        "Lật thẻ ghi nhớ · Theo lượt",
                        "/arena/images/games/memory-match.webp", MEMORY_BOARD_SIZES.get(0) / 2, ALL_FIELDS,
                        new GameOptions(null, null, 20, 15, "CLASSIC", null, true, null),
                        choices("boardSize", MEMORY_BOARD_SIZES, "turnSeconds", MEMORY_TURN_SECONDS,
                                "objective", MEMORY_OBJECTIVES, "totalSeconds", MEMORY_TOTAL_SECONDS)),
                new CatalogItem(GameType.AIR_DEFENSE, GameType.AIR_DEFENSE.slug(), "Bắn máy bay",
                        "Gõ đáp án · Hạ máy bay địch",
                        "/arena/images/games/air-defense.webp", AIR_MIN_WORDS, ALL_FIELDS,
                        new GameOptions(null, 180, null, null, null, null, null, null),
                        choices("durationSeconds", AIR_VERSUS_DURATIONS, "soloDurationSeconds", AIR_SOLO_DURATIONS))
        );
    }

    public static CatalogItem item(GameType type) {
        return catalog().stream().filter(item -> item.type() == type).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, "Game không hợp lệ"));
    }

    public static String gameName(GameType type) {
        return item(type).name();
    }

    public static void validateFields(DeckField question, DeckField answer) {
        if (question == null || answer == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn nội dung câu hỏi và nội dung đáp án");
        }
        if (question == answer) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Nội dung đáp án phải khác nội dung câu hỏi");
        }
    }

    /** Điền giá trị mặc định và từ chối mọi giá trị ngoài danh sách cho phép. */
    public static GameOptions normalize(GameType type, PlayMode mode, GameOptions raw) {
        GameOptions in = raw == null ? new GameOptions(null, null, null, null, null, null, null, null) : raw;
        return switch (type) {
            case NEKO_CATCH -> new GameOptions(
                    pick("Độ khó", upper(in.difficulty()), NEKO_DIFFICULTIES, "NORMAL"),
                    pick("Thời gian", in.durationSeconds(), NEKO_SOLO_DURATIONS, 60),
                    null, null, null, null, null, null,
                    in.lives() == null ? null : pick("Số tim", in.lives(), List.of(1, 2, 3, 4, 5), 3),
                    in.choices() == null ? null : pick("Số cá", in.choices(), List.of(2, 3, 4, 5, 6, 7), 5),
                    in.cardIds() == null ? null : List.copyOf(in.cardIds()));
            case MEMORY_MATCH -> {
                String objective = pick("Mục tiêu", upper(in.objective()), MEMORY_OBJECTIVES, "CLASSIC");
                Integer total = "TIME_ATTACK".equals(objective)
                        ? pick("Tổng thời gian", in.totalSeconds(), MEMORY_TOTAL_SECONDS, 300)
                        : null;
                yield new GameOptions(null, null,
                        pick("Số thẻ", in.boardSize(), MEMORY_BOARD_SIZES, 20),
                        pick("Thời gian mỗi lượt", in.turnSeconds(), MEMORY_TURN_SECONDS, 15),
                        objective, total, in.keepTurnOnMatch() == null || in.keepTurnOnMatch(),
                        // Người lật trước (do tung đồng xu quyết định); engine chỉ nhận nếu là người chơi của trận.
                        in.firstUserId(), null, null,
                        in.cardIds() == null ? null : List.copyOf(in.cardIds()),
                        Boolean.TRUE.equals(in.distinguishCardTypes()));
            }
            case AIR_DEFENSE -> new GameOptions(null,
                    mode == PlayMode.VERSUS
                            ? pick("Thời gian", in.durationSeconds(), AIR_VERSUS_DURATIONS, 180)
                            : AIR_SOLO_DURATIONS.get(0),
                    null, null, null, null, null, null, null, null,
                    in.cardIds() == null ? null : List.copyOf(in.cardIds()));
        };
    }

    /** Số thẻ (đủ 2 trường, không trùng) tối thiểu để mở trận. */
    public static int minWords(GameType type, GameOptions normalized) {
        return switch (type) {
            case NEKO_CATCH -> NEKO_MIN_WORDS;
            case MEMORY_MATCH -> normalized.boardSize() / 2;
            case AIR_DEFENSE -> AIR_MIN_WORDS;
        };
    }

    private static <T> T pick(String label, T value, List<T> allowed, T fallback) {
        if (value == null) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, label + " không hợp lệ");
        }
        return value;
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, List<Object>> choices(Object... pairs) {
        Map<String, List<Object>> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], List.copyOf((List<?>) pairs[i + 1]));
        }
        return map;
    }
}
