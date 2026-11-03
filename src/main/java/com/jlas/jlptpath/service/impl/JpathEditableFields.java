package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import com.jlas.jlptpath.entity.JpathKanji;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathReportTarget;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * [JLAS-W0108] Columns an editor may change from the "Báo sai" queue, per target type, with their limits (the column
 * lengths of the jpath DDL). Field names are the column names, which the seed generator applies as they are. Kana have
 * no editable field. Values are trimmed; an empty value clears a nullable column; JSON columns are stored compact and
 * compared by content.
 */
final class JpathEditableFields {

    /** How a field is checked. */
    enum Kind {
        /** VARCHAR(max) counted in code points. */
        TEXT,
        /** TEXT column: at most 20 000 code points and 65 535 bytes UTF-8. */
        LONG_TEXT,
        /** One of the four content statuses. */
        STATUS,
        /** JSON array of exactly 4 non-empty strings. */
        OPTIONS,
        /** 0..3. */
        ANSWER_INDEX,
        /** JSON array of non-empty strings; empty → NULL. */
        ACCEPT
    }

    /** One editable column of an entity. */
    record Field(String name, Kind kind, int max, boolean required, Function<Object, String> getter,
                 BiConsumer<Object, String> setter) {

        boolean json() {
            return kind == Kind.OPTIONS || kind == Kind.ACCEPT;
        }
    }

    static final String FURIGANA_MISMATCH = "Furigana không khớp câu";
    private static final int LONG_TEXT_MAX = 20_000;
    private static final int LONG_TEXT_MAX_BYTES = 65_535;
    private static final Pattern FURIGANA_READING = Pattern.compile("[(（][^)）]*[)）]");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<JpathReportTarget, Map<String, Field>> FIELDS = new EnumMap<>(JpathReportTarget.class);

    static {
        FIELDS.put(JpathReportTarget.VOCAB, fields(
                text(JpathVocabulary.class, "meaning_vi", 255, JpathVocabulary::getMeaningVi, JpathVocabulary::setMeaningVi),
                text(JpathVocabulary.class, "word_type_label", 40, JpathVocabulary::getWordTypeLabel,
                        JpathVocabulary::setWordTypeLabel),
                text(JpathVocabulary.class, "mnemonic_vi", 500, JpathVocabulary::getMnemonicVi,
                        JpathVocabulary::setMnemonicVi),
                text(JpathVocabulary.class, "romaji", 160, JpathVocabulary::getRomaji, JpathVocabulary::setRomaji),
                status(JpathVocabulary.class, JpathVocabulary::getStatus, JpathVocabulary::setStatus)));
        FIELDS.put(JpathReportTarget.KANJI, fields(
                text(JpathKanji.class, "han_viet", 40, JpathKanji::getHanViet, JpathKanji::setHanViet),
                text(JpathKanji.class, "meanings_vi", 255, JpathKanji::getMeaningsVi, JpathKanji::setMeaningsVi),
                text(JpathKanji.class, "mnemonic_vi", 500, JpathKanji::getMnemonicVi, JpathKanji::setMnemonicVi),
                status(JpathKanji.class, JpathKanji::getStatus, JpathKanji::setStatus)));
        FIELDS.put(JpathReportTarget.GRAMMAR, fields(
                text(JpathGrammar.class, "meaning_vi", 1000, JpathGrammar::getMeaningVi, JpathGrammar::setMeaningVi),
                text(JpathGrammar.class, "formation", 255, JpathGrammar::getFormation, JpathGrammar::setFormation),
                field(JpathGrammar.class, "usage_vi", Kind.LONG_TEXT, LONG_TEXT_MAX, false, JpathGrammar::getUsageVi,
                        JpathGrammar::setUsageVi),
                field(JpathGrammar.class, "nuance_vi", Kind.LONG_TEXT, LONG_TEXT_MAX, false, JpathGrammar::getNuanceVi,
                        JpathGrammar::setNuanceVi),
                field(JpathGrammar.class, "common_mistakes_vi", Kind.LONG_TEXT, LONG_TEXT_MAX, false,
                        JpathGrammar::getCommonMistakesVi, JpathGrammar::setCommonMistakesVi),
                status(JpathGrammar.class, JpathGrammar::getStatus, JpathGrammar::setStatus)));
        FIELDS.put(JpathReportTarget.EXAMPLE, fields(
                field(JpathExample.class, "ja", Kind.TEXT, 255, true, JpathExample::getJa, JpathExample::setJa),
                text(JpathExample.class, "ja_furigana", 600, JpathExample::getJaFurigana, JpathExample::setJaFurigana),
                text(JpathExample.class, "vi", 500, JpathExample::getVi, JpathExample::setVi),
                status(JpathExample.class, JpathExample::getStatus, JpathExample::setStatus)));
        FIELDS.put(JpathReportTarget.QUESTION, fields(
                field(JpathGrammarQuestion.class, "prompt_ja", Kind.TEXT, 500, true, JpathGrammarQuestion::getPromptJa,
                        JpathGrammarQuestion::setPromptJa),
                text(JpathGrammarQuestion.class, "prompt_furigana", 800, JpathGrammarQuestion::getPromptFurigana,
                        JpathGrammarQuestion::setPromptFurigana),
                field(JpathGrammarQuestion.class, "options_json", Kind.OPTIONS, 0, false,
                        JpathGrammarQuestion::getOptionsJson, JpathGrammarQuestion::setOptionsJson),
                field(JpathGrammarQuestion.class, "answer_index", Kind.ANSWER_INDEX, 0, false,
                        q -> q.getAnswerIndex() == null ? null : q.getAnswerIndex().toString(),
                        (q, v) -> q.setAnswerIndex(v == null ? null : Integer.valueOf(v))),
                text(JpathGrammarQuestion.class, "answer_text", 255, JpathGrammarQuestion::getAnswerText,
                        JpathGrammarQuestion::setAnswerText),
                field(JpathGrammarQuestion.class, "accept_json", Kind.ACCEPT, 0, false,
                        JpathGrammarQuestion::getAcceptJson, JpathGrammarQuestion::setAcceptJson),
                text(JpathGrammarQuestion.class, "explanation_vi", 1000, JpathGrammarQuestion::getExplanationVi,
                        JpathGrammarQuestion::setExplanationVi),
                status(JpathGrammarQuestion.class, JpathGrammarQuestion::getStatus,
                        JpathGrammarQuestion::setStatus)));
    }

