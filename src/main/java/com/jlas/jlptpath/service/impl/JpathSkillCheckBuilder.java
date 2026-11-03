package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathOptionView;
import com.jlas.jlptpath.dto.JpathQuestionView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathSkillQuestion;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillSet;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathSkillSetRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSkillCheckIssuer;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.AnswerKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Plan 3 Task 13 — builds the checks of a skill session and is the {@link JpathSkillCheckIssuer} of
 * {@code JpathSkillSessionServiceImpl}. SKILL_PRACTICE: the visible questions of the session's sets, set by set then by
 * sort order; question ids {@code s<set>q<n>} (1-based); options {@code o1..o4} shuffled with a seed of the token and
 * the question id, so one check always shows the same order. A Listening question carries its set's audio slot (the
 * set index) and the key its audio path, served through the check token. The check row keeps the set codes in
 * {@code item_codes_json} and the skill session id in {@code issue_json.skillSessionId}; it has no study session and
 * no plan side effects (Task 16 pays the skill day).
 */
@Component
public class JpathSkillCheckBuilder implements JpathSkillCheckIssuer {

    static final String SKILL_SESSION_ID = "skillSessionId";
    static final String READING_HINT = "Đọc đoạn văn rồi chọn đáp án đúng.";
    static final String LISTENING_HINT = "Nghe rồi chọn đáp án đúng.";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JpathCheckSetRepository checks;
    private final JpathSkillSetRepository sets;
    private final JpathSkillContentReader reader;
    private final JpathClock clock;

    public JpathSkillCheckBuilder(JpathCheckSetRepository checks, JpathSkillSetRepository sets,
                                  JpathSkillContentReader reader, JpathClock clock) {
        this.checks = checks;
        this.sets = sets;
        this.reader = reader;
        this.clock = clock;
    }

    /** Question id of the {@code n}-th question (1-based) of the {@code set}-th set (1-based). */
    static String questionId(int set, int n) {
        return "s" + set + "q" + n;
    }

    /** The set index (1-based) of a question id {@code s<set>q<n>}; 0 when it is not one. */
    static int setIndex(String questionId) {
        if (questionId == null || !questionId.matches("s\\d+q\\d+")) {
            return 0;
        }
        return Integer.parseInt(questionId.substring(1, questionId.indexOf('q')));
    }

    /** The visible sets of {@code codes} in that order (hidden or unknown ones dropped). */
    List<JpathSkillSet> setsInOrder(List<String> codes) {
        Map<String, JpathSkillSet> byCode = new LinkedHashMap<>();
        sets.findAllById(codes).forEach(s -> byCode.put(s.getCode(), s));
        return codes.stream().map(byCode::get).filter(Objects::nonNull).filter(s -> !s.isReportHidden()).toList();
    }

    @Override
    public String issuePractice(JpathSkillSession session) {
        List<String> codes = readList(session.getSetCodesJson());
        List<JpathSkillSet> ordered = setsInOrder(codes);
        Map<String, List<JpathSkillQuestion>> bySet = new LinkedHashMap<>();
        reader.visibleQuestions(ordered.stream().map(JpathSkillSet::getCode).toList())
                .forEach(q -> bySet.computeIfAbsent(q.getSetCode(), k -> new ArrayList<>()).add(q));
        String token = UUID.randomUUID().toString();
        List<JpathQuestionView> questions = new ArrayList<>();
        Map<String, AnswerKey> keys = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        int index = 0;
        for (JpathSkillSet set : ordered) {
            List<JpathSkillQuestion> qs = bySet.getOrDefault(set.getCode(), List.of());
            if (qs.isEmpty()) {
                continue;
            }
            index++;
            used.add(set.getCode());
            boolean listening = set.getSkill() == JpathSkill.LISTENING;
            int n = 0;
            for (JpathSkillQuestion q : qs) {
                String qid = questionId(index, ++n);
                List<String> labels = readList(q.getOptionsJson());
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < labels.size(); i++) {
                    order.add(i);
                }
                Collections.shuffle(order, new Random((token + ":" + qid).hashCode()));
                List<JpathOptionView> options = new ArrayList<>();
                String correct = null;
                for (int i = 0; i < order.size(); i++) {
                    String id = "o" + (i + 1);
                    options.add(new JpathOptionView(id, labels.get(order.get(i))));
                    if (Objects.equals(order.get(i), q.getAnswerIndex())) {
                        correct = id;
                    }
                }
                questions.add(new JpathQuestionView(qid, listening ? "AUDIO_MCQ" : "MCQ", null,
                        listening ? LISTENING_HINT : READING_HINT, q.getQuestionJa(), null,
                        listening && set.getAudioPath() != null ? index : null, options, null, null));
                keys.put(qid, new AnswerKey(correct, null, null, null, List.of(), null, null, null,
                        q.getExplanationVi(), listening ? set.getAudioPath() : null, q.getCode(), null));
            }
        }
        if (questions.isEmpty()) {
            throw new ApiException(ErrorCode.JPATH_SKILL_NO_SETS);
        }
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("kind", JpathCheckKind.SKILL_PRACTICE.name());
        issue.put("tab", JpathTab.SKILL.name());
        issue.put("level", session.getLevel());
        issue.put(SKILL_SESSION_ID, session.getId());
        checks.saveAndFlush(JpathCheckSet.builder().token(token).userId(session.getUserId())
                .planId(session.getPlanId()).kind(JpathCheckKind.SKILL_PRACTICE).tab(JpathTab.SKILL)
                .level(session.getLevel()).timerOn(false).totalCount(questions.size()).issueJson(json(issue))
                .questionsJson(json(questions))
                .answersJson(json(new JpathCheckServiceImpl.StoredKeys(keys, Map.of())))
                .progressJson(json(new JpathCheckServiceImpl.Progress(null, null))).itemCodesJson(json(used))
                .issuedAt(clock.now()).build());
        return token;
    }

    /** Task 14 adds the dictation check; until then a listening session has no dictation line. */
    @Override
    public String issueDictation(JpathSkillSession session) {
        return null;
    }

    static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
