package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Một đoạn hội thoại ở màn hình biên soạn: chính là một lesson_section loại EXAMPLE kèm các lời thoại
 * bên trong. {@code title} đã bỏ tiền tố "Hội thoại: " để hiển thị cho gọn; {@code sectionTitle} giữ
 * nguyên tên thật trong DB.
 */
@Data
@Builder
public class DialogueSceneResponse {
    private Long sectionId;
    private String title;
    private String sectionTitle;
    private Integer orderIndex;
    private List<DialogueLineResponse> lines;
}
