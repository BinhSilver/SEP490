package com.jlas.dictionary.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Request tạo/sửa một lời thoại. Đoạn hội thoại cha nằm trên URL
 * (POST /dialogue-scenes/{sectionId}/lines).
 * <p>
 * Cụm tách nghĩa gửi KÈM trong cùng request chứ không có endpoint riêng: ghép các cụm phải ra đúng
 * nguyên câu, nên câu và cụm phải được ghi trong cùng một transaction, không thể sửa rời từng bên rồi
 * hy vọng chúng vẫn khớp.
 */
@Data
public class DialogueLineUpsertRequest {

    /** Vai nói lời này; để trống thì lời không gắn vai (dialogue_role_id cho phép NULL). */
    private Long roleId;

    /** content_item.title — nhãn hiển thị của người nói, thường trùng tên vai. */
    @NotBlank(message = "Nhãn người nói không được để trống")
    @Size(max = 255, message = "Nhãn người nói tối đa 255 ký tự")
    private String title;

    @NotBlank(message = "Lời thoại không được để trống")
    private String textJa;

    private String textVi;

    /** Chỉ nhận đúng hai giá trị của chk_dialogue_bubble_side; để trống thì bong bóng theo mặc định. */
    @Pattern(regexp = "^(trai|phai)?$", message = "Vị trí bong bóng chỉ nhận trai hoặc phai")
    private String bubbleSide;

    @Size(max = 64, message = "Mã cảnh tối đa 64 ký tự")
    private String sceneCode;

    /**
     * Danh sách cụm theo đúng thứ tự trong câu. Để trống thì lời không có cụm và giao diện học hiển thị
     * nguyên câu — hợp lệ. Nếu có thì ghép lại phải ra đúng {@code textJa}, service kiểm và trả 400 nếu lệch.
     */
    @Valid
    private List<DialogueChunkInput> chunks;
}
