package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户注册来源枚举。
 * <p>
 * 记录用户首次注册时使用的渠道，用于运营分析和渠道统计。
 */
@Getter
@AllArgsConstructor
public enum SignupSource {

    /** 邮箱注册 —— 通过邮箱+密码直接注册 */
    EMAIL("email", 1, "邮箱注册"),
    /** LinuxDo OAuth —— 通过 LinuxDo 社区 OAuth 登录注册 */
    LINUXDO("linuxdo", 2, "LinuxDo OAuth"),
    /** 微信登录 —— 通过微信公众号/小程序授权登录 */
    WECHAT("wechat", 3, "微信登录"),
    /** OIDC 通用 —— 通过通用 OIDC 协议登录（如 Keycloak、Auth0） */
    OIDC("oidc", 4, "OIDC 通用"),
    /** GitHub OAuth —— 通过 GitHub OAuth App 登录 */
    GITHUB("github", 5, "GitHub OAuth"),
    /** Google OAuth —— 通过 Google OAuth 登录 */
    GOOGLE("google", 6, "Google OAuth");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
