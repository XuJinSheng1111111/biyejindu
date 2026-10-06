package servlet;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import mapper.UserOauthMapper;
import org.apache.ibatis.session.SqlSession;
import pojo.Account;
import pojo.Result;
import pojo.UserOauth;
import service.AccountService;
import service.GiteeOAuthService;
import service.RequestRateLimiter;
import util.BcryptUtil;
import util.SecurityUtil;
import util.SqlSessionFactoryUtils;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gitee OAuth2 授权码登录。
 *
 * authorize -> Gitee 授权 -> callback -> 已绑定直接登录 / 未绑定跳邮箱账号绑定页。
 */
@Slf4j
@WebServlet(urlPatterns = {"/api/account/oauth/gitee/*", "/gitee_callback"})
public class GiteeOAuthServlet extends BaseServlet {


    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if ("/gitee_callback".equals(req.getServletPath())) {
            callback(req, resp);
            return;
        }
        super.doGet(req, resp);
    }

    private static final String PLATFORM = "gitee";
    private static final GiteeOAuthService oauthService = new GiteeOAuthService();
    private static final AccountService accountService = new AccountService();
    private static final Duration BIND_WINDOW = Duration.ofMinutes(15);
    private static final int MAX_BIND_FAILURES = 5;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final String DUMMY_PASSWORD_HASH = BcryptUtil.encrypt("invalid-password-placeholder");

    protected void authorize(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!oauthService.isConfigured()) {
            writeError(req, resp, "Gitee OAuth 尚未配置，请在 auth.properties 中设置 gitee.client.id 和 gitee.client.secret");
            return;
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        req.getSession().setAttribute("giteeOauthState", state);
        resp.sendRedirect(oauthService.buildAuthorizeUrl(state));
    }

    protected void callback(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String error = req.getParameter("error");
        if (error != null) {
            writeError(req, resp, req.getParameter("error_description") == null
                    ? "Gitee 授权失败"
                    : req.getParameter("error_description"));
            return;
        }

        HttpSession session = req.getSession(false);
        if (session == null) {
            writeError(req, resp, "登录会话已失效，请重新发起 Gitee 登录");
            return;
        }

        String expectedState = (String) session.getAttribute("giteeOauthState");
        String state = req.getParameter("state");
        session.removeAttribute("giteeOauthState");
        if (expectedState == null || !expectedState.equals(state)) {
            writeError(req, resp, "Gitee OAuth state 校验失败，请重新登录");
            return;
        }

        String code = req.getParameter("code");
        if (code == null || code.isBlank()) {
            writeError(req, resp, "Gitee 未返回授权码");
            return;
        }

        try {
            String accessToken = oauthService.exchangeAccessToken(code);
            JSONObject userInfo = oauthService.fetchUser(accessToken);

            String openid = String.valueOf(userInfo.get("id"));
            String nickname = firstNotBlank(userInfo.getString("name"), userInfo.getString("login"), "Gitee用户");
            String avatar = userInfo.getString("avatar_url");
            String email = userInfo.getString("email");

            if (openid == null || "null".equals(openid) || openid.isBlank()) {
                writeError(req, resp, "Gitee 用户信息缺少 id");
                return;
            }

            UserOauth binding = findBinding(openid);
            if (binding != null) {
                Account account = accountService.getUserById(binding.getUserId());
                if (account == null || !isActive(account)) {
                    writeError(req, resp, "绑定的本地账号不存在，请联系管理员");
                    return;
                }
                req.changeSessionId();
                session.setAttribute("loginAccount", account);
                session.setMaxInactiveInterval(30 * 60);
                resp.sendRedirect(req.getContextPath() + "/home.html");
                return;
            }

            session.setAttribute("pendingGiteeOpenid", openid);
            session.setAttribute("pendingGiteeNickname", nickname);
            session.setAttribute("pendingGiteeAvatar", avatar);
            session.setAttribute("pendingGiteeEmail", email);
            resp.sendRedirect(req.getContextPath() + "/oauthBind.html");
        } catch (Exception err) {
            log.error("Gitee OAuth 回调处理失败", err);
            writeError(req, resp, "Gitee 登录失败，请稍后重试");
        }
    }

    protected void bind(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        try {
            HttpSession session = req.getSession(false);
            if (session == null) {
                resp.getWriter().write(JSON.toJSONString(Result.fail(401, "登录会话已失效")));
                return;
            }

            String openid = (String) session.getAttribute("pendingGiteeOpenid");
            if (openid == null) {
                resp.getWriter().write(JSON.toJSONString(Result.fail(400, "没有待绑定的 Gitee 授权信息")));
                return;
            }

            JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
            String email = normalizeEmail(json.getString("email"));
            String password = json.getString("password");
            if (email == null || password == null || password.isBlank()) {
                resp.getWriter().write(JSON.toJSONString(Result.fail(400, "请输入邮箱和密码")));
                return;
            }

            String emailRateKey = rateKey("oauth-bind-email", email);
            String ipRateKey = rateKey("oauth-bind-ip", req.getRemoteAddr());
            if (RequestRateLimiter.isBlocked(emailRateKey, MAX_BIND_FAILURES, BIND_WINDOW)
                    || RequestRateLimiter.isBlocked(ipRateKey, MAX_BIND_FAILURES * 3, BIND_WINDOW)) {
                resp.setStatus(429);
                resp.getWriter().write(JSON.toJSONString(Result.fail(429, "绑定尝试过于频繁，请稍后再试")));
                return;
            }

            Account account = accountService.queryByEmail(email);
            String storedPassword = account == null || account.getPassword() == null
                    ? DUMMY_PASSWORD_HASH : account.getPassword();
            boolean passwordMatches = BcryptUtil.checkPwd(password, storedPassword);
            if (account == null || !passwordMatches || !isActive(account)) {
                if (account != null) {
                    accountService.incrementLoginFailure(account.getId());
                }
                boolean emailRecorded = RequestRateLimiter.recordFailure(emailRateKey, BIND_WINDOW);
                boolean ipRecorded = RequestRateLimiter.recordFailure(ipRateKey, BIND_WINDOW);
                if (!emailRecorded || !ipRecorded) {
                    resp.setStatus(429);
                    resp.getWriter().write(JSON.toJSONString(Result.fail(429, "绑定保护容量已满，请稍后再试")));
                    return;
                }
                resp.getWriter().write(JSON.toJSONString(Result.fail(401, "邮箱或密码错误")));
                return;
            }

            int failCount = account.getLoginFailCount() == null ? 0 : account.getLoginFailCount();
            if (failCount >= 3 || Short.valueOf((short) 1).equals(account.getSliderRequired())) {
                resp.setStatus(429);
                resp.getWriter().write(JSON.toJSONString(Result.fail(429, "账号需要安全验证，请先使用邮箱密码登录")));
                return;
            }

            UserOauth existingUserBinding = findUserBinding(account.getId());
            if (existingUserBinding != null) {
                resp.getWriter().write(JSON.toJSONString(Result.fail(409, "该账号已绑定其他 Gitee 用户")));
                return;
            }

            UserOauth userOauth = new UserOauth();
            userOauth.setUserId(account.getId());
            userOauth.setPlatform(PLATFORM);
            userOauth.setOpenid(openid);
            userOauth.setNickname((String) session.getAttribute("pendingGiteeNickname"));
            userOauth.setAvatar((String) session.getAttribute("pendingGiteeAvatar"));
            userOauth.setEmail((String) session.getAttribute("pendingGiteeEmail"));

            try (SqlSession sqlSession = SqlSessionFactoryUtils.getSqlSessionFactory().openSession(true)) {
                sqlSession.getMapper(UserOauthMapper.class).insert(userOauth);
            }

            clearPending(session);
            accountService.resetLoginSecurity(account.getId());
            RequestRateLimiter.reset(emailRateKey);
            req.changeSessionId();
            session.setAttribute("loginAccount", account);
            session.setMaxInactiveInterval(30 * 60);
            resp.getWriter().write(JSON.toJSONString(Result.success(null)));
        } catch (Exception err) {
            log.error("Gitee 账号绑定失败", err);
            resp.getWriter().write(JSON.toJSONString(Result.fail(500, "绑定失败，请稍后重试")));
        }
    }

    private UserOauth findBinding(String openid) throws Exception {
        try (SqlSession sqlSession = SqlSessionFactoryUtils.getSqlSessionFactory().openSession()) {
            return sqlSession.getMapper(UserOauthMapper.class)
                    .selectByPlatformAndOpenid(PLATFORM, openid);
        }
    }

    private UserOauth findUserBinding(Integer userId) throws Exception {
        try (SqlSession sqlSession = SqlSessionFactoryUtils.getSqlSessionFactory().openSession()) {
            return sqlSession.getMapper(UserOauthMapper.class)
                    .selectByUserAndPlatform(userId, PLATFORM);
        }
    }

    private void clearPending(HttpSession session) {
        session.removeAttribute("pendingGiteeOpenid");
        session.removeAttribute("pendingGiteeNickname");
        session.removeAttribute("pendingGiteeAvatar");
        session.removeAttribute("pendingGiteeEmail");
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isActive(Account account) {
        return account != null && Short.valueOf((short) 1).equals(account.getStatus());
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String value = email.trim().toLowerCase();
        return EMAIL_PATTERN.matcher(value).matches() ? value : null;
    }

    private String rateKey(String scope, String value) {
        String safeValue = value == null ? "unknown" : value;
        return scope + ":" + SecurityUtil.sha256(safeValue);
    }

    private void writeError(HttpServletRequest req, HttpServletResponse resp, String message) throws IOException {
        resp.setContentType("text/html;charset=utf-8");
        resp.getWriter().write("<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>Gitee登录失败</title></head><body style=\"font-family:sans-serif;text-align:center;margin-top:80px;\">"
                + "<h3 style=\"color:#f56c6c;\">" + escapeHtml(message) + "</h3>"
                + "<p>3秒后返回登录页</p>"
                + "<script>setTimeout(function(){location.href='"
                + resp.encodeRedirectURL(req.getContextPath() + "/login.html") + "';},3000);</script>"
                + "</body></html>");
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
