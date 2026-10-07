package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditPlanRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deactivatesAndReplacesPlanWithoutLosingHistory() throws Exception {
        Path registry = tempDir.resolve("plans.json");
        CreditPlanRegistryService service = new CreditPlanRegistryService(registry);
        var modules = List.of(new CreditAuditService.ModuleRequirement("专业必修", 40, "测试", "已识别"));
        var extraction = new CreditAuditService.PlanExtraction(modules, 40, 40, 0,
                List.of(), List.of(), List.of(), "方案.csv");
        String firstHash = "1".repeat(64);
        String secondHash = "2".repeat(64);

        var original = service.saveProvisional("测试大学", "测试专业", "2024", firstHash,
                "测试大学-测试专业-2024级-人才培养方案.csv", extraction);
        assertTrue(service.listAvailable().stream().anyMatch(item -> item.id().equals(original.id())));

        service.deactivate(original.id());
        assertFalse(service.listAvailable().stream().anyMatch(item -> item.id().equals(original.id())));

        var replacement = service.replace(original.id(), "测试大学", "测试专业", "2024", secondHash,
                "测试大学-测试专业-2024级-人才培养方案.csv", extraction);
        assertTrue(service.listAvailable().stream().anyMatch(item -> item.id().equals(replacement.id())));
        assertTrue(service.listManaged().stream().anyMatch(item -> item.id().equals(original.id()) && !item.active()));

        CreditPlanRegistryService reloaded = new CreditPlanRegistryService(registry);
        assertFalse(reloaded.listAvailable().stream().anyMatch(item -> item.id().equals(original.id())));
        assertTrue(reloaded.listAvailable().stream().anyMatch(item -> item.id().equals(replacement.id())));
    }
}
