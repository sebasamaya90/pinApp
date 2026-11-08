package com.pinapp.notifications.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Objects;

/**
 * Caches a token and refreshes it shortly BEFORE it expires ({@code refreshSkew}), so calls never go out
 * with an expired credential. If a refresh fails but the cached token is still valid it is reused; if
 * there is nothing valid left, the failure is reported. Thread-safe: concurrent callers share a single
 * refresh (they would all need the new token anyway).
 */
public final class RefreshingTokenProvider implements TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(RefreshingTokenProvider.class);

    private final TokenFetcher fetcher;
    private final Duration refreshSkew;
    private final InstantSource clock;
    private final Object lock = new Object();
    private AccessToken cached;

    public RefreshingTokenProvider(TokenFetcher fetcher, Duration refreshSkew) {
        this(fetcher, refreshSkew, InstantSource.system());
    }

    public RefreshingTokenProvider(TokenFetcher fetcher, Duration refreshSkew, InstantSource clock) {
        this.fetcher = Objects.requireNonNull(fetcher);
        this.refreshSkew = Objects.requireNonNull(refreshSkew);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Secret currentToken() throws IOException {
        synchronized (lock) {
            var now = clock.instant();
            if (cached != null && now.isBefore(cached.expiresAt().minus(refreshSkew))) {
                return cached.value();
            }
            try {
                cached = fetcher.fetch();
                return cached.value();
            } catch (IOException | RuntimeException e) {
                if (cached != null && now.isBefore(cached.expiresAt())) {
                    log.warn("Token refresh failed ({}); reusing the still valid cached token", e.getMessage());
                    return cached.value();
                }
                throw e instanceof IOException io ? io : new IOException("token refresh failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void invalidate() {
        synchronized (lock) {
            cached = null;
        }
    }
}
