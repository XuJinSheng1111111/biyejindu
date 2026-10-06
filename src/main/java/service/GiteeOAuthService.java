package service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import util.AuthConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Gitee OAuth2 授权码登录辅助服务。
 */
public class GiteeOAuthService {

    private static final String AUTHORIZE_URL = "https://gitee.com/oauth/authorize";
    private static final String TOKEN_URL = "https://gitee.com/oauth/token";
    private static final String USER_URL = "https://gitee.com/api/v5/user";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public boolean isConfigured() {
        return !clientId().isBlank() && !clientSecret().isBlank();
    }

    public String buildAuthorizeUrl(String state) {
        return AUTHORIZE_URL
                + "?client_id=" + encode(clientId())
                + "&redirect_uri=" + encode(redirectUri())
                + "&response_type=code"
                + "&scope=user_info"
                + "&state=" + encode(state)
                + "&force=true";
    }

    public String exchangeAccessToken(String code) throws Exception {
        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&client_id=" + encode(clientId())
                + "&client_secret=" + encode(clientSecret())
                + "&redirect_uri=" + encode(redirectUri());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gitee token 获取失败: " + response.body());
        }
        JSONObject json = JSON.parseObject(response.body());
        String accessToken = json.getString("access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Gitee token 响应缺少 access_token");
        }
        return accessToken;
    }

    public JSONObject fetchUser(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USER_URL + "?access_token=" + encode(accessToken)))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gitee 用户信息获取失败: " + response.body());
        }
        return JSON.parseObject(response.body());
    }

    public String clientId() {
        return config("gitee.client.id", "");
    }

    public String clientSecret() {
        return config("gitee.client.secret", "");
    }

    public String redirectUri() {
        return config(
                "gitee.redirect.url",
                "http://localhost:8080/student_system/gitee_callback"
        );
    }

    private String config(String name, String defaultValue) {
        return AuthConfig.get(name, defaultValue);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}