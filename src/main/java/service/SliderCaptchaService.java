package service;

import javax.servlet.http.HttpSession;
import java.time.Instant;
import util.SecurityUtil;

/**
 * 服务端滑块挑战。
 * 当前是轻量级挑战：令牌只能使用一次，前端必须将滑块拖到末端。
 * 该机制仅用于降低误操作和普通脚本滥用，不能替代专业人机验证服务。
 */
public final class SliderCaptchaService {

    private static final String SESSION_KEY = "sliderCaptchaChallenge";
    private static final long EXPIRE_SECONDS = 300;

    private SliderCaptchaService() {}

    public static Challenge create(HttpSession session, String purpose) {
        Challenge challenge = new Challenge(
                SecurityUtil.randomToken(),
                Instant.now().plusSeconds(EXPIRE_SECONDS).toEpochMilli(),
                purpose
        );
        session.setAttribute(SESSION_KEY, challenge);
        return challenge;
    }

    public static boolean verify(HttpSession session, String purpose, String token, Integer value) {
        Object valueObj = session.getAttribute(SESSION_KEY);
        if (!(valueObj instanceof Challenge challenge)) {
            return false;
        }
        session.removeAttribute(SESSION_KEY);
        return challenge.purpose().equals(purpose)
                && SecurityUtil.constantTimeEquals(challenge.token(), token)
                && challenge.expireAt() >= System.currentTimeMillis()
                && value != null
                && value == 100;
    }

    public record Challenge(String token, long expireAt, String purpose) {}
}
