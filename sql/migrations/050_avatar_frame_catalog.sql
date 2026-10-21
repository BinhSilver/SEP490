-- [JLAS-AVATAR-FRAME-ADMIN] Danh mục khung avatar chuyển từ code (AvatarFrameCatalog.java) vào DB để admin quản lý
-- ở /admin/avatar-frames. Người đã mua vẫn ở user_avatar_frame, khung đang đeo ở user_profile_style.
--   theme: cosmic | seraph | inferno | vampire — chọn bộ hiệu ứng CSS có sẵn; name_style luôn bằng theme.
--   gems_json: toạ độ đá chớp sáng đo trên ảnh gốc (% khung); NULL với khung admin thêm.
--   active = FALSE: ngừng bán, người đã mua vẫn đeo. deleted = TRUE: chỉ khi chưa ai mua.
-- Nạp sẵn 4 khung gốc, giữ id 3001–3004 (reference_id trong sổ CP). INSERT IGNORE: chạy lại an toàn và không ghi
-- đè chỉnh sửa của admin. AvatarFrameSchemaInitializer chạy cùng nội dung khi khởi động.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS avatar_frame (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code             VARCHAR(40)  NOT NULL UNIQUE,
    name             VARCHAR(100) NOT NULL,
    description      VARCHAR(500) NOT NULL DEFAULT '',
    price            INT          NOT NULL DEFAULT 0,
    image            VARCHAR(1024) NOT NULL,
    theme            VARCHAR(20)  NOT NULL,
    name_style       VARCHAR(40)  NOT NULL,
    name_style_label VARCHAR(60)  NOT NULL,
    gems_json        TEXT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_avatar_frame_price CHECK (price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO avatar_frame (id, code, name, description, price, image, theme, name_style, name_style_label, gems_json, sort_order) VALUES
    (3001, 'frame-cosmic', 'Tinh Vân Huyền Bí', '"Vạn vì tinh tú chỉ là mảnh vụn quay quanh quỹ đạo của ta."', 300,
     '/images/avatar-frames/cosmic.webp', 'cosmic', 'cosmic', 'Tinh vân',
     '{"big":[[49.8,13.8],[50.5,85.6]],"gems":[[77.1,24.0],[77.2,75.3],[22.5,75.5],[22.4,24.6]],"orbs":[[12.9,48.7],[88.6,50.0]]}', 1),
    (3002, 'frame-seraph', 'Thiên Sứ Quang Minh', '"Nơi nào ta đặt chân tới, nơi đó bóng tối không còn quyền tồn tại."', 300,
     '/images/avatar-frames/seraph.webp', 'seraph', 'seraph', 'Thánh quang',
     '{"big":[[49.5,7.4],[49.8,85.1]],"gems":[[49.8,15.4],[21.9,27.3],[76.9,27.2],[12.9,51.0],[87.0,50.6]]}', 2),
    (3003, 'frame-inferno', 'Hỏa Ngục Ma Vương', '"Tro tàn dưới chân ta từng là những kẻ kiêu ngạo nhất cõi đời này."', 300,
     '/images/avatar-frames/inferno.webp', 'inferno', 'inferno', 'Hỏa ngục',
     '{"big":[[49.8,13.4],[50.3,83.3]],"gems":[[26.8,23.2],[73.2,23.0],[17.8,31.6],[82.3,31.7],[12.9,50.2],[86.8,50.2],[19.9,70.3],[80.0,69.9]]}', 3),
    (3004, 'frame-vampire', 'Bá Tước Huyết Tộc', '"Vương miện của ta đúc từ bóng tối, và màn đêm này cúi đầu chào chủ nhân của nó."', 300,
     '/images/avatar-frames/vampire.webp', 'vampire', 'vampire', 'Huyết tộc',
     '{"big":[[49.8,16.0],[49.8,81.1]],"gems":[[25.7,19.5],[74.6,19.3],[37.9,15.1],[61.7,15.0],[12.0,48.9],[88.0,49.1],[14.1,67.2],[85.8,67.3],[50.1,91.7]],"drips":[[15.8,76.3],[84.1,76.4]]}', 4);
