package service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditAuditServiceTest {

    private final CreditAuditService service = new CreditAuditService();

    @Test
    void parsesExportedGradeHtmlWithoutExecutingContent() throws Exception {
        String html = """
                <html><body><script>throw new Error('不应执行')</script><table>
                <tr><th>序号</th><th>开课学期</th><th>课程代码</th><th>课程名称</th><th>成绩</th><th>学分</th><th>考试性质</th><th>课程性质</th><th>课程属性</th></tr>
                <tr><td>1</td><td>2025-1</td><td>N0001</td><td>数据结构</td><td>86</td><td>4</td><td>正考</td><td>必修</td><td>专业必修课</td></tr>
                </table></body></html>
                """;
        var result = service.parseCourses(html.getBytes(StandardCharsets.UTF_8), "成绩.html");
        assertEquals(1, result.courses().size());
        assertEquals("数据结构", result.courses().getFirst().name());
        assertEquals(4, result.courses().getFirst().credits());
        assertEquals("专业必修课", result.courses().getFirst().category());
    }

    @Test
    void rejectsMismatchedPdfExtension() {
        assertThrows(Exception.class, () -> service.parseCourses("不是PDF".getBytes(StandardCharsets.UTF_8), "成绩.pdf"));
    }

    @Test
    void keepsRetakeRecordsForFinalConfirmation() throws Exception {
        String html = """
                <table><tr><th>课程名称</th><th>成绩</th><th>学分</th></tr>
                <tr><td>高等代数</td><td>52</td><td>4</td></tr>
                <tr><td>高等代数</td><td>82</td><td>4</td></tr></table>
                """;
        var result = service.parseCourses(html.getBytes(StandardCharsets.UTF_8), "重修成绩.html");
        assertEquals(2, result.courses().size());
        assertFalse(result.courses().getFirst().passed());
    }

    @Test
    void parsesSimplePlanCsv() throws Exception {
        String csv = "课程模块,要求学分\n通识必修,43.5\n专业必修,41.5\n专业选修,23\n劳动学分,2\n";
        var result = service.parsePlan(csv.getBytes(StandardCharsets.UTF_8), "方案.csv");
        assertEquals(4, result.modules().size());
        assertTrue(result.modules().stream().anyMatch(module -> "劳动学分".equals(module.name())));
        assertTrue(result.modules().stream().allMatch(module -> !module.name().contains("实践教学")));
    }

    @Test
    void explainsHowToHandleExportWrapperHtml() throws Exception {
        String wrapper = "<html><body><iframe src=\"课程成绩查询_files/cjcx_list.html\"></iframe></body></html>";
        var result = service.parseCourses(wrapper.getBytes(StandardCharsets.UTF_8), "课程成绩查询.html");
        assertTrue(result.courses().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(message -> message.contains("cjcx_list.html")));
    }

    @Test
    void prioritizesGraduationMinimumTableAndHandlesMergedCategories() throws Exception {
        String html = """
                <table>
                <tr><td>通识课程</td><td>必修课</td><td>43.5</td></tr>
                <tr><td></td><td>选修课</td><td>15</td></tr>
                <tr><td>学科基础课程</td><td>必修课</td><td>42</td></tr>
                <tr><td>专业课程</td><td>必修课</td><td>41.5</td></tr>
                <tr><td></td><td>选修课</td><td>23</td></tr>
                <tr><td>毕业标准最低总学分合计</td><td>165</td></tr>
                </table>
                <table><tr><td>劳动</td><td>必修</td><td>2</td></tr></table>
                """;
        var result = service.parsePlan(html.getBytes(StandardCharsets.UTF_8), "方案.html");
        assertEquals(6, result.modules().size());
        assertTrue(result.modules().stream().anyMatch(module -> "专业选修".equals(module.name()) && module.requiredCredits() == 23));
        assertTrue(result.modules().stream().anyMatch(module -> "劳动学分".equals(module.name()) && module.requiredCredits() == 2));
    }
}
