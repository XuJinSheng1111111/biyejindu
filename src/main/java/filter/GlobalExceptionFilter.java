package filter;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import pojo.Result;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 统一兜底异常处理，避免未捕获异常直接向前端返回容器错误页或堆栈。
 */
@Slf4j
@WebFilter(filterName = "GlobalExceptionFilter", urlPatterns = "/*")
public class GlobalExceptionFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (Exception err) {
            log.error("全局请求异常", err);
            if (!(response instanceof HttpServletResponse resp) || resp.isCommitted()) {
                return;
            }
            resp.reset();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("application/json;charset=UTF-8");
            resp.setHeader("Cache-Control", "no-store");
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.setHeader("X-Frame-Options", "DENY");
            resp.setHeader("Referrer-Policy", "no-referrer");
            resp.getWriter().write(JSON.toJSONString(Result.fail(500, "服务器内部错误，请稍后重试")));
        }
    }

    @Override
    public void destroy() {}
}
