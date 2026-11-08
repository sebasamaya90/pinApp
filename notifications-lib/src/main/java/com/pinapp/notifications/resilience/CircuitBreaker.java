package com.pinapp.notifications.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Count-based sliding-window circuit breaker (hand written, no external library).
 *
 * <pre>
 *  CLOSED --(failure rate >= threshold, once minimumCalls seen)--> OPEN
 *  OPEN   --(openDuration elapsed)--> HALF_OPEN
 *  HALF_OPEN --(all probe calls succeed)--> CLOSED
 *  HALF_OPEN --(any probe call fails)--> OPEN
 * </pre>
 * Thread-safe. Time comes from an injected {@link InstantSource} so tests never sleep.
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Transition(String name, State from, State to) {
    }

    /**
     * @param slidingWindowSize      number of most recent calls considered
     * @param minimumCalls           calls needed in the window before the rate is evaluated
     * @param failureRateThreshold   0 &lt; rate &lt;= 1 that opens the breaker
     * @param openDuration           how long to reject calls before probing again
     * @param halfOpenPermittedCalls probe calls allowed (and required to succeed) in HALF_OPEN
     */
    public record Config(int slidingWindowSize, int minimumCalls, double failureRateThreshold,
                         Duration openDuration, int halfOpenPermittedCalls) {
        public Config {
            if (slidingWindowSize < 1) {
                throw new IllegalArgumentException("slidingWindowSize must be >= 1");
            }
            if (minimumCalls < 1 || minimumCalls > slidingWindowSize) {
                throw new IllegalArgumentException("minimumCalls must be between 1 and slidingWindowSize");
            }
            if (!(failureRateThreshold > 0 && failureRateThreshold <= 1)) {
                throw new IllegalArgumentException("failureRateThreshold must be in (0, 1]");
            }
            Objects.requireNonNull(openDuration, "openDuration");
            if (halfOpenPermittedCalls < 1) {
                throw new IllegalArgumentException("halfOpenPermittedCalls must be >= 1");
            }
        }

        public static Config defaults() {
            return new Config(10, 5, 0.5, Duration.ofSeconds(30), 2);
        }
    }

    private final String name;
    private final Config config;
    private final InstantSource clock;
    private final Consumer<Transition> listener;

    private final Object lock = new Object();
    private final boolean[] window;
    private int next;
    private int count;
    private int failures;
    private State state = State.CLOSED;
    private Instant openedAt;
    private int probesAcquired;
    private int probesSucceeded;

    public CircuitBreaker(String name, Config config, InstantSource clock, Consumer<Transition> listener) {
        this.name = Objects.requireNonNull(name);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.listener = Objects.requireNonNull(listener);
        this.window = new boolean[config.slidingWindowSize()];
    }

    /** @return true if the call may proceed; the caller MUST then report {@link #onSuccess()} or {@link #onFailure()}. */
    public boolean tryAcquirePermission() {
        Transition transition = null;
        boolean permitted;
        synchronized (lock) {
            if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(config.openDuration()))) {
                transition = moveTo(State.HALF_OPEN);
            }
            if (state == State.CLOSED) {
                permitted = true;
            } else if (state == State.HALF_OPEN && probesAcquired < config.halfOpenPermittedCalls()) {
                probesAcquired++;
                permitted = true;
            } else {
                permitted = false;
            }
        }
        fire(transition);
        return permitted;
    }

    public void onSuccess() {
        Transition transition = null;
        synchronized (lock) {
            if (state == State.CLOSED) {
                record(false);
            } else if (state == State.HALF_OPEN && ++probesSucceeded >= config.halfOpenPermittedCalls()) {
                transition = moveTo(State.CLOSED);
            }
            // OPEN: a late result from a call started before opening; ignore.
        }
        fire(transition);
    }

    public void onFailure() {
        Transition transition = null;
        synchronized (lock) {
            if (state == State.CLOSED) {
                record(true);
                if (count >= config.minimumCalls() && (double) failures / count >= config.failureRateThreshold()) {
                    transition = moveTo(State.OPEN);
                }
            } else if (state == State.HALF_OPEN) {
                transition = moveTo(State.OPEN);
            }
        }
        fire(transition);
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    // Must be called holding the lock.
    private void record(boolean failed) {
        if (count == window.length) {
            if (window[next]) {
                failures--;
            }
        } else {
            count++;
        }
        window[next] = failed;
        if (failed) {
            failures++;
        }
        next = (next + 1) % window.length;
    }

    // Must be called holding the lock.
    private Transition moveTo(State to) {
        Transition transition = new Transition(name, state, to);
        state = to;
        switch (to) {
            case OPEN -> openedAt = clock.instant();
            case HALF_OPEN -> {
                probesAcquired = 0;
                probesSucceeded = 0;
            }
            case CLOSED -> {
                java.util.Arrays.fill(window, false);
                next = 0;
                count = 0;
                failures = 0;
            }
        }
        return transition;
    }

    private void fire(Transition transition) {
        if (transition == null) {
            return;
        }
        log.warn("Circuit breaker '{}': {} -> {}", transition.name(), transition.from(), transition.to());
        try {
            listener.accept(transition);
        } catch (RuntimeException e) {
            log.warn("Circuit breaker listener failed", e);
        }
    }
}
