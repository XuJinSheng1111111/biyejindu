package servlet;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import pojo.Account;
import pojo.Result;
import pojo.UserSettings;
import service.UserSettingsService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

@Slf4j
@WebServlet("/api/settings/*")
public class UserSettingsServlet extends BaseServlet {

    private static final UserSettingsService userSettingsService = new UserSettingsService();

    protected void get(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "GET")) return;
        Account account = currentAccount(req, resp);
        if (account == null) return;

        try {
            UserSettings settings = userSettingsService.getOrCreate(account.getId());
            write(resp, Result.success(toClientSettings(settings)));
        } catch (Exception err) {
            log.error("获取用户显示设置失败，accountId={}", account.getId(), err);
            write(resp, Result.fail(500, "显示设置读取失败"));
        }
    }

    protected void save(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        Account account = currentAccount(req, resp);
        if (account == null) return;
        try {
            UserSettings input = JSON.parseObject(req.getInputStream(), UserSettings.class);
            UserSettings settings = userSettingsService.save(account.getId(), input);
            write(resp, Result.success(toClientSettings(settings)));
        } catch (IllegalArgumentException err) {
            write(resp, Result.fail(400, err.getMessage()));
        } catch (Exception err) {
            log.error("保存用户显示设置失败，accountId={}", account.getId(), err);
            write(resp, Result.fail(500, "显示设置保存失败"));
        }
    }

    protected void completeGuide(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        Account account = currentAccount(req, resp);
        if (account == null) return;

        try {
            if (!userSettingsService.markGuideCompleted(account.getId())) {
                write(resp, Result.fail(500, "新手指引状态保存失败"));
                return;
            }
            write(resp, Result.success(true));
        } catch (Exception err) {
            log.error("更新新手指引状态失败，accountId={}", account.getId(), err);
            write(resp, Result.fail(500, "新手指引状态保存失败"));
        }
    }
    private UserSettings toClientSettings(UserSettings settings) {
        if (settings == null) {
            return null;
        }
        UserSettings result = new UserSettings();
        result.setTheme(settings.getTheme());
        result.setThemeColor(settings.getThemeColor());
        result.setFontSize(settings.getFontSize());
        result.setSidebarCollapsed(Boolean.TRUE.equals(settings.getSidebarCollapsed()));
        result.setGuideCompleted(Boolean.TRUE.equals(settings.getGuideCompleted()));
        return result;
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