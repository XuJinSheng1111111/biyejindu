package service;

import org.mindrot.jbcrypt.BCrypt;
import util.AuthConfig;
import util.SecurityUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * 运营后台密码服务。
 * 首次安装沿用环境变量中的管理密钥；修改后只保存 BCrypt 哈希，旧密钥立即失效。
 */
public final class AdminCredentialService {

    private static final AdminCredentialService INSTANCE = new AdminCredentialService(resolveFile());
    private static final int BCRYPT_COST = 13;
    private final Path hashFile;

    AdminCredentialService(Path hashFile) {
        this.hashFile = hashFile;
    }

    public static AdminCredentialService getInstance() {
        return INSTANCE;
    }

    public boolean configured() {
        return readHash() != null || AuthConfig.get("CREDIT_AUDIT_ADMIN_KEY", "").length() >= 24;
    }

    public boolean verify(String candidate) {
        if (candidate == null || candidate.length() > 256) return false;
        String stored = readHash();
        if (stored != null) {
            if (stored.isBlank()) return false;
            try {
                return BCrypt.checkpw(candidate, stored);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return SecurityUtil.constantTimeEquals(AuthConfig.get("CREDIT_AUDIT_ADMIN_KEY", ""), candidate);
    }

    public synchronized void change(String currentPassword, String newPassword) throws IOException {
        if (!verify(currentPassword)) throw new IllegalArgumentException("当前后台密码不正确");
        validateNewPassword(newPassword);
        if (SecurityUtil.constantTimeEquals(currentPassword, newPassword)) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        writeHash(BCrypt.hashpw(newPassword, BCrypt.gensalt(BCRYPT_COST)));
    }

    static void validateNewPassword(String value) {
        if (value == null || value.length() < 6 || value.length() > 128) {
            throw new IllegalArgumentException("新密码需为 6 至 128 个字符");
        }
    }

    private String readHash() {
        try {
            if (!Files.isRegularFile(hashFile)) return null;
            String value = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
            return value.startsWith("$2") ? value : "";
        } catch (IOException ignored) {
            // 已存在但损坏或不可读时必须失败关闭，不能重新启用安装时的旧密钥。
            return Files.exists(hashFile) ? "" : null;
        }
    }

    private void writeHash(String value) throws IOException {
        Files.createDirectories(hashFile.getParent());
        Path temporary = hashFile.resolveSibling(hashFile.getFileName() + ".tmp");
        Files.writeString(temporary, value + System.lineSeparator(), StandardCharsets.UTF_8);
        restrictPermissions(temporary);
        try {
            Files.move(temporary, hashFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, hashFile, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictPermissions(hashFile);
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
        return directory.resolve("admin-password.bcrypt");
    }
}
