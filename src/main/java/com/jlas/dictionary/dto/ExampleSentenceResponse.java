package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Câu ví dụ của bài học (content_item content_type = GRAMMAR) kèm audio.
 * grammarPointId: mẫu ngữ pháp mà câu minh hoạ (content_item.grammar_point_id, [JLAS-W0070]); null = câu không gắn mẫu.
 */
@Data
@Builder
public class ExampleSentenceResponse {
    private Long contentItemId;
    private Long sectionId;
    private String sectionTitle;
    /** order_index của phần chứa mục; trang học dùng để xếp các phần đọc/nghe đúng thứ tự trong bài. */
    private Integer sectionOrderIndex;
    /** Mã gốc của mục (content_source.stable_code), để trang học gắn câu hỏi luyện tập vào đúng bài đọc/nghe. */
    private String stableCode;
    private String title;
    private String textJa;
    private String textVi;
    private Integer orderIndex;
    private Long grammarPointId;
    private List<ContentAudioResponse> audios;
}
