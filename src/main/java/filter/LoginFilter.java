package filter;

import pojo.Account;
import service.AccountService;
import util.SecurityUtil;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

/**
 * 登录、账号状态、跨站请求伪造防护与通用响应头过滤器。
 */
@WebFilter(filterName = "LoginFilter", urlPatterns = "/*")
public class LoginFilter implements Filter {

    private static final String CSRF_SESSION_KEY = "csrfToken";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    @Override
    public void init(FilterConfig filterConfig) {
        // 无需初始化。
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;

        addSecurityHeaders(req, resp);
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        String path = getPath(req);
        HttpSession session = req.getSession(false);
        Object loginValue = session == null ? null : session.getAttribute("loginAccount");
        Account account = loginValue instanceof Account ? (Account) loginValue : null;
        boolean hadLoginAccount = account != null;
        if (account != null && !isStaticResource(path)) {
            try {
                account = accountService().getUserById(account.getId());
                if (account != null) {
                    session.setAttribute("loginAccount", account);
                }
            } catch (Exception err) {
                writeJsonError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "账号状态校验暂时不可用");
                return;
            }
        }
        boolean loggedIn = isActive(account);
        boolean pendingOauthBind = session != null && session.getAttribute("pendingGiteeOpenid") instanceof String;

        if (hadLoginAccount && !loggedIn) {
            session.invalidate();
            session = null;
            pendingOauthBind = false;
            if (!isPublicEndpoint(req, path, false)) {
                if (isApiRequest(req, path)) {
                    writeJsonError(resp, HttpServletResponse.SC_FORBIDDEN, "账号已停用，请联系管理员");
                } else {
                    resp.sendRedirect(req.getContextPath() + "/login.html");
                }
                return;
            }
        }

        if (session != null && (loggedIn || pendingOauthBind)) {
            ensureCsrfToken(req, resp, session);
        }

        boolean unsafeRequest = !isSafeMethod(req.getMethod());
        if (unsafeRequest && (loggedIn || pendingOauthBind) && !isCsrfValid(req, session)) {
            writeJsonError(resp, HttpServletResponse.SC_FORBIDDEN, "请求安全校验失败，请刷新页面后重试");
            return;
        }

        if (isPublicEndpoint(req, path, pendingOauthBind)) {
            chain.doFilter(req, resp);
            return;
        }

        if (!loggedIn) {
            if (isApiRequest(req, path)) {
                writeJsonError(resp, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期");
            } else {
                resp.sendRedirect(req.getContextPath() + "/login.html");
            }
            return;
        }

        chain.doFilter(req, resp);
    }

    private void addSecurityHeaders(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "DENY");
        resp.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        resp.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; "
                        + "media-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        resp.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        resp.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        resp.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        if (req.isSecure()) {
            resp.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    private void ensureCsrfToken(HttpServletRequest req, HttpServletResponse resp, HttpSession session) {
        String token = (String) session.getAttribute(CSRF_SESSION_KEY);
        if (token == null) {
            token = SecurityUtil.randomToken();
            session.setAttribute(CSRF_SESSION_KEY, token);
        }
        String path = req.getContextPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        StringBuilder cookie = new StringBuilder(CSRF_COOKIE_NAME)
                .append('=').append(token)
                .append("; Path=").append(path)
                .append("; SameSite=Strict");
        if (req.isSecure()) {
            cookie.append("; Secure");
        }
        resp.addHeader("Set-Cookie", cookie.toString());
    }

    private boolean isCsrfValid(HttpServletRequest req, HttpSession session) {
        if (session == null) {
            return false;
        }
        String fetchSite = req.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            return false;
        }
        Object expected = session.getAttribute(CSRF_SESSION_KEY);
        return expected instanceof String
                && SecurityUtil.constantTimeEquals((String) expected, req.getHeader(CSRF_HEADER_NAME));
    }

    private boolean isActive(Account account) {
        return account != null && Short.valueOf((short) 1).equals(account.getStatus());
    }

    private AccountService accountService() {
        return AccountServiceHolder.INSTANCE;
    }

    /** 仅登录态请求需要数据库，免登录学分自查不应被数据库配置阻断。 */
    private static final class AccountServiceHolder {
        private static final AccountService INSTANCE = new AccountService();
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private String getPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        String path = uri.substring(contextPath.length());
        return path.isEmpty() ? "/" : path;
    }

    private boolean isPublicEndpoint(HttpServletRequest req, String path, boolean pendingOauthBind) {
        if (isStaticResource(path)) {
            return true;
        }
        if ("/".equals(path)
                || "/index.html".equals(path)
                || "/credit-admin.html".equals(path)
                || "/login.html".equals(path)
                || "/register.html".equals(path)
                || "/oauthBind.html".equals(path)
                || "/error404.html".equals(path)) {
            return isSafeMethod(req.getMethod());
        }
        if ("/api/account/login".equals(path)
                || "/api/account/register".equals(path)
                || "/api/account/sendEmailCode".equals(path)) {
            return "POST".equalsIgnoreCase(req.getMethod());
        }
        if ("/api/credit-audit/parse".equals(path)) {
            return "GET".equalsIgnoreCase(req.getMethod()) || "POST".equalsIgnoreCase(req.getMethod());
        }
        if ("/api/credit-audit/plans".equals(path) || "/api/credit-audit/visit".equals(path)) {
            return "GET".equalsIgnoreCase(req.getMethod());
        }
        if ("/api/credit-audit/feedback".equals(path)) {
            return "POST".equalsIgnoreCase(req.getMethod());
        }
        if ("/api/credit-audit/admin".equals(path)) {
            return "GET".equalsIgnoreCase(req.getMethod()) || "POST".equalsIgnoreCase(req.getMethod());
        }
        if ("/api/credit-audit/admin/plans".equals(path)) {
            return "GET".equalsIgnoreCase(req.getMethod())
                    || "POST".equalsIgnoreCase(req.getMethod())
                    || "DELETE".equalsIgnoreCase(req.getMethod());
        }
        if ("/api/account/sliderChallenge".equals(path)
                || "/api/account/oauth/gitee/authorize".equals(path)
                || "/gitee_callback".equals(path)
                || "/getCode".equals(path)) {
            return "GET".equalsIgnoreCase(req.getMethod());
        }
        return "/api/account/oauth/gitee/bind".equals(path)
                && "POST".equalsIgnoreCase(req.getMethod())
                && pendingOauthBind;
    }

    private boolean isStaticResource(String path) {
        return path != null
                && (path.startsWith("/static/") || path.startsWith("/lib/") || "/favicon.ico".equals(path));
    }

    private boolean isApiRequest(HttpServletRequest req, String path) {
        if (path != null && (path.startsWith("/api/") || path.startsWith("/page/"))) {
            return true;
        }
        String requestedWith = req.getHeader("X-Requested-With");
        if ("XMLHttpRequest".equalsIgnoreCase(requestedWith)) {
            return true;
        }
        String accept = req.getHeader("Accept");
        return accept != null && accept.contains("application/json");
    }

    private void writeJsonError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"code\":" + status + ",\"msg\":\"" + message + "\",\"data\":null}");
    }

    @Override
    public void destroy() {
        // 无需释放资源。
    }
}
