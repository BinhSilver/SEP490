package com.jlas.game.runtime;

import com.jlas.game.dto.GameDtos.DeckPairView;
import com.jlas.game.dto.GameDtos.GameOptions;
import com.jlas.game.dto.GameDtos.GameSettings;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.util.GameRules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Chọn cài đặt bằng tung đồng xu cho phòng ghép trận nhanh (kiểu Master Duel): mỗi cài đặt tung một lần,
 * người ra mặt cáo được chọn hoặc nhường quyền chọn cho đối thủ. Riêng "Lượt lật đầu tiên" không chọn:
 * ai ra mặt cáo đi trước. Người thắng được bốc ngẫu nhiên ngay khi tung; client chỉ diễn hoạt cảnh cho khớp.
 * Chỉ sửa khi đang giữ lock của phòng.
 */
public class QuickDraft {

    /** Bước được quyết định thẳng bằng đồng xu (mặt cáo đi trước), không có lượt chọn. */
    static final String AUTO_FIRST = "first";
    public static final long INTRO_MS = 4_500;
    public static final long TOSS_MS = 2_600;
    public static final long CHOOSE_MS = 15_000;
    /** Thời gian xem kết quả xu ở bước "đi trước" (không có lượt chọn theo sau). */
    public static final long FIRST_REVEAL_MS = 1_500;

    public enum Phase { INTRO, TOSS, CHOOSE, DONE }

    public record Option(String value, String label, String hint) {
    }

    /** Một cài đặt cần chọn. {@code options} được tính lúc tới lượt (số thẻ phụ thuộc cặp hỏi–đáp đã chọn). */
    public static final class Step {
        private final String key;
        private final String title;
        private List<Option> options = List.of();
        private String recommended;
        private Long tossWinner;
        private Long chooser;
        private boolean passed;
        private String chosenValue;
        private String chosenLabel;
        private Long chosenBy;
        private boolean timedOut;

        Step(String key, String title) {
            this.key = key;
            this.title = title;
        }

        public String key() { return key; }
        public String title() { return title; }
        public List<Option> options() { return options; }
        public String recommended() { return recommended; }
        public Long tossWinner() { return tossWinner; }
        public Long chooser() { return chooser; }
        public boolean passed() { return passed; }
        public String chosenValue() { return chosenValue; }
        public String chosenLabel() { return chosenLabel; }
        public Long chosenBy() { return chosenBy; }
        public boolean timedOut() { return timedOut; }
    }

    private final GameType gameType;
    private final long hostUserId;
    private final long guestUserId;
    private final List<DeckPairView> pairs;
    private final List<Step> steps;
    private GameSettings settings;
    private Phase phase = Phase.INTRO;
    private int index = -1;
    private Instant phaseEndsAt;
    private int token;

    public QuickDraft(GameType gameType, long hostUserId, long guestUserId, List<DeckPairView> pairs,
                      GameSettings initial, Instant now) {
        this.gameType = gameType;
        this.hostUserId = hostUserId;
        this.guestUserId = guestUserId;
        this.pairs = List.copyOf(pairs);
        this.settings = initial;
        this.steps = plan(gameType);
        this.phaseEndsAt = now.plusMillis(INTRO_MS);
    }

    private static List<Step> plan(GameType type) {
        List<Step> list = new ArrayList<>();
        list.add(new Step("fields", "Cặp hỏi – đáp"));
        switch (type) {
            case NEKO_CATCH -> {
                list.add(new Step("difficulty", "Độ khó"));
                list.add(new Step("duration", "Thời gian mỗi người"));
            }
            case MEMORY_MATCH -> {
                list.add(new Step("board", "Số thẻ trên bàn"));
                list.add(new Step("turn", "Thời gian mỗi lượt"));
                list.add(new Step("first", "Lượt lật đầu tiên"));
            }
            case AIR_DEFENSE -> list.add(new Step("duration", "Thời gian trận"));
        }
        return list;
    }

    // ------------------------------------------------------------------ flow

    /** Bộ đếm giờ tới hạn: hết intro → tung; tung xong → chọn; hết giờ chọn → lấy giá trị gợi ý. */
    public void onTimer(Instant now, Random random) {
        switch (phase) {
            case INTRO -> startStep(0, now, random);
            case TOSS -> {
                Step step = current();
                if (step != null && AUTO_FIRST.equals(step.key)) {
                    // Lượt đầu tiên không ai chọn: ai ra mặt cáo (tossWinner, bốc ngẫu nhiên lúc tung) đi trước.
                    apply(step, "FIRST", step.tossWinner);
                    next(now, random);
                    return;
                }
                phase = Phase.CHOOSE;
                phaseEndsAt = now.plusMillis(CHOOSE_MS);
                token++;
            }
            case CHOOSE -> {
                Step step = current();
                step.timedOut = true;
                apply(step, step.recommended, step.chooser);
                next(now, random);
            }
            case DONE -> { }
        }
    }

