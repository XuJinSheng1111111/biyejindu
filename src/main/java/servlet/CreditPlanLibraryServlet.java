package servlet;

import pojo.Result;
import service.CreditPlanRegistryService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 供学生选择服务器已有培养方案的只读接口。 */
@WebServlet("/api/credit-audit/plans")
public class CreditPlanLibraryServlet extends CreditAuditApiServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!isSameOrigin(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        write(response, 200, Result.success(CreditPlanRegistryService.getInstance().listAvailable()));
    }
}
