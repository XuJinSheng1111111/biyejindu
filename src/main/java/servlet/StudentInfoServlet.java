package servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import dto.ProfileDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pojo.Account;
import pojo.Result;
import pojo.StudentInfo;
import service.StudentInfoService;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;


@WebServlet("/api/student/*")
public class StudentInfoServlet extends BaseServlet {

    private final static ObjectMapper mapper = new ObjectMapper();
    private static final StudentInfoService studentInfoService = new StudentInfoService();
    private static final Logger LOGGER = LoggerFactory.getLogger("StudentInfoServlet");
    private static final String MSG_LOGIN_ERR = "未登录";

    /**
     * 处理个人信息返回
     */
    public void userInfo(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            mapper.writeValue(resp.getWriter(), Result.fail(401, MSG_LOGIN_ERR));
            LOGGER.debug("用户信息接口收到未登录请求");
            return;
        }
        Account currentAccount = (Account) session.getAttribute("loginAccount");
        if (currentAccount == null) {
            mapper.writeValue(resp.getWriter(), Result.fail(401, MSG_LOGIN_ERR));
            return;
        }
        int AccountId = currentAccount.getId();
        StudentInfo info = studentInfoService.getStudentInfoById(AccountId);
        mapper.writeValue(resp.getWriter(), Result.success(info));
    }

    /**
     * 处理更新个人信息请求
     */
    public void profile(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!requireMethod(req, resp, "POST", "PUT")) {
            return;
        }
        HttpSession session = req.getSession(false);
        Account currentAccount = session == null ? null : (Account) session.getAttribute("loginAccount");
        if (currentAccount == null) {
            mapper.writeValue(resp.getWriter(), Result.fail(401, MSG_LOGIN_ERR));
            return;
        }
        ProfileDto dto = mapper.readValue(req.getInputStream(), ProfileDto.class);
        dto.setId(currentAccount.getId());
        if (studentInfoService.modifyInfo(dto)) {
            mapper.writeValue(resp.getWriter(), Result.success(null));
        } else {
            mapper.writeValue(resp.getWriter(), Result.fail(404, "更新个人信息失败"));
        }
    }

}
