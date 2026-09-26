package com.jlas.mission.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Current time for the mission module. Day boundaries are always computed in the learner's zone
 * from an {@link Instant}, never from the server's default zone.
 *
 * <p>The offset only moves through the demo tools, so a demo can jump to "tomorrow".
 */
@Component("missionTime")
public class MissionClock {

    private final Clock clock;
    private volatile Duration offset = Duration.ZERO;

    public MissionClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant().plus(offset);
    }

    public LocalDate today(ZoneId zone) {
        return LocalDate.ofInstant(now(), zone);
    }

    public Duration offset() {
        return offset;
    }

    public void shift(Duration value) {
        offset = value == null ? Duration.ZERO : value;
    }
}
