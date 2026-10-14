package com.jlas.game.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;

import java.util.List;
import java.util.Optional;

/**
 * Khung avatar bán trong Cửa hàng. Mỗi khung mở kèm một màu tên cùng chủ đề ({@link Frame#nameStyle()}).
 * Ảnh đã chuẩn hoá: lỗ tròn nằm giữa, khung = 1,5 lần đường kính avatar (xem avatar-frame.css).
 * Hiệu ứng chuyển động (tia sáng, kim tuyến, hạt bay) nằm ở avatar-frame.js theo {@code code}.
 */
public final class AvatarFrameCatalog {

    /** {@code id} cố định: reference_id trong sổ CP để mỗi khung chỉ bị trừ tiền một lần. */
    public record Frame(long id, String code, String name, String description, int price, String image,
                        String nameStyle, String nameStyleLabel) {
    }

    private static final List<Frame> FRAMES = List.of(
            new Frame(3001, "frame-cosmic", "Tinh Vân Huyền Bí",
                    "Vành bạc chứa cả dải ngân hà, pha lê xanh chớp sáng theo vệt sáng chạy vòng.", 300,
                    "/images/avatar-frames/cosmic.webp", "cosmic", "Tinh vân"),
            new Frame(3002, "frame-seraph", "Thiên Sứ Quang Minh",
                    "Đôi cánh trắng và vầng hào quang vàng, lấp lánh ánh thánh quang.", 300,
                    "/images/avatar-frames/seraph.webp", "seraph", "Thánh quang"),
            new Frame(3003, "frame-inferno", "Hỏa Ngục Ma Vương",
                    "Cánh quỷ và dung nham âm ỉ, tàn lửa bay lên không ngừng.", 300,
                    "/images/avatar-frames/inferno.webp", "inferno", "Hỏa ngục"),
            new Frame(3004, "frame-vampire", "Bá Tước Huyết Tộc",
                    "Vàng cổ, hồng ngọc và cánh dơi của dòng dõi quý tộc bóng đêm.", 300,
                    "/images/avatar-frames/vampire.webp", "vampire", "Huyết tộc")
    );

    private AvatarFrameCatalog() {
    }

    public static List<Frame> all() {
        return FRAMES;
    }

    public static Optional<Frame> find(String code) {
        return FRAMES.stream().filter(frame -> frame.code().equals(code)).findFirst();
    }

    public static Frame require(String code) {
        return find(code).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy khung avatar này"));
    }

    /** Khung mở khoá màu tên này; rỗng nếu mã màu không tồn tại. */
    public static Optional<Frame> byNameStyle(String nameStyle) {
        return FRAMES.stream().filter(frame -> frame.nameStyle().equals(nameStyle)).findFirst();
    }
}
