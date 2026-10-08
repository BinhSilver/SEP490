package com.jlas.dictionary.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Kết quả import nội dung bài học từ file Excel. Import theo kiểu tất cả hoặc không: khi {@code errors}
 * không rỗng thì {@code imported = false} và chưa có gì được lưu, các bộ đếm đều bằng 0.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonImportResult {

    private boolean imported;

    /** Từ chưa có trong từ điển, được tạo mới (DRAFT) rồi gắn vào bài. */
    private int vocabularyCreated;
    /** Từ đã có trong từ điển, được gắn vào bài. */
    private int vocabularyAttached;
    /** Từ đã có trong bài, hoặc lặp lại trong file. */
    private int vocabularySkipped;

    private int grammarCreated;
    /** Mẫu đã có trong bài (cùng cách viết); câu ví dụ của mẫu vẫn được thêm. */
    private int grammarSkipped;
    private int examplesCreated;

    private int dialogueRolesCreated;
    private int dialogueScenesCreated;
    private int dialogueLinesCreated;

    private int kanjiAttached;
    /** Kanji đã gắn tay vào bài từ trước, hoặc lặp lại trong file. */
    private int kanjiSkipped;

    @Builder.Default
    private List<RowError> errors = new ArrayList<>();

    /** Một lỗi gắn với vị trí trong file; {@code row} là số dòng như Excel hiển thị (bắt đầu từ 1). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowError {
        private String sheet;
        private int row;
        private String message;
    }
}
