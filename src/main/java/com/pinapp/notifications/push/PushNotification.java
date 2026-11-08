package com.pinapp.notifications.push;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mobile push: addressed to a device token, with an optional title, a key/value data payload, a
 * delivery {@link Priority} and an optional time-to-live (how long the vendor keeps trying while the
 * device is offline; {@code null} = vendor default, {@link Duration#ZERO} = deliver now or never).
 */
public record PushNotification(String deviceToken, String title, String body, Map<String, String> data,
                               Priority priority, Duration ttl) implements Notification {

    public enum Priority { NORMAL, HIGH }

    public PushNotification {
        data = data == null ? Map.of() : Map.copyOf(data);
        priority = priority == null ? Priority.NORMAL : priority;
    }

    public PushNotification(String deviceToken, String title, String body, Map<String, String> data) {
        this(deviceToken, title, body, data, Priority.NORMAL, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChannelType channel() {
        return ChannelType.PUSH;
    }

    @Override
    public String recipient() {
        return deviceToken;
    }

    public static final class Builder {
        private String deviceToken;
        private String title;
        private String body;
        private final Map<String, String> data = new LinkedHashMap<>();
        private Priority priority = Priority.NORMAL;
        private Duration ttl;

        private Builder() {
        }

        public Builder deviceToken(String deviceToken) {
            this.deviceToken = deviceToken;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder data(String key, String value) {
            this.data.put(key, value);
            return this;
        }

        public Builder priority(Priority priority) {
            this.priority = priority;
            return this;
        }

        /** Maximum time the vendor may hold the message for an offline device (max 28 days on FCM). */
        public Builder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public PushNotification build() {
            return new PushNotification(deviceToken, title, body, data, priority, ttl);
        }
    }
}
