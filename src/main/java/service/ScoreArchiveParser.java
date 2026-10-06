package service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 在内存中安全读取网页成绩压缩包，只把最匹配的 HTML 页面交给成绩解析器。
 */
public final class ScoreArchiveParser {

    private static final int MAX_ARCHIVE_ENTRIES = 300;
    private static final int MAX_ARCHIVE_ENTRY_BYTES = CreditAuditService.MAX_UPLOAD_BYTES;
    private static final int MAX_ARCHIVE_HTML_BYTES = 2 * CreditAuditService.MAX_UPLOAD_BYTES;
    private static final int MAX_ARCHIVE_TOTAL_BYTES = 4 * CreditAuditService.MAX_UPLOAD_BYTES;
    private static final Charset LEGACY_ZIP_CHARSET = Charset.forName("GBK");

    private final CreditAuditService documentParser;

    public ScoreArchiveParser(CreditAuditService documentParser) {
        this.documentParser = documentParser;
    }

    public CreditAuditService.CourseExtraction parse(byte[] archiveBytes) throws IOException {
        requireZipSignature(archiveBytes);
        ArchivePage selected = null;
        int entries = 0;
        int[] htmlBytes = {0};
        int[] totalBytes = {0};
        int expandedLimit = (int) Math.min(MAX_ARCHIVE_TOTAL_BYTES,
                Math.max(4L * 1024 * 1024, archiveBytes.length * 40L));
        try (ZipInputStream input = new ZipInputStream(
                new ByteArrayInputStream(archiveBytes), LEGACY_ZIP_CHARSET)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("压缩包文件数量超过 300 个");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String path = safeArchivePath(entry.getName());
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (entry.getSize() > MAX_ARCHIVE_ENTRY_BYTES) {
                    throw new IOException("压缩包内单个文件不能超过 8MB");
                }
                boolean html = name.toLowerCase(Locale.ROOT).matches(".*\\.(html|htm)$");
                if (Thread.currentThread().isInterrupted()) throw new IOException("压缩包解析已取消");
                byte[] page = readArchiveEntry(input, totalBytes, htmlBytes, html, expandedLimit);
                if (!html) {
                    continue;
                }
                int priority = scorePagePriority(name);
                if (selected == null || priority > selected.priority()) {
                    selected = new ArchivePage(page, name, priority);
                }
            }
        } catch (java.util.zip.ZipException | IllegalArgumentException error) {
            throw new IOException("网页压缩包格式不正确", error);
        }
        if (selected == null) {
            throw new IOException("压缩包中未找到成绩 HTML 页面");
        }
        var result = documentParser.parseCourses(selected.bytes(), selected.name());
        List<String> warnings = new ArrayList<>();
        warnings.add("已从网页压缩包中读取“" + selected.name() + "”。");
        warnings.addAll(result.warnings());
        return new CreditAuditService.CourseExtraction(
                result.courses(), List.copyOf(warnings), selected.name());
    }

    private String safeArchivePath(String value) throws IOException {
        if (value == null) {
            throw new IOException("压缩包内文件名无效");
        }
        String path = value.replace('\\', '/').trim();
        if (path.isBlank() || path.startsWith("/") || path.contains("../") || path.contains(":")
                || path.indexOf('\0') >= 0 || path.length() > 240) {
            throw new IOException("压缩包内文件路径不安全");
        }
        return path;
    }

    private byte[] readArchiveEntry(ZipInputStream input, int[] totalBytes, int[] htmlBytes,
                                    boolean keepHtml, int expandedLimit)
            throws IOException {
        ByteArrayOutputStream output = keepHtml ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[8192];
        int entryBytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("压缩包解析已取消");
            entryBytes += read;
            totalBytes[0] += read;
            if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES) {
                throw new IOException("压缩包内单个文件不能超过 8MB");
            }
            if (totalBytes[0] > expandedLimit) {
                throw new IOException("压缩包解压比例或总量超过安全限制");
            }
            if (keepHtml) {
                htmlBytes[0] += read;
                if (htmlBytes[0] > MAX_ARCHIVE_HTML_BYTES) {
                    throw new IOException("压缩包内网页内容超过 24MB");
                }
                output.write(buffer, 0, read);
            }
        }
        return keepHtml ? output.toByteArray() : new byte[0];
    }

    private void requireZipSignature(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4 || bytes[0] != 0x50 || bytes[1] != 0x4B
                || !((bytes[2] == 0x03 && bytes[3] == 0x04)
                || (bytes[2] == 0x05 && bytes[3] == 0x06)
                || (bytes[2] == 0x07 && bytes[3] == 0x08))) {
            throw new IOException("网页压缩包内容与扩展名不匹配");
        }
    }

    private int scorePagePriority(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.matches("cjcx_list\\.html?")) {
            return 3;
        }
        if (lower.contains("课程成绩") || lower.contains("cjcx") || lower.contains("grade")) {
            return 2;
        }
        return 1;
    }

    private record ArchivePage(byte[] bytes, String name, int priority) {
    }
}
