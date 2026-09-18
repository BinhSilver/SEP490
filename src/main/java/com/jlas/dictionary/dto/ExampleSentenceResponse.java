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
    private String title;
    private String textJa;
    private String textVi;
    private Integer orderIndex;
    private Long grammarPointId;
    private List<ContentAudioResponse> audios;
}
