package com.timealert.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory rate limiter scoped to the current JVM instance.
 * Not shared across multiple instances — designed for single-instance deployment.
 *
 * Security note: X-Forwarded-For is trusted for IP extraction. In production,
 * ensure this service is deployed behind a trusted reverse proxy (Heroku/Render/Railway)
 * that sets this header — do not expose the app directly to the internet without a proxy.
 */
@Component
public class RateLimitFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Map<String, IpRequestTracker> trackers = new ConcurrentHashMap<>();

    // Auth/upload endpoints: tighter limit (30 per minute)
    private static final int MAX_AUTH_REQUESTS_PER_MINUTE = 30;

    // All other API endpoints: broader limit (120 per minute)
    private static final int MAX_API_REQUESTS_PER_MINUTE = 120;

    // Cleanup threshold: evict expired entries when map grows large
    private static final int CLEANUP_THRESHOLD = 5000;

    private static final long WINDOW_MS = 60_000L;

    private static class IpRequestTracker {
        final long resetTime;
        final AtomicInteger count = new AtomicInteger(0);

        IpRequestTracker() {
            this.resetTime = System.currentTimeMillis() + WINDOW_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > resetTime;
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        // Only rate limit API paths; skip actuator/static resources
        if (path.startsWith("/api/")) {
            String ip = getClientIP(httpRequest);

            // Evict expired entries periodically to prevent unbounded memory growth
            if (trackers.size() > CLEANUP_THRESHOLD) {
                evictExpiredEntries();
            }

            boolean isAuthOrUpload = path.startsWith("/api/auth/") || path.startsWith("/api/upload");
            int maxRequests = isAuthOrUpload ? MAX_AUTH_REQUESTS_PER_MINUTE : MAX_API_REQUESTS_PER_MINUTE;

            // Use a composite key so auth and general limits are tracked independently per IP
            String trackerKey = ip + ":" + (isAuthOrUpload ? "auth" : "api");

            // Perform the increment atomically inside compute() to avoid a race window
            // between tracker retrieval and count increment.
            int[] currentCount = {0};
            trackers.compute(trackerKey, (key, existing) -> {
                IpRequestTracker tracker = (existing == null || existing.isExpired())
                        ? new IpRequestTracker()
                        : existing;
                currentCount[0] = tracker.count.incrementAndGet();
                return tracker;
            });

            if (currentCount[0] > maxRequests) {
                logger.warn("Rate limit exceeded for IP {} on path {} (count: {})", ip, path, currentCount[0]);
                httpResponse.setStatus(429);
                httpResponse.setContentType("application/json");
                httpResponse.setHeader("Retry-After", "60");
                httpResponse.getWriter().write("{\"message\": \"Too many requests. Please try again in a minute.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void evictExpiredEntries() {
        trackers.entrySet().removeIf(entry -> entry.getValue().isExpired());
        logger.debug("Rate limiter: evicted expired IP entries. Current map size: {}", trackers.size());
    }

    private String getClientIP(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        // Take only the first (client) IP from the chain; trim whitespace
        return xfHeader.split(",")[0].trim();
    }
}
