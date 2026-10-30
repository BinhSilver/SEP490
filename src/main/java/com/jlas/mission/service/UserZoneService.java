package com.jlas.mission.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.mission.config.MissionProperties;
import com.jlas.mission.repository.MissionJdbcRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the timezone that defines a learner's day. */
@Service
@RequiredArgsConstructor
public class UserZoneService {

    private final MissionJdbcRepository jdbc;
    private final MissionProperties properties;

    public ZoneId zoneOf(Long userId) {
        return jdbc.findZone(userId).map(this::parseOrDefault).orElseGet(this::defaultZone);
    }

    @Transactional
    public ZoneId update(Long userId, String zoneId) {
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId.trim());
        } catch (DateTimeException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Múi giờ không hợp lệ: " + zoneId);
        }
        jdbc.saveZone(userId, zone.getId());
        return zone;
    }

    private ZoneId parseOrDefault(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ex) {
            return defaultZone();
        }
    }

    private ZoneId defaultZone() {
        return ZoneId.of(properties.defaultZone());
    }
}
