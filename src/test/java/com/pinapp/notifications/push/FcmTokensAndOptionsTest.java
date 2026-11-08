package com.pinapp.notifications.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.pinapp.notifications.config.AccessToken;
import com.pinapp.notifications.config.RefreshingTokenProvider;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.config.TokenProvider;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.support.MutableClock;
import com.pinapp.notifications.support.ScriptedTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FcmTokensAndOptionsTest {

    private static final PushNotification PUSH = PushNotification.builder().deviceToken("dev-1").body("hello").build();

    // ------------------------------------------------------------ token refresh

    /** Fetcher issuing "token-N" valid for one hour from the (fake) clock. */
    private static final class CountingFetcher implements com.pinapp.notifications.config.TokenFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        final MutableClock clock;
        boolean failing;

        CountingFetcher(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public AccessToken fetch() throws IOException {
            if (failing) {
                throw new IOException("identity provider down");
            }
            return new AccessToken(Secret.of("token-" + fetches.incrementAndGet()), clock.instant().plus(Duration.ofHours(1)));
        }
    }

    @Test
    void tokenIsCachedUntilItIsAboutToExpireThenRefreshedAutomatically() throws Exception {
        var clock = new MutableClock();
        var fetcher = new CountingFetcher(clock);
        var tokens = new RefreshingTokenProvider(fetcher, Duration.ofMinutes(5), clock);

        assertEquals("token-1", tokens.currentToken().reveal());
        clock.advance(Duration.ofMinutes(54));
        assertEquals("token-1", tokens.currentToken().reveal(), "still valid (more than 5 min left)");
        assertEquals(1, fetcher.fetches.get());

        clock.advance(Duration.ofMinutes(2));
        assertEquals("token-2", tokens.currentToken().reveal(), "inside the refresh skew: refreshed before expiry");
    }

    @Test
    void failedRefreshReusesTheStillValidTokenButFailsOnceItExpired() throws Exception {
        var clock = new MutableClock();
        var fetcher = new CountingFetcher(clock);
        var tokens = new RefreshingTokenProvider(fetcher, Duration.ofMinutes(5), clock);
        tokens.currentToken();

        fetcher.failing = true;
        clock.advance(Duration.ofMinutes(57));
        assertEquals("token-1", tokens.currentToken().reveal(), "refresh failed but the token is not expired yet");

        clock.advance(Duration.ofMinutes(10));
        assertThrows(IOException.class, tokens::currentToken);
    }

    @Test
    void invalidateForcesAFreshToken() throws Exception {
        var clock = new MutableClock();
        var fetcher = new CountingFetcher(clock);
        var tokens = new RefreshingTokenProvider(fetcher, Duration.ofMinutes(5), clock);
        tokens.currentToken();

        tokens.invalidate();

        assertEquals("token-2", tokens.currentToken().reveal());
    }

    @Test
    void unexpectedRuntimeErrorsFromTheFetcherAreReportedAsIoException() {
        var tokens = new RefreshingTokenProvider(() -> { throw new IllegalStateException("bad json"); }, Duration.ZERO);

        assertThrows(IOException.class, tokens::currentToken);
    }

    @Test
    void fcmUsesTheCurrentTokenOnEveryRequest() throws Exception {
        var clock = new MutableClock();
        var tokens = new RefreshingTokenProvider(new CountingFetcher(clock), Duration.ofMinutes(5), clock);
        var transport = ScriptedTransport.responding(200, "{\"name\":\"n\"}");
        var provider = new FcmPushProvider(new FcmPushProvider.Config("proj", tokens), transport, clock);

        provider.send(PUSH);
        clock.advance(Duration.ofMinutes(58));
        provider.send(PUSH);

        assertEquals("Bearer token-1", transport.requests().get(0).headers().get("Authorization"));
        assertEquals("Bearer token-2", transport.requests().get(1).headers().get("Authorization"));
    }

    @Test
    void unauthorizedResponseInvalidatesTheTokenAndIsRetryable() {
        var tokens = new TokenProvider() {
            int invalidations;

            public Secret currentToken() {
                return Secret.of("stale");
            }

            public void invalidate() {
                invalidations++;
            }
        };
        var transport = ScriptedTransport.responding(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\",\"message\":\"expired\"}}");

        ProviderException e = assertThrows(ProviderException.class,
                () -> new FcmPushProvider(new FcmPushProvider.Config("proj", tokens), transport).send(PUSH));

        assertTrue(e.retryable());
        assertEquals(1, tokens.invalidations);
        assertTrue(e.getMessage().contains("UNAUTHENTICATED"));
    }

    @Test
    void thirdPartyAuthErrorIsAboutApnsCredentialsSoItIsNotRetriedAndKeepsTheToken() {
        var tokens = new TokenProvider() {
            int invalidations;

            public Secret currentToken() {
                return Secret.of("valid");
            }

            public void invalidate() {
                invalidations++;
            }
        };
        var transport = ScriptedTransport.responding(401,
                "{\"error\":{\"status\":\"THIRD_PARTY_AUTH_ERROR\",\"message\":\"APNs certificate invalid\"}}");

        ProviderException e = assertThrows(ProviderException.class,
                () -> new FcmPushProvider(new FcmPushProvider.Config("proj", tokens), transport).send(PUSH));

        assertFalse(e.retryable(), "refreshing our OAuth token cannot fix APNs credentials");
        assertEquals(0, tokens.invalidations);
    }

    @Test
    void quotaExceededAsksForAtLeastOneMinuteOfBackoff() {
        var withoutHeader = ScriptedTransport.responding(429, "{\"error\":{\"status\":\"QUOTA_EXCEEDED\",\"message\":\"slow down\"}}");
        var shortHeader = ScriptedTransport.responding(429, java.util.Map.of("Retry-After", "5"), "{}");
        var config = new FcmPushProvider.Config("proj", Secret.of("t"));

        ProviderException none = assertThrows(ProviderException.class, () -> new FcmPushProvider(config, withoutHeader).send(PUSH));
        ProviderException shorter = assertThrows(ProviderException.class, () -> new FcmPushProvider(config, shortHeader).send(PUSH));

        assertTrue(none.retryable());
        assertEquals(Duration.ofMinutes(1), none.retryAfter());
        assertEquals(Duration.ofMinutes(1), shorter.retryAfter(), "never less than FCM's recommended minimum");
    }

    @Test
    void unavailableHonoursTheRetryAfterHeader() {
        var transport = ScriptedTransport.responding(503, java.util.Map.of("Retry-After", "30"),
                "{\"error\":{\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> new FcmPushProvider(
                new FcmPushProvider.Config("proj", Secret.of("t")), transport).send(PUSH));

        assertTrue(e.retryable());
        assertEquals(Duration.ofSeconds(30), e.retryAfter());
    }

    @Test
    void reservedDataKeysAreRejectedByTheValidator() {
        var validator = new PushValidator();
        for (String key : new String[]{"from", "message_type", "google.sent_time", "gcm.notification.title"}) {
            var push = PushNotification.builder().deviceToken("t").body("b").data(key, "v").build();
            assertEquals("data", validator.validate(push).get(0).field(), key + " is reserved by FCM");
        }
        assertTrue(validator.validate(PushNotification.builder().deviceToken("t").body("b").data("orderId", "42").build()).isEmpty());
    }

    @Test
    void notBeingAbleToGetATokenIsATransientProviderFailure() {
        TokenProvider broken = () -> { throw new IOException("no network to identity provider"); };

        ProviderException e = assertThrows(ProviderException.class, () -> new FcmPushProvider(
                new FcmPushProvider.Config("proj", broken), ScriptedTransport.responding(200, "{}")).send(PUSH));

        assertTrue(e.retryable());
        assertTrue(e.getMessage().contains("access token"));
    }

    // ------------------------------------------------------------ priority and TTL

    @Test
    void highPriorityAndTtlAreMappedToAndroidAndApnsOptions() throws Exception {
        var clock = new MutableClock();
        var transport = ScriptedTransport.responding(200, "{\"name\":\"n\"}");
        var push = PushNotification.builder().deviceToken("dev").body("b")
                .priority(PushNotification.Priority.HIGH).ttl(Duration.ofHours(1)).build();

        new FcmPushProvider(new FcmPushProvider.Config("proj", Secret.of("t")), transport, clock).send(push);

        JsonNode message = Json.read(transport.lastRequest().body()).path("message");
        assertEquals("high", message.at("/android/priority").asText());
        assertEquals("3600s", message.at("/android/ttl").asText());
        assertEquals("10", message.at("/apns/headers/apns-priority").asText());
        assertEquals(String.valueOf(clock.instant().plusSeconds(3600).getEpochSecond()),
                message.at("/apns/headers/apns-expiration").asText());
    }

    @Test
    void zeroTtlMeansDeliverNowOrNever() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"name\":\"n\"}");
        var push = PushNotification.builder().deviceToken("dev").body("b").ttl(Duration.ZERO).build();

        new FcmPushProvider(new FcmPushProvider.Config("proj", Secret.of("t")), transport).send(push);

        JsonNode message = Json.read(transport.lastRequest().body()).path("message");
        assertEquals("0s", message.at("/android/ttl").asText());
        assertEquals("0", message.at("/apns/headers/apns-expiration").asText());
        assertTrue(message.at("/android/priority").isMissingNode(), "normal priority is the vendor default");
    }

    @Test
    void defaultsSendNoDeliveryOptions() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"name\":\"n\"}");

        new FcmPushProvider(new FcmPushProvider.Config("proj", Secret.of("t")), transport).send(PUSH);

        JsonNode message = Json.read(transport.lastRequest().body()).path("message");
        assertTrue(message.path("android").isMissingNode());
        assertTrue(message.path("apns").isMissingNode());
        assertFalse(PUSH.priority() == PushNotification.Priority.HIGH);
    }
}
