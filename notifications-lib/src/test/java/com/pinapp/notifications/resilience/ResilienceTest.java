package com.pinapp.notifications.resilience;

import com.pinapp.notifications.NotificationService;
import com.pinapp.notifications.channel.Channels;
import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderException.Reason;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.provider.RetryingProvider;
import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.sms.SmsNotification;
import com.pinapp.notifications.support.FakeProvider;
import com.pinapp.notifications.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilienceTest {

    private static final SmsNotification SMS = new SmsNotification("+14155552671", "hi");
    private static final EmailNotification EMAIL = EmailNotification.of("ana@example.com", "Hi", "Body");

    // ------------------------------------------------------------ retry: jitter, Retry-After, deadline

    @Test
    void jitterShrinksTheDelayWithinTheConfiguredRange() {
        RetryPolicy base = RetryPolicy.fixedDelay(3, Duration.ofSeconds(1));

        assertEquals(Duration.ofMillis(1000), RetryPolicy.withJitter(base, 0.5, () -> 0.0).delayBeforeRetry(1));
        assertEquals(Duration.ofMillis(500), RetryPolicy.withJitter(base, 0.5, () -> 1.0).delayBeforeRetry(1));
        assertEquals(3, RetryPolicy.withJitter(base, 0.5).maxAttempts());
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.withJitter(base, 1.5));
    }

    private static NotificationProvider<SmsNotification> throttledOnce(Duration retryAfter) {
        return new NotificationProvider<>() {
            private int calls;

            public String name() {
                return "throttled";
            }

            public ProviderResponse send(SmsNotification n) throws ProviderException {
                if (++calls == 1) {
                    throw ProviderException.of(Reason.FAILURE, "429", true, 429, retryAfter, null);
                }
                return ProviderResponse.of("throttled", "ok");
            }
        };
    }

    @Test
    void retryNeverWaitsLessThanTheVendorsRetryAfter() throws Exception {
        List<Duration> sleeps = new ArrayList<>();
        var retrying = new RetryingProvider<>(throttledOnce(Duration.ofSeconds(5)),
                RetryPolicy.fixedDelay(3, Duration.ofMillis(100)), sleeps::add);

        retrying.send(SMS);

        assertEquals(List.of(Duration.ofSeconds(5)), sleeps);
    }

    @Test
    void retryUsesThePolicyDelayWhenItIsLongerThanRetryAfter() throws Exception {
        List<Duration> sleeps = new ArrayList<>();
        var retrying = new RetryingProvider<>(throttledOnce(Duration.ofMillis(10)),
                RetryPolicy.fixedDelay(3, Duration.ofMillis(100)), sleeps::add);

        retrying.send(SMS);

        assertEquals(List.of(Duration.ofMillis(100)), sleeps);
    }

    @Test
    void retryStopsWhenTheNextWaitWouldExceedTheOverallDeadline() {
        var clock = new MutableClock();
        FakeProvider<SmsNotification> down = FakeProvider.alwaysFailing("down", true);
        var retrying = new RetryingProvider<>(down, RetryPolicy.fixedDelay(10, Duration.ofMillis(600)),
                clock::advance, Duration.ofSeconds(1), clock);

        ProviderException e = assertThrows(ProviderException.class, () -> retrying.send(SMS));

        // t=0 fail, wait 600 (<=1000) -> t=600 fail, 600+600 > 1000 -> give up
        assertEquals(2, down.calls());
        assertEquals(2, e.attempts());
    }

    @Test
    void retryAfterLongerThanTheDeadlineStopsImmediately() {
        var clock = new MutableClock();
        var retrying = new RetryingProvider<>(throttledOnce(Duration.ofMinutes(5)),
                RetryPolicy.fixedDelay(3, Duration.ZERO), clock::advance, Duration.ofSeconds(10), clock);

        ProviderException e = assertThrows(ProviderException.class, () -> retrying.send(SMS));

        assertEquals(1, e.attempts());
    }

    // ------------------------------------------------------------ composition

    @Test
    void rateLimitRejectionIsWaitedOutByTheRetryLayer() throws Exception {
        var clock = new MutableClock();
        FakeProvider<SmsNotification> ok = FakeProvider.succeeding("ok");
        NotificationProvider<SmsNotification> stack = Resilience.builder()
                .retryPolicy(RetryPolicy.fixedDelay(3, Duration.ZERO))
                .rateLimiter(new RateLimiter.Config(1, 1))
                .sleeper(clock::advance)
                .clock(clock)
                .build()
                .decorate(ok);

        stack.send(SMS);
        ProviderResponse second = stack.send(SMS);

        assertEquals(2, second.attempts(), "first attempt was rate limited, second passed after waiting");
        assertEquals(2, ok.calls());
    }

    @Test
    void openBreakerSkipsRetriesAndFailsOverToTheBackupImmediately() {
        FakeProvider<EmailNotification> primary = FakeProvider.alwaysFailing("primary", true);
        FakeProvider<EmailNotification> backup = FakeProvider.succeeding("backup");
        List<CircuitBreaker.Transition> transitions = new ArrayList<>();
        Resilience resilience = Resilience.builder()
                .retryPolicy(RetryPolicy.fixedDelay(3, Duration.ZERO))
                .sleeper(d -> { })
                .circuitBreaker(new CircuitBreaker.Config(2, 2, 0.5, Duration.ofSeconds(30), 1))
                .circuitBreakerListener(transitions::add)
                .build();

        try (var service = NotificationService.builder()
                .register(Channels.email().provider(primary).fallbackTo(backup).resilience(resilience).build())
                .build()) {

            assertEquals("backup", service.send(EMAIL).provider());
            assertEquals(2, primary.calls(), "attempt 3 was rejected by the open breaker, not sent");
            assertEquals(1, transitions.size());

            assertEquals("backup", service.send(EMAIL).provider());
            assertEquals(2, primary.calls(), "breaker is open: primary is not even called");
        }
    }

    @Test
    void eachProviderGetsItsOwnBreakerState() {
        FakeProvider<EmailNotification> primary = FakeProvider.alwaysFailing("primary", true);
        FakeProvider<EmailNotification> backup = FakeProvider.succeeding("backup");
        Resilience resilience = Resilience.builder()
                .circuitBreaker(new CircuitBreaker.Config(2, 2, 0.5, Duration.ofSeconds(30), 1))
                .build();

        try (var service = NotificationService.builder()
                .register(Channels.email().provider(primary).fallbackTo(backup).resilience(resilience).build())
                .build()) {
            for (int i = 0; i < 5; i++) {
                assertTrue(service.send(EMAIL).isSuccess());
            }
            assertEquals(5, backup.calls(), "primary's open breaker must not affect the backup");
        }
    }

    @Test
    void customRateLimiterFactoryReplacesTheBuiltInOne() {
        List<String> created = new ArrayList<>();
        FakeProvider<SmsNotification> ok = FakeProvider.succeeding("ok");
        NotificationProvider<SmsNotification> stack = Resilience.builder()
                .rateLimiterFactory(providerName -> {
                    created.add(providerName);
                    return () -> Duration.ofSeconds(3); // e.g. a distributed limiter that says "wait 3 s"
                })
                .build()
                .decorate(ok);

        ProviderException e = assertThrows(ProviderException.class, () -> stack.send(SMS));

        assertEquals(List.of("ok"), created);
        assertEquals(Reason.RATE_LIMITED, e.reason());
        assertEquals(Duration.ofSeconds(3), e.retryAfter());
        assertEquals(0, ok.calls());
    }

    @Test
    void bulkheadObserverReceivesEachProviderBulkhead() {
        List<String> observed = new ArrayList<>();
        Resilience resilience = Resilience.builder()
                .bulkhead(new Bulkhead.Config(3, Duration.ZERO))
                .bulkheadObserver((name, bulkhead) -> observed.add(name + ":" + bulkhead.maxConcurrentCalls()))
                .build();

        resilience.decorate(FakeProvider.<SmsNotification>succeeding("a"));
        resilience.decorate(FakeProvider.<SmsNotification>succeeding("b"));

        assertEquals(List.of("a:3", "b:3"), observed);
    }

    @Test
    void recommendedPresetBuildsAWorkingStack() throws Exception {
        FakeProvider<SmsNotification> ok = FakeProvider.succeeding("ok");

        assertEquals("ok", Resilience.recommended().decorate(ok).send(SMS).provider());
    }
}
