package servlet;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import pojo.Account;
import pojo.Result;
import pojo.StudentInfo;
import pojo.StudentScore;
import service.StudentInfoService;
import service.StudentScoreService;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@WebServlet("/api/file/score")
@MultipartConfig(
        maxFileSize = 5 * 1024 * 1024,
        maxRequestSize = 5 * 1024 * 1024
)
public class ScoreFileServlet extends HttpServlet {

    private static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 100L * 1024 * 1024;
    private static final long MAX_ZIP_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 1_000;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String MSG_LOGIN_ERR = "未登录";

    private static final StudentScoreService studentScoreService = new StudentScoreService();
    private static final StudentInfoService studentInfoService = new StudentInfoService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("application/json;charset=utf-8");
        PrintWriter out = resp.getWriter();

        HttpSession session = req.getSession(false);
        Account currentAccount = session == null ? null : (Account) session.getAttribute("loginAccount");
        if (currentAccount == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            out.write(JSON.toJSONString(Result.fail(401, MSG_LOGIN_ERR)));
            return;
        }

        StudentInfo studentInfo = studentInfoService.getStudentInfoById(currentAccount.getId());
        if (studentInfo == null) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            out.write(JSON.toJSONString(Result.fail(404, "查找失败")));
            return;
        }

        Part filePart;
        try {
            filePart = req.getPart("file");
        } catch (IllegalStateException | ServletException err) {
            log.warn("成绩文件超过上传限制", err);
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            out.write(JSON.toJSONString(Result.fail(413, "文件大小不能超过 5MB")));
            return;
        }
        if (filePart == null || filePart.getSize() <= 0) {
            out.write(JSON.toJSONString(Result.fail(400, "未接受到文件")));
            return;
        }
        if (filePart.getSize() > MAX_UPLOAD_BYTES) {
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            out.write(JSON.toJSONString(Result.fail(413, "文件大小不能超过 5MB")));
            return;
        }

        String fileName = sanitizeFileName(filePart.getSubmittedFileName());
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME_LENGTH) {
            out.write(JSON.toJSONString(Result.fail(400, "文件名不合法")));
            return;
        }

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".xlsx") && !lowerName.endsWith(".xls")) {
            out.write(JSON.toJSONString(Result.fail(400, "只支持 xls/xlsx 格式文件")));
            return;
        }

        byte[] fileBytes;
        try (var input = filePart.getInputStream()) {
            fileBytes = input.readNBytes(MAX_UPLOAD_BYTES + 1);
        }
        if (fileBytes.length > MAX_UPLOAD_BYTES) {
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            out.write(JSON.toJSONString(Result.fail(413, "文件大小不能超过 5MB")));
            return;
        }
        if (!hasSupportedSignature(fileBytes, lowerName)) {
            resp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            out.write(JSON.toJSONString(Result.fail(415, "文件内容与扩展名不匹配")));
            return;
        }
        if (lowerName.endsWith(".xlsx") && !isArchiveWithinLimits(fileBytes)) {
            resp.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            out.write(JSON.toJSONString(Result.fail(415, "Excel 压缩内容超过安全限制")));
            return;
        }

        List<StudentScore> parseResult;
        try (ByteArrayInputStream input = new ByteArrayInputStream(fileBytes)) {
            parseResult = studentScoreService.parseExcel(input);
        } catch (Exception err) {
            log.warn("成绩文件解析失败，fileName={}", fileName, err);
            out.write(JSON.toJSONString(Result.fail(400, "文件内容无法解析，请检查格式")));
            return;
        }

        session.setAttribute("fileName", fileName);
        session.setAttribute("scoreList", parseResult);
        out.write(JSON.toJSONString(Result.success(parseResult)));
    }

    private String sanitizeFileName(String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return null;
        }
        String value = submitted.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        value = slash >= 0 ? value.substring(slash + 1) : value;
        value = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return value.isBlank() ? null : value;
    }

    private boolean hasSupportedSignature(byte[] header, String lowerName) {
        int read = header.length;
        boolean zip = lowerName.endsWith(".xlsx") && read >= 4
                && header[0] == 0x50 && header[1] == 0x4B
                && header[2] == 0x03 && header[3] == 0x04;
        boolean ole = lowerName.endsWith(".xls") && read >= 8
                && (header[0] & 0xFF) == 0xD0 && (header[1] & 0xFF) == 0xCF
                && (header[2] & 0xFF) == 0x11 && (header[3] & 0xFF) == 0xE0
                && (header[4] & 0xFF) == 0xA1 && (header[5] & 0xFF) == 0xB1
                && (header[6] & 0xFF) == 0x1A && (header[7] & 0xFF) == 0xE1;
        return zip || ole;
    }

    private boolean isArchiveWithinLimits(byte[] fileBytes) {
        long totalBytes = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    return false;
                }
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || totalBytes > MAX_EXTRACTED_BYTES) {
                        return false;
                    }
                }
                zip.closeEntry();
            }
            return entries > 0;
        } catch (IOException err) {
            log.warn("Excel 压缩内容校验失败", err);
            return false;
        }
    }
}
