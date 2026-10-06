package servlet;

import com.alibaba.fastjson2.JSON;
import pojo.Account;
import pojo.Result;
import service.CompetitionService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/competition/*")
public class CompetitionServlet extends BaseServlet {

    private static final CompetitionService competitionService = new CompetitionService();

    protected void list(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (getCurrentAccount(req, resp) == null) return;
        String keyword = trim(req.getParameter("keyword"));
        String compLevel = trim(req.getParameter("compLevel"));
        String compStatus = trim(req.getParameter("compStatus"));
        String year = normalizeNumber(req.getParameter("year"), 4);
        String month = normalizeNumber(req.getParameter("month"), 2);
        int pageNum = parseInt(req.getParameter("pageNum"), 1);
        int pageSize = parseInt(req.getParameter("pageSize"), 10);
        Map<String, Object> data = competitionService.queryPage(keyword, compLevel, compStatus, year, month, pageNum, pageSize);
        resp.getWriter().write(JSON.toJSONString(Result.success(data)));
    }

    private Account getCurrentAccount(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Account account = session == null ? null : (Account) session.getAttribute("loginAccount");
        if (account == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write(JSON.toJSONString(Result.fail(401, "未登录")));
            return null;
        }
        return account;
    }
    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeNumber(String value, int length) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.trim();
        if (text.length() != length || !text.matches("\\d+")) {
            return "";
        }
        return text;
    }
    private int parseInt(String value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}