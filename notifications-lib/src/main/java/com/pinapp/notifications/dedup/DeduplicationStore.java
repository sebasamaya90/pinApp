package com.pinapp.notifications.dedup;

import java.time.Duration;

/**
 * Remembers idempotency keys so the same logical notification is not sent twice (client retries,
 * double clicks, redelivered queue messages). The bundled implementation is in memory; for several
 * instances implement this interface on top of Redis ({@code SET key 1 NX PX ttl}) or a database
 * table with a unique key.
 */
public interface DeduplicationStore {

    /** Atomically claims the key. @return true if this caller is the first one (and must send). */
    boolean tryClaim(String key, Duration ttl);

    /** Gives the key back (called when the send failed) so a later retry is allowed. */
    void release(String key);
}
