package com.pinapp.notifications.config;

import java.io.IOException;

/**
 * Obtains a fresh token from the identity provider. Implement it with the library of your choice,
 * e.g. Google's {@code GoogleCredentials.fromStream(...).createScoped(scope)} + {@code refreshAccessToken()}
 * for FCM. This library deliberately does not depend on any of them.
 */
@FunctionalInterface
public interface TokenFetcher {
    AccessToken fetch() throws IOException;
}
