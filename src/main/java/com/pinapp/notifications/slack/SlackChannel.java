package com.pinapp.notifications.slack;

import com.pinapp.notifications.channel.StandardChannel;

/** Builder entry point for the Slack channel (same assembly as the built-in channels). */
public final class SlackChannel {

    private SlackChannel() {
    }

    public static StandardChannel.Builder<SlackNotification> builder() {
        return StandardChannel.builder(SlackNotification.SLACK, SlackNotification.class, new SlackValidator());
    }
}
