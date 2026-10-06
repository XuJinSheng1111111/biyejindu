package servlet;

import pojo.Result;
import service.CreditAuditOperationsService;
import service.RequestRateLimiter;
import util.SecurityUtil;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/** 匿名访问计数与问题反馈接口。 */
@WebServlet(urlPatterns = {"/api/credit-audit/visit", "/api/credit-audit/feedback"})
public class CreditAuditFeedbackServlet extends CreditAuditApiServlet {
    private static final CreditAuditOperationsService OPERATIONS = CreditAuditOperationsService.getInstance();
    private static final Set<String> TYPES = Set.of("bug", "parse", "suggest");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!request.getServletPath().endsWith("/visit")) {
            write(response, 405, Result.fail(405, "请求方式不支持"));
            return;
        }
        if (!trustedWriteRequest(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        String visitor = OPERATIONS.identify(request, response);
        OPERATIONS.recordVisit(visitor);
        write(response, 200, Result.success(true));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!request.getServletPath().endsWith("/feedback")) {
            write(response, 405, Result.fail(405, "请求方式不支持"));
            return;
        }
        if (!isSameOrigin(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        String visitKey = "credit-visit:" + SecurityUtil.sha256(clientAddress(request));
        if (RequestRateLimiter.isBlocked(visitKey, 120, Duration.ofHours(1))
                || !RequestRateLimiter.recordFailure(visitKey, Duration.ofHours(1))) {
            write(response, 429, Result.fail(429, "访问统计请求过于频繁"));
            return;
        }
        String visitor = OPERATIONS.identify(request, response);
        String remoteHash = SecurityUtil.sha256(String.valueOf(request.getRemoteAddr()));
        String rateKey = "credit-feedback:" + remoteHash + ':' + visitor;
        if (RequestRateLimiter.isBlocked(rateKey, 5, Duration.ofHours(1))
                || !RequestRateLimiter.recordFailure(rateKey, Duration.ofHours(1))) {
            write(response, 429, Result.fail(429, "反馈提交过于频繁，请稍后再试"));
            return;
        }
        FeedbackRequest body;
        try {
            body = readJsonBounded(request, FeedbackRequest.class, 8 * 1024);
        } catch (RequestTooLargeException error) {
            write(response, 413, Result.fail(413, "反馈内容超过安全限制"));
            return;
        } catch (Exception error) {
            write(response, 400, Result.fail(400, "反馈内容格式不正确"));
            return;
        }
        String type = clean(body.type(), 20);
        String content = cleanMultiline(body.content(), 2_000);
        String contact = clean(body.contact(), 100);
        if (!TYPES.contains(type) || content.length() < 5) {
            write(response, 400, Result.fail(400, "请完整填写问题描述"));
            return;
        }
        if (!contact.isBlank() && !contact.matches("(?:1\\d{10}|[^\\s@]{1,64}@[^\\s@]{1,80}\\.[^\\s@]{2,20})")) {
            write(response, 400, Result.fail(400, "联系方式格式不正确"));
            return;
        }
        var saved = OPERATIONS.submitFeedback(visitor, type, content, contact, clean(body.page(), 120));
        write(response, 200, Result.success(saved));
    }

    private record FeedbackRequest(String type, String content, String contact, String page) {}
}
