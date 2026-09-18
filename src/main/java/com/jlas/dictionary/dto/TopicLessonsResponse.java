package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** Chủ đề kèm danh sách bài (đã lọc theo quyền xem); chủ đề tồn tại nhưng 0 bài → lessons rỗng, không phải 404. */
@Data
@Builder
public class TopicLessonsResponse {
    private TopicResponse topic;
    private List<TopicLessonResponse> lessons;
}
