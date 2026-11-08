package com.pinapp.notifications.provider;

import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.sms.SmsNotification;
import com.pinapp.notifications.support.FakeProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryingAndFallbackProviderTest {

    private static final SmsNotification SMS = new SmsNotification("+14155552671", "hi");

    @Test
    void retriesTransientFailuresUntilSuccess() throws Exception {
        FakeProvider<SmsNotification> flaky = FakeProvider.failingTimes("flaky", 2);
        List<Duration> sleeps = new ArrayList<>();
        var retrying = new RetryingProvider<>(flaky, RetryPolicy.fixedDelay(3, Duration.ofMillis(10)), sleeps::add);

        ProviderResponse response = retrying.send(SMS);

        assertEquals(3, response.attempts());
        assertEquals(3, flaky.calls());
        assertEquals(2, sleeps.size());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        FakeProvider<SmsNotification> down = FakeProvider.alwaysFailing("down", true);
        var retrying = new RetryingProvider<>(down, RetryPolicy.fixedDelay(3, Duration.ZERO), d -> { });

        ProviderException e = assertThrows(ProviderException.class, () -> retrying.send(SMS));

        assertEquals(3, down.calls());
        assertEquals(3, e.attempts());
    }

    @Test
    void doesNotRetryPermanentFailures() {
        FakeProvider<SmsNotification> rejecting = FakeProvider.alwaysFailing("rejecting", false);
        var retrying = new RetryingProvider<>(rejecting, RetryPolicy.fixedDelay(5, Duration.ZERO), d -> { });

        assertThrows(ProviderException.class, () -> retrying.send(SMS));

        assertEquals(1, rejecting.calls());
    }

    @Test
    void interruptionStopsRetrying() {
        FakeProvider<SmsNotification> down = FakeProvider.alwaysFailing("down", true);
        var retrying = new RetryingProvider<>(down, RetryPolicy.fixedDelay(5, Duration.ZERO), d -> {
            throw new InterruptedException();
        });

        assertThrows(ProviderException.class, () -> retrying.send(SMS));

        assertEquals(1, down.calls());
        assertEquals(true, Thread.interrupted(), "interrupt flag must be restored");
    }

    @Test
    void fallbackUsesNextProviderWhenPrimaryFails() throws Exception {
        FakeProvider<SmsNotification> primary = FakeProvider.alwaysFailing("primary", true);
        FakeProvider<SmsNotification> backup = FakeProvider.succeeding("backup");
        var fallback = new FallbackProvider<>(List.<NotificationProvider<SmsNotification>>of(primary, backup));

        ProviderResponse response = fallback.send(SMS);

        assertEquals("backup", response.provider());
        assertEquals(1, primary.calls());
        assertEquals("failover[primary>backup]", fallback.name());
    }

    @Test
    void fallbackFailsWithLastErrorWhenAllFail() {
        var fallback = new FallbackProvider<>(List.<NotificationProvider<SmsNotification>>of(
                FakeProvider.alwaysFailing("a", true), FakeProvider.alwaysFailing("b", true)));

        ProviderException e = assertThrows(ProviderException.class, () -> fallback.send(SMS));

        assertEquals("b is failing", e.getMessage());
        assertEquals(1, e.getSuppressed().length);
    }
}
