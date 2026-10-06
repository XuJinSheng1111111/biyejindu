package util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 认证与外部服务配置读取工具。
 * 优先级：环境变量 > JVM 系统属性 > 本地忽略配置文件 > 代码默认值。
 */
public final class AuthConfig {

    private static final String[] PROPERTY_RESOURCES = {
            "auth.properties",
            "database.properties",
            "weather.properties",
            "email.properties"
    };

    private static final Properties PROPERTIES = loadProperties();

    private AuthConfig() {}

    public static String get(String key, String defaultValue) {
        String value = System.getenv(key);
        if (isUnavailable(value)) {
            value = System.getProperty(key);
        }
        if (isUnavailable(value)) {
            value = PROPERTIES.getProperty(key);
        }
        return isUnavailable(value) ? defaultValue : value.trim();
    }

    private static boolean isUnavailable(String value) {
        if (value == null || value.isBlank()) return true;

        String normalized = value.trim();
        return normalized.startsWith("你的")
                || normalized.contains("SMTP授权码")
                || normalized.equalsIgnoreCase("your_mail")
                || normalized.equalsIgnoreCase("your_password")
                || normalized.equalsIgnoreCase("your_gitee_client_id")
                || normalized.equalsIgnoreCase("your_gitee_client_secret");
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        for (String resource : PROPERTY_RESOURCES) {
            try (InputStream input = AuthConfig.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    continue;
                }
                try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            } catch (Exception ignored) {
                // 本地配置文件缺失或损坏时，继续使用环境变量/系统属性和默认值。
            }
        }
        return properties;
    }
}
