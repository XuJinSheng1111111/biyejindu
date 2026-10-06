package servlet;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pojo.Account;
import pojo.Result;
import service.UploadService;
import javax.servlet.http.*;
import java.io.IOException;

public abstract class BaseUploadServlet extends HttpServlet {

    protected static final ObjectMapper mapper = new ObjectMapper();
    protected static final UploadService uploadService = new UploadService();
    protected static final Logger LOGGER = LoggerFactory.getLogger("BaseUploadServlet");


    protected abstract String getSaveFolder();

    protected abstract String getOldUrl(int accountId);

    protected abstract boolean updateDb(int accountId, String newUrl);

    protected void writeJson(HttpServletResponse resp, Result result) throws IOException {
        resp.setContentType("application/json;charset=utf-8");
        mapper.writeValue(resp.getWriter(), result);
    }

    protected Part getUploadPart(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            return req.getPart("file");
        } catch (IllegalStateException e) {
            LOGGER.warn("上传文件超出大小限制", e);
            resp.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            writeJson(resp, Result.fail(413, "上传文件不能超过 5MB"));
            return null;
        } catch (Exception e) {
            LOGGER.error(String.valueOf(e));
            writeJson(resp, Result.fail(400, "获取上传文件失败"));
            return null;
        }
    }

    protected void uploadCore(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Account loginAccount = session == null ? null : (Account) session.getAttribute("loginAccount");
        if (loginAccount == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            writeJson(resp, Result.fail(401, "未登录"));
            return;
        }
        int accountId = loginAccount.getId();
        Part part = getUploadPart(req, resp);
        if (part == null || part.getSize() <= 0) return;
        String realRoot = req.getServletContext().getRealPath("");
        String oldUrl = getOldUrl(accountId);
        String newUrl = uploadService.uploadImage(part, getSaveFolder(), realRoot);
        if (newUrl == null) {
            writeJson(resp, Result.fail(400, "图片格式不合法或非真实图片"));
            return;
        }
        boolean result = updateDb(accountId, newUrl);
        if (result) {
            uploadService.deleteOldFile(oldUrl, getSaveFolder(), realRoot);
            writeJson(resp, Result.success(newUrl));
        } else {
            uploadService.deleteOldFile(newUrl, getSaveFolder(), realRoot);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writeJson(resp, Result.fail(500, "数据库更新失败"));
        }
    }
}
