package com.compact.crm.service.mail;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Paces sends PER MAILBOX so the CRM stays inside the providers' published
// limits rather than relying on being throttled (Exchange Online: 30 messages
// per minute per mailbox; Gmail: per-user API rate limits). Two rules:
//   - at most perMinute sends in any rolling 60 seconds;
//   - at least minIntervalMs between two sends.
// A send that would have to wait longer than maxWaitMs is NOT made to wait:
// acquire() returns the seconds until a slot frees, and the caller answers
// 429 + Retry-After instead of holding the request open past the browser's
// timeout. In memory, per instance (the CRM runs a single Render instance; the
// provider's own 429 is the backstop if that ever changes).
@Component
public class MailboxRateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    // null = permitted (after waiting at most maxWaitMs); otherwise seconds to wait.
    public Integer acquire(Long accountId, int perMinute, long minIntervalMs, long maxWaitMs) {

        if (perMinute <= 0 && minIntervalMs <= 0) {
            return null;
        }

        Window window = windows.computeIfAbsent(accountId, id -> new Window());

        long waitMs;

        synchronized (window) {

            long now = System.currentTimeMillis();

            while (!window.sends.isEmpty() && window.sends.peekFirst() <= now - WINDOW_MS) {
                window.sends.pollFirst();
            }

            long earliest = now;

            if (minIntervalMs > 0 && window.last > 0) {
                earliest = Math.max(earliest, window.last + minIntervalMs);
            }

            if (perMinute > 0 && window.sends.size() >= perMinute) {
                // The slot frees when the oldest send in the window ages out.
                long[] sorted = window.sends.stream().mapToLong(Long::longValue).toArray();
                earliest = Math.max(earliest, sorted[window.sends.size() - perMinute] + WINDOW_MS);
            }

            waitMs = earliest - now;

            if (waitMs > maxWaitMs) {
                return (int) Math.max(1, (waitMs + 999) / 1000);
            }

            // Reserve the slot before sleeping so concurrent requests queue behind it.
            window.sends.addLast(earliest);
            window.last = earliest;
        }

        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }

        return null;
    }

    public void forget(Long accountId) {
        windows.remove(accountId);
    }

    private static final class Window {
        final Deque<Long> sends = new ArrayDeque<>();
        long last;
    }
}
