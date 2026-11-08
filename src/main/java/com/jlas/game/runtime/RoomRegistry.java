package com.jlas.game.runtime;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Kho phòng trong bộ nhớ: tra theo id, theo mã phòng và theo người chơi. */
@Component
@RequiredArgsConstructor
public class RoomRegistry {

    /** Mã là chuỗi chữ số để nhập bằng bàn phím số, giữ nguyên số 0 ở đầu. */
    private static final char[] CODE_ALPHABET = "0123456789".toCharArray();
    private static final int CODE_LENGTH = 6;

    private final GameProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<String, String> roomIdByCode = new ConcurrentHashMap<>();
    private final Map<Long, String> roomIdByUser = new ConcurrentHashMap<>();

    public void register(GameRoom room) {
        if (rooms.size() >= properties.arena().maxRooms()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Đang có quá nhiều phòng, bạn thử lại sau ít phút nhé");
        }
        rooms.put(room.getRoomId(), room);
        roomIdByCode.put(room.getCode(), room.getRoomId());
    }

    public Optional<GameRoom> find(String roomId) {
        return roomId == null ? Optional.empty() : Optional.ofNullable(rooms.get(roomId));
    }

    public GameRoom require(String roomId) {
        return find(roomId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Phòng không tồn tại hoặc đã đóng"));
    }

    public Optional<GameRoom> findByCode(String rawCode) {
        String code = normalizeCode(rawCode);
        return Optional.ofNullable(roomIdByCode.get(code)).flatMap(this::find);
    }

    public Optional<GameRoom> roomOf(long userId) {
        return Optional.ofNullable(roomIdByUser.get(userId)).flatMap(this::find);
    }

    public void bindUser(long userId, String roomId) {
        roomIdByUser.put(userId, roomId);
    }

    public void unbindUser(long userId, String roomId) {
        roomIdByUser.remove(userId, roomId);
    }

    public void remove(GameRoom room) {
        rooms.remove(room.getRoomId());
        roomIdByCode.remove(room.getCode(), room.getRoomId());
        roomIdByUser.values().removeIf(room.getRoomId()::equals);
    }

    public List<GameRoom> all() {
        return new ArrayList<>(rooms.values());
    }

    /** Mã gồm 6 chữ số, ví dụ 001234; không trùng với phòng đang tồn tại. */
    public String newCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String candidate = code.toString();
            if (!roomIdByCode.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Không tạo được mã phòng, thử lại");
    }

    public static String normalizeCode(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]", "");
    }

    public static String displayCode(String code) {
        return code;
    }
}
