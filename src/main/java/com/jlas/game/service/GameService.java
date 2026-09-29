package com.jlas.game.service;

import com.jlas.game.dto.GameDtos.CatalogView;
import com.jlas.game.dto.GameDtos.CreateRoomRequest;
import com.jlas.game.dto.GameDtos.DraftChoiceRequest;
import com.jlas.game.dto.GameDtos.GameSettings;
import com.jlas.game.dto.GameDtos.LaunchView;
import com.jlas.game.dto.GameDtos.LobbyView;
import com.jlas.game.dto.GameDtos.QuickMatchRequest;
import com.jlas.game.dto.GameDtos.QuickMatchView;
import com.jlas.game.dto.GameDtos.RoomView;
import com.jlas.game.dto.GameDtos.SessionInfoView;
import com.jlas.game.dto.GameDtos.SoloRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Service xử lý nghiệp vụ game: phòng đối kháng 2 người, chơi đơn, kết nối realtime. */
public interface GameService {

    CatalogView catalog();

    LobbyView lobby(long userId);

    RoomView createRoom(long userId, CreateRoomRequest request);

    RoomView joinByCode(long userId, String code);

    RoomView join(long userId, String roomId);

    RoomView room(long userId, String roomId);

    RoomView updateSettings(long userId, String roomId, GameSettings settings);

    RoomView setReady(long userId, String roomId, boolean ready);

    /** Chủ phòng bắt đầu: đếm ngược rồi mở trận cho cả hai. */
    RoomView start(long userId, String roomId);

    void leave(long userId, String roomId);

    /** Vào hàng chờ ghép trận nhanh (cùng game, cùng bộ); ghép được ngay thì trả MATCHED kèm roomId. */
    QuickMatchView quickMatch(long userId, QuickMatchRequest request);

    QuickMatchView quickMatchStatus(long userId);

    void cancelQuickMatch(long userId);

    /** Người giữ quyền chọn (sau khi tung đồng xu) chọn giá trị cho cài đặt đang tới lượt. */
    RoomView draftChoose(long userId, String roomId, DraftChoiceRequest request);

    /** Nhường quyền chọn cài đặt đang tới lượt cho đối thủ (một lần mỗi cài đặt). */
    RoomView draftPass(long userId, String roomId, DraftChoiceRequest request);

    LaunchView startSolo(long userId, SoloRequest request);

    SessionInfoView session(long userId, String sessionId);

    /** Rời trận đang chơi (xử thua trong đối kháng). */
    void leaveSession(long userId, String sessionId);

    SseEmitter subscribe(long userId);
}
