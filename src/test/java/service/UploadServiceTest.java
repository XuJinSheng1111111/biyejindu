package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UploadServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void 上传目录必须解析在应用根目录内() throws Exception {
        Path resolved = UploadService.resolveDirectory(tempDirectory.toString(), "/upload/avatar");
        assertTrue(resolved.startsWith(tempDirectory));
        assertTrue(resolved.endsWith(Path.of("upload", "avatar")));
    }

    @Test
    void 上传目录拒绝目录穿越() {
        assertThrows(IOException.class,
                () -> UploadService.resolveDirectory(tempDirectory.toString(), "../outside"));
    }
}