    /** @return lỗi hiển thị cho người dùng, hoặc null nếu hợp lệ. */
    public String choose(long userId, String key, String value, Instant now, Random random) {
        Step step = current();
        if (phase != Phase.CHOOSE || step == null || !step.key.equals(key)) {
            return "Chưa tới lượt chọn cài đặt này";
        }
        if (step.chooser == null || step.chooser != userId) {
            return "Đối thủ đang giữ quyền chọn";
        }
        if (step.options.stream().noneMatch(option -> option.value().equals(value))) {
            return "Lựa chọn không hợp lệ";
        }
        apply(step, value, userId);
        next(now, random);
        return null;
    }

    public String pass(long userId, String key, Instant now) {
        Step step = current();
        if (phase != Phase.CHOOSE || step == null || !step.key.equals(key)) {
            return "Chưa tới lượt chọn cài đặt này";
        }
        if (step.chooser == null || step.chooser != userId) {
            return "Bạn không giữ quyền chọn";
        }
        if (step.passed) {
            return "Quyền chọn đã được nhường một lần, không nhường lại được";
        }
        step.passed = true;
        step.chooser = other(userId);
        phaseEndsAt = now.plusMillis(CHOOSE_MS);
        token++;
        return null;
    }

    private void next(Instant now, Random random) {
        if (index + 1 < steps.size()) {
            startStep(index + 1, now, random);
        } else {
            phase = Phase.DONE;
            phaseEndsAt = null;
            token++;
        }
    }

    private void startStep(int stepIndex, Instant now, Random random) {
        index = stepIndex;
        Step step = steps.get(stepIndex);
        step.options = optionsFor(step.key);
        step.recommended = recommendedFor(step.key, step.options);
        step.tossWinner = random.nextBoolean() ? hostUserId : guestUserId;
        step.chooser = step.tossWinner;
        phase = Phase.TOSS;
        // Bước "đi trước" kết thúc ngay khi xu đáp: giữ thêm chút để cả hai kịp thấy ai ra mặt cáo.
        phaseEndsAt = now.plusMillis(TOSS_MS + (AUTO_FIRST.equals(step.key) ? FIRST_REVEAL_MS : 0));
        token++;
    }

    // --------------------------------------------------------------- options

    private List<Option> optionsFor(String key) {
        List<Option> options = new ArrayList<>();
        switch (key) {
            case "fields" -> {
                int need = minCardsForFields(gameType);
                for (DeckPairView pair : pairs) {
                    if (pair.usableCards() >= need) {
                        options.add(new Option(pair.questionField().name() + ">" + pair.answerField().name(),
                                pair.questionField().label() + " → " + pair.answerField().label(),
                                pair.usableCards() + " thẻ"));
                    }
                }
            }
            case "difficulty" -> {
                options.add(new Option("EASY", "Dễ", "5 tim · 3 cá mỗi lượt"));
                options.add(new Option("NORMAL", "Vừa", "3 tim · 5 cá mỗi lượt"));
                options.add(new Option("HARD", "Khó", "2 tim · 7 cá mỗi lượt"));
            }
            case "duration" -> {
                List<Integer> durations = gameType == GameType.AIR_DEFENSE
                        ? GameRules.AIR_VERSUS_DURATIONS : GameRules.NEKO_DURATIONS;
                for (Integer seconds : durations) {
                    options.add(new Option(String.valueOf(seconds),
                            seconds % 60 == 0 && seconds >= 120 ? seconds / 60 + " phút" : seconds + " giây",
                            gameType == GameType.AIR_DEFENSE ? "Hết giờ thì ai còn nhiều máu hơn thắng" : "Đồng hồ riêng mỗi người"));
                }
            }
            case "board" -> {
                int usable = usableCards();
                for (Integer size : GameRules.MEMORY_BOARD_SIZES) {
                    if (size / 2 <= usable) {
                        options.add(new Option(String.valueOf(size), size + " thẻ", size / 2 + " cặp"));
                    }
                }
            }
            case "turn" -> {
                for (Integer seconds : GameRules.MEMORY_TURN_SECONDS) {
                    options.add(new Option(String.valueOf(seconds), seconds + " giây", "Hết giờ thì mất lượt"));
                }
            }
            case "first" -> {
                options.add(new Option("FIRST", "Đi trước", "Lật cặp thẻ đầu tiên"));
                options.add(new Option("SECOND", "Đi sau", "Nhìn đối thủ lật trước"));
            }
            default -> { }
        }
        return List.copyOf(options);
    }

