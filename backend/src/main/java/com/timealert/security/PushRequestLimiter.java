package com.timealert.security;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Per-authenticated-user protection; does not trust X-Forwarded-For. Worker also limits test sends durably. */
@Component
public class PushRequestLimiter {
    private record Window(long start, int count) {}
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    public PushRequestLimiter() { this(Clock.systemUTC()); }
    public PushRequestLimiter(Clock clock) { this.clock = clock; }

    public synchronized boolean allow(Long userId, boolean test) {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> now - e.getValue().start() >= 60_000);
        String key = userId + (test ? ":test" : ":device");
        Window old = windows.get(key);
        // Bound memory; existing keys can still use their remaining quota.
        if (old == null && windows.size() >= 10000) return false;
        if (old != null && old.count() >= (test ? 3 : 30)) return false;
        windows.put(key, new Window(old == null ? now : old.start(), old == null ? 1 : old.count() + 1));
        return true;
    }
}
