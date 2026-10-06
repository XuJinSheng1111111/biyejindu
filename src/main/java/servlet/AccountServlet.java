package servlet;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import pojo.Account;
import pojo.Result;
import service.AccountService;
import service.EmailCodeService;
import service.RequestRateLimiter;
import service.SliderCaptchaService;
import util.BcryptUtil;
import util.SecurityUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@WebServlet("/api/account/*")
public class AccountServlet extends BaseServlet {

    private static final AccountService accountService = new AccountService();
    private static final EmailCodeService emailCodeService = new EmailCodeService();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration CHALLENGE_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_LOGIN_FAILURES = 10;
    private static final String DUMMY_PASSWORD_HASH = BcryptUtil.encrypt("invalid-password-placeholder");

    /**
     * 邮箱 + 密码登录。
     * 前两次密码错误直接提示；连续三次后，后续请求必须通过滑块验证。
     */
    public void login(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        PrintWriter out = resp.getWriter();
        try {
            JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
            String email = normalizeEmail(json.getString("email"));
            String password = json.getString("password");
            String sliderToken = json.getString("sliderToken");
            Integer sliderValue = json.getInteger("sliderValue");
            Integer sessionDays = json.getInteger("sessionDays");

            if (email == null) {
                out.write(JSON.toJSONString(Result.fail(400, "请输入正确邮箱")));
                return;
            }
            if (sessionDays != null && sessionDays != 7 && sessionDays != 30) {
                out.write(JSON.toJSONString(Result.fail(400, "登录有效期参数错误")));
                return;
            }
            if (password == null || password.isBlank()) {
                out.write(JSON.toJSONString(Result.fail(400, "请输入密码")));
                return;
            }

            String emailRateKey = rateKey("login-email", email);
            String ipRateKey = rateKey("login-ip", req.getRemoteAddr());
            if (RequestRateLimiter.isBlocked(emailRateKey, MAX_LOGIN_FAILURES, LOGIN_WINDOW)
                    || RequestRateLimiter.isBlocked(ipRateKey, MAX_LOGIN_FAILURES * 3, LOGIN_WINDOW)) {
                resp.setStatus(429);
                out.write(JSON.toJSONString(Result.fail(429, "登录尝试过于频繁，请稍后再试")));
                return;
            }

            HttpSession session = req.getSession();
            boolean sliderPassed = false;
            if (RequestRateLimiter.failures(emailRateKey, LOGIN_WINDOW) >= 3) {
                sliderPassed = SliderCaptchaService.verify(session, "login", sliderToken, sliderValue);
                if (!sliderPassed) {
                    Result<Map<String, Object>> result = Result.fail(428, "请完成滑块验证");
                    result.setData(Map.of("sliderRequired", true));
                    out.write(JSON.toJSONString(result));
                    return;
                }
            }

            Account account = accountService.queryByEmail(email);
            String storedPassword = account == null || account.getPassword() == null
                    ? DUMMY_PASSWORD_HASH : account.getPassword();
            boolean passwordMatches = BcryptUtil.checkPwd(password, storedPassword);
            boolean accountActive = isActive(account);

            if (account == null || !passwordMatches || !accountActive) {
                if (account != null) {
                    accountService.incrementLoginFailure(account.getId());
                }
                boolean emailRecorded = RequestRateLimiter.recordFailure(emailRateKey, LOGIN_WINDOW);
                boolean ipRecorded = RequestRateLimiter.recordFailure(ipRateKey, LOGIN_WINDOW);
                if (!emailRecorded || !ipRecorded) {
                    resp.setStatus(429);
                    out.write(JSON.toJSONString(Result.fail(429, "登录保护容量已满，请稍后再试")));
                    return;
                }
                out.write(JSON.toJSONString(Result.fail(401, "邮箱或密码错误")));
                return;
            }

            int failCount = account.getLoginFailCount() == null ? 0 : account.getLoginFailCount();
            boolean sliderRequired = failCount >= 3
                    || Short.valueOf((short) 1).equals(account.getSliderRequired());

            if (sliderRequired && !sliderPassed) {
                sliderPassed = SliderCaptchaService.verify(session, "login", sliderToken, sliderValue);
                if (!sliderPassed) {
                    Result<Map<String, Object>> result = Result.fail(428, "请完成滑块验证");
                    result.setData(Map.of("sliderRequired", true));
                    out.write(JSON.toJSONString(result));
                    return;
                }
            }

            accountService.resetLoginSecurity(account.getId());
            RequestRateLimiter.reset(emailRateKey);
            account.setLoginFailCount(0);
            account.setSliderRequired((short) 0);
            req.changeSessionId();
            session.setAttribute("loginAccount", account);
            session.setMaxInactiveInterval(resolveSessionTimeout(sessionDays));
            configureSessionCookie(req, resp, session, sessionDays);
            out.write(JSON.toJSONString(Result.success(null)));
        } catch (Exception err) {
            log.error("登录处理异常", err);
            out.write(JSON.toJSONString(Result.fail(500, "服务器繁忙，请稍后重试")));
        }
    }

