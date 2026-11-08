package com.pinapp.notifications.slack;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;

/**
 * Slack message. Lives in its own package and defines its own {@link ChannelType}: proof that a new
 * channel is added WITHOUT modifying any existing class of the library.
 *
 * <p>There is deliberately no "target channel" field: Slack's docs state that an incoming webhook
 * posts to the channel it was created for and "you cannot override the default channel" (nor the
 * username or icon). To post to several channels, register one webhook per channel (one
 * {@code SlackWebhookProvider} each).
 */
public record SlackNotification(String body) implements Notification {

    public static final ChannelType SLACK = ChannelType.of("slack");

    @Override
    public ChannelType channel() {
        return SLACK;
    }

    /** The destination is fixed by the webhook URL, not by the message. */
    @Override
    public String recipient() {
        return "webhook";
    }
}
