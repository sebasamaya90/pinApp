package com.pinapp.notifications.support;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** Manually advanced clock: resilience tests never really wait. */
public final class MutableClock implements InstantSource {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public synchronized Instant instant() {
        return now;
    }

    public synchronized void advance(Duration duration) {
        now = now.plus(duration);
    }
}
