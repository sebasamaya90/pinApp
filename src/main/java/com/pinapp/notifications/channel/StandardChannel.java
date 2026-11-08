package com.pinapp.notifications.channel;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;
import com.pinapp.notifications.exception.DeliveryException;
import com.pinapp.notifications.exception.ValidationException;
import com.pinapp.notifications.provider.FallbackProvider;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.resilience.Resilience;
import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.retry.Sleeper;
import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generic channel = validator + provider(s) + retry policy. Every built-in channel (and any custom
 * one) is just a different assembly of these parts, so no channel-specific subclassing is needed.
 */
public final class StandardChannel<N extends Notification> implements NotificationChannel {

    private final ChannelType type;
    private final Class<N> notificationType;
    private final NotificationValidator<N> validator;
    private final NotificationProvider<N> provider;

    private StandardChannel(Builder<N> b) {
        this.type = b.type;
        this.notificationType = b.notificationType;
        this.validator = b.validator;
        Resilience resilience = b.resilience != null ? b.resilience
                : Resilience.builder().retryPolicy(b.retryPolicy).sleeper(b.sleeper).build();
        List<NotificationProvider<N>> resilient = b.providers.stream()
                .<NotificationProvider<N>>map(p -> resilience.decorate(p))
                .toList();
        this.provider = resilient.size() == 1 ? resilient.get(0) : new FallbackProvider<>(resilient);
    }

    public static <N extends Notification> Builder<N> builder(
            ChannelType type, Class<N> notificationType, NotificationValidator<N> validator) {
        return new Builder<>(type, notificationType, validator);
    }

    @Override
    public ChannelType type() {
        return type;
    }

    @Override
    public ProviderResponse deliver(Notification notification) {
        if (!notificationType.isInstance(notification)) {
            throw new IllegalArgumentException("Channel '" + type + "' expects " + notificationType.getSimpleName()
                    + " but received " + notification.getClass().getSimpleName());
        }
        N typed = notificationType.cast(notification);

        List<ValidationError> errors = validator.validate(typed);
        if (!errors.isEmpty()) {
            throw new ValidationException(type, errors);
        }
        try {
            return provider.send(typed);
        } catch (ProviderException e) {
            throw new DeliveryException(type, provider.name(), e.attempts(), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new DeliveryException(type, provider.name(), 1, "unexpected error: " + e, e);
        }
    }

    public static final class Builder<N extends Notification> {
        private final ChannelType type;
        private final Class<N> notificationType;
        private final NotificationValidator<N> validator;
        private final List<NotificationProvider<N>> providers = new ArrayList<>();
        private RetryPolicy retryPolicy = RetryPolicy.none();
        private Sleeper sleeper = Sleeper.THREAD;
        private Resilience resilience;

        private Builder(ChannelType type, Class<N> notificationType, NotificationValidator<N> validator) {
            this.type = Objects.requireNonNull(type);
            this.notificationType = Objects.requireNonNull(notificationType);
            this.validator = Objects.requireNonNull(validator);
        }

        /** Primary provider. */
        public Builder<N> provider(NotificationProvider<N> provider) {
            providers.add(0, Objects.requireNonNull(provider));
            return this;
        }

        /** Provider used when the previous ones fail. May be called several times (ordered). */
        public Builder<N> fallbackTo(NotificationProvider<N> provider) {
            providers.add(Objects.requireNonNull(provider));
            return this;
        }

        /**
         * Full resilience stack (retry, circuit breaker, bulkhead, rate limiter) applied to every provider
         * of this channel. When set, it replaces {@link #retryPolicy} and {@link #sleeper}.
         */
        public Builder<N> resilience(Resilience resilience) {
            this.resilience = Objects.requireNonNull(resilience);
            return this;
        }

        public Builder<N> retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy);
            return this;
        }

        /** Mostly for tests: replaces real waiting between retries. */
        public Builder<N> sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper);
            return this;
        }

        public StandardChannel<N> build() {
            if (providers.isEmpty()) {
                throw new IllegalStateException("Channel '" + type + "' needs at least one provider");
            }
            return new StandardChannel<>(this);
        }
    }
}
