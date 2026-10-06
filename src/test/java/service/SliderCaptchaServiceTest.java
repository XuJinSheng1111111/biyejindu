package service;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SliderCaptchaServiceTest {

    @Test
    void 挑战应到达末端且只能使用一次() {
        HttpSession session = sessionStub();
        SliderCaptchaService.Challenge challenge = SliderCaptchaService.create(session, "login");

        assertFalse(SliderCaptchaService.verify(session, "login", challenge.token(), 99));

        challenge = SliderCaptchaService.create(session, "login");
        assertTrue(SliderCaptchaService.verify(session, "login", challenge.token(), 100));
        assertFalse(SliderCaptchaService.verify(session, "login", challenge.token(), 100));
    }

    private HttpSession sessionStub() {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{HttpSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "setAttribute" -> {
                        attributes.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "removeAttribute" -> {
                        attributes.remove((String) args[0]);
                        yield null;
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
