package servlet;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import pojo.Account;
import pojo.Result;
import service.FeedbackService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;

@Slf4j
@WebServlet("/api/feedback/my")
public class MyFeedbackServlet extends BaseServlet {

    private static final FeedbackService feedbackService = new FeedbackService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"GET".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            write(resp, Result.fail(405, "请求方法不允许"));
            return;
        }
        Account account = currentAccount(req, resp);
        if (account == null) {
            return;
        }
        try {
            List<?> feedbackList = feedbackService.listByAccount(account.getId());
            write(resp, Result.success(feedbackList));
        } catch (Exception err) {
            log.error("查询我的意见反馈异常，accountId={}", account.getId(), err);
            write(resp, Result.fail(500, "意见反馈查询失败"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        write(resp, Result.fail(405, "请求方法不允许"));
    }

    private Account currentAccount(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Account account = session == null ? null : (Account) session.getAttribute("loginAccount");
        if (account == null || account.getId() == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            write(resp, Result.fail(401, "未登录或登录已过期"));
            return null;
        }
        return account;
    }

    private void write(HttpServletResponse resp, Result<?> result) throws IOException {
        resp.setContentType("application/json;charset=utf-8");
        resp.setCharacterEncoding("utf-8");
        resp.getWriter().write(JSON.toJSONString(result));
    }
}