package service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekVerificationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void alwaysAddsCriticalCreditRulesToAiReview() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"summary\\\":\\\"核对完成\\\",\\\"confidence\\\":\\\"高\\\",\\\"issues\\\":[]}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekConfigService config = new DeepSeekConfigService(
                    temporaryDirectory.resolve("deepseek-config.json"), "test-master-key");
            config.save("sk-test-secret-value-123456", "deepseek-flash", "high");
            DeepSeekClient client = new DeepSeekClient(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
            DeepSeekVerificationService service = new DeepSeekVerificationService(config, client);
            var plan = new CreditPlanRegistryService.PlanRecord("", "韶关学院", "测试专业", "2024", "",
                    "测试方案.docx", true, "",
                    List.of(new CreditAuditService.ModuleRequirement("通识必修", 43.5, "表6", "已核对")),
                    165, 127, 38, List.of(), List.of(
                    new CreditPlanRegistryService.CompletionRequirement("国家安全教育", 1, false),
                    new CreditPlanRegistryService.CompletionRequirement("劳动", 2, false)));
            var courses = List.of(new CreditAuditService.CourseRecord(
                    "国家安全教育", "通识必修", 1, 80, true, "国家安全教育 1 80"));

            var result = service.verify(plan, courses,
                    List.of("培养方案原文存在矛盾：正文与表格总学分不一致。"));

            assertEquals("中", result.confidence());
            assertTrue(result.summary().contains("人工确认"));
            assertTrue(result.issues().stream().anyMatch(issue -> issue.title().contains("不计入总学分")));
            assertTrue(result.issues().stream().anyMatch(issue -> issue.detail().contains("未识别：劳动")));
            assertTrue(result.issues().stream().anyMatch(issue -> issue.title().contains("原文数值")));
        } finally {
            server.stop(0);
        }
    }
}
