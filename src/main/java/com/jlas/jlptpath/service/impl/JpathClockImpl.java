package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.service.JpathClock;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.service.UserZoneService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Reads time from {@link MissionClock} so the study day always matches the streak day, demo offset
 * included.
 */
@Component
public class JpathClockImpl implements JpathClock {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserZoneService zones;
    private final MissionClock missionClock;

    public JpathClockImpl(UserZoneService zones, MissionClock missionClock) {
        this.zones = zones;
        this.missionClock = missionClock;
    }

    @Override
    public ZoneId zone(Long userId) {
        return zones.zoneOf(userId);
    }

    @Override
    public LocalDate today(Long userId) {
        return missionClock.today(zone(userId));
    }

    @Override
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(missionClock.now(), SERVER_ZONE);
    }

    @Override
    public LocalDateTime localNow(Long userId) {
        return zonedNow(userId).toLocalDateTime();
    }

    @Override
    public ZonedDateTime zonedNow(Long userId) {
        return missionClock.now().atZone(zone(userId));
    }
}
