package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathOptionView;
import com.jlas.jlptpath.dto.JpathSkillLessonCheckResult;
import com.jlas.jlptpath.dto.JpathSkillLessonView;
import com.jlas.jlptpath.entity.JpathSkillLesson;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillType;
import com.jlas.jlptpath.enums.JpathSkillStep;
import com.jlas.jlptpath.repository.JpathSkillSessionRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSkillLessonService;
import com.jlas.jlptpath.util.JpathAudio;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] Strategy lessons and their comprehension check. Reads in a read-only transaction; a correct pick writes
 * in one transaction that takes the path lock first ({@code findActiveForUpdate}), then updates the sessions — the
 * same path-first order as every other writer of the learner's rows.
 */
@Service
public class JpathSkillLessonServiceImpl implements JpathSkillLessonService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathSkillContentReader reader;
    private final JpathSkillSessionRepository sessions;
    private final JpathUserPathRepository paths;
    private final JpathClock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public JpathSkillLessonServiceImpl(JpathSkillContentReader reader, JpathSkillSessionRepository sessions,
            JpathUserPathRepository paths, JpathClock clock, PlatformTransactionManager transactionManager) {
        this.reader = reader;
        this.sessions = sessions;
        this.paths = paths;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    @Override
    public JpathSkillLessonView view(Long userId, String typeCode, Long sessionId) {
        return readTx.execute(status -> {
            JpathSkillLesson lesson = lesson(typeCode);
            JpathSkillType type = reader.type(lesson.getTypeCode()).orElseThrow(JpathSkillLessonServiceImpl::noType);
            boolean passed = passed(userId, lesson.getTypeCode(), sessionId);
            return view(lesson, type, passed);
        });
    }

    @Override
    public JpathSkillLessonCheckResult check(Long userId, String typeCode, String option, Long sessionId) {
        JpathSkillLesson lesson = readTx.execute(status -> lesson(typeCode));
        int picked = optionIndex(option, jsonArray(lesson.getCheckOptionsJson()).size());
        boolean correct = picked == lesson.getCheckAnswerIndex();
        if (!correct) {
            if (sessionId != null) {
                readTx.executeWithoutResult(status -> ownSession(userId, lesson.getTypeCode(), sessionId));
            }
            return new JpathSkillLessonCheckResult(false, lesson.getCheckExplanationVi());
        }
        tx.executeWithoutResult(status -> {
            // path lock first (when there is an ACTIVE path), then the sessions
            paths.findActiveForUpdate(userId);
            List<JpathSkillSession> targets = sessionId != null
                    ? List.of(ownSession(userId, lesson.getTypeCode(), sessionId))
                    : sessions.findByUserIdAndTypeCodeAndCompletedAtIsNullOrderByIdDesc(userId, lesson.getTypeCode());
            LocalDateTime now = clock.now();
            for (JpathSkillSession s : targets) {
                if (s.getLessonPassedAt() == null) {
                    s.setLessonPassedAt(now);
                    if (s.getStep() == JpathSkillStep.THEORY) {
                        s.setStep(JpathSkillStep.PRACTICE);
                    }
                    sessions.save(s);
                }
            }
        });
        return new JpathSkillLessonCheckResult(true, lesson.getCheckExplanationVi());
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private JpathSkillLesson lesson(String typeCode) {
        return reader.lesson(typeCode).orElseThrow(JpathSkillLessonServiceImpl::noType);
    }

    private boolean passed(Long userId, String typeCode, Long sessionId) {
        if (sessionId != null) {
            return ownSession(userId, typeCode, sessionId).getLessonPassedAt() != null;
        }
        Optional<JpathSkillSession> open = sessions
                .findByUserIdAndTypeCodeAndCompletedAtIsNullOrderByIdDesc(userId, typeCode).stream().findFirst();
        if (open.isPresent()) {
            return open.get().getLessonPassedAt() != null;
        }
        return sessions.existsByUserIdAndTypeCodeAndLessonPassedAtIsNotNull(userId, typeCode);
    }

    /** The learner's session of this type; anything else reads as absent. */
    private JpathSkillSession ownSession(Long userId, String typeCode, Long sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .filter(s -> typeCode.equals(s.getTypeCode()))
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_SKILL_SESSION_NOT_FOUND));
    }

    private static JpathSkillLessonView view(JpathSkillLesson lesson, JpathSkillType type, boolean passed) {
        JsonNode ex = parse(lesson.getExampleJson());
        List<String> checkOptions = jsonArray(lesson.getCheckOptionsJson());
        JpathSkillLessonView.Example example = new JpathSkillLessonView.Example(text(ex, "passage_ja"),
                text(ex, "script_ja"), text(ex, "image_description_vi"), text(ex, "question_ja"),
                options(strings(ex.get("options"))), strings(ex.get("walkthrough_vi")), text(ex, "translation_vi"),
                JpathAudio.url(lesson.getExampleAudioPath(), null),
                passed ? optionId(lesson.getExampleAnswerIndex()) : null);
        JpathSkillLessonView.CheckQ check = new JpathSkillLessonView.CheckQ(lesson.getCheckQuestionVi(),
                options(checkOptions), passed ? optionId(lesson.getCheckAnswerIndex()) : null,
                passed ? lesson.getCheckExplanationVi() : null);
        return new JpathSkillLessonView(lesson.getTypeCode(), type.getSkill(), lesson.getTitleVi(),
                lesson.getGoalVi(), jsonArray(lesson.getStepsJson()), jsonArray(lesson.getTrapsJson()),
                jsonArray(lesson.getTipsJson()), example, check, passed);
    }

    /** o1..oN → 0-based index; anything else → VALIDATION_FAILED. */
    private static int optionIndex(String option, int count) {
        if (option != null && option.length() >= 2 && option.charAt(0) == 'o') {
            try {
                int n = Integer.parseInt(option.substring(1));
                if (n >= 1 && n <= count) {
                    return n - 1;
                }
            } catch (NumberFormatException ignored) {
                // falls through
            }
        }
        throw new ApiException(ErrorCode.VALIDATION_FAILED);
    }

    private static String optionId(Integer index) {
        return index == null ? null : "o" + (index + 1);
    }

    private static List<JpathOptionView> options(List<String> labels) {
        List<JpathOptionView> out = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            out.add(new JpathOptionView("o" + (i + 1), labels.get(i)));
        }
        return out;
    }

    private static JsonNode parse(String json) {
        try {
            JsonNode n = json == null ? null : JSON.readTree(json);
            return n == null ? JSON.createObjectNode() : n;
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static List<String> jsonArray(String json) {
        return strings(parse(json));
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.isTextual() ? n.asText() : n.toString()));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static ApiException noType() {
        return new ApiException(ErrorCode.JPATH_SKILL_TYPE_NOT_FOUND);
    }
}
