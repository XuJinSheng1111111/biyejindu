package servlet;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import pojo.Account;
import pojo.AiSummary;
import pojo.Result;
import pojo.StudentInfo;
import pojo.StudentScore;
import service.StudentDataService;
import service.StudentInfoService;
import service.StudentScoreService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@WebServlet("/api/data/*")
public class StudentDataServlet extends BaseServlet {

    private static final StudentInfoService studentInfoService = new StudentInfoService();
    private static final StudentScoreService studentScoreService = new StudentScoreService();
    private static final StudentDataService studentDataService = new StudentDataService();
    private static final String CURRENT_VERSION = "1.0";
    private static final String LATEST_VERSION = "1.0";

    /**
     * 导出全部成绩为 Excel。
     */
    public void exportScores(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        StudentInfo studentInfo = currentStudentInfo(req, resp);
        if (studentInfo == null) return;

        List<StudentScore> scores = studentScoreService.queryAllByStudentInfoId(studentInfo.getId());

        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename=\"scores.xlsx\"");

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("成绩数据");
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);

            String[] headers = {"学年", "学期", "课程号", "课程名称", "课程组", "期末成绩", "总成绩", "绩点", "学分", "考试类型", "课程类型", "通过标识", "备注"};
            writeHeader(sheet, headers, headerStyle);

            int rowIndex = 1;
            for (StudentScore score : scores) {
                Row row = sheet.createRow(rowIndex++);
                int column = 0;
                setCell(row, column++, score.getSchoolYear());
                setCell(row, column++, score.getTerm());
                setCell(row, column++, score.getCourseNo());
                setCell(row, column++, score.getCourseName());
                setCell(row, column++, score.getCourseGroup());
                setCell(row, column++, score.getFinalScore());
                setCell(row, column++, score.getTotalScore());
                setCell(row, column++, score.getGpa());
                setCell(row, column++, score.getCredit());
                setCell(row, column++, score.getExamType());
                setCell(row, column++, score.getCourseType());
                setCell(row, column++, score.getPassFlag());
                Cell remark = setCell(row, column, score.getRemark());
                remark.setCellStyle(wrapStyle);
            }

            autoSize(sheet, headers.length);
            try (OutputStream outputStream = resp.getOutputStream()) {
                workbook.write(outputStream);
            }
        }
    }

    /**
     * 导出全部学业统计总结为 Excel。
     */
    public void exportSummaries(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        StudentInfo studentInfo = currentStudentInfo(req, resp);
        if (studentInfo == null) return;

        List<AiSummary> summaries = studentDataService.listSummaries(studentInfo.getId());

        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename=\"summaries.xlsx\"");

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("学业总结");
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);

            String[] headers = {"总结类型", "学年", "学期", "创建时间", "更新时间", "总结内容"};
            writeHeader(sheet, headers, headerStyle);

            int rowIndex = 1;
            for (AiSummary summary : summaries) {
                Row row = sheet.createRow(rowIndex++);
                setCell(row, 0, "all".equals(summary.getSummaryType()) ? "全学期总结" : "单学期总结");
                setCell(row, 1, summary.getSchoolYear());
                setCell(row, 2, summary.getTerm());
                setCell(row, 3, summary.getCreateTime());
                setCell(row, 4, summary.getUpdateTime());
                Cell content = setCell(row, 5, summary.getSummaryText());
                content.setCellStyle(wrapStyle);
            }

            autoSize(sheet, headers.length);
            sheet.setColumnWidth(5, 80 * 256);
            try (OutputStream outputStream = resp.getOutputStream()) {
                workbook.write(outputStream);
            }
        }
    }

    /**
     * 清空当前账号下的成绩、学业总结和上传历史。
     */
    public void clearAcademicData(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        StudentInfo studentInfo = currentStudentInfo(req, resp);
        if (studentInfo == null) return;

        if (!studentDataService.clearAcademicData(studentInfo.getId())) {
            resp.setStatus(500);
            resp.getWriter().write(JSON.toJSONString(Result.fail(500, "清空学业数据失败，请稍后重试")));
            return;
        }
        resp.getWriter().write(JSON.toJSONString(Result.success(null)));
    }


    /**
     * 检查版本更新。当前项目没有独立发布服务时，返回内置版本信息。
     */
    public void checkUpdate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentVersion", CURRENT_VERSION);
        data.put("latestVersion", LATEST_VERSION);
        data.put("hasUpdate", !CURRENT_VERSION.equals(LATEST_VERSION));
        data.put("releaseDate", "2026-10");
        resp.getWriter().write(JSON.toJSONString(Result.success(data)));
    }
    private StudentInfo currentStudentInfo(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Account account = session == null ? null : (Account) session.getAttribute("loginAccount");
        if (account == null) {
            resp.setStatus(401);
            resp.getWriter().write(JSON.toJSONString(Result.fail(401, "未登录")));
            return null;
        }
        StudentInfo studentInfo = studentInfoService.getStudentInfoById(account.getId());
        if (studentInfo == null) {
            resp.setStatus(404);
            resp.getWriter().write(JSON.toJSONString(Result.fail(404, "学生信息不存在")));
            return null;
        }
        return studentInfo;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private void writeHeader(Sheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private Cell setCell(Row row, int column, Object value) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
        return cell;
    }

    private void autoSize(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
            int width = Math.min(sheet.getColumnWidth(i) + 512, 40 * 256);
            sheet.setColumnWidth(i, Math.max(width, 10 * 256));
        }
    }
}
