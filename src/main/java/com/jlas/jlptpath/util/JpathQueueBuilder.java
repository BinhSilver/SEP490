package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSessionTab;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.util.JpathQueueInput.DueCard;
import com.jlas.jlptpath.util.JpathQueueInput.KanaRowPlan;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * [JLAS-W0108] Pure ordering of a study session queue: reviews first, then new items per tab with their checks.
 * The list helpers return new lists and renumber {@code step} (0-based).
 */
public final class JpathQueueBuilder {

    static final int GROUP_SIZE = 10;
    static final int NOTEBOOK_LIMIT = 50;

    private static final List<JpathTab> CARD_TABS = List.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR, JpathTab.KANA);

    private JpathQueueBuilder() {
    }

    private record Card(JpathTab tab, DueCard card) {
    }

    private static final Comparator<Card> REVIEW_ORDER = Comparator
            .comparing((Card c) -> !c.card().notebook())
            .thenComparing(c -> c.card().nextReviewAt(), Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()))
            .thenComparing(c -> c.tab().ordinal())
            .thenComparing(c -> c.card().code());

    public static List<JpathQueueStep> build(JpathQueueInput in) {
        List<JpathQueueStep> q = new ArrayList<>();
        JpathSessionMode mode = in.mode();
        if (mode == JpathSessionMode.NOTEBOOK) {
            reviews(in, q, true);
        } else if (mode == JpathSessionMode.TODAY) {
            reviews(in, q, false);
            boolean skillDay = in.dayKind() == JpathDayKind.SKILL
                    && (in.tab() == JpathSessionTab.COMBINED || in.tab() == JpathSessionTab.SKILL);
            if (skillDay) {
                if (in.skillQuizNeeded()) q.add(check(JpathTab.SKILL, JpathCheckKind.FREE_QUIZ, null));
            } else {
                news(in, q);
            }
        } else {
            news(in, q);
        }
        return renumber(q);
    }

    /** Tabs whose review cards belong to this session (several tabs are merged and sorted together). */
    private static List<JpathTab> reviewTabs(JpathSessionTab tab) {
        return switch (tab) {
            case COMBINED, SKILL -> CARD_TABS;
            default -> List.of(JpathTab.valueOf(tab.name()));
        };
    }

    private static void reviews(JpathQueueInput in, List<JpathQueueStep> q, boolean notebookOnly) {
        List<Card> cards = new ArrayList<>();
        for (JpathTab t : reviewTabs(in.tab())) {
            for (DueCard c : in.due().getOrDefault(t, List.of())) {
                if (!notebookOnly || c.notebook()) cards.add(new Card(t, c));
            }
        }
        cards.sort(REVIEW_ORDER);
        Map<JpathTab, Integer> room = new HashMap<>(in.reviewRoom());
        int taken = 0;
        for (Card c : cards) {
            if (notebookOnly) {
                if (taken == NOTEBOOK_LIMIT) break;
            } else if (!c.card().hard()) {
                int left = room.getOrDefault(c.tab(), 0);
                if (left <= 0) continue;
                room.put(c.tab(), left - 1);
            }
            taken++;
            JpathStepKind kind = !notebookOnly && c.card().retype() ? JpathStepKind.RETYPE : JpathStepKind.REVIEW;
            q.add(new JpathQueueStep(0, kind, c.tab(), c.card().type(), c.card().code(), null, null, false));
        }
        if (!notebookOnly && !in.grammarDue().isEmpty() && reviewTabs(in.tab()).contains(JpathTab.GRAMMAR)) {
            q.add(check(JpathTab.GRAMMAR, JpathCheckKind.GRAMMAR_REVIEW, null));
        }
    }

    private static List<JpathTab> newTabs(JpathQueueInput in) {
        return switch (in.tab()) {
            case COMBINED -> in.kanaPhase() ? List.of(JpathTab.KANA, JpathTab.VOCAB)
                    : List.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);
            case SKILL -> List.of();
            default -> List.of(JpathTab.valueOf(in.tab().name()));
        };
    }

    private static void news(JpathQueueInput in, List<JpathQueueStep> q) {
        for (JpathTab t : newTabs(in)) {
            switch (t) {
                case KANA -> kana(in.kanaRows(), q);
                case VOCAB -> vocab(in.newCodes().getOrDefault(t, List.of()), q);
                case KANJI -> simple(t, JpathItemType.KANJI, in.newCodes().getOrDefault(t, List.of()), q);
                case GRAMMAR -> grammar(in.newCodes().getOrDefault(t, List.of()), in.grammarPracticeCodes(), q);
                default -> { }
            }
        }
    }

    private static void kana(List<KanaRowPlan> rows, List<JpathQueueStep> q) {
        for (KanaRowPlan row : rows) {
            for (String code : row.kanaCodes()) q.add(card(JpathTab.KANA, JpathItemType.KANA, code));
            q.add(check(JpathTab.KANA, JpathCheckKind.KANA_ROW, row.rowCode()));
        }
    }

    private static void vocab(List<String> codes, List<JpathQueueStep> q) {
        for (int i = 0; i < codes.size(); i++) {
            q.add(card(JpathTab.VOCAB, JpathItemType.VOCAB, codes.get(i)));
            if ((i + 1) % GROUP_SIZE == 0) {
                q.add(new JpathQueueStep(0, JpathStepKind.GROUP_EXERCISE, JpathTab.VOCAB, null, null,
                        JpathCheckKind.GROUP_EXERCISE, null, false));
            }
        }
        if (!codes.isEmpty()) q.add(check(JpathTab.VOCAB, JpathCheckKind.MICRO_CHECK, null));
    }

    private static void simple(JpathTab tab, JpathItemType type, List<String> codes, List<JpathQueueStep> q) {
        for (String code : codes) q.add(card(tab, type, code));
        if (!codes.isEmpty()) q.add(check(tab, JpathCheckKind.MICRO_CHECK, null));
    }

    private static void grammar(List<String> codes, Set<String> practice, List<JpathQueueStep> q) {
        for (String code : codes) {
            q.add(card(JpathTab.GRAMMAR, JpathItemType.GRAMMAR, code));
            if (practice.contains(code)) {
                q.add(new JpathQueueStep(0, JpathStepKind.MICRO_CHECK, JpathTab.GRAMMAR, JpathItemType.GRAMMAR, code,
                        JpathCheckKind.GRAMMAR_PRACTICE, null, false));
            }
        }
        if (!codes.isEmpty()) q.add(check(JpathTab.GRAMMAR, JpathCheckKind.MICRO_CHECK, null));
    }

    private static JpathQueueStep card(JpathTab tab, JpathItemType type, String code) {
        return new JpathQueueStep(0, JpathStepKind.NEW, tab, type, code, null, null, false);
    }

    private static JpathQueueStep check(JpathTab tab, JpathCheckKind kind, String itemCode) {
        return new JpathQueueStep(0, JpathStepKind.MICRO_CHECK, tab, null, itemCode, kind, null, false);
    }

    /** Appends a repeat of {@code of} unless that item already has {@code maxRepeatsPerItem} repeats queued. */
    public static List<JpathQueueStep> appendRepeat(List<JpathQueueStep> q, JpathQueueStep of, int maxRepeatsPerItem) {
        long repeats = q.stream()
                .filter(s -> s.repeat() && s.kind() == of.kind() && s.tab() == of.tab()
                        && Objects.equals(s.itemCode(), of.itemCode()))
                .count();
        List<JpathQueueStep> out = new ArrayList<>(q);
        if (repeats < maxRepeatsPerItem) out.add(of.asRepeat(out.size()));
        return renumber(out);
    }

    /** Inserts {@code steps} right after index {@code position} (−1 = at the front). */
    public static List<JpathQueueStep> insertAfter(List<JpathQueueStep> q, int position, List<JpathQueueStep> steps) {
        List<JpathQueueStep> out = new ArrayList<>(q);
        int at = Math.max(0, Math.min(position + 1, out.size()));
        out.addAll(at, steps);
        return renumber(out);
    }

    /** Moves the first step carrying {@code itemCode} to index {@code position}; unknown code → unchanged copy. */
    public static List<JpathQueueStep> moveToPosition(List<JpathQueueStep> q, String itemCode, int position) {
        List<JpathQueueStep> out = new ArrayList<>(q);
        for (int i = 0; i < out.size(); i++) {
            if (Objects.equals(out.get(i).itemCode(), itemCode)) {
                JpathQueueStep s = out.remove(i);
                out.add(Math.max(0, Math.min(position, out.size())), s);
                break;
            }
        }
        return renumber(out);
    }

    private static List<JpathQueueStep> renumber(List<JpathQueueStep> q) {
        List<JpathQueueStep> out = new ArrayList<>(q.size());
        for (int i = 0; i < q.size(); i++) out.add(q.get(i).atStep(i));
        return out;
    }
}
