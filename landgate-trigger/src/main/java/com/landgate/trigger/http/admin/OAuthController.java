package com.landgate.trigger.http.admin;

import com.landgate.api.oauth.IOAuthService;
import com.landgate.api.oauth.dto.*;
import com.landgate.types.exception.BadRequestException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * OAuth 管理控制器 —— 提供 OAuth 凭证自动获取的 REST API。
 * <p>
 * 路由前缀：{@code /api/v1/admin/oauth}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final IOAuthService oauthService;

    /**
     * 发起 OAuth 授权 —— 生成授权 URL 返回给前端。
     */
    @PostMapping("/authorize")
    public ResponseEntity<?> authorize(@Valid @RequestBody OAuthAuthorizeRequest request) {
        log.info("OAuth authorize request: platform={}", request.platform());
        OAuthAuthorizeResponse response = oauthService.authorize(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 处理 OAuth 回调 —— 用 authorization_code 换取 token 并创建 Account。
     */
    @PostMapping("/callback")
    public ResponseEntity<?> callback(@Valid @RequestBody OAuthCallbackRequest request) {
        log.info("OAuth callback: state={}", request.state());
        OAuthCallbackResponse response = oauthService.callback(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 手动刷新指定账号的 OAuth Token。
     */
    @PostMapping("/accounts/{id}/refresh")
    public ResponseEntity<?> refreshToken(@PathVariable Long id) {
        log.info("Manual OAuth token refresh: account_id={}", id);
        oauthService.refreshToken(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Token refreshed"));
    }

    /**
     * 发起 Device Code Flow 授权 —— 请求设备码和验证 URL（OpenAI）。
     */
    @PostMapping("/device-code")
    public ResponseEntity<?> initiateDeviceCode(@Valid @RequestBody DeviceCodeRequest request) {
        log.info("Device code initiate: platform={}", request.platform());
        DeviceCodeResponse response = oauthService.initiateDeviceCode(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 轮询 Device Code Flow 授权状态 —— 检查用户是否已完成授权。
     */
    @PostMapping("/device-code/poll")
    public ResponseEntity<?> pollDeviceCode(@Valid @RequestBody DeviceCodePollRequest request) {
        log.info("Device code poll: device_auth_id={}", request.deviceAuthId());
        DeviceCodePollResponse response = oauthService.pollDeviceCode(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 统一处理 BadRequestException。
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<?> handleBadRequest(BadRequestException e) {
        return ResponseEntity.status(400).body(Map.of(
                "error", e.getErrorCode(),
                "message", e.getMessage()
        ));
    }
}
