package com.pinapp.notifications.support;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double provider with a scripted number of initial failures. */
public final class FakeProvider<N extends Notification> implements NotificationProvider<N> {

    private final String name;
    private final int failuresBeforeSuccess;
    private final boolean retryable;
    private final List<N> received = new CopyOnWriteArrayList<>();

    private FakeProvider(String name, int failuresBeforeSuccess, boolean retryable) {
        this.name = name;
        this.failuresBeforeSuccess = failuresBeforeSuccess;
        this.retryable = retryable;
    }

    public static <N extends Notification> FakeProvider<N> succeeding(String name) {
        return new FakeProvider<>(name, 0, true);
    }

    public static <N extends Notification> FakeProvider<N> alwaysFailing(String name, boolean retryable) {
        return new FakeProvider<>(name, Integer.MAX_VALUE, retryable);
    }

    public static <N extends Notification> FakeProvider<N> failingTimes(String name, int times) {
        return new FakeProvider<>(name, times, true);
    }

    public int calls() {
        return received.size();
    }

    public List<N> received() {
        return new ArrayList<>(received);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ProviderResponse send(N notification) throws ProviderException {
        received.add(notification);
        if (received.size() <= failuresBeforeSuccess) {
            throw new ProviderException(name + " is failing", retryable, retryable ? 503 : 400, null);
        }
        return ProviderResponse.of(name, name + "-msg-" + received.size());
    }
}
