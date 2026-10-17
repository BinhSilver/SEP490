package com.jlas.game.service;

import com.jlas.game.dto.AvatarFrameDtos.CosmeticsView;
import com.jlas.game.repository.AvatarFrameJdbcRepository.Style;

import java.util.Collection;
import java.util.Map;

/** Khung avatar + màu tên: mua bằng CP chung, chọn khung và màu đang dùng trên hồ sơ. */
public interface AvatarFrameService {

    CosmeticsView view(long userId);

    /** Trừ CP một lần cho mỗi khung, mở màu tên đi kèm và dùng luôn cả hai. */
    CosmeticsView buy(long userId, String frameCode);

    /** Chỉ chọn được khung/màu đã mua; null = bỏ khung / màu mặc định. */
    CosmeticsView equip(long userId, String frameCode, String nameStyle);

    /** Khung + màu đang dùng (đã lọc món không còn hợp lệ), để gắn vào thông tin người dùng. */
    Style style(long userId);

    /**
     * Khung + màu của nhiều người (2 câu truy vấn), cho bảng xếp hạng / phòng game / bình luận. Người không dùng gì
     * không có trong map. Chỉ để trang trí: lỗi đọc DB trả map rỗng thay vì làm hỏng trang.
     */
    Map<Long, Style> styles(Collection<Long> userIds);
}
