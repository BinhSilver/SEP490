package com.jlas.game.enums;

/**
 * Vòng đời phòng: (ghép trận nhanh: tung đồng xu chọn cài đặt) → chờ sẵn sàng → đếm ngược → đang chơi
 * → (xong) quay lại chờ để đấu tiếp.
 */
public enum RoomStatus {
    DRAFTING,
    WAITING,
    COUNTDOWN,
    PLAYING
}
