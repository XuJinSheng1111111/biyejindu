package servlet;


import service.StudentInfoService;

import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


@WebServlet("/api/student/cover")
@MultipartConfig(
        maxFileSize = 5 * 1024 * 1024,
        maxRequestSize = 10 * 1024 * 1024
)
public class CoverServlet extends BaseUploadServlet {

    private final static StudentInfoService studentInfoService = new StudentInfoService();

    @Override
    protected String getSaveFolder() {
        return "/upload/cover";
    }

    @Override
    protected String getOldUrl(int accountId) {
        return studentInfoService.getCoverById(accountId);
    }

    @Override
    protected boolean updateDb(int accountId, String newUrl) {
        return studentInfoService.modifyCoverURL(accountId,newUrl);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        uploadCore(req,resp);
    }
}