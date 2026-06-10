package com.landgate.domain.auth.adapter.port;

/**
 * 邮件发送端口 —— 领域层定义的邮件发送契约。
 * 由基础设施层的 EmailService 实现。
 */
public interface IEmailPort {

    /** 发送注册邮箱验证验证码。 */
    void sendVerificationCode(String to, String username, String code);

    /** 发送忘记密码重置验证码。 */
    void sendPasswordResetCode(String to, String username, String code);
}
