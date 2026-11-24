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

    /** A page asks the zone many times per request; one DB read per user per 30 s is enough. */
    private static final long ZONE_TTL_NANOS = 30_000_000_000L;
    private final java.util.concurrent.ConcurrentHashMap<Long, Object[]> zoneCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public ZoneId zone(Long userId) {
        if (userId == null) return zones.zoneOf(null);
        long now = System.nanoTime();
        Object[] hit = zoneCache.get(userId);
        if (hit != null && now - (Long) hit[1] < ZONE_TTL_NANOS) return (ZoneId) hit[0];
        ZoneId z = zones.zoneOf(userId);
        if (zoneCache.size() > 10_000) zoneCache.clear();
        zoneCache.put(userId, new Object[] {z, now});
        return z;
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