    /**
     * 获取滑块挑战。
     */
    public void sliderChallenge(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession();
        String purpose = req.getParameter("purpose");
        if (!"login".equals(purpose) && !"register".equals(purpose)) {
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "滑块用途参数错误")));
            return;
        }
        String rateKey = rateKey("slider-ip", req.getRemoteAddr());
        if (RequestRateLimiter.isBlocked(rateKey, 30, CHALLENGE_WINDOW)) {
            resp.setStatus(429);
            resp.getWriter().write(JSON.toJSONString(Result.fail(429, "验证请求过于频繁，请稍后再试")));
            return;
        }
        if (!RequestRateLimiter.recordFailure(rateKey, CHALLENGE_WINDOW)) {
            resp.setStatus(429);
            resp.getWriter().write(JSON.toJSONString(Result.fail(429, "验证请求过于频繁，请稍后再试")));
            return;
        }
        SliderCaptchaService.Challenge challenge = SliderCaptchaService.create(session, purpose);
        resp.getWriter().write(JSON.toJSONString(Result.success(Map.of(
                "token", challenge.token(),
                "expireAt", challenge.expireAt()
        ))));
    }

    /**
     * 滑块通过后发送邮箱验证码。
     */
    public void sendEmailCode(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        PrintWriter out = resp.getWriter();
        try {
            JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
            String email = normalizeEmail(json.getString("email"));
            String purpose = json.getString("purpose");
            String sliderToken = json.getString("sliderToken");
            Integer sliderValue = json.getInteger("sliderValue");

            if (email == null) {
                out.write(JSON.toJSONString(Result.fail(400, "请输入正确邮箱")));
                return;
            }
            String sendRateKey = rateKey("email-code-ip", req.getRemoteAddr());
            if (RequestRateLimiter.isBlocked(sendRateKey, 10, CHALLENGE_WINDOW)) {
                resp.setStatus(429);
                out.write(JSON.toJSONString(Result.fail(429, "验证码请求过于频繁，请稍后再试")));
                return;
            }
            if (accountService.queryByEmail(email) != null) {
                out.write(JSON.toJSONString(Result.fail(409, "该邮箱已注册，请直接登录")));
                return;
            }
            if (purpose == null || purpose.isBlank()) {
                purpose = "register";
            }

            HttpSession session = req.getSession();
            boolean sliderPassed = SliderCaptchaService.verify(
                    session, purpose, sliderToken, sliderValue
            );
            if (!sliderPassed) {
                out.write(JSON.toJSONString(Result.fail(400, "滑块验证失败")));
                return;
            }

            if (!RequestRateLimiter.recordFailure(sendRateKey, CHALLENGE_WINDOW)) {
                resp.setStatus(429);
                out.write(JSON.toJSONString(Result.fail(429, "验证码请求过于频繁，请稍后再试")));
                return;
            }
            emailCodeService.sendCode(email);
            session.removeAttribute("emailCodeAttempts:" + email);
            out.write(JSON.toJSONString(Result.success(null)));
        } catch (EmailCodeService.RateLimitException err) {
            out.write(JSON.toJSONString(Result.fail(429, err.getMessage())));
        } catch (EmailCodeService.MailDeliveryException err) {
            log.error("发送邮箱验证码失败: {}", err.getMessage(), err);
            out.write(JSON.toJSONString(Result.fail(502, err.getMessage())));
        } catch (Exception err) {
            log.error("发送邮箱验证码失败", err);
            out.write(JSON.toJSONString(Result.fail(500, "邮箱验证码发送失败")));
        }
    }

    /**
     * 邮箱注册。
     */
    public void register(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        PrintWriter out = resp.getWriter();
        try {
            JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
            String email = normalizeEmail(json.getString("email"));
            String password = json.getString("password");
            String code = json.getString("code");
            String confirmPassword = json.getString("confirmPassword");

            if (email == null) {
                out.write(JSON.toJSONString(Result.fail(400, "请输入正确邮箱")));
                return;
            }
            if (password == null || password.length() < 6 || password.length() > 16) {
                out.write(JSON.toJSONString(Result.fail(400, "密码请设置6-16位")));
                return;
            }
            if (confirmPassword == null || !password.equals(confirmPassword)) {
                out.write(JSON.toJSONString(Result.fail(400, "\u4e24\u6b21\u8f93\u5165\u7684\u5bc6\u7801\u4e0d\u4e00\u81f4")));
                return;
            }
            if (code == null || code.isBlank()) {
                out.write(JSON.toJSONString(Result.fail(400, "请输入邮箱验证码")));
                return;
            }
            if (accountService.queryByEmail(email) != null) {
                out.write(JSON.toJSONString(Result.fail(400, "该邮箱已注册")));
                return;
            }
            HttpSession session = req.getSession();
            String attemptKey = "emailCodeAttempts:" + email;
            Integer attempts = (Integer) session.getAttribute(attemptKey);
            if (attempts != null && attempts >= 5) {
                resp.setStatus(429);
                out.write(JSON.toJSONString(Result.fail(429, "验证码错误次数过多，请重新获取验证码")));
                return;
            }
            if (!emailCodeService.verifyCode(email, code)) {
                int nextAttempts = attempts == null ? 1 : attempts + 1;
                session.setAttribute(attemptKey, nextAttempts);
                if (nextAttempts >= 5) {
                    emailCodeService.invalidateCode(email);
                    resp.setStatus(429);
                    out.write(JSON.toJSONString(Result.fail(429, "验证码错误次数过多，请重新获取验证码")));
                    return;
                }
                out.write(JSON.toJSONString(Result.fail(400, "邮箱验证码错误或已过期")));
                return;
            }
            session.removeAttribute(attemptKey);

            Account account = new Account();
            account.setEmail(email);
            account.setUsername(null);
            account.setPassword(BcryptUtil.encrypt(password));
            if (!accountService.add(account)) {
                out.write(JSON.toJSONString(Result.fail(500, "注册失败，请稍后重试")));
                return;
            }
            out.write(JSON.toJSONString(Result.success(null)));
        } catch (Exception err) {
            log.error("邮箱注册失败", err);
            out.write(JSON.toJSONString(Result.fail(500, "注册失败，请稍后重试")));
        }
    }

    public void logout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        resp.getWriter().write(JSON.toJSONString(Result.success(null)));
    }

    /**
     * 修改密码。
     */
    public void password(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "PUT", "POST")) return;
        PrintWriter out = resp.getWriter();
        try {
            HttpSession session = req.getSession(false);
            if (session == null) {
                resp.setStatus(401);
                out.write(JSON.toJSONString(Result.fail(401, "未登录")));
                return;
            }
            Account currentAccount = (Account) session.getAttribute("loginAccount");
            if (currentAccount == null) {
                resp.setStatus(401);
                out.write(JSON.toJSONString(Result.fail(401, "未登录")));
                return;
            }

            JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
            String inputOldPwd = json.getString("oldPwd");
            String inputNewPwd = json.getString("newPwd");

            if (inputNewPwd == null || inputNewPwd.length() < 6 || inputNewPwd.length() > 16) {
                out.write(JSON.toJSONString(Result.fail(400, "新密码请设置6-16位")));
                return;
            }
            if (!BcryptUtil.checkPwd(inputOldPwd, currentAccount.getPassword())) {
                out.write(JSON.toJSONString(Result.fail(401, "原密码错误")));
                return;
            }
            currentAccount.setPassword(BcryptUtil.encrypt(inputNewPwd));
            if (!accountService.modifyPwdById(currentAccount)) {
                out.write(JSON.toJSONString(Result.fail(500, "密码修改失败")));
                return;
            }
            session.invalidate();
            out.write(JSON.toJSONString(Result.success(null)));
        } catch (Exception err) {
            log.error("修改密码异常", err);
            out.write(JSON.toJSONString(Result.fail(500, "服务器繁忙，请稍后重试")));
        }
    }


    /**
     * 注销当前账号。删除 account 后由数据库级联删除成绩、总结、上传历史和第三方绑定。
     */
    public void deleteAccount(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        PrintWriter out = resp.getWriter();
        try {
            HttpSession session = req.getSession(false);
            if (session == null) {
                resp.setStatus(401);
                out.write(JSON.toJSONString(Result.fail(401, "未登录")));
                return;
            }
            Account currentAccount = (Account) session.getAttribute("loginAccount");
            if (currentAccount == null) {
                resp.setStatus(401);
                out.write(JSON.toJSONString(Result.fail(401, "未登录")));
                return;
            }

            JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
            String password = json.getString("password");
            if (password == null || password.isBlank()) {
                out.write(JSON.toJSONString(Result.fail(400, "请输入当前密码")));
                return;
            }
            if (!BcryptUtil.checkPwd(password, currentAccount.getPassword())) {
                out.write(JSON.toJSONString(Result.fail(401, "当前密码错误")));
                return;
            }
            if (!accountService.deleteById(currentAccount.getId())) {
                out.write(JSON.toJSONString(Result.fail(500, "注销账号失败，请稍后重试")));
                return;
            }
            session.invalidate();
            out.write(JSON.toJSONString(Result.success(null)));
        } catch (Exception err) {
            log.error("注销账号异常", err);
            out.write(JSON.toJSONString(Result.fail(500, "服务器繁忙，请稍后重试")));
        }
    }


    private void configureSessionCookie(HttpServletRequest req, HttpServletResponse resp, HttpSession session, Integer sessionDays) {
        int maxAge = resolveSessionTimeout(sessionDays);
        String contextPath = req.getContextPath();
        if (contextPath == null || contextPath.isBlank()) {
            contextPath = "/";
        }
        StringBuilder cookie = new StringBuilder("JSESSIONID=").append(session.getId()).append("; Path=").append(contextPath).append("; HttpOnly; SameSite=Lax");
        if (maxAge > 0) {
            cookie.append("; Max-Age=").append(maxAge);
        }
        if (req.isSecure()) {
            cookie.append("; Secure");
        }
        resp.setHeader("Set-Cookie", cookie.toString());
    }

    private int resolveSessionTimeout(Integer sessionDays) {
        if (sessionDays == null) {
            return 30 * 60;
        }
        return sessionDays * 24 * 60 * 60;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String value = email.trim().toLowerCase();
        return EMAIL_PATTERN.matcher(value).matches() ? value : null;
    }

    private boolean isActive(Account account) {
        return account != null && Short.valueOf((short) 1).equals(account.getStatus());
    }

    private String rateKey(String scope, String value) {
        String safeValue = value == null ? "unknown" : value;
        return scope + ":" + SecurityUtil.sha256(safeValue);
    }
}
