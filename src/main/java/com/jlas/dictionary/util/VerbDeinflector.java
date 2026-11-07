package com.jlas.dictionary.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Đưa động từ/tính từ đuôi い đã chia về thể từ điển để tra (行きます, 行った, 行き -&gt; 行く;
 * 食べませんでした -&gt; 食べる; 飲ませられた -&gt; 飲む), theo kiểu luật hậu tố của Yomitan.
 *
 * <p>Mỗi luật thay một hậu tố và cho biết dạng trước khi bỏ hậu tố thuộc loại gì ({@link Form}), nên
 * có thể bóc nhiều lớp liên tiếp. Kết quả chỉ là ứng viên: nơi gọi phải kiểm tra lại trong từ điển
 * rằng từ đó tồn tại và có từ loại khớp {@link Candidate#forms()} (đã có v5k-s mới nhận 行く).</p>
 */
public final class VerbDeinflector {
    private VerbDeinflector() { }

    /** Loại của một dạng trung gian; 5 loại đầu là thể từ điển, đối chiếu với từ loại đã import. */
    public enum Form {
        V1, V5, VK, VS, ADJ_I,
        /** Dạng liên dụng (連用形): 行き, 食べ. */
        STEM,
        /** Dạng ます còn chia tiếp được (ました, ません...). */
        MASU,
        TE,
        TA;

        /** Từ loại import (mã JMdict) có đúng là loại này không. */
        public boolean matchesPartOfSpeech(String code) {
            if (code == null) return false;
            return switch (this) {
                case V1 -> code.equals("v1") || code.equals("v1-s");
                case V5 -> code.startsWith("v5");
                case VK -> code.equals("vk");
                case VS -> code.equals("vs") || code.equals("vs-i") || code.equals("vs-s");
                case ADJ_I -> code.equals("adj-i") || code.equals("adj-ix");
                default -> false;
            };
        }

        boolean isDictionaryForm() {
            return ordinal() <= ADJ_I.ordinal();
        }
    }

    /** Thể từ điển ứng viên và các loại từ mà nó phải thuộc về. */
    public record Candidate(String term, Set<Form> forms) { }

    /** Áp luật lên dạng có hậu tố {@code from} thuộc {@code inflected}; rỗng = chỉ ở query gốc. */
    private record Rule(String from, String to, Set<Form> inflected, Set<Form> base) { }

    private static final int MAX_DEPTH = 6;
    private static final int MAX_STATES = 300;

    private static final String U_ROW = "うくぐすつぬぶむる";
    private static final String I_ROW = "いきぎしちにびみり";
    private static final String A_ROW = "わかがさたなばまら";
    private static final String E_ROW = "えけげせてねべめれ";
    private static final String O_ROW = "おこごそとのぼもろ";

    private static final List<Rule> RULES = buildRules();

    /**
     * Các thể từ điển có thể có của {@code query} (đã normalize), theo thứ tự bóc ít lớp trước,
     * không gồm chính query.
     */
    public static List<Candidate> deinflect(String query) {
        if (query == null || query.isEmpty()) return List.of();

        Map<String, Set<Form>> seen = new LinkedHashMap<>();
        Map<String, Set<Form>> results = new LinkedHashMap<>();
        Deque<State> queue = new ArrayDeque<>();
        queue.add(new State(query, null, 0));

        while (!queue.isEmpty() && seen.size() < MAX_STATES) {
            State state = queue.poll();
            if (state.depth >= MAX_DEPTH) continue;
            for (Rule rule : RULES) {
                if (!state.term.endsWith(rule.from)) continue;
                if (state.term.length() == rule.from.length() && rule.to.isEmpty()) continue;
                // Hậu tố rỗng (食べ -> 食べる): query gốc phải tận cùng bằng kana (không ra 学校る), còn
                // dạng đã bóc ます thì được (来ます -> 来 -> 来る).
                if (rule.from.isEmpty() && state.forms == null && !endsWithHiragana(state.term)) continue;
                if (state.forms != null && Collections.disjoint(state.forms, rule.inflected)) continue;

                String term = state.term.substring(0, state.term.length() - rule.from.length()) + rule.to;
                Set<Form> known = seen.computeIfAbsent(term, key -> EnumSet.noneOf(Form.class));
                if (known.containsAll(rule.base)) continue;
                known.addAll(rule.base);
                queue.add(new State(term, rule.base, state.depth + 1));

                if (!term.equals(query)) {
                    for (Form form : rule.base) {
                        if (form.isDictionaryForm()) {
                            results.computeIfAbsent(term, key -> EnumSet.noneOf(Form.class)).add(form);
                        }
                    }
                }
            }
        }

        List<Candidate> candidates = new ArrayList<>(results.size());
        results.forEach((term, forms) -> candidates.add(new Candidate(term, Collections.unmodifiableSet(forms))));
        return candidates;
    }

    private static boolean endsWithHiragana(String term) {
        int last = term.codePointBefore(term.length());
        return last >= 0x3041 && last <= 0x309F;
    }

    private record State(String term, Set<Form> forms, int depth) { }

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();
        Set<Form> v1 = EnumSet.of(Form.V1, Form.VK); // 来る viết Kanji chia như nhóm II: 来ない, 来ます
        Set<Form> v5 = EnumSet.of(Form.V5);
        Set<Form> vk = EnumSet.of(Form.VK);
        Set<Form> vs = EnumSet.of(Form.VS);
        Set<Form> adjI = EnumSet.of(Form.ADJ_I);
        Set<Form> v1In = EnumSet.of(Form.V1);
        Set<Form> v5In = EnumSet.of(Form.V5);
        Set<Form> stem = EnumSet.of(Form.STEM);
        Set<Form> masu = EnumSet.of(Form.MASU);
        Set<Form> te = EnumSet.of(Form.TE);
        Set<Form> ta = EnumSet.of(Form.TA);
        Set<Form> terminal = EnumSet.noneOf(Form.class);

        // Lịch sự, nguyện vọng, tiếp diễn: bóc về dạng liên dụng / て rồi mới về thể từ điển.
        add(rules, terminal, masu, "ました", "ます", "ません", "ます", "ませんでした", "ます",
                "ましょう", "ます", "まして", "ます");
        add(rules, ta, masu, "ました", "ます");
        add(rules, masu, stem, "ます", "");
        add(rules, adjI, stem, "たい", "");
        add(rules, terminal, stem, "ながら", "", "なさい", "");
        add(rules, v1In, te, "ている", "て", "てる", "て", "でいる", "で", "でる", "で");
        add(rules, v5In, te, "ておく", "て", "でおく", "で", "てしまう", "て", "でしまう", "で", "てある", "て");
        add(rules, terminal, te, "てください", "て", "でください", "で", "ても", "て", "でも", "で");
        add(rules, terminal, ta, "たら", "た", "だら", "だ", "たり", "た", "だり", "だ");

        // Nhóm II (và 来る viết Kanji).
        add(rules, stem, v1, "", "る");
        add(rules, adjI, v1, "ない", "る");
        add(rules, v1In, v1, "られる", "る", "させる", "る", "れる", "る");
        add(rules, te, v1, "て", "る");
        add(rules, ta, v1, "た", "る");
        add(rules, terminal, v1, "ず", "る", "よう", "る", "れば", "る", "ろ", "る", "よ", "る");

        // Nhóm I theo từng hàng kana.
        for (int i = 0; i < U_ROW.length(); i++) {
            String u = String.valueOf(U_ROW.charAt(i));
            String a = String.valueOf(A_ROW.charAt(i));
            add(rules, stem, v5, String.valueOf(I_ROW.charAt(i)), u);
            add(rules, adjI, v5, a + "ない", u);
            add(rules, v1In, v5, a + "れる", u, a + "せる", u, E_ROW.charAt(i) + "る", u);
            add(rules, terminal, v5, a + "ず", u, E_ROW.charAt(i) + "ば", u,
                    String.valueOf(E_ROW.charAt(i)), u, O_ROW.charAt(i) + "う", u);
        }
        // 行く chia って/った thay vì いて/いた; đặt trước để 行った ra 行く trước 行う.
        add(rules, te, v5, "行って", "行く", "いって", "いく");
        add(rules, ta, v5, "行った", "行く", "いった", "いく");
        add(rules, te, v5, "って", "う", "って", "つ", "って", "る", "いて", "く", "いで", "ぐ", "して", "す",
                "んで", "ぬ", "んで", "ぶ", "んで", "む");
        add(rules, ta, v5, "った", "う", "った", "つ", "った", "る", "いた", "く", "いだ", "ぐ", "した", "す",
                "んだ", "ぬ", "んだ", "ぶ", "んだ", "む");

        // する (cả danh từ + する) và くる viết kana.
        add(rules, stem, vs, "し", "する");
        add(rules, adjI, vs, "しない", "する");
        add(rules, v1In, vs, "される", "する", "させる", "する");
        add(rules, te, vs, "して", "する");
        add(rules, ta, vs, "した", "する");
        add(rules, terminal, vs, "しよう", "する", "すれば", "する", "しろ", "する", "せず", "する");
        add(rules, vs, vs, "する", "");
        add(rules, stem, vk, "き", "くる");
        add(rules, adjI, vk, "こない", "くる");
        add(rules, v1In, vk, "こられる", "くる", "こさせる", "くる");
        add(rules, te, vk, "きて", "くる");
        add(rules, ta, vk, "きた", "くる");
        add(rules, terminal, vk, "こよう", "くる", "くれば", "くる", "こい", "くる");

        // Tính từ đuôi い (dạng く trạng từ bỏ qua: 早く, 良く đã là mục riêng và dễ nhận nhầm).
        add(rules, ta, adjI, "かった", "い");
        add(rules, adjI, adjI, "くない", "い");
        add(rules, te, adjI, "くて", "い");
        add(rules, terminal, adjI, "ければ", "い");
        return List.copyOf(rules);
    }

    private static void add(List<Rule> rules, Set<Form> inflected, Set<Form> base, String... fromTo) {
        for (int i = 0; i < fromTo.length; i += 2) {
            rules.add(new Rule(fromTo[i], fromTo[i + 1], inflected, base));
        }
    }
}
