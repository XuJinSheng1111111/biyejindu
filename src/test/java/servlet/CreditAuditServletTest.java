package servlet;

import org.junit.jupiter.api.Test;
import service.CreditAuditService;
import service.ScoreArchiveParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreditAuditServletTest {

    private final ScoreArchiveParser parser = new ScoreArchiveParser(new CreditAuditService());

    @Test
    void parsesWindowsChineseZipEntryNames() throws Exception {
        String html = """
                <table><tr><th>课程名称</th><th>成绩</th><th>学分</th><th>课程类别</th></tr>
                <tr><td>数学分析</td><td>88</td><td>5</td><td>专业必修</td></tr></table>
                """;
        byte[] archive = zip("成绩/课程成绩查询_files/cjcx_list.html", html.getBytes(StandardCharsets.UTF_8),
                Charset.forName("GBK"));

        var result = parser.parse(archive);

        assertEquals(1, result.courses().size());
        assertEquals("数学分析", result.courses().getFirst().name());
    }

    @Test
    void rejectsFakeZipContent() {
        assertThrows(IOException.class,
                () -> parser.parse("不是ZIP".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsArchivePathTraversal() throws Exception {
        byte[] archive = zip("../cjcx_list.html", "<table></table>".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> parser.parse(archive));
    }

    @Test
    void rejectsEntryExpandingBeyondLimit() throws Exception {
        byte[] oversized = new byte[8 * 1024 * 1024 + 1];
        byte[] archive = zip("cjcx_list.html", oversized, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> parser.parse(archive));
    }

    private byte[] zip(String entryName, byte[] content, Charset charset) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, charset)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
