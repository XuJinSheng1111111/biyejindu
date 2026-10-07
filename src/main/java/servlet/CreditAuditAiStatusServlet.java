package servlet;

import pojo.Result;
import service.DeepSeekConfigService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 学生端只读取 AI 二次核对是否可用，不暴露任何密钥信息。 */
@WebServlet("/api/credit-audit/ai/status")
public class CreditAuditAiStatusServlet extends CreditAuditApiServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!isSameOrigin(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        try {
            var settings = DeepSeekConfigService.getInstance().view();
            String name = settings.models().stream().filter(model -> model.id().equals(settings.modelId()))
                    .map(DeepSeekConfigService.ModelOption::name).findFirst().orElse(settings.modelId());
            write(response, 200, Result.success(new AiStatus(settings.apiKeyConfigured(), name)));
        } catch (IllegalStateException error) {
            write(response, 200, Result.success(new AiStatus(false, "")));
        }
    }

    private record AiStatus(boolean available, String modelName) {}
}
