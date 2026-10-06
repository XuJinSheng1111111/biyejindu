package servlet;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import pojo.Account;
import pojo.File;
import pojo.Result;
import pojo.StudentInfo;
import pojo.StudentScore;
import service.ScoreSummaryService;
import service.StudentInfoService;
import service.StudentScoreService;

import javax.servlet.ServletInputStream;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

@Slf4j
@WebServlet("/api/score/*")
public class StudentScoreServlet extends BaseServlet {

    private static final StudentScoreService studentScoreService = new StudentScoreService();
    private static final StudentInfoService studentInfoService = new StudentInfoService();
    private static final ScoreSummaryService scoreSummaryService = new ScoreSummaryService();

    /**
     * 查询成绩上传历史（返回当前学生所有已导入的成绩记录）
     */
    protected void uploadHistories(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PrintWriter out = resp.getWriter();
        HttpSession session = req.getSession(false);
        if (session == null) {
            out.write(JSON.toJSONString(Result.fail(401, "未登录")));
            return;
        }
        Account currentAccount = (Account) session.getAttribute("loginAccount");
        if (currentAccount == null) {
            out.write(JSON.toJSONString(Result.fail(401, "未登录")));
            return;
        }
        StudentInfo studentInfo = studentInfoService.getStudentInfoById(currentAccount.getId());
        if (studentInfo == null) {
            out.write(JSON.toJSONString(Result.fail(404, "学生信息不存在")));
            return;
        }
        List<File> historyList = studentScoreService.queryFileHistoryByStudentInfoId(studentInfo.getId());
        out.write(JSON.toJSONString(Result.success(historyList)));
    }

    /**
     * 兼容旧接口路径。
     */
    protected void importExcel(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        importConfirm(req, resp);
    }

    /**
     * 确认导入成绩，并在写入成功后生成当前学期规则统计总结。
     */
    protected void importConfirm(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        PrintWriter out = resp.getWriter();
        HttpSession session = req.getSession(false);
        if (session == null) {
            out.write(JSON.toJSONString(Result.fail(401, "未登录")));
            return;
        }
        Account currentAccount = (Account) session.getAttribute("loginAccount");
        if (currentAccount == null) {
            out.write(JSON.toJSONString(Result.fail(401, "未登录")));
            return;
        }
        StudentInfo studentInfo = studentInfoService.getStudentInfoById(currentAccount.getId());
        if (studentInfo == null) {
            out.write(JSON.toJSONString(Result.fail(404, "查找失败")));
            return;
        }
        @SuppressWarnings("unchecked")
        List<StudentScore> scoreList = (List<StudentScore>) session.getAttribute("scoreList");
        if (scoreList == null || scoreList.isEmpty()) {
            out.write(JSON.toJSONString(Result.fail(400, "文件为空")));
            return;
        }
        int courseSum = scoreList.size();
        StudentScore firstScore = scoreList.get(0);
        String schoolYear = firstScore.getSchoolYear();
        String term = firstScore.getTerm();

        for (StudentScore studentScore : scoreList) {
            studentScore.setStudentInfoId(studentInfo.getId());
        }

        // saveScoreList 内部使用显式事务，成功返回前已经提交成绩数据。
        int rows = studentScoreService.saveScoreList(scoreList);
        if (rows > 0) {
            // 成绩提交成功后，再读取该学生本学期全部成绩，避免只统计本次新增课程。
            List<StudentScore> allTermScores = studentScoreService.queryByStudentAndTerm(schoolYear, term, studentInfo.getId());
            String summaryText = scoreSummaryService.generateSingleSummary(allTermScores);
            if (summaryText == null) {
                log.warn("成绩导入成功，但单学期学业总结生成失败，studentInfoId={}, schoolYear={}, term={}", studentInfo.getId(), schoolYear, term);
            }
            String fileName = (String) session.getAttribute("fileName");
            try {
                studentScoreService.saveFileHistory(fileName, courseSum, 1, studentInfo.getId());
            } catch (Exception err) {
                log.error("保存上传历史失败，fileName={}", fileName, err);
            }
            out.write(JSON.toJSONString(Result.success(null)));
        } else {
            out.write(JSON.toJSONString(Result.fail(400, "更新成绩失败 ，请重新上传！")));
        }
        session.removeAttribute("scoreList");
        session.removeAttribute("fileName");
    }

    /**
     * 查询学生全部学期，用于学业智能分析趋势图。
     */
    protected void getAllTermList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PrintWriter out = resp.getWriter();
        StudentInfo studentInfo = getCurrentStudentInfo(req, resp);
        if (studentInfo == null) return;
        out.write(JSON.toJSONString(Result.success(studentScoreService.queryTermListByStudentInfoId(studentInfo.getId()))));
    }

    /**
     * 查询学生全部学期成绩，用于趋势图和全学期规则统计总结。
     */
    protected void getAllScore(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PrintWriter out = resp.getWriter();
        StudentInfo studentInfo = getCurrentStudentInfo(req, resp);
        if (studentInfo == null) return;
        out.write(JSON.toJSONString(Result.success(studentScoreService.queryAllByStudentInfoId(studentInfo.getId()))));
    }

    /**
     * 按学期查询成绩，支持 term=2025-2026-秋。
     */
    protected void getScoreByTerm(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        PrintWriter out = resp.getWriter();
        StudentInfo studentInfo = getCurrentStudentInfo(req, resp);
        if (studentInfo == null) return;

        TermInfo termInfo = parseTerm(req.getParameter("term"), req.getParameter("schoolYear"));
        if (termInfo == null) {
            out.write(JSON.toJSONString(Result.fail(400, "学期参数错误")));
            return;
        }

        List<StudentScore> scores = studentScoreService.queryByStudentAndTerm(termInfo.schoolYear(), termInfo.term(), studentInfo.getId());
        out.write(JSON.toJSONString(Result.success(scores)));
    }

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

    private TermInfo parseTerm(String rawTerm, String rawSchoolYear) {
        if (rawTerm == null || rawTerm.isBlank()) return null;
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
        if (schoolYear == null || schoolYear.isBlank()) return null;
        return new TermInfo(schoolYear, term);
    }

    private record TermInfo(String schoolYear, String term) {
    }

    /**
     * 返回用户成绩单
     */
    public void scoreList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ServletInputStream inputStream = req.getInputStream();
        PrintWriter out = resp.getWriter();
        JSONObject json = JSON.parseObject(inputStream, JSONObject.class);
        String schoolYear = json.getString("schoolYear");
        String term = json.getString("term");
        String courseName = json.getString("courseName");

        HttpSession session = req.getSession(false);
        Account currentAccount = null;
        if (session != null) {
            currentAccount = (Account) session.getAttribute("loginAccount");
        }
        if (currentAccount == null) {
            Result<List<StudentScore>> result = Result.fail(401, "未登录");
            out.write(JSON.toJSONString(result));
            return;
        }

        int accountId = currentAccount.getId();
        StudentInfo info = studentInfoService.getStudentInfoById(accountId);
        if (info == null) {
            Result<List<StudentScore>> result = Result.fail(404, "查找失败");
            out.write(JSON.toJSONString(result));
            return;
        }
        int studentInfoId = info.getId();
        List<StudentScore> studentScores = studentScoreService.queryByCondition(schoolYear, term, courseName, studentInfoId);
        Result<List<StudentScore>> result = Result.success(studentScores);
        out.write(JSON.toJSONString(result));
    }

}
