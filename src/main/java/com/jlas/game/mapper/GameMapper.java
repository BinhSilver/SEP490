package com.jlas.game.mapper;

import com.jlas.game.dto.GameDtos.DraftStepView;
import com.jlas.game.dto.GameDtos.DraftOptionView;
import com.jlas.game.dto.GameDtos.DraftView;
import com.jlas.game.dto.GameDtos.RoomPlayerView;
import com.jlas.game.dto.GameDtos.RoomView;
import com.jlas.game.dto.GameDtos.SessionInfoView;
import com.jlas.game.dto.GameDtos.SessionPlayerView;
import com.jlas.game.dto.GameDtos.SkinLook;
import com.jlas.game.enums.GameType;
import com.jlas.game.runtime.ArenaEventHub;
import com.jlas.game.runtime.GameRoom;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.QuickDraft;
import com.jlas.game.runtime.RoomPlayer;
import com.jlas.game.runtime.RoomRegistry;
import com.jlas.game.service.GameShopService;
import com.jlas.game.service.GameSkinCatalogService;
import com.jlas.game.util.GameRules;
import com.jlas.game.util.GameShopCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Mapper cho Game module: phòng/trận trong bộ nhớ → DTO. Gọi khi đang giữ lock của phòng/trận. */
@Component
@RequiredArgsConstructor
public class GameMapper {

    private final ArenaEventHub hub;
    private final GameShopService shop;
    private final GameSkinCatalogService skins;

    public RoomView toRoomView(GameRoom room) {
        List<RoomPlayerView> players = room.orderedPlayers().stream()
                .map(player -> toPlayerView(room, player))
                .toList();
        return new RoomView(room.getRoomId(), RoomRegistry.displayCode(room.getCode()), room.getGameType(),
                GameRules.gameName(room.getGameType()), room.getVisibility(), room.getStatus(),
                room.getHostUserId(), room.getSettings(), players, GameRoom.MAX_PLAYERS,
                room.getCountdownEndsAt() == null ? null : room.getCountdownEndsAt().toEpochMilli(),
                room.getSessionId(),
                room.getSessionId() == null ? null : room.getGameType().playUrl(room.getSessionId()),
                room.getLastResult(), room.isQuickMatch(), toDraftView(room.getDraft()), room.getVersion(),
                System.currentTimeMillis());
    }

    private static DraftView toDraftView(QuickDraft draft) {
        if (draft == null) {
            return null;
        }
        List<DraftStepView> steps = new ArrayList<>();
        for (int i = 0; i < draft.steps().size(); i++) {
            QuickDraft.Step step = draft.steps().get(i);
            boolean current = i == draft.index() && !draft.isComplete();
            String phase = step.chosenValue() != null ? "DONE"
                    : current ? draft.phase().name() : "WAITING";
            List<DraftOptionView> options = current
                    ? step.options().stream().map(o -> new DraftOptionView(o.value(), o.label(), o.hint())).toList()
                    : List.of();
            steps.add(new DraftStepView(step.key(), step.title(), phase, step.tossWinner(), step.chooser(),
                    step.passed(), step.chosenValue(), step.chosenLabel(), step.chosenBy(), step.timedOut(), options,
                    current ? step.recommended() : null));
        }
        return new DraftView(draft.phase().name(), draft.index(),
                draft.phaseEndsAt() == null ? null : draft.phaseEndsAt().toEpochMilli(), steps);
    }

    public SessionInfoView toSessionInfo(GameSession session, long userId) {
        List<SessionPlayerView> players = session.getSeats().stream()
                .map(seat -> new SessionPlayerView(seat.userId(), seat.displayName(), seat.avatarUrl(), seat.slot(),
                        session.isOnline(seat.userId()), seat.avatarFrame(), seat.nameStyle()))
                .toList();
        String backUrl = session.getRoomId() == null ? "/games" : "/games/room/" + session.getRoomId();
        return new SessionInfoView(session.getSessionId(), session.getGameType(), session.getMode(),
                session.getRoomId(), session.getRoomCode() == null ? null : RoomRegistry.displayCode(session.getRoomCode()),
                session.getStatus(), session.getDeckId(), session.getDeckTitle(), session.getQuestionField(),
                session.getAnswerField(), players, userId, session.getGameType().playUrl(session.getSessionId()),
                backUrl, System.currentTimeMillis());
    }

    private RoomPlayerView toPlayerView(GameRoom room, RoomPlayer player) {
        boolean host = player.getUserId() == room.getHostUserId();
        // Phòng bạn bè: chủ phòng bấm "Bắt đầu" nên coi như luôn sẵn sàng; ghép trận nhanh: cả hai tự bấm.
        boolean ready = player.isReady() || (host && !room.isQuickMatch());
        return new RoomPlayerView(player.getUserId(), player.getDisplayName(), player.getAvatarUrl(),
                player.getSlot(), host, ready, hub.isOnline(player.getUserId()), player.getAvatarFrame(),
                player.getNameStyle(), skinOf(player.getUserId(), room.getGameType()));
    }

    /** Read on every view rather than snapshotted at join, because a player may change skin while waiting. */
    private SkinLook skinOf(long userId, GameType gameType) {
        if (!GameShopCatalog.SHOP_GAMES.contains(gameType)) {
            return null;
        }
        String code = shop.equippedCode(userId, gameType);
        return code == null ? null : skins.find(code)
                .map(item -> new SkinLook(item.code(), item.name(), item.image(), item.renderCode(), item.packPath()))
                .orElse(null);
    }
}
