package com.pinapp.notifications.resilience;

import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderException.Reason;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.sms.SmsNotification;
import com.pinapp.notifications.support.FakeProvider;
import com.pinapp.notifications.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderDecoratorsTest {

    private static final SmsNotification SMS = new SmsNotification("+14155552671", "hi");

    // ------------------------------------------------------------ circuit breaker

    private static CircuitBreaker breaker(MutableClock clock) {
        return new CircuitBreaker("cb", new CircuitBreaker.Config(2, 2, 0.5, Duration.ofSeconds(30), 1), clock, t -> { });
    }

    @Test
    void openBreakerRejectsWithoutCallingTheProviderAndIsNotRetryable() {
        FakeProvider<SmsNotification> down = FakeProvider.alwaysFailing("down", true);
        var provider = new CircuitBreakerProvider<>(down, breaker(new MutableClock()));
        assertThrows(ProviderException.class, () -> provider.send(SMS));
        assertThrows(ProviderException.class, () -> provider.send(SMS));

        ProviderException rejected = assertThrows(ProviderException.class, () -> provider.send(SMS));

        assertEquals(Reason.CIRCUIT_OPEN, rejected.reason());
        assertFalse(rejected.retryable());
        assertEquals(2, down.calls(), "third call must not reach the provider");
    }

    @Test
    void permanentErrorsDoNotTripTheBreaker() {
        FakeProvider<SmsNotification> rejecting = FakeProvider.alwaysFailing("bad-requests", false);
        var cb = breaker(new MutableClock());
        var provider = new CircuitBreakerProvider<>(rejecting, cb);

        for (int i = 0; i < 10; i++) {
            assertThrows(ProviderException.class, () -> provider.send(SMS));
        }

        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertEquals(10, rejecting.calls());
    }

    @Test
    void unexpectedRuntimeErrorsCountAsFailures() {
        NotificationProvider<SmsNotification> buggy = new NotificationProvider<>() {
            public String name() {
                return "buggy";
            }

            public ProviderResponse send(SmsNotification n) {
                throw new IllegalStateException("bug");
            }
        };
        var cb = breaker(new MutableClock());
        var provider = new CircuitBreakerProvider<>(buggy, cb);

        assertThrows(IllegalStateException.class, () -> provider.send(SMS));
        assertThrows(IllegalStateException.class, () -> provider.send(SMS));

        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    // ------------------------------------------------------------ bulkhead

    @Test
    void bulkheadRejectsWhenAllSlotsAreBusyAndRecoversAfterRelease() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        NotificationProvider<SmsNotification> slow = new NotificationProvider<>() {
            public String name() {
                return "slow";
            }

            public ProviderResponse send(SmsNotification n) {
                entered.countDown();
                try {
                    finish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ProviderResponse.of("slow", "id");
            }
        };
        var bulkhead = new Bulkhead(new Bulkhead.Config(1, Duration.ZERO));
        var provider = new BulkheadProvider<>(slow, bulkhead);

        CompletableFuture<ProviderResponse> inFlight = CompletableFuture.supplyAsync(() -> {
            try {
                return provider.send(SMS);
            } catch (ProviderException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        ProviderException rejected = assertThrows(ProviderException.class, () -> provider.send(SMS));
        assertEquals(Reason.BULKHEAD_FULL, rejected.reason());
        assertFalse(rejected.retryable());

        finish.countDown();
        inFlight.get(5, TimeUnit.SECONDS);
        assertEquals(1, bulkhead.availablePermits());
        assertEquals("slow", provider.send(SMS).provider());
    }

    @Test
    void bulkheadSlotIsReleasedEvenWhenTheProviderFails() {
        var bulkhead = new Bulkhead(new Bulkhead.Config(1, Duration.ZERO));
        FakeProvider<SmsNotification> down = FakeProvider.alwaysFailing("down", true);
        var provider = new BulkheadProvider<>(down, bulkhead);

        assertThrows(ProviderException.class, () -> provider.send(SMS));
        ProviderException second = assertThrows(ProviderException.class, () -> provider.send(SMS));

        assertEquals(Reason.FAILURE, second.reason());
        assertEquals(2, down.calls());
        assertEquals(1, bulkhead.availablePermits());
    }

    // ------------------------------------------------------------ rate limiter

    @Test
    void rateLimiterAllowsBurstThenReportsHowLongToWait() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(new RateLimiter.Config(2, 3), clock);

        assertEquals(Duration.ZERO, limiter.tryAcquire());
        assertEquals(Duration.ZERO, limiter.tryAcquire());
        assertEquals(Duration.ZERO, limiter.tryAcquire());
        assertEquals(Duration.ofMillis(500), limiter.tryAcquire());

        clock.advance(Duration.ofMillis(500));
        assertEquals(Duration.ZERO, limiter.tryAcquire());
    }

    @Test
    void rateLimiterNeverAccumulatesMoreThanTheBurst() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(new RateLimiter.Config(1, 2), clock);
        clock.advance(Duration.ofHours(1));

        assertEquals(Duration.ZERO, limiter.tryAcquire());
        assertEquals(Duration.ZERO, limiter.tryAcquire());
        assertFalse(limiter.tryAcquire().isZero());
    }

    @Test
    void rateLimitedCallsAreRetryableAndCarryTheWaitTime() throws Exception {
        FakeProvider<SmsNotification> ok = FakeProvider.succeeding("ok");
        var provider = new RateLimiterProvider<>(ok,
                new RateLimiter(new RateLimiter.Config(1, 1), new MutableClock()));
        provider.send(SMS);

        ProviderException limited = assertThrows(ProviderException.class, () -> provider.send(SMS));

        assertEquals(Reason.RATE_LIMITED, limited.reason());
        assertTrue(limited.retryable());
        assertEquals(Duration.ofSeconds(1), limited.retryAfter());
        assertEquals(1, ok.calls(), "limited call must not reach the provider");
    }
}
