package com.compact.crm.security;

import com.compact.crm.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Basic, single-instance, in-memory fixed-window rate limiter for the
// unauthenticated public lead endpoint (POST /api/public/leads) - the only
// unauthenticated write in the app, so it's the only place abuse throttling
// is needed. Deliberately not backed by Redis/Bucket4j: this project runs a
// single backend instance today (see project notes on Render deployment),
// so an in-memory map is sufficient and adds no new dependency. If the
// backend is ever horizontally scaled, this would need to move to a shared
// store (e.g. Redis) since each instance would otherwise track its own
// counts independently.
@Component
public class PublicLeadRateLimiter {

    @Value("${crm.public-lead.rate-limit.max-requests:5}")
    private int maxRequests;

    @Value("${crm.public-lead.rate-limit.window-seconds:60}")
    private long windowSeconds;

    private final ConcurrentHashMap<String, Window> windowsByIp = new ConcurrentHashMap<>();

    public void checkAllowed(String clientIp) {

        String key = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp;

        Window window = windowsByIp.computeIfAbsent(key, k -> new Window());

        synchronized (window) {

            Instant now = Instant.now();

            if (now.isAfter(window.windowStart.plusSeconds(windowSeconds))) {
                window.windowStart = now;
                window.count.set(0);
            }

            if (window.count.incrementAndGet() > maxRequests) {
                throw new TooManyRequestsException(
                        "Too many submissions. Please wait a minute and try again.");
            }
        }
    }

    // Prevents unbounded growth of windowsByIp from one-off/expired
    // callers. Runs independently of any individual request.
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void evictExpiredWindows() {

        Instant now = Instant.now();

        windowsByIp.entrySet().removeIf(entry ->
                now.isAfter(entry.getValue().windowStart.plusSeconds(windowSeconds * 2)));
    }

    private static class Window {

        private volatile Instant windowStart = Instant.now();
        private final AtomicInteger count = new AtomicInteger(0);
    }
}
