package com.landgate.domain.auth.adapter.port;

/**
 * 验证码服务端口 —— 领域层定义的验证码生成与校验契约。
 * 由基础设施层的 VerificationCodeService 实现。
 */
public interface IVerificationCodePort {

    /** 邮箱验证场景：注册后验证邮箱归属。 */
    String PURPOSE_VERIFY_EMAIL = "verify_email";

    /** 重置密码场景：忘记密码后校验邮箱归属。 */
    String PURPOSE_RESET_PASSWORD = "reset_password";

    /**
     * 生成指定业务场景的 6 位邮箱验证码。
     */
    String generateCode(String email, String purpose);

    /**
     * 校验指定业务场景的邮箱验证码，校验成功后验证码会失效。
     */
    boolean validateCode(String email, String code, String purpose);
}
