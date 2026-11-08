package com.jlas.mission.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.dto.ClaimMissionResponse;
import com.jlas.mission.dto.MissionItemResponse;
import com.jlas.mission.entity.MissionDefinition;
import com.jlas.mission.entity.UserMission;
import com.jlas.mission.enums.MissionCode;
import com.jlas.mission.repository.MissionDefinitionRepository;
import com.jlas.mission.repository.MissionJdbcRepository;
import com.jlas.mission.repository.UserMissionRepository;
import com.jlas.mission.service.DailyMissionService;
import com.jlas.mission.service.UserZoneService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.moderation.credit.service.UserCreditService.CreditResult;
import com.jlas.notification.service.UserNotificationPort;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link DailyMissionService}. */
@Service
@RequiredArgsConstructor
public class DailyMissionServiceImpl implements DailyMissionService {

    static final String LIFETIME = "LIFETIME";
    private static final String REF_USER_MISSION = "USER_MISSION";

    private final MissionDefinitionRepository definitions;
    private final UserMissionRepository userMissions;
    private final MissionJdbcRepository jdbc;
    private final UserNotificationPort notifications;
    private final UserCreditService credits;
    private final UserZoneService zones;
    private final MissionClock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(Long userId, MissionCode code, LocalDate day, int amount) {
        MissionDefinition definition = definitions.findByCode(code.name())
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .filter(value -> MissionCode.TYPE_DAILY.equals(value.getMissionType()))
                .orElse(null);
        if (definition == null || amount <= 0) {
            return;
        }
        UserMission mission = lockOrCreate(userId, definition, day.toString());
        if (mission.getCompletedAt() != null) {
            return;
        }
        int target = target(definition);
        mission.setProgressCount(Math.min(target, value(mission.getProgressCount()) + amount));
        if (mission.getProgressCount() >= target) {
            mission.setCompletedAt(LocalDateTime.now());
            // A finished mission is announced in the notification bell; the reward itself is claimed on /goals.
            notifications.notifyUser(userId, "MISSION", "Nhiệm vụ hoàn thành",
                    definition.getTitle() + " · vào nhận +" + value(definition.getRewardCredits()) + " CP",
                    "/goals", "mission-ready:" + definition.getCode() + ":" + day);
        }
        userMissions.save(mission);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MissionItemResponse> list(Long userId, LocalDate day) {
        Map<Long, UserMission> progress = userMissions.findWithDefinition(userId, day.toString()).stream()
                .collect(Collectors.toMap(value -> value.getMissionDefinition().getId(), Function.identity()));
        return definitions.findByMissionTypeAndIsActiveTrueOrderByDisplayOrderAscIdAsc(MissionCode.TYPE_DAILY)
                .stream()
                .map(definition -> toItem(definition, progress.get(definition.getId())))
                .toList();
    }

    @Override
    @Transactional
    public ClaimMissionResponse claim(Long userId, String code) {
        MissionDefinition definition = definitions.findByCode(code)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .filter(value -> MissionCode.TYPE_DAILY.equals(value.getMissionType()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy nhiệm vụ."));
        LocalDate today = clock.today(zones.zoneOf(userId));
        UserMission mission = lockOrCreate(userId, definition, today.toString());
        int reward = value(definition.getRewardCredits());
        if (mission.getClaimedAt() != null) {
            return new ClaimMissionResponse(definition.getCode(), definition.getTitle(), reward, true,
                    credits.balance(userId));
        }
        if (MissionCode.DAILY_CHECK_IN.name().equals(definition.getCode())) {
            // Claiming the check-in is the check-in; it does not count toward the streak.
            mission.setProgressCount(target(definition));
            mission.setCompletedAt(LocalDateTime.now());
        } else if (mission.getCompletedAt() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Nhiệm vụ chưa hoàn thành.");
        }
        mission.setClaimedAt(LocalDateTime.now());
        userMissions.save(mission);
        if (reward <= 0) {
            return new ClaimMissionResponse(definition.getCode(), definition.getTitle(), 0, false,
                    credits.balance(userId));
        }
        CreditResult result = credits.earn(userId, reward, reasonOf(definition), REF_USER_MISSION, mission.getId(),
                definition.getTitle());
        int granted = result.applied() ? result.amount() : reward;
        return new ClaimMissionResponse(definition.getCode(), definition.getTitle(), granted, !result.applied(),
                result.balance(), result.applied() && granted < reward);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean completeLifetime(Long userId, MissionDefinition definition) {
        UserMission mission = lockOrCreate(userId, definition, LIFETIME);
        if (mission.getCompletedAt() != null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        mission.setProgressCount(target(definition));
        mission.setCompletedAt(now);
        mission.setClaimedAt(now);
        userMissions.save(mission);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MissionDefinition> activeStreakMilestones() {
        return definitions.findByMissionTypeAndIsActiveTrueOrderByDisplayOrderAscIdAsc(MissionCode.TYPE_STREAK)
                .stream()
                .sorted((left, right) -> Integer.compare(target(left), target(right)))
                .toList();
    }

    private UserMission lockOrCreate(Long userId, MissionDefinition definition, String occurrenceKey) {
        jdbc.ensureUserMission(userId, definition.getId(), occurrenceKey);
        return userMissions.findForUpdate(userId, definition.getId(), occurrenceKey)
                .orElseThrow(() -> new IllegalStateException("user_mission row missing after insert"));
    }

    private MissionItemResponse toItem(MissionDefinition definition, UserMission mission) {
        boolean checkIn = MissionCode.DAILY_CHECK_IN.name().equals(definition.getCode());
        boolean claimed = mission != null && mission.getClaimedAt() != null;
        boolean completed = checkIn || (mission != null && mission.getCompletedAt() != null);
        int progress = mission == null ? 0 : value(mission.getProgressCount());
        return new MissionItemResponse(definition.getCode(), definition.getTitle(), definition.getDescription(),
                checkIn && !claimed ? target(definition) : progress, target(definition),
                value(definition.getRewardCredits()), completed, claimed);
    }

    private static CreditReason reasonOf(MissionDefinition definition) {
        return MissionCode.parse(definition.getCode()).map(MissionCode::creditReason).orElse(CreditReason.STUDY_REWARD);
    }

    private static int target(MissionDefinition definition) {
        return Math.max(1, value(definition.getTargetCount()));
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
