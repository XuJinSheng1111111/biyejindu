package service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCredentialServiceTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearProperty() {
        System.clearProperty("CREDIT_AUDIT_ADMIN_KEY");
    }

    @Test
    void changesPasswordAndInvalidatesInitialKey() throws Exception {
        String initial = "initial-admin-password-123";
        String replacement = "new-admin-password-456";
        System.setProperty("CREDIT_AUDIT_ADMIN_KEY", initial);
        Path file = temporaryDirectory.resolve("admin-password.bcrypt");
        AdminCredentialService service = new AdminCredentialService(file);

        assertTrue(service.verify(initial));
        service.change(initial, replacement);

        assertFalse(service.verify(initial));
        assertTrue(service.verify(replacement));
        assertTrue(new AdminCredentialService(file).verify(replacement));
    }

    @Test
    void rejectsTooShortPassword() {
        assertThrows(IllegalArgumentException.class,
                () -> AdminCredentialService.validateNewPassword("12345"));
    }
}
