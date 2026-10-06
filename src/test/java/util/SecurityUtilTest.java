package util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityUtilTest {

    @Test
    void 生成的安全令牌应随机且长度充足() {
        String first = SecurityUtil.randomToken();
        String second = SecurityUtil.randomToken();

        assertNotEquals(first, second);
        assertTrue(first.length() >= 43);
    }

    @Test
    void 常量时间比较应正确处理空值与差异() {
        assertTrue(SecurityUtil.constantTimeEquals("abc", "abc"));
        assertFalse(SecurityUtil.constantTimeEquals("abc", "abd"));
        assertFalse(SecurityUtil.constantTimeEquals(null, "abc"));
    }

    @Test
    void 外部链接只允许无用户信息的Http协议() {
        assertEquals("https://example.com/a?b=1", SecurityUtil.sanitizeHttpUrl(" https://example.com/a?b=1 "));
        assertNull(SecurityUtil.sanitizeHttpUrl("javascript:alert(1)"));
        assertNull(SecurityUtil.sanitizeHttpUrl("https://user:pass@example.com"));
        assertNull(SecurityUtil.sanitizeHttpUrl("/relative/path"));
    }
}
