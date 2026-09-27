package com.jlas.game.runtime;

/**
 * Một thẻ flashcard đã chuẩn hoá cho game: {@code prompt} là trường câu hỏi, {@code answer} là
 * trường đáp án người chơi chọn; 4 trường gốc giữ lại để hiển thị và ôn tập.
 */
public record GameWord(long cardId, String prompt, String answer, String kanji, String reading, String meaning,
                       String hanViet) {
}
