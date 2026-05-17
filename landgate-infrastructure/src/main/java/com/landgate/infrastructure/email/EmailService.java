package com.landgate.infrastructure.email;

import com.landgate.domain.auth.adapter.port.IEmailPort;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService implements IEmailPort {

    private final JavaMailSender mailSender;

    public void sendVerificationCode(String to, String username, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject("邮箱验证码 - LandGate");
            String html = buildVerificationCodeHtml(username, code);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Verification code sent to: {}", to);
        } catch (MessagingException e) {
            log.error("Failed to send verification code to: {}", to, e);
            throw new RuntimeException("Failed to send verification email", e);
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
}
