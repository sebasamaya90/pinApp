package com.pinapp.notifications.resilience;

import com.pinapp.notifications.resilience.CircuitBreaker.State;
import com.pinapp.notifications.resilience.CircuitBreaker.Transition;
import com.pinapp.notifications.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private MutableClock clock;
    private List<Transition> transitions;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        transitions = new ArrayList<>();
        // window of 4 calls, evaluated from 2 calls, opens at >= 50% failures, 30 s open, 2 probes
        breaker = new CircuitBreaker("cb", new CircuitBreaker.Config(4, 2, 0.5, Duration.ofSeconds(30), 2),
                clock, transitions::add);
    }

    private void call(boolean ok) {
        assertTrue(breaker.tryAcquirePermission());
        if (ok) {
            breaker.onSuccess();
        } else {
            breaker.onFailure();
        }
    }

    private void open() {
        call(false);
        call(false);
    }

    @Test
    void staysClosedBelowMinimumCalls() {
        call(false);

        assertEquals(State.CLOSED, breaker.state());
    }

    @Test
    void staysClosedWhenFailureRateIsUnderThreshold() {
        call(true);
        call(true);
        call(true);
        call(false);

        assertEquals(State.CLOSED, breaker.state());
    }

    @Test
    void opensWhenFailureRateReachesThresholdAndRejectsCalls() {
        open();

        assertEquals(State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());
    }

    @Test
    void keepsRejectingUntilOpenDurationElapses() {
        open();

        clock.advance(Duration.ofSeconds(29));

        assertFalse(breaker.tryAcquirePermission());
        assertEquals(State.OPEN, breaker.state());
    }

    @Test
    void afterOpenDurationAllowsOnlyTheConfiguredProbes() {
        open();
        clock.advance(Duration.ofSeconds(30));

        assertTrue(breaker.tryAcquirePermission());
        assertTrue(breaker.tryAcquirePermission());
        assertFalse(breaker.tryAcquirePermission(), "only 2 probes are allowed");
        assertEquals(State.HALF_OPEN, breaker.state());
    }

    @Test
    void closesAgainWhenAllProbesSucceed() {
        open();
        clock.advance(Duration.ofSeconds(30));
        call(true);
        call(true);

        assertEquals(State.CLOSED, breaker.state());
        assertEquals(List.of(State.OPEN, State.HALF_OPEN, State.CLOSED),
                transitions.stream().map(Transition::to).toList());
    }

    @Test
    void reopensWhenAProbeFails() {
        open();
        clock.advance(Duration.ofSeconds(30));
        call(false);

        assertEquals(State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission(), "must wait a full openDuration again");
    }

    @Test
    void closingForgetsThePastSoOneFailureDoesNotReopen() {
        open();
        clock.advance(Duration.ofSeconds(30));
        call(true);
        call(true);

        call(false);

        assertEquals(State.CLOSED, breaker.state());
    }

    @Test
    void lateResultsWhileOpenAreIgnored() {
        open();

        breaker.onFailure();
        breaker.onSuccess();

        assertEquals(State.OPEN, breaker.state());
    }

    @Test
    void faultyListenerDoesNotBreakTheBreaker() {
        var b = new CircuitBreaker("x", new CircuitBreaker.Config(2, 1, 1.0, Duration.ofSeconds(1), 1),
                clock, t -> { throw new IllegalStateException("boom"); });

        assertTrue(b.tryAcquirePermission());
        b.onFailure();

        assertEquals(State.OPEN, b.state());
    }

    @Test
    void validatesConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker.Config(0, 1, 0.5, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker.Config(4, 5, 0.5, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker.Config(4, 2, 0, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker.Config(4, 2, 0.5, Duration.ZERO, 0));
    }
}
