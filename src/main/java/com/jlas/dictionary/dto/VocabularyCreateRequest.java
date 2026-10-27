package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Tạo từ mới (chưa có trong từ điển) rồi gắn vào bài. Từ do người biên soạn tạo mang
 * usage_scope = TEXTBOOK, dictionary_status = DRAFT: hiện trong bài học nhưng không lẫn vào tra cứu
 * từ điển chung cho tới khi được duyệt.
 */
@Data
public class VocabularyCreateRequest {

    /** Cách viết, ví dụ 学生. Từ chỉ có kana thì ghi giống cách đọc. */
    @NotBlank(message = "Hãy nhập từ")
    @Size(max = 255)
    private String expression;

    /** Cách đọc bằng kana, ví dụ がくせい. */
    @NotBlank(message = "Hãy nhập cách đọc")
    @Size(max = 255)
    private String reading;

    @Size(max = 255)
    private String readingRomaji;

    @NotBlank(message = "Hãy nhập nghĩa")
    private String meaning;

    @Size(max = 50)
    private String wordType;

    /** N5..N1; để trống nếu chưa xếp cấp. */
    @Pattern(regexp = "^(N[1-5])?$", message = "Cấp JLPT phải là N5, N4, N3, N2 hoặc N1")
    private String jlptLevel;

    private String exampleJa;

    private String exampleVi;
}
