package service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单节点固定窗口限流器，用于拦截登录和绑定接口的高频失败请求。
 * 多节点部署时应替换为 Redis 等共享存储。
 */
public final class RequestRateLimiter {

    private static final int MAX_KEYS = 20_000;
    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();
    private static long capacityBlockedUntil;

    private RequestRateLimiter() {
    }

    public static boolean isBlocked(String key, int limit, Duration duration) {
        Window window = currentWindow(key);
        if (window != null) {
            return window.count >= limit;
        }
        synchronized (WINDOWS) {
            return !hasCapacity(System.currentTimeMillis());
        }
    }

    public static int failures(String key, Duration duration) {
        Window window = currentWindow(key);
        return window == null ? 0 : window.count;
    }

    public static boolean recordFailure(String key, Duration duration) {
        long now = System.currentTimeMillis();
        long windowMillis = duration.toMillis();
        synchronized (WINDOWS) {
            Window existing = WINDOWS.get(key);
            if (existing == null || existing.expiresAt <= now) {
                if (existing != null) {
                    WINDOWS.remove(key, existing);
                }
                if (!hasCapacity(now)) {
                    return false;
                }
                WINDOWS.put(key, new Window(now + windowMillis, 1));
            } else {
                WINDOWS.put(key, new Window(existing.expiresAt, existing.count + 1));
            }
            return true;
        }
    }

    public static void reset(String key) {
        synchronized (WINDOWS) {
            WINDOWS.remove(key);
            capacityBlockedUntil = 0;
        }
    }

    private static Window currentWindow(String key) {
        long now = System.currentTimeMillis();
        Window existing = WINDOWS.get(key);
        if (existing != null && existing.expiresAt <= now) {
            WINDOWS.remove(key, existing);
            capacityBlockedUntil = 0;
            existing = null;
        }
        return existing;
    }

    private static boolean hasCapacity(long now) {
        if (WINDOWS.size() < MAX_KEYS) {
            return true;
        }
        if (now < capacityBlockedUntil) {
            return false;
        }
        WINDOWS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        if (WINDOWS.size() < MAX_KEYS) {
            capacityBlockedUntil = 0;
            return true;
        }
        long earliestExpiry = Long.MAX_VALUE;
        for (Window window : WINDOWS.values()) {
            earliestExpiry = Math.min(earliestExpiry, window.expiresAt);
        }
        capacityBlockedUntil = earliestExpiry;
        return false;
    }

    static void clearForTesting() {
        synchronized (WINDOWS) {
            WINDOWS.clear();
            capacityBlockedUntil = 0;
        }
    }

    private record Window(long expiresAt, int count) {
    }
}
