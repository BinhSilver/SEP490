package com.jlas.game.controller;

import com.jlas.game.enums.GameType;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.game.dto.GameDtos.CatalogView;
import com.jlas.game.dto.GameDtos.CreateRoomRequest;
import com.jlas.game.dto.GameDtos.DraftChoiceRequest;
import com.jlas.game.dto.GameDtos.DeckCardView;
import com.jlas.game.dto.GameDtos.DeckDetailView;
import com.jlas.game.dto.GameDtos.DeckPage;
import com.jlas.game.dto.GameDtos.GameSettings;
import com.jlas.game.dto.GameDtos.JoinRoomRequest;
import com.jlas.game.dto.GameDtos.LaunchView;
import com.jlas.game.dto.GameDtos.LobbyView;
import com.jlas.game.dto.GameDtos.QuickMatchRequest;
import com.jlas.game.dto.GameDtos.QuickMatchView;
import com.jlas.game.dto.GameDtos.ReadyRequest;
import com.jlas.game.dto.GameDtos.RoomView;
import com.jlas.game.dto.GameDtos.SessionInfoView;
import com.jlas.game.dto.GameDtos.SoloRequest;
import com.jlas.game.service.GameDeckService;
import com.jlas.game.service.GameService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Controller API Game Arena: danh mục, bộ thẻ, phòng đối kháng, chơi đơn, kênh realtime. Base path: /api/games */
@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameController {
    private final GameService gameService;
    private final GameDeckService deckService;

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<CatalogView>> catalog() {
        return ok(gameService.catalog());
    }

    @GetMapping("/lobby")
    public ResponseEntity<ApiResponse<LobbyView>> lobby(@AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.lobby(user.userId()));
    }

    /** tab = MINE | RECENT | ALL; q lọc theo tên bộ; game = chỉ lấy bộ chơi được game đó. */
    @GetMapping("/decks")
    public ResponseEntity<ApiResponse<DeckPage>> decks(@RequestParam(defaultValue = "MINE") String tab,
                                                       @RequestParam(required = false) String q,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(required = false) GameType game,
                                                       @AuthenticationPrincipal UserPrincipal user) {
        return ok(deckService.decks(user.userId(), tab, q, page, game));
    }

    @GetMapping("/decks/{setId}")
    public ResponseEntity<ApiResponse<DeckDetailView>> deck(@PathVariable Long setId,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        return ok(deckService.deck(user.userId(), setId));
    }

    @GetMapping("/decks/{setId}/cards")
    public ResponseEntity<ApiResponse<List<DeckCardView>>> deckCards(@PathVariable Long setId,
                                                                    @AuthenticationPrincipal UserPrincipal user) {
        return ok(deckService.cards(user.userId(), setId));
    }

    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<RoomView>> createRoom(@Valid @RequestBody CreateRoomRequest request,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.createRoom(user.userId(), request));
    }

    @PostMapping("/rooms/join")
    public ResponseEntity<ApiResponse<RoomView>> joinByCode(@Valid @RequestBody JoinRoomRequest request,
                                                            @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.joinByCode(user.userId(), request.code()));
    }

    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ApiResponse<RoomView>> room(@PathVariable String roomId,
                                                      @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.room(user.userId(), roomId));
    }

    @PostMapping("/rooms/{roomId}/join")
    public ResponseEntity<ApiResponse<RoomView>> join(@PathVariable String roomId,
                                                      @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.join(user.userId(), roomId));
    }

    @PutMapping("/rooms/{roomId}/settings")
    public ResponseEntity<ApiResponse<RoomView>> settings(@PathVariable String roomId,
                                                          @RequestBody GameSettings settings,
                                                          @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.updateSettings(user.userId(), roomId, settings));
    }

    @PostMapping("/rooms/{roomId}/ready")
    public ResponseEntity<ApiResponse<RoomView>> ready(@PathVariable String roomId, @RequestBody ReadyRequest request,
                                                       @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.setReady(user.userId(), roomId, request.ready()));
    }

    @PostMapping("/rooms/{roomId}/start")
    public ResponseEntity<ApiResponse<RoomView>> start(@PathVariable String roomId,
                                                       @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.start(user.userId(), roomId));
    }

    @PostMapping("/rooms/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leave(@PathVariable String roomId,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        gameService.leave(user.userId(), roomId);
        return ResponseEntity.ok(ApiResponse.ok("OK"));
    }

    @PostMapping("/quick-match")
    public ResponseEntity<ApiResponse<QuickMatchView>> quickMatch(@Valid @RequestBody QuickMatchRequest request,
                                                                  @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.quickMatch(user.userId(), request));
    }

    @GetMapping("/quick-match")
    public ResponseEntity<ApiResponse<QuickMatchView>> quickMatchStatus(@AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.quickMatchStatus(user.userId()));
    }

    @PostMapping("/quick-match/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelQuickMatch(@AuthenticationPrincipal UserPrincipal user) {
        gameService.cancelQuickMatch(user.userId());
        return ResponseEntity.ok(ApiResponse.ok("OK"));
    }

    @PostMapping("/rooms/{roomId}/draft/choose")
    public ResponseEntity<ApiResponse<RoomView>> draftChoose(@PathVariable String roomId,
                                                             @Valid @RequestBody DraftChoiceRequest request,
                                                             @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.draftChoose(user.userId(), roomId, request));
    }

    @PostMapping("/rooms/{roomId}/draft/pass")
    public ResponseEntity<ApiResponse<RoomView>> draftPass(@PathVariable String roomId,
                                                           @Valid @RequestBody DraftChoiceRequest request,
                                                           @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.draftPass(user.userId(), roomId, request));
    }

    @PostMapping("/solo")
    public ResponseEntity<ApiResponse<LaunchView>> solo(@Valid @RequestBody SoloRequest request,
                                                        @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.startSolo(user.userId(), request));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoView>> session(@PathVariable String sessionId,
                                                                @AuthenticationPrincipal UserPrincipal user) {
        return ok(gameService.session(user.userId(), sessionId));
    }

    @PostMapping("/sessions/{sessionId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveSession(@PathVariable String sessionId,
                                                          @AuthenticationPrincipal UserPrincipal user) {
        gameService.leaveSession(user.userId(), sessionId);
        return ResponseEntity.ok(ApiResponse.ok("OK"));
    }

    /** Kênh Server-Sent Events của người đang đăng nhập (phòng + trận). */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal UserPrincipal user) {
        return gameService.subscribe(user.userId());
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok("OK", data));
    }
}
