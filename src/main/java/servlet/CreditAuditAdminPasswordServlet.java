package servlet;

import pojo.Result;
import service.AdminCredentialService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 修改运营后台密码。 */
@WebServlet("/api/credit-audit/admin/password")
public class CreditAuditAdminPasswordServlet extends CreditAuditApiServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!authorized(request, response)) return;
        if (!trustedWriteRequest(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        try {
            PasswordRequest body = readJsonBounded(request, PasswordRequest.class, 4 * 1024);
            if (body == null || !safeEquals(body.newPassword(), body.confirmPassword())) {
                write(response, 400, Result.fail(400, "两次输入的新密码不一致"));
                return;
            }
            AdminCredentialService.getInstance().change(body.currentPassword(), body.newPassword());
            write(response, 200, Result.success("后台密码已更新"));
        } catch (IllegalArgumentException error) {
            write(response, 400, Result.fail(400, safeMessage(error.getMessage(), "密码不符合要求", 120)));
        } catch (RequestTooLargeException error) {
            write(response, 413, Result.fail(413, "请求内容超过安全限制"));
        }
    }

    private boolean safeEquals(String left, String right) {
        return left != null && right != null && util.SecurityUtil.constantTimeEquals(left, right);
    }

    private record PasswordRequest(String currentPassword, String newPassword, String confirmPassword) {}
}
