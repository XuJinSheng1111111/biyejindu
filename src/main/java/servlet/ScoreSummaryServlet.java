package servlet;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import pojo.Account;
import pojo.Result;
import pojo.StudentInfo;
import service.CompetitionService;
import service.ScoreSummaryService;
import service.StudentInfoService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;

/**
 * 学业总结接口。
 * getSummary：只读取数据库中的单学期总结，不调用 AI。
 * getAllSummary：用户点击按钮后生成全学期总结。
 */
@Slf4j
@WebServlet("/api/scoreSummary/*")
public class ScoreSummaryServlet extends BaseServlet {

    private static final ScoreSummaryService scoreSummaryService = new ScoreSummaryService();
    private static final CompetitionService competitionService = new CompetitionService();
    private static final StudentInfoService studentInfoService = new StudentInfoService();

    /**
     * 查询单学期总结。
     * 入参 term 推荐格式：2025-2026-秋。
     */
    protected void getSummary(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        StudentInfo studentInfo = getCurrentStudentInfo(req, resp);
        if (studentInfo == null) {
            return;
        }

        TermInfo termInfo = parseTerm(req.getParameter("term"), req.getParameter("schoolYear"));
        if (termInfo == null) {
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "学期参数错误")));
            return;
        }

        String summaryText = scoreSummaryService.getSingleSummary(
                studentInfo.getId(),
                termInfo.schoolYear(),
                termInfo.term()
        );

        if (summaryText == null || summaryText.isBlank()) {
            resp.getWriter().write(JSON.toJSONString(Result.success(null)));
            return;
        }

        resp.getWriter().write(JSON.toJSONString(
                Result.success(Map.of("summaryText", summaryText))
        ));
    }

    /**
     * 生成或刷新全学期综合总结。
     * 只有前端点击“生成整体学业总结”时才调用。
     */
    protected void getAllSummary(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        StudentInfo studentInfo = getCurrentStudentInfo(req, resp);
        if (studentInfo == null) {
            return;
        }

        String summaryText = scoreSummaryService.generateAllSummary(studentInfo.getId());
        if (summaryText == null || summaryText.isBlank()) {
            resp.getWriter().write(JSON.toJSONString(
                    Result.fail(500, "全学期学业总结生成失败")
            ));
            return;
        }

        resp.getWriter().write(JSON.toJSONString(
                Result.success(Map.of("summaryText", summaryText))
        ));
    }

    /**
     * 智能推荐竞赛。
     * 前端调用：GET /api/scoreSummary/recommendCompetition
     */
    protected void recommendCompetition(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        StudentInfo studentInfo = getCurrentStudentInfo(req, resp);
        if (studentInfo == null) {
            return;
        }
        resp.getWriter().write(JSON.toJSONString(
                Result.success(
                        competitionService.recommendForStudent(studentInfo.getId())
                )
        ));
    }

    /**
     * 从 Session 中获取当前学生信息，未登录时直接返回 JSON。
     */
    private StudentInfo getCurrentStudentInfo(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            resp.getWriter().write(JSON.toJSONString(Result.fail(401, "未登录")));
            return null;
        }

        Account account = (Account) session.getAttribute("loginAccount");
        if (account == null) {
            resp.getWriter().write(JSON.toJSONString(Result.fail(401, "未登录")));
            return null;
        }

        StudentInfo studentInfo = studentInfoService.getStudentInfoById(account.getId());
        if (studentInfo == null) {
            resp.getWriter().write(JSON.toJSONString(Result.fail(404, "学生信息不存在")));
            return null;
        }
        return studentInfo;
    }

    /**
     * 支持 term=2025-2026-秋，也支持 schoolYear=2025-2026&term=秋。
     */
    private TermInfo parseTerm(String rawTerm, String rawSchoolYear) {
        if (rawTerm == null || rawTerm.isBlank()) {
            return null;
        }

        String term = rawTerm.trim();
        String schoolYear = rawSchoolYear == null ? null : rawSchoolYear.trim();

        int splitIndex = term.lastIndexOf('-');
        if (splitIndex > 0) {
            String suffix = term.substring(splitIndex + 1);
            if ("春".equals(suffix) || "秋".equals(suffix)) {
                schoolYear = term.substring(0, splitIndex);
                term = suffix;
            }
        }

        if (schoolYear == null || schoolYear.isBlank()) {
            return null;
        }
        return new TermInfo(schoolYear, term);
    }

    private record TermInfo(String schoolYear, String term) {}
}