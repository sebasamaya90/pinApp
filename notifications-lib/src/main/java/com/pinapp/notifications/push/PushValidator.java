package com.pinapp.notifications.push;

import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;
import com.pinapp.notifications.validation.ValidationErrors;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class PushValidator implements NotificationValidator<PushNotification> {

    public static final int MAX_TITLE_LENGTH = 256;
    public static final int MAX_BODY_LENGTH = 4096;
    /** FCM limits the data payload to 4 KB. */
    public static final int MAX_DATA_BYTES = 4096;
    /** FCM keeps undelivered messages for at most 4 weeks. */
    public static final Duration MAX_TTL = Duration.ofDays(28);

    @Override
    public List<ValidationError> validate(PushNotification n) {
        return new ValidationErrors()
                .requireNotBlank("deviceToken", n.deviceToken())
                .requireNotBlank("body", n.body())
                .requireMaxLength("body", n.body(), MAX_BODY_LENGTH)
                .requireMaxLength("title", n.title(), MAX_TITLE_LENGTH)
                .require(dataBytes(n.data()) <= MAX_DATA_BYTES, "data",
                        "payload must not exceed " + MAX_DATA_BYTES + " bytes")
                .require(n.ttl() == null || (!n.ttl().isNegative() && n.ttl().compareTo(MAX_TTL) <= 0), "ttl",
                        "must be between 0 and " + MAX_TTL.toDays() + " days")
                .toList();
    }

    private static int dataBytes(Map<String, String> data) {
        return data.entrySet().stream()
                .mapToInt(e -> e.getKey().getBytes(StandardCharsets.UTF_8).length
                        + e.getValue().getBytes(StandardCharsets.UTF_8).length)
                .sum();
    }
}
