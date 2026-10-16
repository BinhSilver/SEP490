package com.jlas.dictionary.service;

import com.jlas.dictionary.dto.ComponentSearchResponse;
import com.jlas.dictionary.dto.HandwritingRequest;
import com.jlas.dictionary.dto.KanjiComponentResponse;
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

    /**
     * Nhận diện Kanji từ các nét vẽ tay (kể cả vẽ dở, sai thứ tự nét); khớp nhất đứng đầu.
     * Chỉ là một cách nhập: chọn ứng viên xong FE dùng lại {@link #search}, không có luồng riêng.
     */
    List<KanjiResponse> recognizeHandwriting(HandwritingRequest request);

    /** Bảng bộ phận để tra Kanji theo bộ thủ, theo số nét. */
    List<KanjiComponentResponse> listKanjiComponents();

    /**
     * Kanji chứa tất cả bộ phận đã chọn, kèm các bộ phận còn chọn thêm được. Cũng chỉ là một cách
     * nhập: chọn Kanji xong FE dùng lại {@link #search}.
     */
    ComponentSearchResponse searchByComponents(List<Integer> componentIds);

    /** Lịch sử tra cứu gần nhất của user; rỗng nếu chưa đăng nhập. */
    List<SearchHistoryResponse> getRecentHistory(Long userId);
}
