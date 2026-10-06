package service;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import mapper.EmailVerifyCodeMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import pojo.EmailVerifyCode;
import util.MailService;
import util.SqlSessionFactoryUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class EmailCodeService {

    private static final SqlSessionFactory sqlSessionFactory = SqlSessionFactoryUtils.getSqlSessionFactory();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long CODE_EXPIRE_MINUTES = 5;
    private static final long SEND_INTERVAL_SECONDS = 60;
    private static final int MAX_DAILY_SEND = 10;

    public void sendCode(String email) {
        EmailVerifyCode latest;
        int todayCount;
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            EmailVerifyCodeMapper mapper = sqlSession.getMapper(EmailVerifyCodeMapper.class);
            todayCount = mapper.countTodayByEmail(email);
            latest = mapper.selectLatestByEmail(email);
        }

        if (todayCount >= MAX_DAILY_SEND) {
            throw new RateLimitException("该邮箱今日验证码发送次数已达上限");
        }

        if (latest != null && latest.getCreateTime() != null) {
            LocalDateTime lastSend = LocalDateTime.parse(latest.getCreateTime(), FORMATTER);
            if (lastSend.plusSeconds(SEND_INTERVAL_SECONDS).isAfter(LocalDateTime.now())) {
                long remain = java.time.Duration.between(LocalDateTime.now(), lastSend.plusSeconds(SEND_INTERVAL_SECONDS)).getSeconds();
                throw new RateLimitException("发送过于频繁，请" + Math.max(remain, 1) + "秒后再试");
            }
        }

        String code = RandomUtil.randomNumbers(6);
        EmailVerifyCode verifyCode = new EmailVerifyCode();
        verifyCode.setEmail(email);
        verifyCode.setCode(code);
        verifyCode.setExpireTime(LocalDateTime.now().plusMinutes(CODE_EXPIRE_MINUTES).format(FORMATTER));

        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            EmailVerifyCodeMapper mapper = sqlSession.getMapper(EmailVerifyCodeMapper.class);
            mapper.disableOldCode(email);
            mapper.insert(verifyCode);

            try {
                sendMail(email, code);
            } catch (Exception err) {
                sqlSession.rollback();
                throw new MailDeliveryException(MailService.resolveErrorMessage(err), err);
            }
            sqlSession.commit();
        }
    }

    public boolean verifyCode(String email, String code) {
        if (email == null || code == null || code.isBlank()) {
            return false;
        }

        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            EmailVerifyCodeMapper mapper = sqlSession.getMapper(EmailVerifyCodeMapper.class);
            EmailVerifyCode latest = mapper.selectLatestByEmail(email);
            if (latest == null || !Integer.valueOf(0).equals(latest.getUsed())) return false;
            if (!code.trim().equals(latest.getCode())) return false;
            if (LocalDateTime.parse(latest.getExpireTime(), FORMATTER).isBefore(LocalDateTime.now())) return false;
            mapper.markUsed(latest.getId());
            return true;
        } catch (Exception err) {
            log.error("校验邮箱验证码失败，email={}", email, err);
            return false;
        }
    }

public void invalidateCode(String email) {
        if (email == null || email.isBlank()) return;
        try (SqlSession sqlSession = sqlSessionFactory.openSession(true)) {
            sqlSession.getMapper(EmailVerifyCodeMapper.class).disableOldCode(email);
        }
    }

    private void sendMail(String email, String code) {
        String content =
                "<div style=\"background:#f5f7fa;padding:30px 0;font-family:微软雅黑,Microsoft YaHei,Arial,sans-serif;\">" +
                        "<div style=\"max-width:600px;margin:0 auto;background:#ffffff;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,0.08);padding:40px 30px;\">" +
                        "<h2 style=\"color:#2c3e50;font-size:22px;margin:0 0 24px 0;text-align:center;\">学生智能学业管理系统</h2>" +
                        "<p style=\"color:#34495e;font-size:16px;line-height:1.6;margin:0 0 20px 0;\">您好，</p>" +
                        "<p style=\"color:#34495e;font-size:16px;line-height:1.6;margin:0 0 20px 0;\">您正在进行邮箱注册验证，验证码如下：</p>" +

                        "<div style=\"background:#e8f3ff;border-radius:6px;padding:24px;text-align:center;margin:24px 0;\">" +
                        "<span style=\"font-size:32px;font-weight:bold;color:#1976d2;letter-spacing:6px;\">" + code + "</span>" +
                        "</div>" +

                        "<p style=\"color:#606266;font-size:14px;line-height:1.6;margin:0 0 10px 0;\">⏱ 验证码 <b>5 分钟</b> 内有效，过期请重新获取。</p>" +
                        "<p style=\"color:#606266;font-size:14px;line-height:1.6;margin:0 0 30px 0;\">⚠️ 请勿向任何人泄露验证码，谨防账号被盗。</p>" +

                        "<div style=\"border-top:1px solid #ebeef5;padding-top:20px;margin-top:20px;\">" +
                        "<p style=\"color:#909399;font-size:13px;line-height:1.6;margin:0 0 8px 0;\">此邮件由系统自动发送，请勿直接回复。</p>" +
                        "<p style=\"color:#909399;font-size:13px;line-height:1.6;margin:0 0 16px 0;\">如有疑问，请致信：<a href=\"mailto:ondog2026@outlook.com\" style=\"color:#1976d2;text-decoration:none;\">ondog2026@outlook.com</a></p>" +
                        "<div style=\"border-top:1px dashed #f0f2f5;padding-top:12px;text-align:center;\">" +
                        "<span style=\"font-size:14px;font-weight:600;color:#2c3e50;letter-spacing:2px;\">SIAMS</span>" +
                        "</div>" +
                        "</div>" +
                        "</div>" +
                        "</div>";

        MailService.sendHtml(email, "SIAMS 注册验证码", content);
    }

    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) {
            super(message);
        }
    }
}
