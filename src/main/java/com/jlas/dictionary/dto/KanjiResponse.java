package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Response chứa thông tin Kanji. Dữ liệu Kanji lấy từ KANJIDIC2 (EDRDG, CC BY-SA 4.0).
 *
 * <p>Dùng chung cho hai trang với hai hình dạng dữ liệu khác nhau — mỗi trang chỉ đọc nhóm
 * trường của mình, nhóm còn lại để null:</p>
 * <ul>
 *   <li>Trang bài học giáo trình (textbook-lesson.js): {@code meaning}, {@code radicalNumber},
 *       {@code radicalCharacter}, {@code radicalNameVi}, {@code words} (các từ trong bài chứa chữ đó).</li>
 *   <li>Trang tra từ điển (dictionary-page.js): {@code meaningVi}, {@code strokeSvgUri},
 *       {@code radical} (lồng), {@code relatedVocabulary}.</li>
 * </ul>
 */
@Data
@Builder
public class KanjiResponse {
    private Long id;
    private String character;
    private String unicodeCode;
    private Integer strokeCount;
    private String hanViet;
    private String jlptLevel;
    private List<KanjiReadingResponse> readings;

    // --- trang bài học giáo trình ---
    private String meaning;
    private Short radicalNumber;
    private String radicalCharacter;
    private String radicalNameVi;
    private List<KanjiWordResponse> words;

    // --- trang tra từ điển ---
    private String meaningVi;
    private String strokeSvgUri;
    private RadicalResponse radical;
    /** Chỉ populate ở trang chi tiết Kanji, để trống trong kết quả search để tránh query thừa. */
    private List<VocabularyResponse> relatedVocabulary;
}
