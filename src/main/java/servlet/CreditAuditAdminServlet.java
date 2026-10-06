package servlet;

import pojo.Result;
import service.CreditAuditOperationsService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 仅供站点管理者查看匿名运营数据和处理反馈。 */
@WebServlet("/api/credit-audit/admin")
public class CreditAuditAdminServlet extends CreditAuditApiServlet {
    private static final CreditAuditOperationsService OPERATIONS = CreditAuditOperationsService.getInstance();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!authorized(request, response)) return;
        write(response, 200, Result.success(OPERATIONS.dashboard()));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!authorized(request, response)) return;
        if (!trustedWriteRequest(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        ActionRequest body;
        try {
            body = readJsonBounded(request, ActionRequest.class, 4 * 1024);
        } catch (RequestTooLargeException error) {
            write(response, 413, Result.fail(413, "请求内容超过安全限制"));
            return;
        } catch (Exception error) {
            write(response, 400, Result.fail(400, "请求内容格式不正确"));
            return;
        }
        boolean updated = body != null && body.id() != null && body.id().length() <= 80
                && OPERATIONS.updateFeedback(body.id(), body.status());
        if (!updated) {
            write(response, 404, Result.fail(404, "反馈记录不存在或状态不正确"));
            return;
        }
        write(response, 200, Result.success(OPERATIONS.dashboard()));
    }

    private record ActionRequest(String id, String status) {}
}
