package servlet;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditAuditDeploymentContractTest {

    @Test
    void freshInstallAllowsAllBoundedAuditParts() throws Exception {
        String script = Files.readString(Path.of("deploy", "install-biyejindu.sh"));

        assertTrue(script.contains("maxPartCount=\"8\""));
    }

    @Test
    void updateMigratesAndCanRestoreTomcatPartLimit() throws Exception {
        String script = Files.readString(Path.of("deploy", "update-biyejindu.sh"));

        assertTrue(script.contains("maxPartCount=\"4\"/maxPartCount=\"8\""));
        assertTrue(script.contains("${BACKUP_DIR}/server.xml"));
        assertTrue(script.contains("健康检查失败，自动恢复旧版本"));
    }
}
