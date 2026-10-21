package com.jlas.game.util;

import java.util.List;
import java.util.Map;

/**
 * Dữ liệu khởi tạo của danh mục khung avatar (bảng {@code avatar_frame}, migration 042). Danh mục thật do admin
 * quản lý trong DB; lớp này chỉ còn nạp sẵn 4 khung gốc và là danh mục dự phòng khi DB chưa có bảng.
 * Ảnh đã chuẩn hoá: lỗ tròn nằm giữa, khung = 1,5 lần đường kính avatar (xem avatar-frame.css).
 */
public final class AvatarFrameCatalog {

    /**
     * {@code id} cố định: reference_id trong sổ CP để mỗi khung chỉ bị trừ tiền một lần.
     * {@code theme}: một trong {@link #THEMES}, quyết định màu ánh sáng, hào quang, kim tuyến (avatar-frame.css).
     * {@code nameStyle}: màu tên đi kèm (luôn bằng theme). {@code gemsJson}: toạ độ đá chớp sáng đo trên ảnh,
     * null với khung admin thêm (khung vẫn có mọi hiệu ứng khác).
     */
    public record Frame(long id, String code, String name, String description, int price, String image,
                        String theme, String nameStyle, String nameStyleLabel, String gemsJson,
                        int sortOrder, boolean active, boolean deleted) {
    }

    /** Chủ đề có sẵn CSS hiệu ứng + màu tên; khoá = mã chủ đề = mã màu tên, giá trị = nhãn màu tên. */
    public static final Map<String, String> THEMES = Map.of(
            "cosmic", "Tinh vân", "seraph", "Thánh quang", "inferno", "Hỏa ngục", "vampire", "Huyết tộc");

    private static final List<Frame> SEEDS = List.of(
            new Frame(3001, "frame-cosmic", "Tinh Vân Huyền Bí",
                    "\"Vạn vì tinh tú chỉ là mảnh vụn quay quanh quỹ đạo của ta.\"", 300,
                    "/images/avatar-frames/cosmic.webp", "cosmic", "cosmic", "Tinh vân",
                    "{\"big\":[[49.8,13.8],[50.5,85.6]],\"gems\":[[77.1,24.0],[77.2,75.3],[22.5,75.5],[22.4,24.6]],"
                            + "\"orbs\":[[12.9,48.7],[88.6,50.0]]}", 1, true, false),
            new Frame(3002, "frame-seraph", "Thiên Sứ Quang Minh",
                    "\"Nơi nào ta đặt chân tới, nơi đó bóng tối không còn quyền tồn tại.\"", 300,
                    "/images/avatar-frames/seraph.webp", "seraph", "seraph", "Thánh quang",
                    "{\"big\":[[49.5,7.4],[49.8,85.1]],\"gems\":[[49.8,15.4],[21.9,27.3],[76.9,27.2],[12.9,51.0],"
                            + "[87.0,50.6]]}", 2, true, false),
            new Frame(3003, "frame-inferno", "Hỏa Ngục Ma Vương",
                    "\"Tro tàn dưới chân ta từng là những kẻ kiêu ngạo nhất cõi đời này.\"", 300,
                    "/images/avatar-frames/inferno.webp", "inferno", "inferno", "Hỏa ngục",
                    "{\"big\":[[49.8,13.4],[50.3,83.3]],\"gems\":[[26.8,23.2],[73.2,23.0],[17.8,31.6],[82.3,31.7],"
                            + "[12.9,50.2],[86.8,50.2],[19.9,70.3],[80.0,69.9]]}", 3, true, false),
            new Frame(3004, "frame-vampire", "Bá Tước Huyết Tộc",
                    "\"Vương miện của ta đúc từ bóng tối, và màn đêm này cúi đầu chào chủ nhân của nó.\"", 300,
                    "/images/avatar-frames/vampire.webp", "vampire", "vampire", "Huyết tộc",
                    "{\"big\":[[49.8,16.0],[49.8,81.1]],\"gems\":[[25.7,19.5],[74.6,19.3],[37.9,15.1],[61.7,15.0],"
                            + "[12.0,48.9],[88.0,49.1],[14.1,67.2],[85.8,67.3],[50.1,91.7]],"
                            + "\"drips\":[[15.8,76.3],[84.1,76.4]]}", 4, true, false)
    );

    private AvatarFrameCatalog() {
    }

    /** 4 khung gốc, theo thứ tự hiển thị. */
    public static List<Frame> seeds() {
        return SEEDS;
    }
}
