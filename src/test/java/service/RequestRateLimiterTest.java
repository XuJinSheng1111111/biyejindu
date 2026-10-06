package service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestRateLimiterTest {

    @AfterEach
    void 清理限流状态() {
        RequestRateLimiter.clearForTesting();
    }

    @Test
    void 失败次数达到阈值后应阻止请求并可重置() {
        String key = "test:" + UUID.randomUUID();
        Duration window = Duration.ofMinutes(1);

        assertTrue(RequestRateLimiter.recordFailure(key, window));
        assertTrue(RequestRateLimiter.recordFailure(key, window));
        assertFalse(RequestRateLimiter.isBlocked(key, 3, window));

        assertTrue(RequestRateLimiter.recordFailure(key, window));
        assertTrue(RequestRateLimiter.isBlocked(key, 3, window));

        RequestRateLimiter.reset(key);
        assertEquals(0, RequestRateLimiter.failures(key, window));
    }

    @Test
    void 容量饱和时应拒绝新键且保留有效封禁记录() {
        Duration window = Duration.ofHours(1);
        for (int i = 0; i < 20_000; i++) {
            assertTrue(RequestRateLimiter.recordFailure("capacity:" + i, window));
        }

        assertTrue(RequestRateLimiter.isBlocked("overflow", 1, window));
        assertFalse(RequestRateLimiter.recordFailure("overflow", window));
        assertTrue(RequestRateLimiter.isBlocked("capacity:0", 1, window));
    }
}
