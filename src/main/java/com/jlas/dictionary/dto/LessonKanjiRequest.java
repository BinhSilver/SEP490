package com.jlas.dictionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Gắn kanji vào bài bằng một chuỗi ký tự, ví dụ "学生日". Ký tự không phải Kanji (kana, dấu cách,
 * dấu câu) bị bỏ qua; chữ trùng hoặc đã gắn rồi chỉ tính một lần.
 */
@Data
public class LessonKanjiRequest {

    @NotBlank(message = "Hãy nhập ít nhất một chữ Kanji")
    @Size(max = 200, message = "Tối đa 200 ký tự mỗi lần")
    private String characters;
}
