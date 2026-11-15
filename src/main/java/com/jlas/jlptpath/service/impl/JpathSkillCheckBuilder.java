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
import com.jlas.jlptpath.entity.JpathSkillDictation;
import com.jlas.jlptpath.entity.JpathSkillQuestion;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathSkillSet;
import com.jlas.jlptpath.entity.JpathSkillSetUse;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathRetypeVerdict;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathCheckSetRepository;
import com.jlas.jlptpath.repository.JpathSkillDictationRepository;
import com.jlas.jlptpath.repository.JpathSkillSetRepository;
import com.jlas.jlptpath.repository.JpathSkillSetUseRepository;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathSkillCheckIssuer;
import com.jlas.jlptpath.service.impl.JpathCheckBuilder.AnswerKey;
import com.jlas.jlptpath.util.JpathDictationGrader;
import com.jlas.jlptpath.util.JpathSkillPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;
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
    static final String DICTATION_HINT = "Nghe rồi điền chữ còn thiếu vào ô trống.";
    static final String DICTATION = "DICTATION";
    /** Shown in place of each blank; the blank's text never leaves the server before the line is locked. */
    static final String MASK = "＿＿";
    /** Policy #34: a listening session dictates at least this many lines. */
    static final int MIN_DICTATION = 5;
    /** issue_json key of a timed Reading section: the ISO time after which answers are refused (+ grace). */
    static final String DEADLINE_AT = "deadlineAt";
    /** #40: seconds to answer after each Listening audio of a section. */
    static final int LISTENING_ANSWER_SECONDS = 12;
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JpathCheckSetRepository checks;
    private final JpathSkillSetRepository sets;
    private final JpathSkillContentReader reader;
    private final JpathSkillDictationRepository dictations;
    private final JpathSkillSetUseRepository uses;
    private final JpathCatalogService catalog;
    private final JpathClock clock;

    public JpathSkillCheckBuilder(JpathCheckSetRepository checks, JpathSkillSetRepository sets,
                                  JpathSkillContentReader reader, JpathSkillDictationRepository dictations,
                                  JpathSkillSetUseRepository uses, JpathCatalogService catalog, JpathClock clock) {
        this.checks = checks;
        this.sets = sets;
        this.reader = reader;
        this.dictations = dictations;
        this.uses = uses;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Question id of the {@code n}-th question (1-based) of the {@code set}-th set (1-based). */
    /**
     * F106: the shown order of a question's options — position i shows stored option {@code order.get(i)}. Seeded by
     * the token and the question id, so the check and its translation always agree.
     */
    public static List<Integer> optionOrder(String token, String qid, int size) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random((token + ":" + qid).hashCode()));
        return order;
    }

    /**
     * F106: reorders each {@code questions_vi[k].options_vi} of a set translation into the order check {@code token}
     * shows for question {@code qids[k]}. Without a token, or with an id that is not {@code s<n>q<n>}, the entry is
     * left in the stored order. Only the order of lines the learner already sees changes; no answer is revealed.
     */
    @SuppressWarnings("unchecked")
    public static void alignTranslation(java.util.Map<String, Object> node, String token, List<String> qids) {
        if (node == null || token == null || token.isBlank() || qids == null
                || !(node.get("questions_vi") instanceof List<?> questions)) {
            return;
        }
        for (int k = 0; k < questions.size() && k < qids.size(); k++) {
            String qid = qids.get(k);
            if (qid == null || !qid.matches("s\\d+q\\d+")
                    || !(questions.get(k) instanceof java.util.Map<?, ?> q)
                    || !(q.get("options_vi") instanceof List<?> vi)) {
                continue;
            }
            List<Integer> order = optionOrder(token, qid, vi.size());
            List<Object> shown = new ArrayList<>();
            for (Integer from : order) {
                shown.add(vi.get(from));
            }
            ((java.util.Map<String, Object>) q).put("options_vi", shown);
        }
    }

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
        return issueSets(session, JpathCheckKind.SKILL_PRACTICE, false, Map.of());
    }

    /**
     * Plan 3 Task 15 — SKILL_SECTION of a section session (#40): the same questions as a practice of its sets, with
     * the timer on. Reading: {@code issue_json.deadlineAt} = issued + {@code sectionSeconds(level, questions)};
     * Listening: no total clock, {@code issue_json.perSetAnswerSeconds} = 12 after each audio.
     */
    public String issueSection(JpathSkillSession session) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("skill", session.getSkill().name());
        if (session.getSkill() == JpathSkill.READING) {
            int questions = reader.visibleQuestions(readList(session.getSetCodesJson())).size();
            extra.put(DEADLINE_AT, clock.now().plusSeconds(JpathSkillPolicy.sectionSeconds(session.getLevel(), questions))
                    .toString());
        } else {
            extra.put("perSetAnswerSeconds", LISTENING_ANSWER_SECONDS);
        }
        return issueSets(session, JpathCheckKind.SKILL_SECTION, true, extra);
    }

    private String issueSets(JpathSkillSession session, JpathCheckKind kind, boolean timerOn,
                             Map<String, Object> extra) {
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
                List<Integer> order = optionOrder(token, qid, labels.size());
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
        save(token, session, kind, questions, keys, used, timerOn, extra);
        return token;
    }

    /**
     * Plan 3 Task 14 — SKILL_DICTATION of a listening session: the visible lines of its sets, topped up to
     * {@link #MIN_DICTATION} from sets of the same type the learner used before (#39), oldest use first. A line is
     * question {@code d<n>} (type DICTATION): its prompt is the sentence with every blank as {@code ＿＿}, its audio
     * slot {@code n}; the key keeps the sentence ({@code correct}) and the blanks ({@code text}). Null when no line.
     */
    @Override
    public String issueDictation(JpathSkillSession session) {
        if (session.getSkill() != JpathSkill.LISTENING) {
            return null;
        }
        List<String> own = setsInOrder(readList(session.getSetCodesJson())).stream().map(JpathSkillSet::getCode)
                .toList();
        List<JpathSkillDictation> lines = new ArrayList<>(visibleLines(own));
        if (lines.size() < MIN_DICTATION && session.getTypeCode() != null) {
            lines.addAll(extraLines(session, own, MIN_DICTATION - lines.size()));
        }
        if (lines.isEmpty()) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        List<JpathQuestionView> questions = new ArrayList<>();
        Map<String, AnswerKey> keys = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        int n = 0;
        for (JpathSkillDictation line : lines) {
            String qid = "d" + (++n);
            List<Blank> blanks = blanks(line.getBlanksJson());
            questions.add(new JpathQuestionView(qid, DICTATION, null, DICTATION_HINT, mask(line.getTextJa(), blanks),
                    null, line.getAudioPath() != null ? n : null, null, null, null));
            keys.put(qid, new AnswerKey(line.getTextJa(), null, null, json(blanks), List.of(), null, null, null,
                    "Đáp án: " + String.join(" / ", blanks.stream().map(Blank::answer).toList()),
                    line.getAudioPath(), line.getCode(), null));
            if (!used.contains(line.getSetCode())) {
                used.add(line.getSetCode());
            }
        }
        save(token, session, JpathCheckKind.SKILL_DICTATION, questions, keys, used, false, Map.of());
        return token;
    }

    private List<JpathSkillDictation> visibleLines(List<String> setCodes) {
        if (setCodes.isEmpty()) {
            return List.of();
        }
        Map<String, List<JpathSkillDictation>> bySet = new LinkedHashMap<>();
        dictations.findBySetCodeInAndStatusInAndReportHiddenFalseOrderBySetCodeAscSortOrderAsc(setCodes,
                catalog.visibleStatuses()).forEach(d -> bySet.computeIfAbsent(d.getSetCode(), k -> new ArrayList<>())
                .add(d));
        return setCodes.stream().flatMap(c -> bySet.getOrDefault(c, List.of()).stream())
                .filter(d -> !blanks(d.getBlanksJson()).isEmpty()).toList();
    }

    /** Lines of sets of the session's type used before (not in the session), least recently used first. */
    private List<JpathSkillDictation> extraLines(JpathSkillSession session, List<String> own, int wanted) {
        List<String> typeSets = reader.type(session.getTypeCode()).map(reader::visibleSets).orElse(List.of())
                .stream().map(v -> v.set().getCode()).filter(c -> !own.contains(c)).toList();
        if (typeSets.isEmpty()) {
            return List.of();
        }
        List<String> usedBefore = uses.findByUserIdAndSetCodeIn(session.getUserId(), typeSets).stream()
                .sorted(Comparator.comparing(JpathSkillSetUse::getLastUsedDay,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(JpathSkillSetUse::getSetCode).toList();
        List<JpathSkillDictation> out = new ArrayList<>();
        for (JpathSkillDictation d : visibleLines(usedBefore)) {
            if (out.size() >= wanted) {
                break;
            }
            out.add(d);
        }
        return out;
    }

    private void save(String token, JpathSkillSession session, JpathCheckKind kind, List<JpathQuestionView> questions,
                      Map<String, AnswerKey> keys, List<String> used, boolean timerOn, Map<String, Object> extra) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("kind", kind.name());
        issue.put("tab", JpathTab.SKILL.name());
        issue.put("level", session.getLevel());
        issue.put(SKILL_SESSION_ID, session.getId());
        issue.putAll(extra);
        checks.saveAndFlush(JpathCheckSet.builder().token(token).userId(session.getUserId())
                .planId(session.getPlanId()).kind(kind).tab(JpathTab.SKILL)
                .level(session.getLevel()).timerOn(timerOn).totalCount(questions.size()).issueJson(json(issue))
                .questionsJson(json(questions))
                .answersJson(json(new JpathCheckServiceImpl.StoredKeys(keys, Map.of())))
                .progressJson(json(new JpathCheckServiceImpl.Progress(null, null))).itemCodesJson(json(used))
                .issuedAt(clock.now()).build());
    }

    // ---- dictation blanks ----------------------------------------------------------------------------------

    /** One blank of a line: characters {@code [start, end)} of the sentence, its answer and accepted forms. */
    record Blank(int start, int end, String answer, List<String> accept) {
        Blank {
            accept = accept == null ? List.of() : List.copyOf(accept);
        }
    }

    static List<Blank> blanks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Blank> out = new ArrayList<>(JSON.readValue(json, new TypeReference<List<Blank>>() { }));
            out.sort(Comparator.comparingInt(Blank::start));
            return out;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** The sentence with every blank replaced by {@link #MASK}. */
    static String mask(String text, List<Blank> blanks) {
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (Blank b : blanks) {
            if (b.start() < at || b.end() > text.length() || b.start() >= b.end()) {
                continue;
            }
            sb.append(text, at, b.start()).append(MASK);
            at = b.end();
        }
        return sb.append(text.substring(at)).toString();
    }

    /** The typed blanks of an answer: a JSON array of strings, else the text split at "|". */
    static List<String> typed(String value) {
        if (value == null) {
            return List.of();
        }
        String v = value.trim();
        if (v.startsWith("[")) {
            try {
                return JSON.readValue(v, new TypeReference<List<String>>() { });
            } catch (JsonProcessingException e) {
                return List.of();
            }
        }
        return List.of(v.split(Pattern.quote("|"), -1));
    }

    /** Verdict of each blank (EXACT, MINOR, WRONG) for {@code value} against the key's blanks JSON. */
    static List<JpathRetypeVerdict> verdicts(String value, String blanksJson) {
        List<Blank> blanks = blanks(blanksJson);
        List<String> typed = typed(value);
        List<JpathRetypeVerdict> out = new ArrayList<>();
        for (int i = 0; i < blanks.size(); i++) {
            String in = i < typed.size() ? typed.get(i) : null;
            out.add(JpathDictationGrader.grade(in, blanks.get(i).answer(), blanks.get(i).accept()));
        }
        return out;
    }

    /** #35: a line is right when no blank is WRONG (a MINOR slip counts, its difference is shown). */
    static boolean dictationCorrect(String value, String blanksJson) {
        List<JpathRetypeVerdict> v = verdicts(value, blanksJson);
        return !v.isEmpty() && v.stream().noneMatch(x -> x == JpathRetypeVerdict.WRONG);
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
