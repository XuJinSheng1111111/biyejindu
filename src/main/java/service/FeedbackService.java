package service;

import lombok.extern.slf4j.Slf4j;
import mapper.FeedbackMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.Account;
import pojo.Feedback;
import util.AuthConfig;
import util.MailService;
import util.SqlSessionFactoryUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
public class FeedbackService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();
    private static final Set<String> ALLOWED_TYPES = Set.of("bug", "suggest", "consult");
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_CONTACT_LENGTH = 100;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Long submit(Account account, Feedback input) {
        Feedback feedback = normalize(account, input);

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            FeedbackMapper mapper = sqlSession.getMapper(FeedbackMapper.class);
            mapper.insert(feedback);
            sqlSession.commit();
        } catch (Exception err) {
            log.error("提交意见反馈失败，accountId={}", account.getId(), err);
            throw new RuntimeException("意见反馈提交失败", err);
        }

        notifyAdmin(account, feedback);
        return feedback.getId();
    }

    public List<Feedback> listByAccount(Integer accountId) {
        requireAccountId(accountId);
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            return sqlSession.getMapper(FeedbackMapper.class).selectByAccountId(accountId);
        } catch (Exception err) {
            log.error("查询用户意见反馈失败，accountId={}", accountId, err);
            throw new RuntimeException("意见反馈查询失败", err);
        }
    }

    private Feedback normalize(Account account, Feedback input) {
        if (account == null) {
            throw new IllegalArgumentException("登录信息不能为空");
        }
        requireAccountId(account.getId());
        if (input == null) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }

        String type = input.getType() == null ? "" : input.getType().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("反馈类型不合法");
        }

        String content = input.getContent() == null ? "" : input.getContent().trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("反馈内容不能超过" + MAX_CONTENT_LENGTH + "字");
        }

        String contact = input.getContact() == null ? "" : input.getContact().trim();
        if (contact.length() > MAX_CONTACT_LENGTH) {
            throw new IllegalArgumentException("联系方式不能超过" + MAX_CONTACT_LENGTH + "字");
        }

        Feedback feedback = new Feedback();
        feedback.setAccountId(account.getId());
        feedback.setType(type);
        feedback.setContent(content);
        feedback.setContact(contact.isEmpty() ? null : contact);
        feedback.setStatus(0);
        return feedback;
    }

    private void notifyAdmin(Account account, Feedback feedback) {
        String adminEmail = AuthConfig.get("FEEDBACK_ADMIN_EMAIL", AuthConfig.get("mail.user", ""));
        if (adminEmail.isBlank()) {
            log.warn("未配置反馈通知邮箱，feedbackId={}", feedback.getId());
            return;
        }

        String subject = "【SIAMS】新的用户反馈 - " + typeLabel(feedback.getType());
        String content = ""
                + "<div style=\"font-family:Microsoft YaHei,Arial,sans-serif;color:#303133;line-height:1.8;\">"
                + "<h2 style=\"color:#409eff;margin:0 0 18px;\">收到新的用户反馈</h2>"
                + "<p><b>反馈编号：</b>" + feedback.getId() + "</p>"
                + "<p><b>用户账号：</b>" + escapeHtml(account.getUsername())
                + "（ID：" + account.getId() + "）</p>"
                + "<p><b>反馈类型：</b>" + typeLabel(feedback.getType()) + "</p>"
                + "<p><b>联系方式：</b>" + escapeHtml(feedback.getContact()) + "</p>"
                + "<p><b>提交时间：</b>"
                + LocalDateTime.now().format(DATE_TIME_FORMATTER) + "</p>"
                + "<div style=\"background:#f5f7fa;border-left:4px solid #409eff;"
                + "padding:14px 16px;margin:18px 0;\">"
                + escapeHtml(feedback.getContent()).replace("\n", "<br/>")
                + "</div>"
                + "<p style=\"color:#909399;font-size:13px;\">请登录后台查看并处理该反馈。</p>"
                + "</div>";

        try {
            MailService.sendHtml(adminEmail, subject, content);
        } catch (Exception err) {
            log.error("发送反馈通知邮件失败，feedbackId={}, reason={}", feedback.getId(), MailService.resolveErrorMessage(err), err);
        }
    }

    private String typeLabel(String type) {
        if ("bug".equals(type)) {
            return "问题反馈";
        }
        if ("consult".equals(type)) {
            return "使用咨询";
        }
        return "功能建议";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void requireAccountId(Integer accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("账号信息不能为空");
        }
    }
}