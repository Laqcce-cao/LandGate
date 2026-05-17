package com.landgate.types.constant;

/**
 * Redis Key 统一管理 —— 所有 Redis Key 前缀和 Key 构造方法集中定义于此。
 * <p>
 * 避免散落各处的字符串拼接，方便统一修改和全局搜索。
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** 邮箱验证码 */
    public static final String EMAIL_CODE_PREFIX = "email_code:";
    /** 邮箱验证码重发冷却期 */
    public static final String EMAIL_CODE_COOLDOWN_PREFIX = "email_code_cooldown:";
    /** 注册频率限制（按 IP） */
    public static final String REGISTER_RATE_PREFIX = "register_rate:";

    // ==================== Key 构造方法 ====================

    public static String emailCodeKey(String email) {
        return EMAIL_CODE_PREFIX + email;
    }

    public static String emailCodeCooldownKey(String email) {
        return EMAIL_CODE_COOLDOWN_PREFIX + email;
    }

    public static String registerRateKey(String ip) {
        return REGISTER_RATE_PREFIX + ip;
    }
}
