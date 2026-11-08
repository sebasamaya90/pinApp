package com.pinapp.notifications.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {

    @Test
    void noneMeansSingleAttempt() {
        assertEquals(1, RetryPolicy.none().maxAttempts());
    }

    @Test
    void fixedDelayIsConstant() {
        var policy = RetryPolicy.fixedDelay(3, Duration.ofMillis(200));
        assertEquals(Duration.ofMillis(200), policy.delayBeforeRetry(1));
        assertEquals(Duration.ofMillis(200), policy.delayBeforeRetry(2));
    }

    @Test
    void exponentialBackoffDoublesAndIsCapped() {
        var policy = RetryPolicy.exponentialBackoff(10, Duration.ofMillis(100), Duration.ofMillis(500));
        assertEquals(Duration.ofMillis(100), policy.delayBeforeRetry(1));
        assertEquals(Duration.ofMillis(200), policy.delayBeforeRetry(2));
        assertEquals(Duration.ofMillis(400), policy.delayBeforeRetry(3));
        assertEquals(Duration.ofMillis(500), policy.delayBeforeRetry(4));
        assertEquals(Duration.ofMillis(500), policy.delayBeforeRetry(1000));
    }

    @Test
    void requiresAtLeastOneAttempt() {
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.fixedDelay(0, Duration.ZERO));
    }
}
