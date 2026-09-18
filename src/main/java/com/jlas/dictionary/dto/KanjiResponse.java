package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Response chứa thông tin Kanji: âm Hán Việt, số nét, bộ thủ, cách đọc On/Kun
 * và các từ trong bài chứa chữ đó. Dữ liệu Kanji lấy từ KANJIDIC2 (EDRDG, CC BY-SA 4.0).
 * meaning hiện chưa có (KANJIDIC2 không có nghĩa tiếng Việt) nên thường null.
 */
@Data
@Builder
public class KanjiResponse {
    private Long id;
    private String character;
    private String unicodeCode;
    private String meaning;
    private String hanViet;
    private Byte strokeCount;
    private String jlptLevel;
    private Short radicalNumber;
    private String radicalCharacter;
    private String radicalNameVi;
    private List<KanjiReadingResponse> readings;
    private List<KanjiWordResponse> words;
}
