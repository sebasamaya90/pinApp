package com.pinapp.notifications.resilience;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.RetryingProvider;
import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.retry.Sleeper;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Immutable description of the resilience stack applied to EACH provider of a channel.
 * Every provider gets its own breaker, bulkhead and limiter (state is never shared between vendors).
 *
 * <pre>
 *  Retry( RateLimiter( Bulkhead( CircuitBreaker( provider ))))
 * </pre>
 * Why this order:
 * <ul>
 *   <li>the breaker is innermost so it only judges real vendor calls, not local rejections;</li>
 *   <li>the bulkhead and limiter sit outside it so rejected calls never touch the vendor;</li>
 *   <li>retry is outermost so every attempt passes through all protections; an open breaker or full
 *       bulkhead is not retryable, so a {@code FallbackProvider} above takes over immediately.</li>
 * </ul>
 * Mechanisms other than retry are opt-in; {@link #recommended()} enables a sensible set.
 */
public final class Resilience {

    private final RetryPolicy retryPolicy;
    private final Duration maxElapsed;
    private final Sleeper sleeper;
    private final InstantSource clock;
    private final CircuitBreaker.Config breaker;
    private final Consumer<CircuitBreaker.Transition> breakerListener;
    private final Bulkhead.Config bulkhead;
    private final BiConsumer<String, Bulkhead> bulkheadObserver;
    private final RateLimiter.Config rateLimiter;
    private final Function<String, RateLimiter> rateLimiterFactory;

    private Resilience(Builder b) {
        this.retryPolicy = b.retryPolicy;
        this.maxElapsed = b.maxElapsed;
        this.sleeper = b.sleeper;
        this.clock = b.clock;
        this.breaker = b.breaker;
        this.breakerListener = b.breakerListener;
        this.bulkhead = b.bulkhead;
        this.bulkheadObserver = b.bulkheadObserver;
        this.rateLimiter = b.rateLimiter;
        this.rateLimiterFactory = b.rateLimiterFactory;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Production-oriented defaults: up to 3 attempts with exponential backoff (200 ms to 2 s, 50% jitter),
     * a 10 s overall deadline, circuit breaker (10-call window, opens at 50% failures, 30 s open) and a
     * bulkhead of 20 concurrent calls per provider. The rate limiter is NOT included because quotas are
     * vendor/plan specific: add {@code .rateLimiter(...)} with your contracted limit.
     */
    public static Resilience recommended() {
        return builder()
                .retryPolicy(RetryPolicy.withJitter(
                        RetryPolicy.exponentialBackoff(3, Duration.ofMillis(200), Duration.ofSeconds(2)), 0.5))
                .maxElapsed(Duration.ofSeconds(10))
                .circuitBreaker(CircuitBreaker.Config.defaults())
                .bulkhead(Bulkhead.Config.defaults())
                .build();
    }

    /** Wraps one provider with a fresh set of protections (fresh breaker/bulkhead/limiter state). */
    public <N extends Notification> NotificationProvider<N> decorate(NotificationProvider<N> provider) {
        NotificationProvider<N> decorated = provider;
        if (breaker != null) {
            decorated = new CircuitBreakerProvider<>(decorated,
                    new CircuitBreaker(provider.name(), breaker, clock, breakerListener));
        }
        if (bulkhead != null) {
            Bulkhead instance = new Bulkhead(bulkhead);
            bulkheadObserver.accept(provider.name(), instance);
            decorated = new BulkheadProvider<>(decorated, instance);
        }
        RateLimiter limiter = rateLimiterFactory != null ? rateLimiterFactory.apply(provider.name())
                : rateLimiter != null ? new TokenBucketRateLimiter(rateLimiter, clock) : null;
        if (limiter != null) {
            decorated = new RateLimiterProvider<>(decorated, limiter);
        }
        return new RetryingProvider<>(decorated, retryPolicy, sleeper, maxElapsed, clock);
    }

    public static final class Builder {
        private RetryPolicy retryPolicy = RetryPolicy.none();
        private Duration maxElapsed;
        private Sleeper sleeper = Sleeper.THREAD;
        private InstantSource clock = InstantSource.system();
        private CircuitBreaker.Config breaker;
        private Consumer<CircuitBreaker.Transition> breakerListener = t -> { };
        private Bulkhead.Config bulkhead;
        private BiConsumer<String, Bulkhead> bulkheadObserver = (name, bulkhead) -> { };
        private RateLimiter.Config rateLimiter;
        private Function<String, RateLimiter> rateLimiterFactory;

        private Builder() {
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy);
            return this;
        }

        /** Overall time budget for all attempts of one send; retries stop when it would be exceeded. */
        public Builder maxElapsed(Duration maxElapsed) {
            this.maxElapsed = Objects.requireNonNull(maxElapsed);
            return this;
        }

        public Builder circuitBreaker(CircuitBreaker.Config config) {
            this.breaker = Objects.requireNonNull(config);
            return this;
        }

        /** Observe breaker transitions (CLOSED/OPEN/HALF_OPEN), e.g. to emit metrics or alerts. */
        public Builder circuitBreakerListener(Consumer<CircuitBreaker.Transition> listener) {
            this.breakerListener = Objects.requireNonNull(listener);
            return this;
        }

        public Builder bulkhead(Bulkhead.Config config) {
            this.bulkhead = Objects.requireNonNull(config);
            return this;
        }

        /** Receives (providerName, bulkhead) when each bulkhead is created, e.g. to register gauges. */
        public Builder bulkheadObserver(BiConsumer<String, Bulkhead> observer) {
            this.bulkheadObserver = Objects.requireNonNull(observer);
            return this;
        }

        /** Built-in per-JVM token bucket, one per provider. */
        public Builder rateLimiter(RateLimiter.Config config) {
            this.rateLimiter = Objects.requireNonNull(config);
            this.rateLimiterFactory = null;
            return this;
        }

        /** Custom limiter per provider name, e.g. a distributed one backed by Redis. */
        public Builder rateLimiterFactory(Function<String, RateLimiter> factory) {
            this.rateLimiterFactory = Objects.requireNonNull(factory);
            this.rateLimiter = null;
            return this;
        }

        /** Mostly for tests: replaces real waiting between retries. */
        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper);
            return this;
        }

        /** Mostly for tests: replaces the system clock. */
        public Builder clock(InstantSource clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        public Resilience build() {
            return new Resilience(this);
        }
    }
}
