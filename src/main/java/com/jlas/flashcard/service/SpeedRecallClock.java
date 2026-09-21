package com.jlas.flashcard.service;
import java.time.*;
import org.springframework.stereotype.Component;
@Component
public class SpeedRecallClock {
    public LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }
}
