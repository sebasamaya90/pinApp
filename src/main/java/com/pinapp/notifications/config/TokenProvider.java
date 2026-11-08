package com.pinapp.notifications.config;

import java.io.IOException;
import java.util.Objects;

/** Source of the credential used on each vendor call; lets short-lived tokens be refreshed transparently. */
@FunctionalInterface
public interface TokenProvider {

    /** The token to use right now. */
    Secret currentToken() throws IOException;

    /** Discards any cached token (called when the vendor says it is no longer valid). */
    default void invalidate() {
        // static tokens have nothing to discard
    }

    /** A token that never changes. */
    static TokenProvider fixed(Secret token) {
        Objects.requireNonNull(token, "token");
        return () -> token;
    }
}
