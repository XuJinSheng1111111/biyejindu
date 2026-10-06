package servlet;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import pojo.Account;
import pojo.Feedback;
import pojo.Result;
import service.FeedbackService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.regex.Pattern;

@Slf4j
@WebServlet("/api/feedback/submit")
public class SubmitFeedbackServlet extends BaseServlet {

    private static final FeedbackService feedbackService = new FeedbackService();
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            write(resp, Result.fail(405, "请求方法不允许"));
            return;
        }
        req.setCharacterEncoding("UTF-8");
        Account account = currentAccount(req, resp);
        if (account == null) return;
        try {
            Feedback input = JSON.parseObject(req.getInputStream(), Feedback.class);
            if (input != null && !isValidContact(input.getContact())) {
                write(resp, Result.fail(400, "请输入正确的手机号或邮箱"));
                return;
            }
            Long feedbackId = feedbackService.submit(account, input);
            write(resp, Result.success(feedbackId));
        } catch (IllegalArgumentException err) {
            write(resp, Result.fail(400, err.getMessage()));
        } catch (Exception err) {
            log.error("提交意见反馈异常，accountId={}", account.getId(), err);
            write(resp, Result.fail(500, "意见反馈提交失败"));
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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

    private static boolean isValidContact(String contact) {
        if (contact == null || contact.isBlank()) {
            return true;
        }
        String value = contact.trim();
        return MOBILE_PATTERN.matcher(value).matches() || EMAIL_PATTERN.matcher(value).matches();
    }

    private void write(HttpServletResponse resp, Result<?> result) throws IOException {
        resp.setContentType("application/json;charset=utf-8");
        resp.setCharacterEncoding("utf-8");
        resp.getWriter().write(JSON.toJSONString(result));
    }
}