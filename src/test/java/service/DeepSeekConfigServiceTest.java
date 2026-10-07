package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekConfigServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void encryptsApiKeyAndReloadsSettings() throws Exception {
        Path file = temporaryDirectory.resolve("deepseek-config.json");
        DeepSeekConfigService service = new DeepSeekConfigService(file, "test-master-key-with-enough-entropy");
        service.save("sk-test-secret-value-123456", "deepseek-flash", "max");

        String stored = Files.readString(file);
        assertFalse(stored.contains("sk-test-secret-value-123456"));
        assertTrue(service.view().apiKeyMask().endsWith("3456"));

        DeepSeekConfigService reloaded = new DeepSeekConfigService(file, "test-master-key-with-enough-entropy");
        assertEquals("sk-test-secret-value-123456", reloaded.runtimeConfig().apiKey());
        assertEquals("max", reloaded.runtimeConfig().reasoningEffort());
    }

    @Test
    void supportsAddingAndSelectingFutureModel() throws Exception {
        DeepSeekConfigService service = new DeepSeekConfigService(
                temporaryDirectory.resolve("deepseek-config.json"), "test-master-key");
        service.addCustomModel("deepseek-future", "DeepSeek Future", List.of("low", "max"));
        service.save("sk-test-secret-value-123456", "deepseek-future", "max");

        assertEquals("deepseek-future", service.runtimeConfig().modelId());
        assertThrows(IllegalArgumentException.class,
                () -> service.save("", "deepseek-future", "high"));
    }
}