    private static String recommendedFor(String key, List<Option> options) {
        String preferred = switch (key) {
            case "fields" -> "KANJI>MEANING";
            case "difficulty" -> "NORMAL";
            case "duration" -> options.stream().anyMatch(option -> option.value().equals("60")) ? "60" : "180";
            case "board" -> "20";
            case "turn" -> "15";
            case "first" -> "FIRST";
            default -> null;
        };
        for (Option option : options) {
            if (option.value().equals(preferred)) {
                return option.value();
            }
        }
        // Bàn Memory: lấy bàn lớn nhất vừa bộ thẻ; các cài đặt khác: lựa chọn đầu tiên.
        if ("board".equals(key) && !options.isEmpty()) {
            return options.get(options.size() - 1).value();
        }
        return options.isEmpty() ? null : options.get(0).value();
    }

    /** Số thẻ tối thiểu để một cặp hỏi–đáp chơi được (Memory tính theo bàn nhỏ nhất). */
    public static int minCardsForFields(GameType type) {
        return type == GameType.MEMORY_MATCH
                ? GameRules.MEMORY_BOARD_SIZES.get(0) / 2
                : GameRules.minWords(type, null);
    }

    private int usableCards() {
        return pairs.stream()
                .filter(pair -> pair.questionField() == settings.questionField()
                        && pair.answerField() == settings.answerField())
                .mapToInt(DeckPairView::usableCards)
                .findFirst().orElse(0);
    }

    // -------------------------------------------------------------- settings

    private void apply(Step step, String value, long userId) {
        Option option = step.options.stream().filter(o -> o.value().equals(value)).findFirst().orElse(null);
        if (option == null) {
            return;
        }
        step.chosenValue = option.value();
        step.chosenLabel = option.label();
        step.chosenBy = userId;
        GameOptions o = settings.options();
        switch (step.key) {
            case "fields" -> {
                String[] parts = value.split(">", 2);
                settings = new GameSettings(settings.setId(), settings.setTitle(), DeckField.valueOf(parts[0]),
                        DeckField.valueOf(parts[1]), o);
            }
            case "difficulty" -> settings = settings.withOptions(new GameOptions(value, o.durationSeconds(),
                    o.boardSize(), o.turnSeconds(), o.objective(), o.totalSeconds(), o.keepTurnOnMatch(), o.firstUserId()));
            case "duration" -> settings = settings.withOptions(new GameOptions(o.difficulty(), Integer.valueOf(value),
                    o.boardSize(), o.turnSeconds(), o.objective(), o.totalSeconds(), o.keepTurnOnMatch(), o.firstUserId()));
            case "board" -> settings = settings.withOptions(new GameOptions(o.difficulty(), o.durationSeconds(),
                    Integer.valueOf(value), o.turnSeconds(), o.objective(), o.totalSeconds(), o.keepTurnOnMatch(),
                    o.firstUserId()));
            case "turn" -> settings = settings.withOptions(new GameOptions(o.difficulty(), o.durationSeconds(),
                    o.boardSize(), Integer.valueOf(value), o.objective(), o.totalSeconds(), o.keepTurnOnMatch(),
                    o.firstUserId()));
            case "first" -> {
                long first = "FIRST".equals(value) ? userId : other(userId);
                settings = settings.withOptions(new GameOptions(o.difficulty(), o.durationSeconds(), o.boardSize(),
                        o.turnSeconds(), o.objective(), o.totalSeconds(), o.keepTurnOnMatch(), first));
            }
            default -> { }
        }
    }

    private long other(long userId) {
        return userId == hostUserId ? guestUserId : hostUserId;
    }

    // --------------------------------------------------------------- getters

    public Step current() {
        return index >= 0 && index < steps.size() ? steps.get(index) : null;
    }

    public List<Step> steps() {
        return steps;
    }

    public GameSettings settings() {
        return settings;
    }

    public Phase phase() {
        return phase;
    }

    public int index() {
        return index;
    }

    public Instant phaseEndsAt() {
        return phaseEndsAt;
    }

    public int token() {
        return token;
    }

    public boolean isComplete() {
        return phase == Phase.DONE;
    }

    public boolean involves(long userId) {
        return userId == hostUserId || userId == guestUserId;
    }
}
