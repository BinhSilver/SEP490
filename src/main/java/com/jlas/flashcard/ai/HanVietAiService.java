package com.jlas.flashcard.ai;

import java.util.List;
import java.util.Map;

/**
 * AI for Hán Việt + auto-fill card fields from kanji (key).
 */
public interface HanVietAiService {

    /**
     * Generate Hán Việt for a single term.
     *
     * @param front Kanji / term (required key)
     * @param back  meaning (context, optional)
     * @return Hán Việt string (may be empty if unknown)
     */
    String generateHanViet(String front, String back);

    /**
     * From kanji only: fill reading, meaning (Vietnamese), and Hán Việt.
     * Kanji is the key — without kanji this must not be called.
     */
    CardEnrichResult enrichFromKanji(String kanji);

    /**
     * From list of kanjis: batch fill reading, meaning, and Hán Việt in 1 AI call.
     */
    List<CardEnrichResult> enrichBatchFromKanji(List<String> kanjiList);

    /**
     * Batch generate. Key = card id or index key, value = generated Hán Việt.
     */
    Map<Long, String> generateHanVietBatch(List<HanVietRequest> items);

    /**
     * Parse import text lines and fill missing Hán Việt.
     * Input lines keep format: {@code Kanji - Hiragana : Meaning}
     * Output: same lines with optional {@code (Hán Việt)} appended when missing.
     */
    String enrichImportText(String text);

    /** Generate complete draft cards from free-form source data, without persisting them. */
    List<CardEnrichResult> generateImportCards(String text);

    record HanVietRequest(Long id, String front, String back) {
    }

    /**
     * @param kanji   original kanji (front)
     * @param reading hiragana / reading
     * @param meaning Vietnamese meaning
     * @param hanViet Sino-Vietnamese
     * @param back    combined display for Back field: {@code reading — meaning}
     */
    record CardEnrichResult(String kanji, String reading, String meaning, String hanViet, String back) {
    }
}
