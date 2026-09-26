package com.jlas.learning.dto;

/**
 * [JLAS-W0108] Evidence điểm yếu theo skill với canonical ID (spec §2.1 + §2.2).
 *
 * <p>Thay mô hình cũ chỉ gom theo tag KNOWLEDGE của lesson (grammar). Giờ mỗi skill có khóa
 * canonical riêng để Sổ tay điểm yếu và warm-up selector trỏ đúng item/outcome/topic:</p>
 *
 * <ul>
 *   <li>KANJI / VOCABULARY → {@code canonicalId = "KANJI:<lexeme>" / "VOCAB:<lexeme>"}, không có tag.</li>
 *   <li>GRAMMAR → {@code canonicalId = "GP:<grammarPointId>"}, {@code tagCode/tagId} = tag lesson bổ sung.</li>
 *   <li>READING / LISTENING → {@code canonicalId = "READ"/"LISTEN"} cấp skill; mang outcome/topic/situation yếu.</li>
 * </ul>
 *
 * <p>Dữ liệu này được sinh server-side từ attempt đã SUBMITTED, không bao giờ nhận từ request JSON.</p>
 */
public record WeaknessEvidence(
        String skill,           // KANJI / VOCABULARY / GRAMMAR / READING / LISTENING
        String canonicalId,     // KANJI:食 / VOCAB:先生 / GP:<id> / READ / LISTEN
        int assessedCount,      // số câu hợp lệ đã đánh giá cho item/tag/skill này
        int wrongCount,         // số câu sai (gồm NO_RESPONSE do hết giờ)
        String tagCode,         // grammar: tag lesson; reading/listening: tag SITUATION (nullable)
        Long tagId,             // FK tag.id (nullable — KANJI/VOCAB không có tag)
        String outcomeCode,     // reading/listening (nullable)
        Long outcomeId,         // FK outcome.id (nullable)
        String topicTagCode,    // reading/listening (nullable; JLPT listening = null)
        Long topicTagId,        // FK tag.id TOPIC (nullable)
        String jlptLevel,       // cấp độ JLPT của item/point/skill (để chặn warm-up trên mức kết luận)
        Long vocabularyEntryId) { // KANJI/VOCAB: FK vocabulary_entry.id (nullable — anchor resolve warm-up)

    public static final String SKILL_KANJI = "KANJI";
    public static final String SKILL_VOCABULARY = "VOCABULARY";
    public static final String SKILL_GRAMMAR = "GRAMMAR";
    public static final String SKILL_READING = "READING";
    public static final String SKILL_LISTENING = "LISTENING";

    /** KANJI/VOCABULARY sai chính xác item đó (spec: câu nào sai thì lưu chính item). */
    public static WeaknessEvidence item(String skill, String canonicalId, int assessedCount, int wrongCount) {
        return item(skill, canonicalId, assessedCount, wrongCount, null, null);
    }

    /** [JLAS-W0108] Như trên, kèm cấp độ JLPT của item (để warm-up chỉ nhận item <= trình độ kết luận). */
    public static WeaknessEvidence item(String skill, String canonicalId, int assessedCount, int wrongCount,
            String jlptLevel) {
        return item(skill, canonicalId, assessedCount, wrongCount, jlptLevel, null);
    }

    /** [JLAS-W0108] Như trên, kèm vocabulary_entry_id để resolve warm-up tới đúng bài dạy từ đó. */
    public static WeaknessEvidence item(String skill, String canonicalId, int assessedCount, int wrongCount,
            String jlptLevel, Long vocabularyEntryId) {
        return new WeaknessEvidence(skill, canonicalId, assessedCount, wrongCount,
                null, null, null, null, null, null, jlptLevel, vocabularyEntryId);
    }

    /** GRAMMAR gom theo grammarPointId, tag lesson là phân loại bổ sung. */
    public static WeaknessEvidence grammar(long grammarPointId, String tagCode, Long tagId,
            int assessedCount, int wrongCount) {
        return grammar(grammarPointId, tagCode, tagId, assessedCount, wrongCount, null);
    }

    /** [JLAS-W0108] Như trên, kèm cấp độ JLPT của grammar point. */
    public static WeaknessEvidence grammar(long grammarPointId, String tagCode, Long tagId,
            int assessedCount, int wrongCount, String jlptLevel) {
        return new WeaknessEvidence(SKILL_GRAMMAR, "GP:" + grammarPointId, assessedCount, wrongCount,
                tagCode, tagId, null, null, null, null, jlptLevel, null);
    }

    /** READING/LISTENING gom cấp skill theo cửa sổ 5 câu; outcome/topic/situation yếu kèm theo. */
    public static WeaknessEvidence comprehension(String skill, int assessedCount, int wrongCount,
            String tagCode, Long tagId, String outcomeCode, Long outcomeId,
            String topicTagCode, Long topicTagId, String jlptLevel) {
        return new WeaknessEvidence(skill, skill.equals(SKILL_READING) ? "READ" : "LISTEN",
                assessedCount, wrongCount, tagCode, tagId, outcomeCode, outcomeId,
                topicTagCode, topicTagId, jlptLevel, null);
    }

    /** Khóa ổn định để sắp xếp/báo gap khi không có tag code (KANJI/VOCAB/READING/LISTENING). */
    public String sortKey() {
        return tagCode != null ? tagCode : canonicalId;
    }

    /** grammar_point_id parsed from {@code canonicalId = "GP:<id>"}; null cho skill khác. */
    public Long grammarPointId() {
        if (canonicalId == null || !canonicalId.startsWith("GP:")) return null;
        try {
            return Long.valueOf(canonicalId.substring(3));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
