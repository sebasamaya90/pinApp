package com.pinapp.notifications.provider;

import java.util.Optional;

/**
 * Per-delivery information (notification id, optional idempotency key) made available to providers
 * WITHOUT changing the {@link NotificationProvider} contract or any decorator. It is bound with a
 * {@link ScopedValue} for the duration of one delivery, retries and failover included, so every
 * attempt carries the same id.
 *
 * <p>Providers use it to tag the vendor request (e.g. SendGrid {@code custom_args}, Mailgun
 * {@code v:} variables) so duplicates can be reconciled from vendor webhooks. No idempotency header
 * was found in the official docs of SendGrid, Mailgun, Twilio, Vonage or FCM for these endpoints, hence
 * deduplication is done locally (see {@code DeduplicationStore}).
 *
 * @param notificationId  generated once per send, stable across retries
 * @param idempotencyKey caller supplied key; {@code null} when none was given
 */
public record DeliveryContext(String notificationId, String idempotencyKey) {

    private static final ScopedValue<DeliveryContext> CURRENT = ScopedValue.newInstance();

    @FunctionalInterface
    public interface ThrowingSupplier<R, X extends Exception> {
        R get() throws X;
    }

    /** The context of the delivery running on this thread, if any. */
    public static Optional<DeliveryContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** Runs {@code operation} with this context bound. */
    public <R, X extends Exception> R callWith(ThrowingSupplier<R, X> operation) throws X {
        return ScopedValue.where(CURRENT, this).call(operation::get);
    }
}
