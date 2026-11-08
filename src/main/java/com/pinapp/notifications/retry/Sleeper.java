package com.pinapp.notifications.retry;

import java.time.Duration;

/** Abstraction over waiting so retry logic is testable without real delays. */
@FunctionalInterface
public interface Sleeper {

    Sleeper THREAD = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
