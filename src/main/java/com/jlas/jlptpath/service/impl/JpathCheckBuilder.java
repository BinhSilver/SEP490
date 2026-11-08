package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.jlptpath.dto.JpathBoardView;
import com.jlas.jlptpath.dto.JpathOptionView;
import com.jlas.jlptpath.dto.JpathQuestionView;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import com.jlas.jlptpath.entity.JpathKana;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathQuizDirection;
import com.jlas.jlptpath.util.JpathAudio;
import com.jlas.jlptpath.util.JpathCheckSizing;
import com.jlas.jlptpath.util.JpathKanaText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Builds check questions (policy #17, #18). The public part ({@link JpathQuestionView}) carries only
 * q/b/o/l/r ids; every answer and item code lives in {@link AnswerKey}, which stays on the server until the
 * question is locked. Same input + same seed = same check.
 */
@Component
public class JpathCheckBuilder {

    /** Questions plus their server-only keys (by question id) and the items the check covers, in input order. */
    public record BuiltCheck(List<JpathQuestionView> questions, Map<String, AnswerKey> answers,
                             List<String> itemCodes) {
    }

    /**
     * Server-only answer of one question. MCQ: {@code correct} option id; board: {@code pairs} left → right id and
     * {@code pairItems} left id → item code; {@code accepts} = FILL_TEXT synonyms ({@code accept_json}), empty
     * otherwise; {@code audioPath} = AUDIO_MCQ file under the content audio store, served only through the check's
     * token + {@code audioSlot} (never sent: the file name is the item code); {@code questionCode} = the grammar
     * bank question ({@code <JG>-C|O|F|X<nn>}) behind the question (retry "used" codes, QUESTION report target), null
     * for vocabulary/kanji/kana; {@code partnerCode} = the confusable partner a CONFUSABLE question contrasts.
     */
    public record AnswerKey(String correct, Map<String, String> pairs, List<String> order, String text,
                            List<String> accepts, JpathItemType itemType, String itemCode,
                            Map<String, String> pairItems, String explanation, String audioPath,
                            String questionCode, String partnerCode) {
    }

    /**
     * Distractor sources: {@code session} = other items of this study session, {@code sameLevel} = visible
     * learnable items of the open level and below, {@code confusables} = item code → its confusable partner, given
     * only when the learner has studied both.
     */
    public record DistractorPool(List<?> session, List<?> sameLevel, Map<String, String> confusables) {
    }

    static final String PAIR_BOARD = "PAIR_BOARD";
    static final String MCQ = "MCQ";
    static final String AUDIO_MCQ = "AUDIO_MCQ";
    static final String ORDER = "ORDER";
    static final String FILL_TEXT = "FILL_TEXT";
    static final int CONFUSABLE_MAX = 10;

    private static final int MAX_DISTRACTORS = 3;
    private static final int GROUP_MCQ = 3;
    private static final int GROUP_AUDIO = 2;
    /**
     * Owner 05/11: questions should test using the pattern like the JLPT, not its Vietnamese meaning. The rotation
     * asks the two JLPT forms (FILL, ORDER_STAR) twice as often as ERROR and MEANING.
     */
    private static final List<String> GRAMMAR_FORMS = List.of("FILL", "SITUATION", "ORDER_STAR", "FILL", "ERROR",
            "SITUATION", "ORDER_STAR", "MEANING");
    private static final Map<String, String> GRAMMAR_INSTRUCTIONS = Map.of(
            "FILL", "（　）に入るものはどれですか · Chọn phần điền vào chỗ trống",
            "ORDER_STAR", "★に入るものはどれですか · Sắp xếp câu, chọn phần ở ô ★",
            "MEANING", "Câu này có nghĩa là gì?",
            "ERROR", "使い方が正しくない文はどれですか · Chọn câu dùng sai",
            "SITUATION", "この場面で何と言いますか · Chọn câu nói hợp tình huống");
    /** A situation question stores "tình huống ‖ prompt" (no column of its own); the context joins the instruction. */
    private static final String CONTEXT_SEP = " ‖ ";
    private static final String ERROR_PROMPT_JA = "使い方が正しくない文はどれですか。";
    private static final Set<String> NOT_ASKED_READING_SOURCES = Set.of("FIRST_READING", "OVERRIDE");
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- public builders -----------------------------------------------------------------------------------

    /** One word–meaning board of min(5, n) pairs, then the quiz alternating JA_VI / VI_JA (starts JA_VI). */
    public BuiltCheck vocabCheck(List<JpathVocabulary> items, DistractorPool pool, boolean timerOn, long seed) {
        Random rnd = new Random(seed);
        List<Card> cards = vocabCards(items);
        Draft d = new Draft();
        if (cards.isEmpty()) {
            return d.build(cards);
        }
        Context ctx = context(cards, pool, JpathVocabulary.class);
        List<Card> board = pickBoard(cards, JpathCheckSizing.vocabBoardPairs(cards.size()), rnd);
        d.board(board, Card::ja, Card::vi, "Nối từ với nghĩa đúng", JpathItemType.VOCAB, rnd);
        alternatingQuiz(d, ctx, priority(cards, board, rnd), JpathCheckSizing.vocabQuizCount(cards.size()),
                timerOn, rnd);
        return d.build(cards);
    }

    /**
     * Kanji–meaning board, kanji–reading board (first level reading whose label is not taken; kanji whose readings
     * all collide, or whose readings come from FIRST_READING/OVERRIDE, stay off it), then the meaning quiz both
     * ways.
     */
    public BuiltCheck kanjiCheck(List<JpathKanji> items, DistractorPool pool, boolean timerOn, long seed) {
        Random rnd = new Random(seed);
        List<Card> cards = kanjiCards(items);
        Draft d = new Draft();
        if (cards.isEmpty()) {
            return d.build(cards);
        }
        Context ctx = context(cards, pool, JpathKanji.class);
        int pairs = JpathCheckSizing.vocabBoardPairs(cards.size());
        List<Card> board = pickBoard(cards, pairs, rnd);
        d.board(board, Card::ja, Card::vi, "Nối chữ Hán với nghĩa đúng", JpathItemType.KANJI, rnd);
        Map<Card, String> readings = readingBoard(items, cards, pairs, rnd);
        if (!readings.isEmpty()) {
            d.board(new ArrayList<>(readings.keySet()), Card::ja, readings::get, "Nối chữ Hán với cách đọc đúng",
                    JpathItemType.KANJI, rnd);
        }
        alternatingQuiz(d, ctx, priority(cards, board, rnd), JpathCheckSizing.kanjiQuizCount(cards.size()),
                timerOn, rnd);
        return d.build(cards);
    }

    /**
     * clamp(2n, 4, 10) questions; question i asks pattern i % n in form FILL, ORDER_STAR, MEANING, ERROR
     * [(i + i / n) % 4] (each round shifts the forms, so a pattern never gets the same form twice by rotation).
     * Choice per slot: same form not in {@code usedCodes} → another form not used → same form used before → any
     * form used before; a question never repeats within one check, and a slot with nothing left is dropped.
     *
     * @param bank grammar code → its CHECK-bank questions (visible), in sort order
     */
    public BuiltCheck grammarCheck(List<JpathGrammar> items, Map<String, List<JpathGrammarQuestion>> bank,
                                   Set<String> usedCodes, boolean timerOn, long seed) {
        return grammarQuestions(items, bank, usedCodes, -1, timerOn, seed);
    }

    /**
     * {@code count} choice questions with the micro-check rotation and fallback of {@link #grammarCheck}
     * ({@code count} &lt; 0 = clamp(2n, 4, 10)). count = n gives one question per pattern (grammar review, skip one,
     * free quiz); one pattern with count 3 gives three different questions (Luyện thêm / EXTRA bank).
     */
    public BuiltCheck grammarQuestions(List<JpathGrammar> items, Map<String, List<JpathGrammarQuestion>> bank,
                                       Set<String> usedCodes, int count, boolean timerOn, long seed) {
        Random rnd = new Random(seed);
        Set<String> used = usedCodes == null ? Set.of() : usedCodes;
        List<JpathGrammar> patterns = new ArrayList<>();
        Map<String, List<Mcq>> valid = new HashMap<>();
        for (JpathGrammar g : distinct(items, JpathGrammar::getCode)) {
            List<Mcq> qs = new ArrayList<>();
            for (JpathGrammarQuestion q : bank == null ? List.<JpathGrammarQuestion>of()
                    : bank.getOrDefault(g.getCode(), List.of())) {
                Mcq m = grammarMcq(q);
                if (m != null) {
                    qs.add(m);
                }
            }
            if (!qs.isEmpty()) {
                patterns.add(g);
                valid.put(g.getCode(), qs);
            }
        }
        Draft d = new Draft();
        if (patterns.isEmpty()) {
            return new BuiltCheck(List.of(), Map.of(), List.of());
        }
        if (count < 0) {
            count = JpathCheckSizing.grammarQuestionCount(patterns.size());
        }
        Set<String> chosen = new HashSet<>();
        List<String> covered = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JpathGrammar g = patterns.get(i % patterns.size());
            String form = GRAMMAR_FORMS.get((i + i / patterns.size()) % GRAMMAR_FORMS.size());
            List<Mcq> left = valid.get(g.getCode()).stream().filter(m -> !chosen.contains(m.q.getCode())).toList();
            Mcq pick = first(left, m -> form.equals(m.q.getQuestionType()) && !used.contains(m.q.getCode()));
            if (pick == null) {
                pick = first(left, m -> !used.contains(m.q.getCode()));
            }
            if (pick == null) {
                pick = first(left, m -> form.equals(m.q.getQuestionType()));
            }
            if (pick == null) {
                pick = first(left, m -> true);
            }
            if (pick == null) {
                continue;
            }
            chosen.add(pick.q.getCode());
            if (!covered.contains(g.getCode())) {
                covered.add(g.getCode());
            }
            JpathGrammarQuestion q = pick.q;
            Integer timer = timerOn ? JpathCheckSizing.grammarTimerSec(g.getLevel(),
                    q.getPromptJa().codePointCount(0, q.getPromptJa().length())) : null;
            List<Integer> order = shuffledIndexes(pick.labels.size(), rnd);
            List<JpathOptionView> options = new ArrayList<>();
            String correct = null;
            for (int k = 0; k < order.size(); k++) {
                String id = "o" + (k + 1);
                options.add(new JpathOptionView(id, pick.labels.get(order.get(k))));
                if (order.get(k).intValue() == q.getAnswerIndex().intValue()) {
                    correct = id;
                }
            }
            String baseInstruction = GRAMMAR_INSTRUCTIONS.getOrDefault(q.getQuestionType(), "Chọn đáp án đúng");
            // An ERROR prompt written in Vietnamese ("Câu nào dùng sai…") is asked in Japanese, like the exam.
            boolean viPrompt = "ERROR".equals(q.getQuestionType()) && !q.getPromptJa().matches(".*[\\u3040-\\u30ff\\u4e00-\\u9fff].*");
            String rawJa = viPrompt ? ERROR_PROMPT_JA : q.getPromptJa();
            String rawFuri = viPrompt ? null : q.getPromptFurigana();
            int cut = rawJa.indexOf(CONTEXT_SEP);
            String instruction = cut < 0 ? baseInstruction
                    : "Tình huống: " + rawJa.substring(0, cut).trim() + " · " + baseInstruction;
            String promptJa = cut < 0 ? rawJa : rawJa.substring(cut + CONTEXT_SEP.length());
            String promptFuri = rawFuri == null ? null : rawFuri.contains(CONTEXT_SEP)
                    ? rawFuri.substring(rawFuri.indexOf(CONTEXT_SEP) + CONTEXT_SEP.length()) : rawFuri;
            d.add(qid -> new JpathQuestionView(qid, MCQ, null, instruction, promptJa, promptFuri,
                            null, options, null, timer),
                    new AnswerKey(correct, null, null, null, List.of(), JpathItemType.GRAMMAR, g.getCode(), null,
                            q.getExplanationVi(), null, q.getCode(), null));
        }
        return new BuiltCheck(d.questions, d.answers, covered);
    }

    /** One kana row: ceil(c/5) kana–romaji boards of even size (first ones larger). No timer. */
    public BuiltCheck kanaRowCheck(List<JpathKana> row, long seed) {
        Random rnd = new Random(seed);
        List<JpathKana> kana = new ArrayList<>(distinct(row, JpathKana::getCode).stream()
                .filter(k -> hasText(k.getKana()) && hasText(k.getRomaji())).toList());
        Collections.shuffle(kana, rnd);
        Draft d = new Draft();
        List<Card> all = new ArrayList<>();
        int from = 0;
        for (int size : JpathCheckSizing.kanaBoardSizes(kana.size())) {
            List<Card> board = kana.subList(from, from + size).stream()
                    .map(k -> new Card(k.getCode(), JpathItemType.KANA, null, k.getKana(), null, k.getRomaji(),
                            k.getRomaji(), k.getKana(), null, null, null))
                    .toList();
            from += size;
            all.addAll(board);
            d.board(board, Card::ja, Card::vi, "Nối chữ với cách đọc đúng", JpathItemType.KANA, rnd);
        }
        all.sort(Comparator.comparingInt(c -> indexOf(row, c.code)));
        return d.build(all);
    }

    /**
     * Group exercise after 10 new words (#18): one board of min(5, n) pairs, 3 MCQ JA_VI, 2 AUDIO_MCQ (listen,
     * choose the word) — only for words with an audio file (Decision 39), otherwise MCQ VI_JA. No timer.
     */
    public BuiltCheck groupExercise(List<JpathVocabulary> last10, DistractorPool pool, long seed) {
        return groupExercise(last10, pool, false, seed);
    }

    /** {@link #groupExercise(List, DistractorPool, long)} with the V/K quiz timer (#20) on the choice questions. */
    public BuiltCheck groupExercise(List<JpathVocabulary> last10, DistractorPool pool, boolean timerOn, long seed) {
        Random rnd = new Random(seed);
        Integer timer = timerOn ? JpathCheckSizing.VK_TIMER_SEC : null;
        List<Card> cards = vocabCards(last10);
        Draft d = new Draft();
        if (cards.isEmpty()) {
            return d.build(cards);
        }
        Context ctx = context(cards, pool, JpathVocabulary.class);
        List<Card> board = pickBoard(cards, JpathCheckSizing.vocabBoardPairs(cards.size()), rnd);
        d.board(board, Card::ja, Card::vi, "Nối từ với nghĩa đúng", JpathItemType.VOCAB, rnd);
        Chooser chooser = new Chooser(priority(cards, board, rnd));
        for (int i = 0; i < GROUP_MCQ; i++) {
            chooser.ask(d, ctx, JpathQuizDirection.JA_VI, false, timer, rnd);
        }
        for (int i = 0; i < GROUP_AUDIO; i++) {
            if (!chooser.ask(d, ctx, JpathQuizDirection.VI_JA, true, timer, rnd)) {
                chooser.ask(d, ctx, JpathQuizDirection.VI_JA, false, timer, rnd);
            }
        }
        return d.build(cards);
    }

    /**
     * Choice quiz without boards (skip one, topic check, free quiz, redo): up to {@code count} MCQs over
     * {@code items} (all {@link JpathVocabulary} or all {@link JpathKanji}); JA_VI and VI_JA ask one direction,
     * MIXED (or null) alternates starting with JA_VI. An item is never asked twice in the same direction; the quiz
     * ends early when no item is left to ask.
     */
    public BuiltCheck quiz(List<?> items, DistractorPool pool, int count, JpathQuizDirection direction,
                           boolean timerOn, long seed) {
        Random rnd = new Random(seed);
        Class<?> kind = kindOf(items);
        List<Card> cards = kind == JpathKanji.class ? kanjiCards(typed(items, JpathKanji.class))
                : vocabCards(typed(items, JpathVocabulary.class));
        Draft d = new Draft();
        if (cards.isEmpty() || count <= 0) {
            return d.build(cards);
        }
        Context ctx = context(cards, pool, kind);
        Chooser chooser = new Chooser(priority(cards, List.of(), rnd));
        Integer timer = timerOn ? JpathCheckSizing.VK_TIMER_SEC : null;
        JpathQuizDirection mode = direction == null ? JpathQuizDirection.MIXED : direction;
        for (int produced = 0; produced < count; produced++) {
            JpathQuizDirection dir = mode != JpathQuizDirection.MIXED ? mode
                    : produced % 2 == 0 ? JpathQuizDirection.JA_VI : JpathQuizDirection.VI_JA;
            if (!chooser.ask(d, ctx, dir, false, timer, rnd)) {
                break;
            }
        }
        return d.build(cards);
    }

    /**
     * Confusable drill (#19): one VI_JA question per pair (answer i with partner i, same type), options = the
     * answer, its partner and up to 2 other distractors; at most {@value #CONFUSABLE_MAX} questions in input order.
     * A pair whose partner clashes with the answer (same label or meaning) is skipped. The key keeps the partner
     * ({@code partnerCode}) so a wrong answer can show the two side by side.
     */
    public BuiltCheck confusableQuiz(List<?> answers, List<?> partners, DistractorPool pool, long seed) {
        Random rnd = new Random(seed);
        Class<?> kind = kindOf(answers);
        List<Card> answerCards = new ArrayList<>();
        Map<String, Card> partnerOf = new HashMap<>();
        for (int i = 0; i < Math.min(answers.size(), partners.size()); i++) {
            List<Card> a = cardsOf(Collections.singletonList(answers.get(i)), kind);
            List<Card> b = cardsOf(Collections.singletonList(partners.get(i)), kind);
            if (!a.isEmpty() && !b.isEmpty() && !partnerOf.containsKey(a.get(0).code)) {
                answerCards.add(a.get(0));
                partnerOf.put(a.get(0).code, b.get(0));
            }
        }
        Draft d = new Draft();
        Context ctx = context(answerCards, pool, kind);
        for (Card answer : answerCards) {
            if (d.questions.size() >= CONFUSABLE_MAX) {
                break;
            }
            Card partner = partnerOf.get(answer.code);
            // Confusable partners often share a reading (雨/飴): only the same word or the same meaning is unusable.
            if (partner.ja.equals(answer.ja) || partner.meaningKey.equals(answer.meaningKey)
                    || partner.vi.equals(answer.vi)) {
                continue;
            }
            List<Card> opts = new ArrayList<>();
            opts.add(partner);
            for (Card c : distractors(answer, ctx, rnd)) {
                if (opts.size() >= MAX_DISTRACTORS) {
                    break;
                }
                if (!c.code.equals(partner.code) && !c.clashes(partner) && opts.stream().noneMatch(c::clashes)) {
                    opts.add(c);
                }
            }
            d.mcq(answer, opts, JpathQuizDirection.VI_JA, false, null, partner.code, rnd);
        }
        return d.build(answerCards);
    }

    /**
     * "Luyện ngay" (#19): one ORDER question (pieces as p1.. in random order, answer = piece ids in sentence order)
     * and one FILL_TEXT question (answer {@code answer_text}, synonyms {@code accept_json}), each picked by the seed
     * among the valid rows. Empty when either bank has no valid row. No timer.
     */
    public BuiltCheck grammarPractice(JpathGrammar g, List<JpathGrammarQuestion> orderRows,
                                      List<JpathGrammarQuestion> fillRows, long seed) {
        Random rnd = new Random(seed);
        List<JpathGrammarQuestion> orders = orderRows == null ? List.of() : orderRows.stream()
                .filter(q -> q != null && q.getCode() != null && orderOf(q) != null).toList();
        List<JpathGrammarQuestion> fills = fillRows == null ? List.of() : fillRows.stream()
                .filter(q -> q != null && q.getCode() != null && hasText(q.getAnswerText())
                        && hasText(q.getPromptJa())).toList();
        if (g == null || orders.isEmpty() || fills.isEmpty()) {
            return new BuiltCheck(List.of(), Map.of(), List.of());
        }
        Draft d = new Draft();
        JpathGrammarQuestion o = orders.get(rnd.nextInt(orders.size()));
        List<String> pieces = jsonStrings(o.getPiecesJson());
        List<Integer> shown = shuffledIndexes(pieces.size(), rnd);
        List<JpathOptionView> options = new ArrayList<>();
        Map<Integer, String> idOf = new HashMap<>();
        for (int k = 0; k < shown.size(); k++) {
            String id = "p" + (k + 1);
            idOf.put(shown.get(k), id);
            options.add(new JpathOptionView(id, pieces.get(shown.get(k))));
        }
        List<String> answer = orderOf(o).stream().map(idOf::get).toList();
        List<JpathOptionView> frozen = List.copyOf(options);
        d.add(qid -> new JpathQuestionView(qid, ORDER, null, "Sắp xếp các phần thành câu đúng", null, null, null,
                        frozen, null, null),
                new AnswerKey(null, null, answer, null, List.of(), JpathItemType.GRAMMAR, g.getCode(), null,
                        o.getExplanationVi(), null, o.getCode(), null));
        JpathGrammarQuestion f = fills.get(rnd.nextInt(fills.size()));
        d.add(qid -> new JpathQuestionView(qid, FILL_TEXT, null, "Điền vào chỗ trống", f.getPromptJa(),
                        f.getPromptFurigana(), null, null, null, null),
                new AnswerKey(null, null, null, f.getAnswerText().trim(), jsonStrings(f.getAcceptJson()),
                        JpathItemType.GRAMMAR, g.getCode(), null, f.getExplanationVi(), null, f.getCode(), null));
        return new BuiltCheck(List.copyOf(d.questions), Collections.unmodifiableMap(d.answers), List.of(g.getCode()));
    }

    /** Sentence order of an ORDER row (a permutation of its piece indexes), null when the row is broken. */
    private static List<Integer> orderOf(JpathGrammarQuestion q) {
        List<String> pieces = jsonStrings(q.getPiecesJson());
        if (pieces.size() < 2 || pieces.stream().anyMatch(String::isBlank) || !hasText(q.getAnswerOrderJson())) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(q.getAnswerOrderJson());
            if (node == null || !node.isArray() || node.size() != pieces.size()) {
                return null;
            }
            List<Integer> out = new ArrayList<>();
            for (JsonNode n : node) {
                if (!n.canConvertToInt() || n.asInt() < 0 || n.asInt() >= pieces.size() || out.contains(n.asInt())) {
                    return null;
                }
                out.add(n.asInt());
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Class<?> kindOf(List<?> items) {
        return items != null && items.stream().anyMatch(o -> o instanceof JpathKanji) ? JpathKanji.class
                : JpathVocabulary.class;
    }

    private static <T> List<T> typed(List<?> items, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Object o : items == null ? List.of() : items) {
            if (type.isInstance(o)) {
                out.add(type.cast(o));
            }
        }
        return out;
    }

    // ---- quiz ----------------------------------------------------------------------------------------------

    private void alternatingQuiz(Draft d, Context ctx, List<Card> priority, int count, boolean timerOn,
                                 Random rnd) {
        Chooser chooser = new Chooser(priority);
        Integer timer = timerOn ? JpathCheckSizing.VK_TIMER_SEC : null;
        for (int produced = 0; produced < count; produced++) {
            JpathQuizDirection dir = produced % 2 == 0 ? JpathQuizDirection.JA_VI : JpathQuizDirection.VI_JA;
            if (!chooser.ask(d, ctx, dir, false, timer, rnd)) {
                break;
            }
        }
    }

    /** Picks the next item to ask: fewest uses first, then priority order; never the same item + kind twice. */
    private final class Chooser {
        private final List<Card> priority;
        private final Map<String, Integer> uses = new HashMap<>();
        private final Set<String> asked = new HashSet<>();

        Chooser(List<Card> priority) {
            this.priority = priority;
        }

        boolean ask(Draft d, Context ctx, JpathQuizDirection dir, boolean audio, Integer timer, Random rnd) {
            String kind = audio ? AUDIO_MCQ : dir.name();
            List<Card> order = priority.stream()
                    .filter(c -> !asked.contains(c.code + "|" + kind))
                    .filter(c -> !audio || c.hasAudio())
                    .sorted(Comparator.comparingInt(c -> uses.getOrDefault(c.code, 0)))
                    .toList();
            for (Card c : order) {
                List<Card> distractors = distractors(c, ctx, rnd);
                if (distractors.isEmpty()) {
                    continue;
                }
                asked.add(c.code + "|" + kind);
                uses.merge(c.code, 1, Integer::sum);
                d.mcq(c, distractors, dir, audio, timer, rnd);
                return true;
            }
            return false;
        }
    }

    /**
     * Up to 3 distractors: same session → confusable partner → same word type at the answer's level or below →
     * same level. A candidate never shares meaning key, normalized expression/reading or label with the answer or
     * with another chosen distractor.
     */
    private List<Card> distractors(Card answer, Context ctx, Random rnd) {
        List<List<Card>> tiers = new ArrayList<>();
        tiers.add(shuffled(ctx.session, rnd));
        String partner = ctx.confusables.get(answer.code);
        Card partnerCard = partner == null ? null : ctx.byCode.get(partner);
        tiers.add(partnerCard == null ? List.of() : List.of(partnerCard));
        int rank = levelRank(answer.level);
        tiers.add(shuffled(ctx.sameLevel.stream().filter(c -> hasText(answer.wordType)
                && answer.wordType.equals(c.wordType) && levelRank(c.level) <= rank).toList(), rnd));
        tiers.add(shuffled(ctx.sameLevel.stream().filter(c -> Objects.equals(answer.level, c.level)).toList(), rnd));
        List<Card> out = new ArrayList<>();
        for (List<Card> tier : tiers) {
            for (Card c : tier) {
                if (out.size() >= MAX_DISTRACTORS) {
                    return out;
                }
                if (!c.code.equals(answer.code) && !c.clashes(answer) && out.stream().noneMatch(c::clashes)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    // ---- boards --------------------------------------------------------------------------------------------

    /** Up to {@code size} cards in random order whose labels on both sides are unique on the board. */
    private static List<Card> pickBoard(List<Card> cards, int size, Random rnd) {
        List<Card> out = new ArrayList<>();
        for (Card c : shuffled(cards, rnd)) {
            if (out.size() >= size) {
                break;
            }
            if (out.stream().noneMatch(o -> o.ja.equals(c.ja) || o.meaningKey.equals(c.meaningKey))) {
                out.add(c);
            }
        }
        return out;
    }

    /** Kanji → reading label for the reading board; kanji with fewer readings claim their label first. */
    private static Map<Card, String> readingBoard(List<JpathKanji> items, List<Card> cards, int size, Random rnd) {
        Map<String, List<String>> readings = new HashMap<>();
        for (JpathKanji k : items) {
            // Set.of(..).contains(null) throws: a kanji without a reading source is asked like LEVEL_WORDS.
            String source = k == null ? null : k.getLevelReadingSource();
            if (k != null && k.getCode() != null && (source == null || !NOT_ASKED_READING_SOURCES.contains(source))) {
                List<String> labels = new ArrayList<>();
                for (String r : jsonStrings(k.getLevelReadingsJson())) {
                    String label = JpathKanaText.kanjiReadingLabel(r);
                    if (!label.isBlank() && !labels.contains(label)) {
                        labels.add(label);
                    }
                }
                if (!labels.isEmpty()) {
                    readings.putIfAbsent(k.getCode(), labels);
                }
            }
        }
        List<Card> order = new ArrayList<>(shuffled(cards.stream()
                .filter(c -> readings.containsKey(c.code)).toList(), rnd));
        order.sort(Comparator.comparingInt(c -> readings.get(c.code).size()));
        Map<Card, String> out = new LinkedHashMap<>();
        for (Card c : order) {
            if (out.size() >= size) {
                break;
            }
            readings.get(c.code).stream().filter(l -> !out.containsValue(l)).findFirst()
                    .ifPresent(l -> out.put(c, l));
        }
        return out;
    }

    // ---- draft (assigns q/b/o/l/r ids) ---------------------------------------------------------------------

    private interface ViewMaker {
        JpathQuestionView make(String questionId);
    }

    private interface Label {
        String of(Card c);
    }

    private static final class Draft {
        private final List<JpathQuestionView> questions = new ArrayList<>();
        private final Map<String, AnswerKey> answers = new LinkedHashMap<>();
        private final Set<String> asked = new LinkedHashSet<>();
        private int boards;
        private int audioSlots;

        void add(ViewMaker view, AnswerKey key) {
            String id = "q" + (questions.size() + 1);
            questions.add(view.make(id));
            answers.put(id, key);
        }

        void board(List<Card> cards, Label left, Label right, String instruction, JpathItemType type, Random rnd) {
            if (cards.isEmpty()) {
                return;
            }
            List<Card> l = shuffled(cards, rnd);
            List<Card> r = shuffled(cards, rnd);
            List<JpathOptionView> leftViews = new ArrayList<>();
            List<JpathOptionView> rightViews = new ArrayList<>();
            Map<Card, String> rightIds = new HashMap<>();
            for (int i = 0; i < r.size(); i++) {
                rightIds.put(r.get(i), "r" + (i + 1));
                rightViews.add(new JpathOptionView("r" + (i + 1), right.of(r.get(i))));
            }
            Map<String, String> pairs = new LinkedHashMap<>();
            Map<String, String> pairItems = new LinkedHashMap<>();
            for (int i = 0; i < l.size(); i++) {
                String id = "l" + (i + 1);
                leftViews.add(new JpathOptionView(id, left.of(l.get(i))));
                pairs.put(id, rightIds.get(l.get(i)));
                pairItems.put(id, l.get(i).code);
                asked.add(l.get(i).code);
            }
            JpathBoardView board = new JpathBoardView("b" + (++boards), List.copyOf(leftViews),
                    List.copyOf(rightViews));
            add(qid -> new JpathQuestionView(qid, PAIR_BOARD, null, instruction, null, null, null, null, board, null),
                    new AnswerKey(null, pairs, null, null, List.of(), type, null, pairItems, null, null, null, null));
        }

        void mcq(Card answer, List<Card> distractors, JpathQuizDirection dir, boolean audio, Integer timer,
                 Random rnd) {
            mcq(answer, distractors, dir, audio, timer, null, rnd);
        }

        void mcq(Card answer, List<Card> distractors, JpathQuizDirection dir, boolean audio, Integer timer,
                 String partner, Random rnd) {
            boolean viLabels = !audio && dir == JpathQuizDirection.JA_VI;
            List<Card> opts = new ArrayList<>(distractors);
            opts.add(answer);
            Collections.shuffle(opts, rnd);
            List<JpathOptionView> options = new ArrayList<>();
            String correct = null;
            for (int i = 0; i < opts.size(); i++) {
                String id = "o" + (i + 1);
                options.add(new JpathOptionView(id, viLabels ? opts.get(i).vi : opts.get(i).ja));
                if (opts.get(i) == answer) {
                    correct = id;
                }
            }
            asked.add(answer.code);
            boolean kanji = answer.type == JpathItemType.KANJI;
            String instruction = audio ? "Nghe và chọn từ đúng"
                    : viLabels ? (kanji ? "Chọn nghĩa đúng của chữ Hán" : "Chọn nghĩa đúng của từ")
                    : (kanji ? "Chọn chữ Hán đúng với nghĩa" : "Chọn từ đúng với nghĩa");
            String prompt = audio ? null : viLabels ? answer.ja : answer.vi;
            String furigana = audio || !viLabels ? null : answer.furigana;
            Integer audioSlot = audio ? ++audioSlots : null;
            String audioPath = audio ? answer.audioPath : null;
            String type = audio ? AUDIO_MCQ : MCQ;
            String direction = audio ? null : dir.name();
            List<JpathOptionView> frozen = List.copyOf(options);
            add(qid -> new JpathQuestionView(qid, type, direction, instruction, prompt, furigana, audioSlot, frozen,
                            null, timer),
                    new AnswerKey(correct, null, null, null, List.of(), answer.type, answer.code, null, null,
                            audioPath, null, partner));
        }

        /** Item codes that appear in a question, in input order. */
        BuiltCheck build(List<Card> inputOrder) {
            List<String> codes = inputOrder.stream().map(c -> c.code).filter(asked::contains).distinct().toList();
            return new BuiltCheck(List.copyOf(questions), Collections.unmodifiableMap(answers), codes);
        }
    }

    // ---- cards ---------------------------------------------------------------------------------------------

    /** What a vocabulary word or kanji looks like in a check; {@code vi} = first sense of its meaning. */
    private record Card(String code, JpathItemType type, String level, String ja, String furigana, String vi,
                        String meaningKey, String normExpr, String normRead, String wordType, String audioPath) {

        boolean clashes(Card o) {
            return meaningKey.equals(o.meaningKey) || ja.equals(o.ja) || vi.equals(o.vi)
                    || same(normExpr, o.normExpr) || same(normRead, o.normRead)
                    || nested(core(vi), core(o.vi)) || nested(normRead, o.normRead)
                    || sameSenseGroup(this, o);
        }

        /**
         * Near-synonyms written differently (じゃ、また / さようなら both "tạm biệt"; ありがとう / どうも): a learner cannot
         * tell which one is meant, so two cards of one everyday group never meet as answer and distractor.
         */
        static boolean sameSenseGroup(Card a, Card b) {
            String ga = JpathSenseGroups.of(a.normRead, a.normExpr, core(a.vi));
            return ga != null && ga.equals(JpathSenseGroups.of(b.normRead, b.normExpr, core(b.vi)));
        }

        /** Meaning without notes in brackets, polite "ạ", punctuation and case: "chào buổi sáng ạ (lịch sự)" -> "chào buổi sáng". */
        static String core(String v) {
            if (v == null) {
                return "";
            }
            String t = v.toLowerCase(java.util.Locale.ROOT).replaceAll("\\([^)]*\\)|\\[[^]]*]", " ")
                    .replaceAll("[\\p{Punct}\\u3000-\\u303F]", " ").replaceAll("\\s+", " ").trim();
            return t.replaceAll("(\\s(ạ|à|nhé|nha))+$", "").trim();
        }

        /** One side is the other plus extra words (e.g. おはよう / おはようございます): too close to tell apart. */
        private static boolean nested(String a, String b) {
            if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
                return false;
            }
            if (a.equals(b)) {
                return true;
            }
            String s = a.length() < b.length() ? a : b;
            String l = a.length() < b.length() ? b : a;
            return s.codePointCount(0, s.length()) >= 2 && l.startsWith(s);
        }

        /** A safe audio path exists (the same check the play URL uses). */
        boolean hasAudio() {
            return JpathAudio.url(audioPath, null) != null;
        }

        private static boolean same(String a, String b) {
            return a != null && !a.isEmpty() && a.equals(b);
        }
    }

    private record Context(List<Card> session, List<Card> sameLevel, Map<String, Card> byCode,
                           Map<String, String> confusables) {
    }

    private static Context context(List<Card> checkCards, DistractorPool pool, Class<?> kind) {
        Map<String, Card> session = new LinkedHashMap<>();
        checkCards.forEach(c -> session.putIfAbsent(c.code, c));
        List<?> extraSession = pool == null || pool.session() == null ? List.of() : pool.session();
        List<?> same = pool == null || pool.sameLevel() == null ? List.of() : pool.sameLevel();
        cardsOf(extraSession, kind).forEach(c -> session.putIfAbsent(c.code, c));
        List<Card> sameCards = cardsOf(same, kind);
        Map<String, Card> byCode = new HashMap<>();
        sameCards.forEach(c -> byCode.putIfAbsent(c.code, c));
        session.values().forEach(c -> byCode.put(c.code, c));
        Map<String, String> confusables = pool == null || pool.confusables() == null ? Map.of() : pool.confusables();
        return new Context(new ArrayList<>(session.values()), sameCards, byCode, confusables);
    }

    private static List<Card> cardsOf(List<?> rows, Class<?> kind) {
        List<Card> out = new ArrayList<>();
        for (Object o : rows) {
            Card c = kind == JpathVocabulary.class && o instanceof JpathVocabulary v ? vocabCard(v)
                    : kind == JpathKanji.class && o instanceof JpathKanji k ? kanjiCard(k) : null;
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Card> vocabCards(List<JpathVocabulary> items) {
        return cardsOf(distinct(items, JpathVocabulary::getCode), JpathVocabulary.class);
    }

    private static List<Card> kanjiCards(List<JpathKanji> items) {
        return cardsOf(distinct(items, JpathKanji::getCode), JpathKanji.class);
    }

    /** Null when the word cannot be asked (no code, expression or meaning). */
    private static Card vocabCard(JpathVocabulary v) {
        String vi = JpathKanaText.firstSense(v.getMeaningVi());
        if (v.getCode() == null || !hasText(v.getExpression()) || vi.isEmpty()) {
            return null;
        }
        String ja = v.getExpression().trim();
        String furigana = JpathKanaText.containsCjk(ja) && hasText(v.getReading())
                ? ja + "(" + v.getReading().trim() + ")" : null;
        return new Card(v.getCode(), JpathItemType.VOCAB, v.getLevel(), ja, furigana, vi,
                JpathKanaText.meaningKey(v.getMeaningVi()), blankToNull(v.getNormalizedExpression()),
                blankToNull(v.getNormalizedReading()), blankToNull(v.getWordTypeLabel()), v.getAudioPath());
    }

    /** Null when the kanji cannot be asked (no code, character or meaning). */
    private static Card kanjiCard(JpathKanji k) {
        String vi = JpathKanaText.firstSense(k.getMeaningsVi());
        if (k.getCode() == null || !hasText(k.getCharacter()) || vi.isEmpty()) {
            return null;
        }
        String ch = k.getCharacter().trim();
        return new Card(k.getCode(), JpathItemType.KANJI, k.getLevel(), ch, null, vi,
                JpathKanaText.meaningKey(k.getMeaningsVi()), ch, null, null, null);
    }

    // ---- grammar helpers -----------------------------------------------------------------------------------

    private record Mcq(JpathGrammarQuestion q, List<String> labels) {
    }

    /** Null when the bank row cannot be shown as a choice question (bad options or answer index). */
    private static Mcq grammarMcq(JpathGrammarQuestion q) {
        if (q == null || q.getCode() == null || !hasText(q.getPromptJa()) || q.getAnswerIndex() == null) {
            return null;
        }
        List<String> labels = jsonStrings(q.getOptionsJson());
        if (labels.size() < 2 || q.getAnswerIndex() < 0 || q.getAnswerIndex() >= labels.size()
                || labels.stream().anyMatch(l -> l.isBlank()) || new HashSet<>(labels).size() != labels.size()) {
            return null;
        }
        return new Mcq(q, labels);
    }

    // ---- small helpers -------------------------------------------------------------------------------------

    private static List<Card> priority(List<Card> cards, List<Card> board, Random rnd) {
        List<Card> out = new ArrayList<>(shuffled(cards.stream().filter(c -> !board.contains(c)).toList(), rnd));
        out.addAll(shuffled(board, rnd));
        return out;
    }

    private static <T> List<T> shuffled(Collection<T> in, Random rnd) {
        List<T> out = new ArrayList<>(in);
        Collections.shuffle(out, rnd);
        return out;
    }

    private static List<Integer> shuffledIndexes(int n, Random rnd) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    private static <T> T first(List<T> list, Predicate<T> test) {
        return list.stream().filter(test).findFirst().orElse(null);
    }

    private static <T> List<T> distinct(List<T> items, java.util.function.Function<T, String> code) {
        if (items == null) {
            return List.of();
        }
        Map<String, T> out = new LinkedHashMap<>();
        for (T t : items) {
            if (t != null && code.apply(t) != null) {
                out.putIfAbsent(code.apply(t), t);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static int indexOf(List<JpathKana> row, String code) {
        for (int i = 0; i < row.size(); i++) {
            if (row.get(i) != null && code.equals(row.get(i).getCode())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** N5 = 1 … N1 = 5; unknown = 1. */
    private static int levelRank(String level) {
        if (level != null && level.length() == 2 && level.charAt(0) == 'N') {
            int n = level.charAt(1) - '0';
            if (n >= 1 && n <= 5) {
                return 6 - n;
            }
        }
        return 1;
    }

    private static List<String> jsonStrings(String json) {
        List<String> out = new ArrayList<>();
        if (!hasText(json)) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node != null && node.isArray()) {
                node.forEach(n -> {
                    if (n.isTextual()) {
                        out.add(n.asText());
                    }
                });
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return hasText(s) ? s : null;
    }
}
