package com.landgate.domain.auth.adapter.port;

/**
 * 邮件发送端口 —— 领域层定义的邮件发送契约。
 * 由基础设施层的 EmailService 实现。
 */
public interface IEmailPort {

    void sendVerificationCode(String to, String username, String code);
}
