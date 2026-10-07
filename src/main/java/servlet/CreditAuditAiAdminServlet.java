package servlet;

import pojo.Result;
import service.DeepSeekClient;
import service.DeepSeekConfigService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/** 管理 DeepSeek 密钥、模型列表和推理档位。 */
@WebServlet("/api/credit-audit/admin/ai")
public class CreditAuditAiAdminServlet extends CreditAuditApiServlet {

    private static final DeepSeekConfigService CONFIG = DeepSeekConfigService.getInstance();
    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!authorized(request, response)) return;
        write(response, 200, Result.success(CONFIG.view()));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        if (!authorized(request, response)) return;
        if (!trustedWriteRequest(request)) {
            write(response, 403, Result.fail(403, "请求来源校验失败"));
            return;
        }
        try {
            AiAdminRequest body = readJsonBounded(request, AiAdminRequest.class, 16 * 1024);
            if (body == null || body.action() == null) throw new IllegalArgumentException("操作类型不能为空");
            Object result = switch (body.action()) {
                case "save" -> CONFIG.save(body.apiKey(), body.modelId(), body.reasoningEffort());
                case "sync" -> syncModels();
                case "test" -> testConnection();
                case "addModel" -> CONFIG.addCustomModel(body.modelId(), body.modelName(), body.supportedEfforts());
                case "removeModel" -> CONFIG.removeCustomModel(body.modelId());
                default -> throw new IllegalArgumentException("不支持的操作类型");
            };
            write(response, 200, Result.success(result));
        } catch (RequestTooLargeException error) {
            write(response, 413, Result.fail(413, "请求内容超过安全限制"));
        } catch (IllegalArgumentException | IllegalStateException error) {
            write(response, 400, Result.fail(400, safeMessage(error.getMessage(), "AI 设置不正确", 180)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            write(response, 503, Result.fail(503, "DeepSeek 连接测试已中断"));
        } catch (IOException error) {
            write(response, 502, Result.fail(502, safeMessage(error.getMessage(), "DeepSeek 服务暂时不可用", 180)));
        }
    }

    private DeepSeekConfigService.SettingsView syncModels() throws IOException, InterruptedException {
        var runtime = CONFIG.runtimeConfig();
        return CONFIG.replaceDiscoveredModels(client.listModels(runtime.apiKey()));
    }

    private ConnectionResult testConnection() throws IOException, InterruptedException {
        var runtime = CONFIG.runtimeConfig();
        client.test(runtime);
        return new ConnectionResult(true, "DeepSeek 连接正常", CONFIG.view());
    }

    private record AiAdminRequest(String action, String apiKey, String modelId, String modelName,
                                  String reasoningEffort, List<String> supportedEfforts) {}
    private record ConnectionResult(boolean connected, String message,
                                    DeepSeekConfigService.SettingsView settings) {}
}
