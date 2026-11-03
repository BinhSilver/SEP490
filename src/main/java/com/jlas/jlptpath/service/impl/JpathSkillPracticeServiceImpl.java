package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.jlptpath.dto.JpathQuestionView;
import com.jlas.jlptpath.dto.JpathSkillCheckView;
import com.jlas.jlptpath.dto.JpathSkillCheckView.NewWord;
import com.jlas.jlptpath.dto.JpathSkillCheckView.ScriptLine;
import com.jlas.jlptpath.dto.JpathSkillCheckView.SetView;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillSet;
import com.jlas.jlptpath.entity.JpathSkillSetUse;
import com.jlas.jlptpath.entity.JpathSkillSetUseId;
import com.jlas.jlptpath.entity.JpathSkillTypeStat;
import com.jlas.jlptpath.entity.JpathSkillTypeStatId;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.repository.JpathSkillSessionRepository;
import com.jlas.jlptpath.repository.JpathSkillSetUseRepository;
import com.jlas.jlptpath.repository.JpathSkillTypeStatRepository;
import com.jlas.jlptpath.repository.JpathVocabularyRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSkillPracticeService;
import com.jlas.jlptpath.service.JpathSkillSessionService;
import com.jlas.jlptpath.util.JpathKanaText;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** [JLAS-W0108] {@link JpathSkillPracticeService}. */
@Service
public class JpathSkillPracticeServiceImpl implements JpathSkillPracticeService {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JpathSkillCheckBuilder builder;
    private final JpathSkillSessionRepository skillSessions;
    private final JpathSkillTypeStatRepository stats;
    private final JpathSkillSetUseRepository uses;
    private final JpathVocabularyRepository vocabularies;
    private final JpathContentReader content;
    private final JpathClock clock;
    // looked up when used: the session service issues checks through the builder
    private final ObjectProvider<JpathSkillSessionService> sessionService;

    public JpathSkillPracticeServiceImpl(JpathSkillCheckBuilder builder, JpathSkillSessionRepository skillSessions,
            JpathSkillTypeStatRepository stats, JpathSkillSetUseRepository uses, JpathVocabularyRepository vocabularies,
            JpathContentReader content, JpathClock clock, ObjectProvider<JpathSkillSessionService> sessionService) {
        this.builder = builder;
        this.skillSessions = skillSessions;
        this.stats = stats;
        this.uses = uses;
        this.vocabularies = vocabularies;
        this.content = content;
        this.clock = clock;
        this.sessionService = sessionService;
    }

    // ---- view ----------------------------------------------------------------------------------------------

