package com.jlas.kaiwa.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Tra cứu chính xác từ vựng và kanji trong MySQL cho câu hỏi của người học. Không qua vector: âm Hán Việt,
 * cách đọc và nghĩa phải đúng từng chữ, và model 4B đã được đo là tự bịa các thông tin này.
 */
@Repository
public class RagLexiconLookup {

    static final int MAX_KANJI = 8;
    static final int MAX_WORDS = 6;
    private static final Pattern JAPANESE_RUN = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]+");
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}々]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");

    private final JdbcTemplate jdbc;
    private final RagProperties properties;

    public RagLexiconLookup(JdbcTemplate jdbc, RagProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public record KanjiEntry(long id, String character, String hanViet, String meaningVi, String jlptLevel,
                             List<String> onyomi, List<String> kunyomi) { }

    /** @param extras dòng bổ sung từ DB: từ trái nghĩa, mẹo nhớ, câu ví dụ có furigana */
    public record WordEntry(long id, String expression, String reading, String meaning, String wordType, String jlptLevel,
                            List<String> extras) {
        public WordEntry(long id, String expression, String reading, String meaning, String wordType, String jlptLevel) {
            this(id, expression, reading, meaning, wordType, jlptLevel, List.of());
        }
    }

    public record Result(List<WordEntry> words, List<KanjiEntry> kanji) {
        public boolean isEmpty() { return words.isEmpty() && kanji.isEmpty(); }
    }

    public Result lookup(String question) {
        if (question == null || question.isBlank()) return new Result(List.of(), List.of());
        return new Result(words(wordCandidates(question)), kanji(kanjiCandidates(question)));
    }

    /** Cụm tiếng Nhật trong câu hỏi, cụm bỏ đuôi kana (勉強する → 勉強) và cụm chỉ gồm kanji. */
    static List<String> wordCandidates(String question) {
        Set<String> candidates = new LinkedHashSet<>();
        Matcher run = JAPANESE_RUN.matcher(question);
        while (run.find()) {
            String text = run.group();
            if (text.codePointCount(0, text.length()) >= 2) candidates.add(text);
            String trimmed = text.replaceAll("[\\p{IsHiragana}]+$", "");
            if (!trimmed.isEmpty() && HAN.matcher(trimmed).find()) candidates.add(trimmed);
            Matcher han = HAN_RUN.matcher(text);
            while (han.find()) if (han.group().length() >= 2) candidates.add(han.group());
        }
        return candidates.stream().limit(12).toList();
    }

    static List<String> kanjiCandidates(String question) {
        Set<String> kanji = new LinkedHashSet<>();
        Matcher han = HAN.matcher(question);
        while (han.find() && kanji.size() < MAX_KANJI) kanji.add(han.group());
        return List.copyOf(kanji);
    }

    private List<WordEntry> words(List<String> candidates) {
        if (candidates.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(candidates.size(), "?"));
        // Từ do seed vnjpclub thêm mới không được dùng (chưa có giấy phép); từ có sẵn được vnjpclub dùng lại thì giữ.
        String vnjpclub = properties.includeVnjpclub() ? "" : " AND NOT EXISTS (SELECT 1 FROM content_source cs"
                + " WHERE cs.entity_type = 'VOCABULARY' AND cs.entity_id = v.id AND cs.source_code = 'VNJPCLUB'"
                + " AND cs.note = 'Tu moi tu vnjpclub')";
        List<WordEntry> found = jdbc.query("SELECT v.id, v.expression, v.reading, v.meaning, v.word_type, v.jlpt_level"
                        + " FROM vocabulary_entry v WHERE v.dictionary_status = 'PUBLISHED' AND v.expression IN (" + marks + ")"
                        + vnjpclub + " ORDER BY v.id",
                (rs, n) -> new WordEntry(rs.getLong("id"), rs.getString("expression"), rs.getString("reading"),
                        rs.getString("meaning"), rs.getString("word_type"), rs.getString("jlpt_level")),
                candidates.toArray());
        // Giữ thứ tự xuất hiện trong câu hỏi, không lặp cùng biểu thức.
        Map<String, WordEntry> byExpression = new LinkedHashMap<>();
        for (String candidate : candidates)
            found.stream().filter(w -> w.expression().equals(candidate)).forEach(w -> byExpression.putIfAbsent(w.expression() + "|" + w.reading(), w));
        List<WordEntry> words = byExpression.values().stream().limit(MAX_WORDS).toList();
        Map<Long, List<String>> extras = extras(words);
        return words.stream().map(w -> new WordEntry(w.id(), w.expression(), w.reading(), w.meaning(), w.wordType(), w.jlptLevel(),
                extras.getOrDefault(w.id(), List.of()))).toList();
    }

    static final int MAX_WORD_EXAMPLES = 3;

    /**
     * Từ trái nghĩa của từ điển, và mẹo nhớ + câu ví dụ của lộ trình JLPT (cùng luật trạng thái với index).
     * Lộ trình JLPT được ghép theo chữ + cách đọc: dictionary_entry_id của nó không trùng id mục từ điển Nhật-Việt.
     */
    private Map<Long, List<String>> extras(List<WordEntry> words) {
        Map<Long, List<String>> extras = new LinkedHashMap<>();
        if (words.isEmpty()) return extras;
        String marks = String.join(",", Collections.nCopies(words.size(), "?"));
        jdbc.query("SELECT a.vocabulary_id, a.antonym_text, a.antonym_reading FROM vocabulary_antonym a WHERE a.vocabulary_id IN (" + marks + ") ORDER BY a.id",
                rs -> {
                    String reading = rs.getString("antonym_reading");
                    extras.computeIfAbsent(rs.getLong("vocabulary_id"), k -> new ArrayList<>()).add("Trái nghĩa: " + rs.getString("antonym_text")
                            + (reading == null || reading.isBlank() ? "" : " (" + reading + ")"));
                }, words.stream().map(WordEntry::id).toArray());

        Map<String, Long> idByWord = new LinkedHashMap<>();
        words.forEach(w -> idByWord.putIfAbsent(w.expression() + "|" + w.reading(), w.id()));
        String allowed = "%1$s.report_hidden = FALSE AND %1$s.status IN ('AI_CHECKED', 'REVIEWED'" + (properties.includeUnreviewed() ? ", 'HOLD'" : "") + ")";
        Object[] expressions = words.stream().map(WordEntry::expression).toArray();
        jdbc.query("SELECT v.expression, v.reading, v.mnemonic_vi FROM jpath_vocabulary v WHERE v.expression IN (" + marks + ")"
                        + " AND v.mnemonic_vi IS NOT NULL AND v.mnemonic_vi <> '' AND " + allowed.formatted("v") + " ORDER BY v.code",
                rs -> {
                    Long id = idByWord.get(rs.getString("expression") + "|" + rs.getString("reading"));
                    if (id == null) return;
                    List<String> lines = extras.computeIfAbsent(id, k -> new ArrayList<>());
                    if (lines.stream().noneMatch(l -> l.startsWith("Mẹo nhớ"))) lines.add("Mẹo nhớ: " + rs.getString("mnemonic_vi"));
                }, expressions);
        Map<Long, Integer> examples = new LinkedHashMap<>();
        jdbc.query("SELECT v.expression, v.reading, e.ja, e.ja_furigana, e.vi FROM jpath_vocabulary v JOIN jpath_example e"
                        + " ON e.owner_type = 'VOCAB' AND e.owner_code = v.code WHERE v.expression IN (" + marks + ")"
                        + " AND " + allowed.formatted("v") + " AND " + allowed.formatted("e") + " ORDER BY v.code, e.sort_order, e.code",
                rs -> {
                    Long id = idByWord.get(rs.getString("expression") + "|" + rs.getString("reading"));
                    if (id == null || examples.merge(id, 1, Integer::sum) > MAX_WORD_EXAMPLES) return;
                    String furigana = rs.getString("ja_furigana");
                    extras.computeIfAbsent(id, k -> new ArrayList<>()).add("Ví dụ: "
                            + (furigana == null || furigana.isBlank() ? rs.getString("ja") : furigana) + " — " + rs.getString("vi"));
                }, expressions);
        return extras;
    }

    private List<KanjiEntry> kanji(List<String> characters) {
        if (characters.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(characters.size(), "?"));
        Map<Long, KanjiEntry> byId = new LinkedHashMap<>();
        Map<Long, List<String>> on = new LinkedHashMap<>();
        Map<Long, List<String>> kun = new LinkedHashMap<>();
        jdbc.query("SELECT r.kanji_id, r.reading_type, r.reading_text FROM kanji_reading r JOIN kanji k ON k.id = r.kanji_id"
                        + " WHERE k.`character` IN (" + marks + ") ORDER BY r.is_common DESC, r.id",
                rs -> {
                    String type = rs.getString("reading_type");
                    var target = type != null && type.toUpperCase().startsWith("ON") ? on : kun;
                    target.computeIfAbsent(rs.getLong("kanji_id"), k -> new ArrayList<>()).add(rs.getString("reading_text"));
                }, characters.toArray());
        jdbc.query("SELECT k.id, k.`character`, k.han_viet, k.meaning_vi, k.jlpt_level FROM kanji k WHERE k.`character` IN (" + marks + ")",
                rs -> {
                    long id = rs.getLong("id");
                    byId.put(id, new KanjiEntry(id, rs.getString("character"), rs.getString("han_viet"), rs.getString("meaning_vi"),
                            rs.getString("jlpt_level"), on.getOrDefault(id, List.of()), kun.getOrDefault(id, List.of())));
                }, characters.toArray());
        List<KanjiEntry> ordered = new ArrayList<>();
        for (String c : characters) byId.values().stream().filter(k -> k.character().equals(c)).findFirst().ifPresent(ordered::add);
        return ordered;
    }
}
