package service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> requestBody = new AtomicReference<>("");
    private final AtomicBoolean emptyFirstResponse = new AtomicBoolean(false);
    private final AtomicBoolean slowFirstResponse = new AtomicBoolean(false);
    private final AtomicInteger chatCalls = new AtomicInteger(0);
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/models", exchange -> {
            byte[] bytes = "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-flash\",\"name\":\"DeepSeek-V4.1-Flash\",\"effort\":{\"supported_levels\":[\"low\",\"high\",\"max\"]}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = chatCalls.incrementAndGet();
            if (slowFirstResponse.get() && call == 1) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            String response = emptyFirstResponse.get() && call == 1
                    ? "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"reasoning_content\":\"分析中\",\"content\":\"\"}}]}"
                    : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"```json\\n{\\\"summary\\\":\\\"核对完成\\\",\\\"confidence\\\":\\\"高\\\",\\\"issues\\\":[]}\\n```\"}}]}";
            byte[] bytes = response
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    @Test
    void readsOfficialModelMetadata() throws Exception {
        DeepSeekClient client = new DeepSeekClient(HttpClient.newHttpClient(), baseUri);
        var models = client.listModels("sk-test");
        assertEquals("deepseek-flash", models.getFirst().id());
        assertEquals(List.of("low", "high", "max"), models.getFirst().supportedEfforts());
    }

    @Test
    void redactsIdentityAndKeepsDocumentEvidenceForReview() throws Exception {
        DeepSeekClient client = new DeepSeekClient(HttpClient.newHttpClient(), baseUri);
        var review = client.verify(new DeepSeekConfigService.RuntimeConfig("sk-test", "deepseek-flash", "high"),
                plan("姓名：张三 学号：2024123456 专业必修 40"),
                List.of(new CreditAuditService.CourseRecord("高等数学", "专业必修", 4, 92, true,
                        "姓名：张三 学号：2024123456 高等数学 4 92")), List.of());

        assertTrue(review.completed());
        assertTrue(requestBody.get().contains("高等数学"));
        assertTrue(requestBody.get().contains("92"));
        assertFalse(requestBody.get().contains("张三"));
        assertFalse(requestBody.get().contains("2024123456"));
    }

    @Test
    void retriesWithoutThinkingWhenDeepSeekReturnsEmptyFinalContent() throws Exception {
        emptyFirstResponse.set(true);
        DeepSeekClient client = new DeepSeekClient(HttpClient.newHttpClient(), baseUri);

        var review = client.verify(new DeepSeekConfigService.RuntimeConfig("sk-test", "deepseek-flash", "high"),
                plan("专业必修 40"),
                List.of(new CreditAuditService.CourseRecord("高等数学", "专业必修", 4, 92, true, "高等数学 4 92")),
                List.of());

        assertTrue(review.completed());
        assertEquals(2, chatCalls.get());
        assertTrue(requestBody.get().contains("\"type\":\"disabled\""));
        assertTrue(requestBody.get().contains("\"max_tokens\":4096"));
        assertFalse(requestBody.get().contains("response_format"));
    }

    @Test
    void retriesWithoutThinkingWhenFirstDeepSeekRequestTimesOut() throws Exception {
        slowFirstResponse.set(true);
        DeepSeekClient client = new DeepSeekClient(HttpClient.newHttpClient(), baseUri,
                Duration.ofMillis(80), Duration.ofSeconds(2));

        var review = client.verify(new DeepSeekConfigService.RuntimeConfig("sk-test", "deepseek-flash", "high"),
                plan("专业必修 40"),
                List.of(new CreditAuditService.CourseRecord("高等数学", "专业必修", 4, 92, true, "高等数学 4 92")),
                List.of());

        assertTrue(review.completed());
        assertEquals(2, chatCalls.get());
        assertTrue(requestBody.get().contains("\"type\":\"disabled\""));
    }

    private CreditPlanRegistryService.PlanRecord plan(String source) {
        return new CreditPlanRegistryService.PlanRecord("", "韶关学院", "测试专业", "2024", "", "测试方案.docx",
                false, "", List.of(new CreditAuditService.ModuleRequirement("专业必修", 40, source, "已识别")),
                40, 40, 0, List.of(), List.of(new CreditPlanRegistryService.CompletionRequirement("劳动", 2, false)));
    }
}
