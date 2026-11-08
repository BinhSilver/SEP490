package com.jlas.mission.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.event.LearningEvent;
import com.jlas.common.event.LearningEventType;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.CreditAwardWorker;
import com.jlas.mission.service.MissionDemoResetService;
import com.jlas.mission.service.UserZoneService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo tools: publish mock learning events for the current learner, move the mission clock and
 * simulate a credit outage. Only registered when {@code mission.dev-tools=true}.
 */
@RestController
@RequestMapping("/api/dev/mission")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class MissionDevController {

    private final ApplicationEventPublisher events;
    private final MissionClock clock;
    private final CreditAwardWorker worker;
    private final CreditAwardService awards;
    private final MissionDemoResetService resetService;
    private final UserZoneService zones;

    /**
     * @param eventId optional; send the same id twice to show that a replayed event is ignored
     */
    public record MockEventRequest(@NotNull LearningEventType type, Long referenceId,
                                   @Min(0) @Max(600) Integer minutes, @Min(0) @Max(500) Integer vocabularyCount,
                                   @Size(max = 120) String eventId) {
    }

    public record ClockRequest(@Min(-60) @Max(60) int offsetDays) {
    }

    public record CreditFailureRequest(boolean enabled) {
    }

    @PostMapping("/events")
    public ApiResponse<Map<String, Object>> publish(@AuthenticationPrincipal UserPrincipal user,
                                                    @Valid @RequestBody MockEventRequest request) {
        String eventId = request.eventId() == null || request.eventId().isBlank()
                ? "MOCK:" + request.type() + ":" + UUID.randomUUID() : request.eventId();
        LearningEvent event = new LearningEvent(eventId, request.type(), user.userId(),
                request.referenceId() == null ? 0L : request.referenceId(),
                request.minutes() == null ? 0 : request.minutes(),
                request.vocabularyCount() == null ? 0 : request.vocabularyCount(), Instant.now());
        events.publishEvent(event);
        return ApiResponse.ok("Đã phát event", Map.of("eventId", eventId, "type", request.type()));
    }

    @GetMapping("/clock")
    public ApiResponse<Map<String, Object>> clock(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", clockState(user.userId()));
    }

    @PostMapping("/clock")
    public ApiResponse<Map<String, Object>> shiftClock(@AuthenticationPrincipal UserPrincipal user,
                                                       @Valid @RequestBody ClockRequest request) {
        clock.shift(Duration.ofDays(request.offsetDays()));
        return ApiResponse.ok("Đã chỉnh đồng hồ demo", clockState(user.userId()));
    }

    /** Wipes the current learner's mission data and mission credits so a manual test can start over. */
    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Đã xoá dữ liệu demo", resetService.reset(user.userId()));
    }

    @PostMapping("/credit-failure")
    public ApiResponse<Map<String, Object>> creditFailure(@AuthenticationPrincipal UserPrincipal user,
                                                          @RequestBody CreditFailureRequest request) {
        worker.simulateFailure(request.enabled());
        if (!request.enabled()) {
            awards.applyOpenAwards(user.userId());
        }
        return ApiResponse.ok("OK", Map.of("simulateFailure", worker.simulateFailure(),
                "openAwards", awards.openAwards(user.userId())));
    }

    private Map<String, Object> clockState(Long userId) {
        return Map.of("offsetDays", clock.offset().toDays(), "now", clock.now().toString(),
                "today", clock.today(zones.zoneOf(userId)).toString());
    }
}
