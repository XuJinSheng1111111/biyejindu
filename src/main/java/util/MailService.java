package util;

import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.MailUtil;

import javax.mail.AuthenticationFailedException;
import java.util.Collections;

/**
 * 复用系统现有 SMTP 配置发送 HTML 邮件。
 */
public final class MailService {

    private MailService() {}

    public static void sendHtml(String to, String subject, String content) {
        if (to == null || to.isBlank() || !to.contains("@")) {
            throw new IllegalArgumentException("收件邮箱不能为空且格式必须正确");
        }
        MailUtil.send(buildAccount(), Collections.singletonList(to.trim()), subject, content, true);
    }

    public static String resolveErrorMessage(Throwable err) {
        Throwable current = err;
        while (current != null) {
            if (current instanceof AuthenticationFailedException) {
                return "邮件服务认证失败，请检查邮箱 SMTP 授权码和发件邮箱配置";
            }
            String message = current.getMessage();
            if (message != null && (message.contains("535")
                    || message.contains("Authentication unsuccessful")
                    || message.contains("SmtpClientAuthentication is disabled"))) {
                return "邮件服务认证失败，请检查邮箱 SMTP 授权码和发件邮箱配置";
            }
            if (current instanceof IllegalStateException && message != null) {
                return message;
            }
            current = current.getCause();
        }
        return "邮件发送失败，请检查 mail.host、mail.port 和加密配置";
    }

    private static MailAccount buildAccount() {
        String host = AuthConfig.get("mail.host", "smtp.qq.com");
        int port = Integer.parseInt(AuthConfig.get("mail.port", "465"));
        String user = AuthConfig.get("mail.user", "");
        String password = AuthConfig.get("mail.pass", "");
        String from = AuthConfig.get("mail.from", user);

        if (user.isBlank() || !user.contains("@")) {
            throw new IllegalStateException("mail.user 必须是完整的发件邮箱地址");
        }
        if (password.isBlank()) {
            throw new IllegalStateException("邮件服务未配置，请在 email.properties 中设置 mail.pass");
        }
        if (from.isBlank() || !from.contains("@")) from = user;

        boolean sslEnable = Boolean.parseBoolean(AuthConfig.get("mail.ssl.enable", "true"));
        boolean starttlsEnable = Boolean.parseBoolean(AuthConfig.get("mail.starttls.enable", "false"));

        return new MailAccount()
                .setHost(host)
                .setPort(port)
                .setAuth(true)
                .setSslEnable(sslEnable)
                .setStarttlsEnable(starttlsEnable)
                .setFrom(from)
                .setUser(user)
                .setPass(password);
    }
}