    private JpathEditableFields() {
    }

    /** Editable fields of a target type in display order; empty for kana and any other type. */
    static Map<String, Field> of(JpathReportTarget type) {
        return FIELDS.getOrDefault(type, Map.of());
    }

    /** Current values of every editable field of {@code entity}. */
    static Map<String, String> read(JpathReportTarget type, Object entity) {
        Map<String, String> out = new LinkedHashMap<>();
        of(type).values().forEach(f -> out.put(f.name(), f.getter().apply(entity)));
        return out;
    }

    /**
     * Checks {@code raw} for {@code field} and returns the value to store (null = NULL). Every failure is
     * VALIDATION_FAILED with a Vietnamese message naming the field.
     */
    static String normalize(Field field, String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            if (field.required() || field.kind() == Kind.STATUS) {
                throw invalid(field.name() + " không được để trống");
            }
            return null;
        }
        return switch (field.kind()) {
            case TEXT -> {
                if (value.codePointCount(0, value.length()) > field.max()) {
                    throw invalid(field.name() + " dài quá " + field.max() + " ký tự");
                }
                yield value;
            }
            case LONG_TEXT -> {
                if (value.codePointCount(0, value.length()) > field.max()
                        || value.getBytes(StandardCharsets.UTF_8).length > LONG_TEXT_MAX_BYTES) {
                    throw invalid(field.name() + " dài quá " + field.max() + " ký tự");
                }
                yield value;
            }
            case STATUS -> {
                String status = value.toUpperCase(Locale.ROOT);
                for (JpathContentStatus s : JpathContentStatus.values()) {
                    if (s.name().equals(status)) {
                        yield status;
                    }
                }
                throw invalid("Trạng thái không hợp lệ: " + value);
            }
            case ANSWER_INDEX -> {
                if (!value.matches("[0-3]")) {
                    throw invalid("answer_index phải từ 0 đến 3");
                }
                yield value;
            }
            case OPTIONS -> {
                JsonNode node = stringArray(field.name(), value);
                if (node.size() != 4) {
                    throw invalid("options_json phải có đúng 4 lựa chọn");
                }
                yield compact(node);
            }
            case ACCEPT -> {
                JsonNode node = stringArray(field.name(), value);
                yield node.isEmpty() ? null : compact(node);
            }
        };
    }

    /** Whether the stored {@code current} and the normalized {@code next} are the same value. */
    static boolean same(Field field, String current, String next) {
        if (current == null || next == null || !field.json()) {
            return Objects.equals(current, next);
        }
        try {
            return JSON.readTree(current).equals(JSON.readTree(next));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** Question types answered by picking one of 4 options (answer_index); FILL_TEXT and ORDER are not. */
    private static final java.util.Set<String> CHOICE_TYPES = java.util.Set.of("FILL", "MEANING", "ERROR", "ORDER_STAR",
            "MCQ");
    private static final List<String> CHOICE_ONLY = List.of("options_json", "answer_index");
    private static final List<String> TEXT_ONLY = List.of("answer_text", "accept_json");

    /**
     * Checks a grammar question as it will be after {@code next} (normalized values of the edited fields) is applied:
     * <ul>
     *   <li>choice types (FILL, MEANING, ERROR, ORDER_STAR): exactly 4 distinct options and an answer_index 0–3,
     *       neither clearable; no answer_text / accept_json;</li>
     *   <li>FILL_TEXT: answer_text required; no options_json / answer_index;</li>
     *   <li>ORDER (pieces + answer order, which the seed owns and this screen does not edit): none of the four answer
     *       fields;</li>
     *   <li>prompt_furigana, when set, must read as prompt_ja (same rule as an example's furigana).</li>
     * </ul>
     * A field "used" by the wrong type is rejected only when the edit sets it, so a stray stored value never blocks an
     * unrelated fix.
     */
    static void checkQuestion(JpathGrammarQuestion q, Map<String, String> next) {
        String type = q.getQuestionType() == null ? "" : q.getQuestionType();
        Map<String, Field> fields = of(JpathReportTarget.QUESTION);
        Function<String, String> after = name -> next.containsKey(name) ? next.get(name)
                : fields.get(name).getter().apply(q);
        List<String> unused;
        if (CHOICE_TYPES.contains(type)) {
            String options = after.apply("options_json");
            if (options == null || after.apply("answer_index") == null) {
                throw invalid("Câu chọn đáp án cần options_json và answer_index.");
            }
            JsonNode node = stringArray("options_json", options);
            java.util.Set<String> distinct = new java.util.HashSet<>();
            node.forEach(o -> distinct.add(o.asText().strip()));
            if (node.size() != 4 || distinct.size() != 4) {
                throw invalid("options_json phải có đúng 4 lựa chọn khác nhau");
            }
            unused = TEXT_ONLY;
        } else if ("FILL_TEXT".equals(type)) {
            if (after.apply("answer_text") == null) {
                throw invalid("Câu điền chữ cần answer_text.");
            }
            unused = CHOICE_ONLY;
        } else {
            unused = List.of("options_json", "answer_index", "answer_text", "accept_json");
        }
        for (String name : unused) {
            if (next.get(name) != null) {
                throw invalid(name + " không dùng cho dạng câu " + type);
            }
        }
        if (next.containsKey("prompt_ja") || next.containsKey("prompt_furigana")) {
            String furigana = after.apply("prompt_furigana");
            if (furigana != null && !plain(furigana).equals(after.apply("prompt_ja"))) {
                throw invalid(FURIGANA_MISMATCH);
            }
        }
    }

    /** {@code furigana} without its readings ("猫(ねこ)が" → "猫が"); full-width brackets count too. */
    static String plain(String furigana) {
        return FURIGANA_READING.matcher(furigana).replaceAll("");
    }

    private static JsonNode stringArray(String name, String value) {
        JsonNode node;
        try {
            node = JSON.readTree(value);
        } catch (JsonProcessingException e) {
            throw invalid(name + " không phải JSON hợp lệ");
        }
        if (node == null || !node.isArray()) {
            throw invalid(name + " phải là mảng JSON");
        }
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid(name + " chỉ chứa chuỗi khác rỗng");
            }
        }
        return node;
    }

    private static String compact(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static Map<String, Field> fields(Field... fields) {
        Map<String, Field> out = new LinkedHashMap<>();
        for (Field f : fields) {
            out.put(f.name(), f);
        }
        return Collections.unmodifiableMap(out);
    }

    private static <E> Field text(Class<E> type, String name, int max, Function<E, String> getter,
                                  BiConsumer<E, String> setter) {
        return field(type, name, Kind.TEXT, max, false, getter, setter);
    }

    private static <E> Field status(Class<E> type, Function<E, JpathContentStatus> getter,
                                    BiConsumer<E, JpathContentStatus> setter) {
        return field(type, "status", Kind.STATUS, 0, true,
                e -> getter.apply(e) == null ? null : getter.apply(e).name(),
                (e, v) -> setter.accept(e, JpathContentStatus.valueOf(v)));
    }

    private static <E> Field field(Class<E> type, String name, Kind kind, int max, boolean required,
                                   Function<E, String> getter, BiConsumer<E, String> setter) {
        return new Field(name, kind, max, required, o -> getter.apply(type.cast(o)),
                (o, v) -> setter.accept(type.cast(o), v));
    }
}
