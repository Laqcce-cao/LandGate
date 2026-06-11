package com.landgate.infrastructure.email;

import com.landgate.domain.auth.adapter.port.IEmailPort;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService implements IEmailPort {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String from;

    public void sendVerificationCode(String to, String username, String code) {
        sendCodeEmail(
                to,
                "邮箱验证码 - LandGate",
                buildVerificationCodeHtml(username, code),
                "verification code"
        );
    }

    public void sendPasswordResetCode(String to, String username, String code) {
        sendCodeEmail(
                to,
                "重置密码验证码 - LandGate",
                buildPasswordResetCodeHtml(username, code),
                "password reset code"
        );
    }

    @Override
    public void sendAlertEmail(String to, String subject, String html) {
        sendCodeEmail(to, subject, html, "alert email");
    }

    private void sendCodeEmail(String to, String subject, String html, String logName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setFrom(from);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("{} sent to: {}", logName, to);
        } catch (MessagingException e) {
            log.error("Failed to send {} to: {}", logName, to, e);
            throw new RuntimeException("Failed to send email", e);
        }
    }

    private String buildVerificationCodeHtml(String username, String code) {
        return """
            <div style="max-width:600px;margin:0 auto;font-family:Arial,sans-serif;">
                <h2>欢迎注册 LandGate，%s！</h2>
                <p>您的邮箱验证码为：</p>
                <p style="font-size:32px;font-weight:bold;letter-spacing:8px;
                   color:#4F46E5;text-align:center;padding:20px;
                   background:#F3F4F6;border-radius:8px;">%s</p>
                <p>验证码有效期为 <b>5分钟</b>，请尽快完成验证。</p>
                <p>如非本人操作，请忽略此邮件。</p>
            </div>
            """.formatted(username, code);
    }

    private String buildPasswordResetCodeHtml(String username, String code) {
        return """
            <div style="max-width:600px;margin:0 auto;font-family:Arial,sans-serif;">
                <h2>LandGate 密码重置，%s</h2>
                <p>您的重置密码验证码为：</p>
                <p style="font-size:32px;font-weight:bold;letter-spacing:8px;
                   color:#4F46E5;text-align:center;padding:20px;
                   background:#F3F4F6;border-radius:8px;">%s</p>
                <p>验证码有效期为 <b>5分钟</b>，请尽快完成密码重置。</p>
                <p>如非本人操作，请忽略此邮件，您的密码不会被修改。</p>
            </div>
            """.formatted(username, code);
    }
}
