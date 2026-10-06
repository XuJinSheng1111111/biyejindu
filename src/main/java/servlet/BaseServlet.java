package servlet;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import pojo.Result;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Slf4j
public class BaseServlet extends HttpServlet {

    private static final String ACTION_PATTERN = "[A-Za-z][A-Za-z0-9]{0,39}";

    protected boolean requireMethod(HttpServletRequest req, HttpServletResponse resp, String... allowedMethods) throws IOException {
        for (String method : allowedMethods) {
            if (method.equalsIgnoreCase(req.getMethod())) {
                return true;
            }
        }
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        resp.getWriter().write(JSON.toJSONString(Result.fail(405, "请求方法不允许")));
        return false;
    }
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json;charset=utf-8");
        resp.setCharacterEncoding("utf-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.length() <= 1) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write(JSON.toJSONString(Result.fail(400, "请求路径错误")));
            return;
        }
        String name = pathInfo.substring(1);
        if (!name.matches(ACTION_PATTERN)) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.getWriter().write(JSON.toJSONString(Result.fail(404, "请求接口不存在")));
            return;
        }
        Class<? extends BaseServlet> c = this.getClass();
        try {
            Method method = c.getDeclaredMethod(name, HttpServletRequest.class, HttpServletResponse.class);
            method.invoke(this, req, resp);
        } catch (NoSuchMethodException e) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.getWriter().write(JSON.toJSONString(Result.fail(404, "请求接口不存在")));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("请求处理异常", cause);
            if (!resp.isCommitted()) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                resp.getWriter().write(JSON.toJSONString(Result.fail(500, "服务器内部错误，请稍后重试")));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.error("请求分发异常", e);
            if (!resp.isCommitted()) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                resp.getWriter().write(JSON.toJSONString(Result.fail(500, "服务器内部错误，请稍后重试")));
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }
}
