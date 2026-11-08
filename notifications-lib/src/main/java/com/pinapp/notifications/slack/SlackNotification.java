package com.pinapp.notifications.slack;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;

/**
 * Slack message. Lives in its own package and defines its own {@link ChannelType}: proof that a new
 * channel is added WITHOUT modifying any existing class of the library.
 *
 * @param target optional override such as "#alerts"; {@code null} uses the webhook's default channel
 */
public record SlackNotification(String target, String body) implements Notification {

    public static final ChannelType SLACK = ChannelType.of("slack");

    @Override
    public ChannelType channel() {
        return SLACK;
    }

    @Override
    public String recipient() {
        return target == null ? "default" : target;
    }
}
