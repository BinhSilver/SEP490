package com.jlas.dictionary.service;

import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.SearchHistoryResponse;
import com.jlas.dictionary.dto.SearchResultResponse;
import com.jlas.dictionary.dto.VocabularyResponse;

import java.util.List;

/** Service xử lý nghiệp vụ tra cứu từ điển: search, chi tiết Kanji/vocabulary, lịch sử tra cứu. */
public interface DictionaryService {

    /**
     * Tìm kiếm Dictionary/Kanji. Phân loại input (Kanji/Hiragana/Katakana/Romaji/nghĩa) rồi
     * tra theo tier ưu tiên (mục 8), dùng chung cho Dictionary Search và Global Search.
     *
     * @param query từ khóa thô do người dùng nhập
     * @param page  trang vocabularyResults, bắt đầu từ 0
     * @param size  kích thước trang (bị giới hạn ở cận trên nội bộ để tránh query quá nặng)
     * @param userId id user hiện tại, null nếu chưa đăng nhập; dùng để ghi lookup history
     */
    SearchResultResponse search(String query, int page, int size, Long userId);

    /** Chi tiết một vocabulary, gồm senses/glosses và kanji composition. */
    VocabularyResponse getVocabularyDetail(Long vocabularyId);

    /** Chi tiết một Kanji, kèm danh sách vocabulary liên quan qua vocabulary_kanji. */
    KanjiResponse getKanjiDetail(String character);

    /** Lịch sử tra cứu gần nhất của user; rỗng nếu chưa đăng nhập. */
    List<SearchHistoryResponse> getRecentHistory(Long userId);
}
