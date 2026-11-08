package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.learning.JlptPathPolicy;
import com.jlas.learning.dto.LearningEntryNotice;
import com.jlas.learning.dto.LearningEntryQuestions;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.SkillScore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Owner 2026-10-25: a JLPT path never starts from what the system only guessed. Before the path is created the Smart
 * Test result asks the learner, and this policy says which questions apply and what each answer means:
 *
 * <ul>
 *   <li>Test not finished (any level): "do you know the kana?" when the survey level is N5 or unknown (no → kana, then
 *       N5 …), then "you did not finish, so we cannot tell whether you are solid at [survey level]": solid → start at the
 *       next level, not solid → study that level too. Never a warm-up of mistakes (unanswered questions prove nothing).</li>
 *   <li>Finished below N5: the kana question (known → N5 from its first chapter, no kana).</li>
 *   <li>Finished Căn bản below the target: review its mistakes first (warm-up) or go straight to the target.</li>
 * </ul>
 * Owner 2026-10-27 (R1, WEAK_LEVEL): a result that does not hold the current level — finished Nhập môn below the target,
 * finished below the survey level, or unfinished with every full batch at the survey level failed — studies that level
 * in full, then each stage to the target, with no warm-up or mistake review. The learner confirms a notice saying why
 * ("Bắt đầu học …") or, allowed by the owner, starts at the reported level ("Vẫn học thẳng …", see {@link #skipLevel}).
 * A higher result (review-start choice) and an unknown survey far below the target keep their own existing questions.
 * The server refuses a path without the answers a result requires.
 */
public final class LearningEntryPolicy {
    public static final String INCOMPLETE_FOUNDATION = "INCOMPLETE_FOUNDATION";
    public static final String MISTAKE_REVIEW = "MISTAKE_REVIEW";
    public static final String SOLID = "SOLID";
    public static final String NOT_SOLID = "NOT_SOLID";
    public static final String WEAK_LEVEL = "WEAK_LEVEL";
    public static final String STUDY_WEAK_LEVEL = "STUDY_WEAK_LEVEL";
    public static final String SKIP_WEAK_LEVEL = "SKIP_WEAK_LEVEL";
    public static final String REVIEW_MISTAKES = "REVIEW_MISTAKES";
    public static final String SKIP_REVIEW = "SKIP_REVIEW";
    private static final List<String> LEVELS = JlptPathPolicy.TARGET_LEVELS;
    /** Owner 2026-10-27 (Q1): the notice offers "Vẫn học thẳng". */
    private static final boolean ALLOW_SKIP = true;
    private static final List<String> SKILL_ORDER = List.of("KANJI", "VOCABULARY", "GRAMMAR", "READING", "LISTENING");
    private static final Map<String, String> SKILL_LABELS = Map.of("KANJI", "Kanji", "VOCABULARY", "Từ vựng",
            "GRAMMAR", "Ngữ pháp", "READING", "Đọc hiểu", "LISTENING", "Nghe hiểu");

    private LearningEntryPolicy() { }

    /** The questions a result needs; {@code level} is the level a choice is about. */
    public static LearningEntryQuestions questions(LearningProfileSnapshot profile) {
        String target = profile.targetLevel();
        LearningEntryQuestions none = new LearningEntryQuestions(false, null, null, List.of(), target, profile.partial());
        if (!"JLPT".equals(profile.goalCode()) || rank(target) < 0) return none;
        String survey = profile.surveyLevel();
        String weak = weakLevel(profile);
        if (profile.partial()) {
            boolean askKana = rank(survey) < 0 || "N5".equals(survey);
            if (weak != null) return weakLevelQuestions(profile, weak, askKana);
            String level = incompleteLevel(profile);
            boolean choice = rank(level) < rank(target);
            return new LearningEntryQuestions(askKana, choice ? INCOMPLETE_FOUNDATION : null,
                    choice ? level : null, choice ? List.of(SOLID, NOT_SOLID) : List.of(), target, true);
        }
        String assessed = profile.assessedLevel();
        if ("BELOW_N5".equals(assessed)) return new LearningEntryQuestions(true, null, null, List.of(), target, false);
        if (weak != null) return weakLevelQuestions(profile, weak, false);
        if (rank(assessed) < 0 || rank(assessed) > rank(target)) return none;
        if (rank(survey) >= 0 && rank(assessed) < rank(survey)) return none;
        if (rank(survey) < 0 && rank(assessed) + 1 < rank(target)) return none;
        String band = profile.overallAbilityBand();
        if (band != null && (band.endsWith(" Nhập môn") || band.endsWith(" Thành thạo"))) return none;
        return rank(assessed) < rank(target)
                ? new LearningEntryQuestions(false, MISTAKE_REVIEW, assessed, List.of(REVIEW_MISTAKES, SKIP_REVIEW), target, false)
                : none;
    }

    /**
     * Checks the learner's answers against {@link #questions} and returns the profile the path is built from. A missing
     * required answer or an answer to a question the result does not ask is refused.
     */
    public static LearningProfileSnapshot apply(LearningProfileSnapshot profile) {
        LearningEntryQuestions questions = questions(profile);
        Boolean kana = profile.kanaKnown();
        String choice = profile.entryChoice();
        if (!questions.askKana() && kana != null) {
            throw invalid("Câu hỏi về bảng chữ cái không áp dụng cho kết quả Smart Test này.");
        }
        if (questions.askKana() && kana == null) throw required();
        boolean kanaBeginner = questions.askKana() && Boolean.FALSE.equals(kana);
        boolean choiceRequired = questions.choiceKind() != null && !kanaBeginner;
        if (choiceRequired && (choice == null || !questions.choices().contains(choice))) throw required();
        if (!choiceRequired && choice != null) {
            throw invalid("Lựa chọn này không áp dụng cho kết quả Smart Test của bạn.");
        }
        String target = profile.targetLevel();
        if (profile.partial()) {
            if (kanaBeginner) return profile.withEntryRoute("BELOW_N5", null, null);
            String level = incompleteLevel(profile);
            if (WEAK_LEVEL.equals(questions.choiceKind())) {
                return profile.withEntryRoute(level, level + " (tạm tính)",
                        SKIP_WEAK_LEVEL.equals(choice) ? questions.notice().skipLevel() : level);
            }
            String routeLevel = rank(level) <= rank(target) ? level : target;
            String start = questions.choiceKind() == null ? target : SOLID.equals(choice) ? next(level) : level;
            return profile.withEntryRoute(routeLevel, routeLevel + " (tạm tính)", start);
        }
        if ("BELOW_N5".equals(profile.assessedLevel())) {
            return kanaBeginner ? profile : profile.withEntryRoute("N5", "N5 Nhập môn", "N5");
        }
        if (WEAK_LEVEL.equals(questions.choiceKind())) {
            if (profile.discrepancyChoice() != null) {
                throw invalid("Kết quả này dùng thông báo học lại cấp, không dùng lựa chọn đồng ý/từ chối.");
            }
            return profile.withEntryRoute(profile.assessedLevel(), profile.overallAbilityBand(),
                    SKIP_WEAK_LEVEL.equals(choice) ? questions.notice().skipLevel() : questions.level());
        }
        // REVIEW_MISTAKES keeps the ordinary start rules; SKIP_REVIEW only drops the warm-up.
        return profile;
    }

    /** A short, stable code of the answers for the path's policy version (one path per answer set). */
    static String policySuffix(LearningProfileSnapshot profile) {
        String kana = profile.kanaKnown() == null ? "" : Boolean.TRUE.equals(profile.kanaKnown()) ? ":K1" : ":K0";
        String choice = profile.entryChoice() == null ? "" : switch (profile.entryChoice()) {
            case SOLID -> ":ES";
            case NOT_SOLID -> ":EN";
            case STUDY_WEAK_LEVEL -> ":EW";
            case SKIP_WEAK_LEVEL -> ":EX";
            case REVIEW_MISTAKES -> ":ER";
            case SKIP_REVIEW -> ":EK";
            default -> throw invalid("Lựa chọn không hợp lệ.");
        };
        return kana + choice;
    }

    /**
     * Owner 2026-10-27 (R1): the level the result shows the learner does not hold, when it is below the target (a weak
     * target is studied in full anyway). Finished: Nhập môn at the start level the old rule used (survey level, else the
     * assessed one); below the survey level, the assessed level unless held (Thành thạo), then the survey level.
     * Unfinished: the survey level when every full batch answered there failed.
     */
    private static String weakLevel(LearningProfileSnapshot profile) {
        if (!"JLPT".equals(profile.goalCode())) return null;
        String target = profile.targetLevel(), survey = profile.surveyLevel(), assessed = profile.assessedLevel();
        String band = profile.overallAbilityBand();
        String weak = null;
        if (profile.partial()) {
            String level = incompleteLevel(profile);
            if (profile.failedBatchLevels().contains(level)) weak = level;
        } else if (rank(assessed) >= 0 && rank(assessed) <= rank(target)) {
            if (rank(survey) >= 0 && rank(assessed) < rank(survey)) {
                weak = band != null && band.endsWith(" Thành thạo") ? survey : assessed;
            } else if (band != null && band.endsWith(" Nhập môn")) {
                weak = rank(survey) >= 0 && rank(survey) <= rank(target) ? survey : assessed;
            }
        }
        return weak != null && rank(weak) >= 0 && rank(weak) < rank(target) ? weak : null;
    }

    /**
     * Owner 2026-10-28: "Vẫn học thẳng" studies the level the learner reported (self N3, weak N5 → N3), never past the
     * target; with no reported level above the weak one it skips just the weak level, as before.
     */
    private static String skipLevel(LearningProfileSnapshot profile, String weak) {
        String survey = profile.surveyLevel();
        if (rank(survey) > rank(weak)) return rank(survey) <= rank(profile.targetLevel()) ? survey : profile.targetLevel();
        return next(weak);
    }

    private static LearningEntryQuestions weakLevelQuestions(LearningProfileSnapshot profile, String weak, boolean askKana) {
        String target = profile.targetLevel();
        List<String> stages = LEVELS.subList(rank(weak), rank(target) + 1);
        List<LearningEntryNotice.WeakSkill> skills = profile.partial() ? List.of() : profile.skillScores().stream()
                .filter(s -> s.assessedCount() > 0 && SKILL_LABELS.containsKey(s.skill()))
                .sorted(Comparator.comparingDouble(SkillScore::correctRatio)
                        .thenComparing(Comparator.comparingInt(SkillScore::wrongCount).reversed())
                        .thenComparingInt(s -> SKILL_ORDER.indexOf(s.skill())))
                .limit(3)
                .map(s -> new LearningEntryNotice.WeakSkill(s.skill(), SKILL_LABELS.get(s.skill()), s.correctCount(),
                        s.assessedCount()))
                .toList();
        var notice = new LearningEntryNotice(WEAK_LEVEL, weak, target, stages, skipLevel(profile, weak), skills, null,
                profile.minutesPerDay(), ALLOW_SKIP);
        List<String> choices = ALLOW_SKIP ? List.of(STUDY_WEAK_LEVEL, SKIP_WEAK_LEVEL) : List.of(STUDY_WEAK_LEVEL);
        return new LearningEntryQuestions(askKana, WEAK_LEVEL, weak, choices, target, profile.partial(), notice);
    }

    /** The level an unfinished test asks about: the survey level, or N5 when the learner did not know theirs. */
    private static String incompleteLevel(LearningProfileSnapshot profile) {
        return rank(profile.surveyLevel()) >= 0 ? profile.surveyLevel() : "N5";
    }

    private static String next(String level) {
        return LEVELS.get(Math.min(rank(level) + 1, LEVELS.size() - 1));
    }

    private static int rank(String level) {
        return level == null ? -1 : LEVELS.indexOf(level);
    }

    private static ApiException required() {
        return new ApiException(ErrorCode.VALIDATION_FAILED,
                "Hãy quay lại kết quả Smart Test và trả lời câu hỏi xác nhận trước khi tạo lộ trình.");
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
