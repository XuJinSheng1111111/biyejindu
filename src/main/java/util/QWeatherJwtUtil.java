package util;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;

/**
 * 和风天气 JWT 工具类（JDK15+ 原生 EdDSA 实现）。
 * 凭据从环境变量、JVM 系统属性或本地忽略配置文件读取。
 */
public final class QWeatherJwtUtil {

    private static final long EXPIRE_SECONDS = 900;
    private static volatile PrivateKey cachedKey;

    private QWeatherJwtUtil() {}

    public static String generateToken() throws Exception {
        String keyId = required("QWEATHER_KEY_ID", "key.id");
        String projectId = required("QWEATHER_PROJECT_ID", "project.id");

        String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"" + keyId + "\"}";
        long iat = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond() - 30;
        long exp = iat + EXPIRE_SECONDS;
        String payloadJson = "{\"sub\":\"" + projectId + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}";

        Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
        String headerEncoded = urlEncoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadEncoded = urlEncoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String data = headerEncoded + "." + payloadEncoded;

        Signature signer = Signature.getInstance("EdDSA");
        signer.initSign(getPrivateKey());
        signer.update(data.getBytes(StandardCharsets.UTF_8));
        return data + "." + urlEncoder.encodeToString(signer.sign());
    }

    private static PrivateKey getPrivateKey() throws Exception {
        if (cachedKey != null) {
            return cachedKey;
        }
        synchronized (QWeatherJwtUtil.class) {
            if (cachedKey != null) {
                return cachedKey;
            }
            String raw = required("QWEATHER_PRIVATE_KEY", "private.key");
            String pem = raw.contains("BEGIN PRIVATE KEY")
                    ? raw
                    : "-----BEGIN PRIVATE KEY-----\n" + raw + "\n-----END PRIVATE KEY-----";
            String normalized = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(normalized);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            cachedKey = KeyFactory.getInstance("EdDSA").generatePrivate(keySpec);
            return cachedKey;
        }
    }

    private static String required(String envKey, String localKey) {
        String value = AuthConfig.get(envKey, AuthConfig.get(localKey, ""));
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("未配置和风天气凭据：" + envKey);
        }
        return value;
    }
}