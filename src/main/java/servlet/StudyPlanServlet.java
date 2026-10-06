package servlet;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import pojo.Account;
import pojo.Result;
import pojo.StudyPlanTask;
import service.StudyPlanService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;

@WebServlet(urlPatterns = {"/api/plan/*", "/api/studyPlan/*"})
public class StudyPlanServlet extends BaseServlet {

    private static final StudyPlanService studyPlanService = new StudyPlanService();

    protected void list(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Account account = currentAccount(req, resp);
        if (account == null) {
            return;
        }
        List<StudyPlanTask> tasks = studyPlanService.listTasks(account.getId());
        resp.getWriter().write(JSON.toJSONString(Result.success(tasks)));
    }

    protected void create(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) {
            return;
        }
        Account account = currentAccount(req, resp);
        if (account == null) {
            return;
        }

        StudyPlanTask input = JSON.parseObject(req.getInputStream(), StudyPlanTask.class);
        StudyPlanTask task = studyPlanService.createTask(account.getId(), input);
        if (task == null) {
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "任务内容不合法")));
            return;
        }
        resp.getWriter().write(JSON.toJSONString(Result.success(task)));
    }

    protected void update(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "PUT", "POST")) return;
        Account account = currentAccount(req, resp);
        if (account == null) return;

        StudyPlanTask input = JSON.parseObject(req.getInputStream(), StudyPlanTask.class);
        if (studyPlanService.updateTask(account.getId(), input)) {
            resp.getWriter().write(JSON.toJSONString(Result.success(null)));
        } else {
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "任务不存在或不可修改")));
        }
    }

    protected void toggle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        Account account = currentAccount(req, resp);
        if (account == null) return;
        JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
        boolean success = studyPlanService.toggleDone(
                account.getId(),
                json.getLong("id"),
                json.getBoolean("done")
        );
        if (success) {
            resp.getWriter().write(JSON.toJSONString(Result.success(null)));
        } else {
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "任务状态更新失败")));
        }
    }

    protected void delete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        Account account = currentAccount(req, resp);
        if (account == null) return;
        JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
        if (studyPlanService.deleteTask(account.getId(), json.getLong("id"))) {
            resp.getWriter().write(JSON.toJSONString(Result.success(null)));
        } else {
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "任务不存在或删除失败")));
        }
    }

    protected void addCompPlan(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST")) return;
        Account account = currentAccount(req, resp);
        if (account == null) return;
        JSONObject json = JSON.parseObject(req.getInputStream(), JSONObject.class);
        Long competitionId = json.getLong("competitionId");
        if (studyPlanService.addCompetitionToPlan(account.getId(), competitionId)) {
            resp.getWriter().write(JSON.toJSONString(Result.success(null)));
        } else {
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "竞赛不存在或添加失败")));
        }
    }

    private Account currentAccount(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Account account = session == null ? null : (Account) session.getAttribute("loginAccount");
        if (account == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write(JSON.toJSONString(Result.fail(401, "未登录")));
            return null;
        }
        return account;
    }
}