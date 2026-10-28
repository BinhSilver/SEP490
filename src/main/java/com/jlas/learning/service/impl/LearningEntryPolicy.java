package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.learning.JlptPathPolicy;
import com.jlas.learning.dto.LearningEntryQuestions;
import com.jlas.learning.dto.LearningProfileSnapshot;
import java.util.List;

/**
 * Owner 2026-10-25: a JLPT path never starts from what the system only guessed. Before the path is created the Smart
 * Test result asks the learner, and this policy says which questions apply and what each answer means:
 *
 * <ul>
 *   <li>Test not finished (any level): "do you know the kana?" when the survey level is N5 or unknown (no → kana, then
 *       N5 …), then "you did not finish, so we cannot tell whether you are solid at [survey level]": solid → start at the
 *       next level, not solid → study that level too. Never a warm-up of mistakes (unanswered questions prove nothing).</li>
 *   <li>Finished below N5: the kana question (known → N5 from its first chapter, no kana).</li>
 *   <li>Finished Nhập môn below the target: study that level again first (recommended) or go straight on.</li>
 *   <li>Finished Căn bản below the target: review its mistakes first (warm-up) or go straight to the target.</li>
 * </ul>
 * A higher result (review-start choice), a lower result than the survey (accept/decline) and an unknown survey far below
 * the target keep their own existing questions. The server refuses a path without the answers a result requires.
 */
public final class LearningEntryPolicy {
    public static final String INCOMPLETE_FOUNDATION = "INCOMPLETE_FOUNDATION";
    public static final String INTRO_LEVEL = "INTRO_LEVEL";
    public static final String MISTAKE_REVIEW = "MISTAKE_REVIEW";
    public static final String SOLID = "SOLID";
    public static final String NOT_SOLID = "NOT_SOLID";
    public static final String STUDY_LEVEL = "STUDY_LEVEL";
    public static final String SKIP_LEVEL = "SKIP_LEVEL";
    public static final String REVIEW_MISTAKES = "REVIEW_MISTAKES";
    public static final String SKIP_REVIEW = "SKIP_REVIEW";
    private static final List<String> LEVELS = JlptPathPolicy.TARGET_LEVELS;

    private LearningEntryPolicy() { }

    /** The questions a result needs; {@code level} is the level a choice is about. */
    public static LearningEntryQuestions questions(LearningProfileSnapshot profile) {
        String target = profile.targetLevel();
        LearningEntryQuestions none = new LearningEntryQuestions(false, null, null, List.of(), target, profile.partial());
        if (!"JLPT".equals(profile.goalCode()) || rank(target) < 0) return none;
        String survey = profile.surveyLevel();
        if (profile.partial()) {
            String level = incompleteLevel(profile);
            boolean choice = rank(level) < rank(target);
            return new LearningEntryQuestions(rank(survey) < 0 || "N5".equals(survey), choice ? INCOMPLETE_FOUNDATION : null,
                    choice ? level : null, choice ? List.of(SOLID, NOT_SOLID) : List.of(), target, true);
        }
        String assessed = profile.assessedLevel();
        if ("BELOW_N5".equals(assessed)) return new LearningEntryQuestions(true, null, null, List.of(), target, false);
        if (rank(assessed) < 0 || rank(assessed) > rank(target)) return none;
        if (rank(survey) >= 0 && rank(assessed) < rank(survey)) return none;
        if (rank(survey) < 0 && rank(assessed) + 1 < rank(target)) return none;
        String band = profile.overallAbilityBand();
        if (band != null && band.endsWith(" Nhập môn")) {
            String start = rank(survey) >= 0 && rank(survey) <= rank(target) ? survey : assessed;
            return rank(start) < rank(target)
                ? new LearningEntryQuestions(false, INTRO_LEVEL, start, List.of(STUDY_LEVEL, SKIP_LEVEL), target, false) : none;
        }
        if (band != null && band.endsWith(" Thành thạo")) return none;
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
            String routeLevel = rank(level) <= rank(target) ? level : target;
            String start = questions.choiceKind() == null ? target : SOLID.equals(choice) ? next(level) : level;
            return profile.withEntryRoute(routeLevel, routeLevel + " (tạm tính)", start);
        }
        if ("BELOW_N5".equals(profile.assessedLevel())) {
            return kanaBeginner ? profile : profile.withEntryRoute("N5", "N5 Nhập môn", "N5");
        }
        if (INTRO_LEVEL.equals(questions.choiceKind()) && SKIP_LEVEL.equals(choice)) {
            return profile.withEntryRoute(profile.assessedLevel(), profile.overallAbilityBand(), next(questions.level()));
        }
        // STUDY_LEVEL and REVIEW_MISTAKES keep the ordinary start rules; SKIP_REVIEW only drops the warm-up.
        return profile;
    }

    /** A short, stable code of the answers for the path's policy version (one path per answer set). */
    static String policySuffix(LearningProfileSnapshot profile) {
        String kana = profile.kanaKnown() == null ? "" : Boolean.TRUE.equals(profile.kanaKnown()) ? ":K1" : ":K0";
        String choice = profile.entryChoice() == null ? "" : switch (profile.entryChoice()) {
            case SOLID -> ":ES";
            case NOT_SOLID -> ":EN";
            case STUDY_LEVEL -> ":EL";
            case SKIP_LEVEL -> ":ED";
            case REVIEW_MISTAKES -> ":ER";
            case SKIP_REVIEW -> ":EK";
            default -> throw invalid("Lựa chọn không hợp lệ.");
        };
        return kana + choice;
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