    @Override
    public JpathSkillCheckView view(JpathCheckSet set, Set<String> locked) {
        List<JpathSkillSet> ordered = builder.setsInOrder(JpathSkillCheckBuilder.readList(set.getItemCodesJson()));
        Map<Integer, List<String>> questionIds = new LinkedHashMap<>();
        for (JpathQuestionView qv : JpathCheckServiceImpl.readQuestions(set)) {
            questionIds.computeIfAbsent(JpathSkillCheckBuilder.setIndex(qv.id()), k -> new ArrayList<>()).add(qv.id());
        }
        List<SetView> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            JpathSkillSet s = ordered.get(i);
            int index = i + 1;
            List<String> ids = questionIds.getOrDefault(index, List.of());
            boolean listening = s.getSkill() == JpathSkill.LISTENING;
            boolean revealed = !ids.isEmpty() && locked.containsAll(ids);
            out.add(new SetView(s.getCode(), index, s.getSkill().name(), listening ? null : s.getPassageJa(),
                    listening && s.getAudioPath() != null ? index : null, s.getImageDescriptionVi(), ids, revealed,
                    revealed && !listening ? s.getPassageVi() : null,
                    revealed && listening ? script(s.getScriptJson()) : null,
                    revealed && listening ? s.getScriptVi() : null,
                    revealed ? newWords(s.getNewWordsJson(), s.getLevel()) : null));
        }
        return new JpathSkillCheckView(skillSessionId(set), out);
    }

    static Long skillSessionId(JpathCheckSet set) {
        JsonNode id = tree(set.getIssueJson()).path(JpathSkillCheckBuilder.SKILL_SESSION_ID);
        return id.canConvertToLong() ? id.asLong() : null;
    }

    private static List<ScriptLine> script(String json) {
        List<ScriptLine> out = new ArrayList<>();
        for (JsonNode line : tree(json)) {
            out.add(new ScriptLine(text(line, "speaker"), text(line, "ja")));
        }
        return out;
    }

    /** New words of a set; {@code vocabCode} when a visible path word has the same expression and reading. */
    List<NewWord> newWords(String json, String level) {
        List<JsonNode> words = new ArrayList<>();
        tree(json).forEach(words::add);
        if (words.isEmpty()) {
            return List.of();
        }
        List<String> keys = words.stream().map(w -> JpathKanaText.normalize(text(w, "expression"))).toList();
        Map<String, String> codeByKey = new HashMap<>();
        List<String> candidates = vocabularies.findByNormalizedExpressionIn(keys).stream()
                .map(JpathVocabulary::getCode).toList();
        for (JpathVocabulary v : content.vocabs(candidates)) {
            codeByKey.putIfAbsent(JpathKanaText.normalize(v.getExpression()) + "|"
                    + JpathKanaText.normalize(v.getReading()), v.getCode());
        }
        List<NewWord> out = new ArrayList<>();
        for (JsonNode w : words) {
            String key = JpathKanaText.normalize(text(w, "expression")) + "|" + JpathKanaText.normalize(text(w, "reading"));
            out.add(new NewWord(text(w, "expression"), text(w, "reading"), text(w, "meaning_vi"), codeByKey.get(key)));
        }
        return out;
    }

    // ---- submit --------------------------------------------------------------------------------------------

    @Override
    public void afterSubmit(JpathCheckSet set) {
        Long sessionId = skillSessionId(set);
        JpathSkillSession session = sessionId == null ? null
                : skillSessions.findByIdAndUserId(sessionId, set.getUserId()).orElse(null);
        if (set.getKind() == JpathCheckKind.SKILL_PRACTICE) {
            recordPractice(set, session);
        }
        if (session != null) {
            sessionService.getObject().advance(set.getUserId(), session.getId());
        }
    }

    /** #38: last_pct = practice score; sessions/questions/correct add up (DAY and FREE); every set used once more. */
    private void recordPractice(JpathCheckSet set, JpathSkillSession session) {
        Long userId = set.getUserId();
        String typeCode = session != null ? session.getTypeCode() : null;
        if (typeCode != null) {
            JpathSkillTypeStat stat = stats.findById(new JpathSkillTypeStatId(userId, typeCode))
                    .orElseGet(() -> JpathSkillTypeStat.builder().userId(userId).typeCode(typeCode).build());
            int total = set.getTotalCount() == null ? 0 : set.getTotalCount();
            int correct = set.getCorrectCount() == null ? 0 : set.getCorrectCount();
            stat.setSessionsDone(stat.getSessionsDone() + 1);
            stat.setQuestionsDone(stat.getQuestionsDone() + total);
            stat.setCorrectDone(stat.getCorrectDone() + correct);
            stat.setLastPct(set.getScorePct() != null ? set.getScorePct()
                    : total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(100L * correct).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
            stat.setLastSessionDay(session.getStudyDay());
            stat.setUpdatedAt(clock.now());
            stats.save(stat);
        }
        for (String code : JpathSkillCheckBuilder.readList(set.getItemCodesJson())) {
            JpathSkillSetUse use = uses.findById(new JpathSkillSetUseId(userId, code))
                    .orElseGet(() -> JpathSkillSetUse.builder().userId(userId).setCode(code).build());
            use.setUses((use.getUses() == null ? 0 : use.getUses()) + 1);
            use.setLastUsedDay(clock.today(userId));
            uses.save(use);
        }
    }

    // ---- json ----------------------------------------------------------------------------------------------

    private static JsonNode tree(String json) {
        try {
            return json == null || json.isBlank() ? JSON.createArrayNode() : JSON.readTree(json);
        } catch (Exception e) {
            return JSON.createArrayNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }
}
