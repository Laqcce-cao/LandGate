package com.landgate.trigger.http.auth;

import com.landgate.api.auth.dto.AuthDTOs.CreateApiKeyRequest;
import com.landgate.api.auth.dto.AuthDTOs.LoginRequest;
import com.landgate.api.auth.dto.AuthDTOs.RegisterRequest;
import com.landgate.domain.auth.model.entity.ApiKeyEntity;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.auth.service.AuthDomainService;
import com.landgate.infrastructure.captcha.CaptchaService;
import com.landgate.infrastructure.ratelimit.RegistrationRateLimiter;
import com.landgate.infrastructure.security.jwt.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 认证控制器 —— 对应 Go 版本 {@code internal/handler/auth_handler.go}。
 * <p>
 * 提供用户注册、登录、个人信息查询、会话吊销和 API Key 管理接口。
 * <p>
 * 路由前缀：{@code /api/v1/auth}
 *
 * @see AuthDomainService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthDomainService authDomainService;
    private final JwtUtils jwtUtils;
    private final CaptchaService captchaService;
    private final RegistrationRateLimiter registrationRateLimiter;

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req,
                                       HttpServletRequest request) {
        log.info("Register request: email={}", req.email());

        // 频率限制 —— 同IP每小时最多注册N次
        registrationRateLimiter.checkRateLimit(request.getRemoteAddr());

        // CAPTCHA 人机验证
        captchaService.verify(req.captchaToken(), request.getRemoteAddr());

        UserEntity user = authDomainService.register(req.email(), req.password(), req.username());
        // 注册后不直接返回token —— 需要先验证邮箱
        log.debug("Register success (pending verification): user_id={}", user.getId());
        return ResponseEntity.ok(Map.of(
                "message", "Registration successful. Please check your email for the verification code.",
                "user", toUserMap(user)
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        log.info("Login request: email={}", req.email());
        UserEntity user = authDomainService.login(req.email(), req.password());
        String accessToken = jwtUtils.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole(), user.getTokenVersion());
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), UUID.randomUUID().toString());
        log.info("Login success: user_id={}, role={}", user.getId(), user.getRole());
        return ResponseEntity.ok(Map.of(
                "access_token", accessToken,
                "refresh_token", refreshToken,
                "user", toUserMap(user)
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestAttribute("user_id") Long userId) {
        log.debug("Get current user: id={}", userId);
        UserEntity user = authDomainService.getCurrentUser(userId);
        return ResponseEntity.ok(toUserMap(user));
    }

    @PostMapping("/revoke-all-sessions")
    public ResponseEntity<?> revokeAllSessions(@RequestAttribute("user_id") Long userId) {
        log.info("Revoke all sessions: user_id={}", userId);
        authDomainService.revokeAllUserTokens(userId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/api-keys")
    public ResponseEntity<?> listApiKeys(@RequestAttribute("user_id") Long userId) {
        log.debug("List API keys: user_id={}", userId);
        List<ApiKeyEntity> keys = authDomainService.listApiKeys(userId);
        return ResponseEntity.ok(keys.stream().map(k -> Map.of(
                "id", k.getId(),
                "key", k.getKey(),
                "name", k.getName(),
                "status", k.getStatus().name(),
                "groupId", k.getGroupId() != null ? k.getGroupId() : "",
                "createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : ""
        )).toList());
    }

    @PostMapping("/api-keys")
    public ResponseEntity<?> createApiKey(@RequestAttribute("user_id") Long userId,
                                          @RequestBody CreateApiKeyRequest req) {
        log.info("Create API key: user_id={}, name={}, group_id={}", userId, req.name(), req.groupId());
        ApiKeyEntity key = authDomainService.createApiKey(userId, req.name(), req.groupId());
        return ResponseEntity.ok(Map.of(
                "id", key.getId(),
                "key", key.getKey(),
                "name", key.getName(),
                "status", key.getStatus().name()
        ));
    }

    @DeleteMapping("/api-keys/{id}")
    public ResponseEntity<?> deleteApiKey(@RequestAttribute("user_id") Long userId,
                                          @PathVariable Long id) {
        log.info("Delete API key: user_id={}, key_id={}", userId, id);
        authDomainService.deleteApiKey(userId, id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "Email is required"
            ));
        }
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "Verification code is required"
            ));
        }
        authDomainService.verifyEmail(email, code);
        return ResponseEntity.ok(Map.of("message", "Email verified successfully"));
    }

    @PostMapping("/resend-verification-code")
    public ResponseEntity<?> resendVerificationCode(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "Email is required"
            ));
        }
        // generateCode 内部会自动检查冷却期，冷却期内抛出 AuthenticationException
        authDomainService.resendVerificationCode(email);
        return ResponseEntity.ok(Map.of("message", "Verification code resent. Please check your email."));
    }

    // ==================== 工具方法 ====================

    private Map<String, Object> toUserMap(UserEntity u) {
        return Map.of(
                "id", u.getId(),
                "email", u.getEmail(),
                "username", u.getUsername(),
                "role", u.getRole(),
                "status", u.getStatus(),
                "emailVerified", u.getEmailVerified(),
                "balance", u.getBalance(),
                "concurrency", u.getConcurrency()
        );
    }
}
