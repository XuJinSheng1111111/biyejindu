package service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 通用图片上传服务，仅负责文件校验与落盘。
 */
@Slf4j
public class UploadService {

    private static final Set<String> ALLOWED_SUFFIXES = Set.of(".jpg", ".jpeg", ".png", ".gif");
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8_000;
    private static final long MAX_PIXELS_PER_FRAME = 20_000_000L;
    private static final int MAX_FRAMES = 100;
    private static final long MAX_TOTAL_PIXELS = 50_000_000L;

    /**
     * 校验真实图片格式、尺寸和路径后保存，失败时返回 null。
     */
    public String uploadImage(Part part, String saveFolder, String realRootPath) throws IOException {
        if (part == null || part.getSize() <= 0 || part.getSize() > MAX_FILE_BYTES) {
            return null;
        }
        String suffix = getFileSuffix(part);
        if (suffix == null || !ALLOWED_SUFFIXES.contains(suffix)) {
            return null;
        }
        if (!isValidImage(part, suffix)) {
            return null;
        }

        Path directory = resolveDirectory(realRootPath, saveFolder);
        Files.createDirectories(directory);
        String fileName = UUID.randomUUID() + suffix;
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("图片保存路径超出允许目录");
        }
        try (InputStream input = part.getInputStream()) {
            Files.copy(input, target);
        }
        return "/" + normalizeFolder(saveFolder) + "/" + fileName;
    }

    /**
     * 仅删除指定上传目录内的旧文件，拒绝目录穿越路径。
     */
    public void deleteOldFile(String oldUrl, String saveFolder, String realRootPath) {
        if (oldUrl == null || oldUrl.isBlank()) {
            return;
        }
        try {
            Path directory = resolveDirectory(realRootPath, saveFolder);
            String normalizedUrl = oldUrl.replace('\\', '/');
            while (normalizedUrl.startsWith("/")) {
                normalizedUrl = normalizedUrl.substring(1);
            }
            Path candidate = Path.of(realRootPath).toAbsolutePath().normalize()
                    .resolve(normalizedUrl).normalize();
            if (!candidate.startsWith(directory) || candidate.equals(directory)) {
                log.warn("拒绝删除上传目录外文件：{}", oldUrl);
                return;
            }
            Files.deleteIfExists(candidate);
        } catch (Exception err) {
            log.warn("删除旧图片失败：{}", oldUrl, err);
        }
    }

    static Path resolveDirectory(String realRootPath, String saveFolder) throws IOException {
        if (realRootPath == null || realRootPath.isBlank()) {
            throw new IOException("当前部署方式不支持写入 Web 目录");
        }
        Path root = Path.of(realRootPath).toAbsolutePath().normalize();
        Path directory = root.resolve(normalizeFolder(saveFolder)).normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new IOException("上传目录不合法");
        }
        return directory;
    }

    private static String normalizeFolder(String saveFolder) throws IOException {
        if (saveFolder == null || saveFolder.isBlank()) {
            throw new IOException("上传目录不能为空");
        }
        String normalized = saveFolder.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("..") || normalized.contains(":")) {
            throw new IOException("上传目录不合法");
        }
        return normalized;
    }

    private String getFileSuffix(Part part) {
        String name = part.getSubmittedFileName();
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private boolean isValidImage(Part part, String suffix) throws IOException {
        try (InputStream input = part.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!matchesFormat(suffix, format)) {
                    return false;
                }
                int frames = reader.getNumImages(true);
                if (frames <= 0 || frames > MAX_FRAMES) {
                    return false;
                }
                long totalPixels = 0;
                for (int frame = 0; frame < frames; frame++) {
                    int width = reader.getWidth(frame);
                    int height = reader.getHeight(frame);
                    long framePixels = (long) width * height;
                    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                            || framePixels > MAX_PIXELS_PER_FRAME) {
                        return false;
                    }
                    totalPixels += framePixels;
                    if (totalPixels > MAX_TOTAL_PIXELS) {
                        return false;
                    }
                }
                return true;
            } catch (IOException | RuntimeException err) {
                log.debug("图片内容解析失败", err);
                return false;
            } finally {
                reader.dispose();
            }
        }
    }

    private boolean matchesFormat(String suffix, String format) {
        return switch (suffix) {
            case ".jpg", ".jpeg" -> "jpeg".equals(format) || "jpg".equals(format);
            case ".png" -> "png".equals(format);
            case ".gif" -> "gif".equals(format);
            default -> false;
        };
    }
}
