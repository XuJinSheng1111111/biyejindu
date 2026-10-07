package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import util.AuthConfig;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** DeepSeek 密钥、模型和推理档位的本地安全配置。 */
public final class DeepSeekConfigService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    private static final Set<String> EFFORTS = Set.of("none", "low", "high", "max");
    private static final DeepSeekConfigService INSTANCE = new DeepSeekConfigService(
            resolveFile(), AuthConfig.get("CREDIT_AUDIT_SETTINGS_KEY", ""));

    private final Path configFile;
    private final byte[] encryptionKey;
    private final SecureRandom random = new SecureRandom();
    private StoredConfig config;

    DeepSeekConfigService(Path configFile, String masterSecret) {
        this.configFile = configFile;
        this.encryptionKey = deriveKey(masterSecret);
        this.config = load();
    }

    public static DeepSeekConfigService getInstance() {
        return INSTANCE;
    }

    public synchronized SettingsView view() {
        String key = apiKeyOrEmpty();
        return new SettingsView(!key.isBlank(), mask(key), config.modelId(), config.reasoningEffort(),
                availableModels(), config.lastSyncedAt());
    }

    public synchronized RuntimeConfig runtimeConfig() {
        String key = apiKeyOrEmpty();
        if (key.isBlank()) throw new IllegalStateException("AI 二次核对尚未配置 API 密钥");
        ModelOption model = findModel(config.modelId());
        if (model == null) throw new IllegalStateException("当前 AI 模型不存在，请在后台重新选择");
        validateEffort(config.reasoningEffort(), model);
        return new RuntimeConfig(key, model.id(), config.reasoningEffort());
    }

    public synchronized SettingsView save(String apiKey, String modelId, String reasoningEffort) throws IOException {
        String selected = validateModelId(modelId);
        ModelOption model = findModel(selected);
        if (model == null) throw new IllegalArgumentException("请先同步或添加该模型");
        String effort = normalizeEffort(reasoningEffort);
        validateEffort(effort, model);
        String cipher = config.encryptedApiKey();
        if (apiKey != null && !apiKey.isBlank()) cipher = encrypt(validateApiKey(apiKey));
        if ((cipher == null || cipher.isBlank()) && AuthConfig.get("DEEPSEEK_API_KEY", "").isBlank()) {
            throw new IllegalArgumentException("请输入 DeepSeek API 密钥");
        }
        config = new StoredConfig(cipher, selected, effort, config.discoveredModels(), config.customModels(),
                config.lastSyncedAt());
        persist();
        return view();
    }

    public synchronized SettingsView replaceDiscoveredModels(List<ModelOption> models) throws IOException {
        List<ModelOption> safe = sanitizeModels(models, false);
        config = new StoredConfig(config.encryptedApiKey(), config.modelId(), config.reasoningEffort(), safe,
                config.customModels(), Instant.now().toString());
        if (findModel(config.modelId()) == null) {
            config = new StoredConfig(config.encryptedApiKey(), "deepseek-flash", "high", safe,
                    config.customModels(), config.lastSyncedAt());
        }
        persist();
        return view();
    }

    public synchronized SettingsView addCustomModel(String id, String name, List<String> efforts) throws IOException {
        ModelOption model = new ModelOption(validateModelId(id), cleanName(name, id),
                sanitizeEfforts(efforts), "手动添加");
        List<ModelOption> custom = new ArrayList<>(config.customModels());
        custom.removeIf(item -> item.id().equals(model.id()));
        custom.add(model);
        config = new StoredConfig(config.encryptedApiKey(), config.modelId(), config.reasoningEffort(),
                config.discoveredModels(), List.copyOf(custom), config.lastSyncedAt());
        persist();
        return view();
    }

    public synchronized SettingsView removeCustomModel(String id) throws IOException {
        String safeId = validateModelId(id);
        if (safeId.equals(config.modelId())) throw new IllegalArgumentException("当前使用中的模型不能删除");
        List<ModelOption> custom = new ArrayList<>(config.customModels());
        if (!custom.removeIf(item -> item.id().equals(safeId))) {
            throw new IllegalArgumentException("该手动模型不存在");
        }
        config = new StoredConfig(config.encryptedApiKey(), config.modelId(), config.reasoningEffort(),
                config.discoveredModels(), List.copyOf(custom), config.lastSyncedAt());
        persist();
        return view();
    }

    private List<ModelOption> availableModels() {
        Map<String, ModelOption> merged = new LinkedHashMap<>();
        defaultModels().forEach(model -> merged.put(model.id(), model));
        config.discoveredModels().forEach(model -> merged.put(model.id(), model));
        config.customModels().forEach(model -> merged.put(model.id(), model));
        return List.copyOf(merged.values());
    }

    private ModelOption findModel(String id) {
        return availableModels().stream().filter(model -> model.id().equals(id)).findFirst().orElse(null);
    }

    private StoredConfig load() {
        try {
            if (!Files.isRegularFile(configFile)) return defaults();
            StoredConfig loaded = MAPPER.readValue(configFile.toFile(), StoredConfig.class);
            String model = validateModelId(loaded.modelId());
            String effort = normalizeEffort(loaded.reasoningEffort());
            return new StoredConfig(loaded.encryptedApiKey(), model, effort,
                    sanitizeModels(loaded.discoveredModels(), false), sanitizeModels(loaded.customModels(), true),
                    loaded.lastSyncedAt());
        } catch (Exception ignored) {
            return defaults();
        }
    }

    private StoredConfig defaults() {
        return new StoredConfig("", "deepseek-flash", "high", List.of(), List.of(), "");
    }

    private void persist() throws IOException {
        Files.createDirectories(configFile.getParent());
        Path temporary = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), config);
        restrictPermissions(temporary);
        try {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictPermissions(configFile);
    }

    private String apiKeyOrEmpty() {
        if (config.encryptedApiKey() != null && !config.encryptedApiKey().isBlank()) {
            return decrypt(config.encryptedApiKey());
        }
        return AuthConfig.get("DEEPSEEK_API_KEY", "");
    }

    private String encrypt(String value) {
        if (encryptionKey.length == 0) {
            throw new IllegalStateException("服务器尚未配置 AI 设置加密密钥");
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception error) {
            throw new IllegalStateException("API 密钥加密失败", error);
        }
    }

    private String decrypt(String value) {
        if (encryptionKey.length == 0) throw new IllegalStateException("服务器缺少 AI 设置加密密钥");
        try {
            String[] parts = value.split("\\.", 2);
            if (parts.length != 2) throw new IllegalArgumentException("密文格式错误");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("AI 密钥无法解密，请在后台重新填写", error);
        }
    }

    private static byte[] deriveKey(String value) {
        if (value == null || value.isBlank()) return new byte[0];
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String validateApiKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.length() < 20 || key.length() > 256 || key.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("DeepSeek API 密钥格式不正确");
        }
        return key;
    }

    private static String validateModelId(String value) {
        String id = value == null ? "" : value.trim();
        if (!MODEL_ID.matcher(id).matches()) throw new IllegalArgumentException("模型标识格式不正确");
        return id;
    }

    private static String normalizeEffort(String value) {
        String effort = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!EFFORTS.contains(effort)) throw new IllegalArgumentException("推理档位不受支持");
        return effort;
    }

    private static void validateEffort(String effort, ModelOption model) {
        if ("none".equals(effort)) return;
        if (!model.supportedEfforts().contains(effort)) {
            throw new IllegalArgumentException("所选模型不支持该推理档位");
        }
    }

    private static String cleanName(String value, String fallback) {
        String name = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank()) name = fallback;
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    private static List<String> sanitizeEfforts(List<String> values) {
        List<String> safe = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                String effort = normalizeEffort(value);
                if (!"none".equals(effort) && !safe.contains(effort)) safe.add(effort);
            }
        }
        return safe.isEmpty() ? List.of("low", "high", "max") : List.copyOf(safe);
    }

    private static List<ModelOption> sanitizeModels(List<ModelOption> values, boolean custom) {
        if (values == null) return List.of();
        List<ModelOption> safe = new ArrayList<>();
        for (ModelOption value : values) {
            if (value == null) continue;
            String id = validateModelId(value.id());
            safe.add(new ModelOption(id, cleanName(value.name(), id), sanitizeEfforts(value.supportedEfforts()),
                    custom ? "手动添加" : "DeepSeek 官方"));
        }
        return List.copyOf(safe);
    }

    private static List<ModelOption> defaultModels() {
        return List.of(
                new ModelOption("deepseek-flash", "DeepSeek-V4.1-Flash", List.of("low", "high", "max"), "DeepSeek 官方"),
                new ModelOption("deepseek-v4-pro", "DeepSeek-V4-Pro", List.of("low", "high", "max"), "DeepSeek 官方")
        );
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) return "";
        return "已配置 ····" + key.substring(Math.max(0, key.length() - 4));
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 使用目录访问控制；Linux 生产环境额外收紧为仅运行账号可读写。
        }
    }

    private static Path resolveFile() {
        String custom = AuthConfig.get("CREDIT_AUDIT_DATA_DIR", "");
        Path directory = custom.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "biyejindu-data")
                : Path.of(custom);
        return directory.resolve("deepseek-config.json");
    }

    public record ModelOption(String id, String name, List<String> supportedEfforts, String source) {}
    public record SettingsView(boolean apiKeyConfigured, String apiKeyMask, String modelId,
                               String reasoningEffort, List<ModelOption> models, String lastSyncedAt) {}
    public record RuntimeConfig(String apiKey, String modelId, String reasoningEffort) {}
    private record StoredConfig(String encryptedApiKey, String modelId, String reasoningEffort,
                                List<ModelOption> discoveredModels, List<ModelOption> customModels,
                                String lastSyncedAt) {}
}
