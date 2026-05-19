package com.landgate.domain.auth.adapter.port;

/**
 * 验证码服务端口 —— 领域层定义的验证码生成与校验契约。
 * 由基础设施层的 VerificationCodeService 实现。
 */
public interface IVerificationCodePort {

    String generateCode(String email);

    boolean validateCode(String email, String code);
}
