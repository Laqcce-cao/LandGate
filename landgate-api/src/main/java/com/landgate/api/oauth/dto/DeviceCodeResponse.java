package com.landgate.api.oauth.dto;

/**
 * 设备码授权响应 —— 包含用户需在 OpenAI 页面输入的 user_code 和验证页面地址。
 *
 * @param deviceAuthId    设备授权 ID，用于后续轮询
 * @param userCode        用户验证码（格式: XXXX-XXXX），用户需在 OpenAI 页面输入
 * @param verificationUri 验证页面地址，前端应在新窗口打开此 URL
 * @param expiresIn       设备码有效期（秒），超时后需重新发起授权
 * @param interval        建议轮询间隔（秒）
 */
public record DeviceCodeResponse(
        String deviceAuthId,
        String userCode,
        String verificationUri,
        int expiresIn,
        int interval
) {}
