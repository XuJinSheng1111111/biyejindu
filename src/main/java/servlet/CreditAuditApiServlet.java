package servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import pojo.Result;
import util.AuthConfig;
import util.SecurityUtil;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.util.Set;
import java.time.Duration;
import service.RequestRateLimiter;

/**
 * 学分自查接口的统一协议与安全边界。
 * 所有相关接口共用响应格式、同源校验、管理密钥校验和输入清理规则。
 */
public abstract class CreditAuditApiServlet extends HttpServlet {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final String WRITE_REQUEST_HEADER = "X-Credit-Audit-Request";
    private static final int MAX_ADMIN_FAILURES = 60;
    private static final Duration ADMIN_FAILURE_WINDOW = Duration.ofMinutes(15);

    protected final void prepare(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    protected final void write(HttpServletResponse response, int status, Result<?> result) throws IOException {
        response.setStatus(status);
        MAPPER.writeValue(response.getWriter(), result);
    }

    protected final boolean isSameOrigin(HttpServletRequest request) {
        return originAllowed(request.getHeader("Sec-Fetch-Site"), request.getHeader("Origin"),
                request.getScheme(), request.getServerName(), request.getServerPort(), request.isSecure());
    }

    protected final boolean sameOrigin(HttpServletRequest request) {
        return isSameOrigin(request);
    }

    /**
     * 写操作必须同时通过同源校验并携带脚本专用请求头。
     * 普通跨站表单不能添加这个自定义请求头，可降低匿名上传接口被跨站滥用的风险。
     */
    protected final boolean trustedWriteRequest(HttpServletRequest request) {
        return writeRequestAllowed(request.getHeader("Sec-Fetch-Site"), request.getHeader("Origin"),
                request.getScheme(), request.getServerName(), request.getServerPort(), request.isSecure(),
                request.getHeader(WRITE_REQUEST_HEADER));
    }

    protected final boolean authorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String configured = AuthConfig.get("CREDIT_AUDIT_ADMIN_KEY", "");
        if (configured.length() < 24) {
            write(response, 503, Result.fail(503, "运营后台尚未配置安全密钥"));
            return false;
        }
        String rateKey = "credit-admin-auth:" + SecurityUtil.sha256(clientAddress(request));
        if (RequestRateLimiter.isBlocked(rateKey, MAX_ADMIN_FAILURES, ADMIN_FAILURE_WINDOW)) {
            write(response, 429, Result.fail(429, "管理验证失败次数过多，请稍后再试"));
            return false;
        }
        if (!SecurityUtil.constantTimeEquals(configured, request.getHeader("X-Credit-Admin-Key"))) {
            RequestRateLimiter.recordFailure(rateKey, ADMIN_FAILURE_WINDOW);
            write(response, 401, Result.fail(401, "管理密钥不正确"));
            return false;
        }
        RequestRateLimiter.reset(rateKey);
        return true;
    }

    protected final <T> T readJsonBounded(HttpServletRequest request, Class<T> type, int maxBytes)
            throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) throw new RequestTooLargeException();
        byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new RequestTooLargeException();
        return MAPPER.readValue(bytes, type);
    }

    protected final void validateMultipart(HttpServletRequest request, Set<String> allowedNames, int maxParts)
            throws IOException, javax.servlet.ServletException {
        var parts = request.getParts();
        if (parts.size() > maxParts || parts.stream().anyMatch(part -> !allowedNames.contains(part.getName()))) {
            for (Part part : parts) {
                try { part.delete(); } catch (IOException ignored) { }
            }
            throw new IOException("上传字段数量或名称不合法");
        }
    }

    protected final String clientAddress(HttpServletRequest request) {
        String value = request.getRemoteAddr();
        return value == null || value.isBlank() ? "unknown" : value;
    }

    protected final String clean(String value, int maxLength) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }

    protected final String cleanMultiline(String value, int maxLength) {
        String result = value == null ? ""
                : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return result.length() <= maxLength ? result : result.substring(0, maxLength);
    }

    protected final String safeSubmittedName(Part part) throws IOException {
        if (part == null) {
            throw new IOException("文件为空");
        }
        return safeSubmittedName(part.getSubmittedFileName());
    }

    protected final String safeSubmittedName(String value) throws IOException {
        if (value == null) {
            throw new IOException("文件名为空");
        }
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank() || name.length() > 180) {
            throw new IOException("文件名不合法");
        }
        return name;
    }

    protected final String safeMessage(String message, String fallback, int maxLength) {
        if (message == null || message.isBlank() || message.length() > maxLength) {
            return fallback;
        }
        return message.replaceAll("[\\r\\n]", " ").trim();
    }

    static boolean originAllowed(String fetchSite, String origin, String scheme,
                                 String serverName, int serverPort, boolean secure) {
        if ("cross-site".equalsIgnoreCase(fetchSite)) {
            return false;
        }
        if (origin == null || origin.isBlank()) {
            return true;
        }
        boolean defaultPort = (secure && serverPort == 443) || (!secure && serverPort == 80);
        String expected = scheme + "://" + serverName + (defaultPort ? "" : ":" + serverPort);
        return SecurityUtil.constantTimeEquals(expected.toLowerCase(java.util.Locale.ROOT),
                origin.toLowerCase(java.util.Locale.ROOT));
    }

    static boolean writeRequestAllowed(String fetchSite, String origin, String scheme,
                                       String serverName, int serverPort, boolean secure, String marker) {
        return "1".equals(marker)
                && originAllowed(fetchSite, origin, scheme, serverName, serverPort, secure);
    }

    protected static final class RequestTooLargeException extends IOException {
        RequestTooLargeException() { super("请求内容超过安全限制"); }
    }
}
