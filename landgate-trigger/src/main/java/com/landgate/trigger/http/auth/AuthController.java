package com.landgate.trigger.http.auth;

import com.landgate.api.auth.dto.AuthDTOs.CreateApiKeyRequest;
import com.landgate.api.auth.dto.AuthDTOs.LoginRequest;
import com.landgate.api.auth.dto.AuthDTOs.RegisterRequest;
import com.landgate.api.auth.dto.AuthDTOs.UpdateApiKeyRequest;
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

import java.math.BigDecimal;
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

    @PostMapping("/password-reset-code")
    public ResponseEntity<?> requestPasswordResetCode(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "Email is required"
            ));
        }
        authDomainService.requestPasswordResetCode(email);
        return ResponseEntity.ok(Map.of("message", "如果该邮箱已注册，重置密码验证码已发送"));
    }

    @PostMapping("/password-reset")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String code = body.get("code");
        String newPassword = body.get("newPassword");
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
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "New password must be between 8 and 128 characters"
            ));
        }
        authDomainService.resetPassword(email, code, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    @PutMapping("/password")
    public ResponseEntity<?> updatePassword(@RequestAttribute("user_id") Long userId,
                                            @RequestBody Map<String, String> body) {
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || oldPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "Current password is required"
            ));
        }
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "New password must be between 8 and 128 characters"
            ));
        }
        authDomainService.updatePassword(userId, oldPassword, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    @PutMapping("/username")
    public ResponseEntity<?> updateUsername(@RequestAttribute("user_id") Long userId,
                                            @RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "VALIDATION_ERROR",
                    "message", "Username cannot be empty"
            ));
        }
        authDomainService.updateUsername(userId, username);
        return ResponseEntity.ok(Map.of(
                "message", "Username updated successfully",
                "username", username
        ));
    }

    @GetMapping("/api-keys")
    public ResponseEntity<?> listApiKeys(@RequestAttribute("user_id") Long userId) {
        log.debug("List API keys: user_id={}", userId);
        List<ApiKeyEntity> keys = authDomainService.listApiKeys(userId);
        return ResponseEntity.ok(keys.stream().map(k -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("key", k.getKey());
            m.put("name", k.getName());
            m.put("status", k.getStatus().name());
            m.put("groupId", k.getGroupId() != null ? k.getGroupId() : "");
            m.put("createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : "");
            m.put("lastUsedAt", k.getLastUsedAt() != null ? k.getLastUsedAt().toString() : "");
            m.put("expiresAt", k.getExpiresAt() != null ? k.getExpiresAt().toString() : "");
            m.put("quota", k.getQuota() != null ? k.getQuota() : BigDecimal.ZERO);
            m.put("quotaUsed", k.getQuotaUsed() != null ? k.getQuotaUsed() : BigDecimal.ZERO);
            return m;
        }).toList());
    }

    @PostMapping("/api-keys")
    public ResponseEntity<?> createApiKey(@RequestAttribute("user_id") Long userId,
                                          @RequestBody CreateApiKeyRequest req) {
        log.info("Create API key: user_id={}, name={}, group_id={}, quota={}",
                userId, req.name(), req.groupId(), req.quota());
        ApiKeyEntity key = authDomainService.createApiKey(userId, req.name(), req.groupId(), req.quota());
        return ResponseEntity.ok(Map.of(
                "id", key.getId(),
                "key", key.getKey(),
                "name", key.getName(),
                "status", key.getStatus().name(),
                "quota", key.getQuota() != null ? key.getQuota() : BigDecimal.ZERO,
                "quotaUsed", key.getQuotaUsed() != null ? key.getQuotaUsed() : BigDecimal.ZERO
        ));
    }

    @PutMapping("/api-keys/{id}")
    public ResponseEntity<?> updateApiKey(@RequestAttribute("user_id") Long userId,
                                          @PathVariable Long id,
                                          @RequestBody UpdateApiKeyRequest req) {
        log.info("Update API key: user_id={}, key_id={}", userId, id);
        ApiKeyEntity key = authDomainService.updateApiKey(userId, id, req);
        return ResponseEntity.ok(Map.of(
                "id", key.getId(),
                "key", key.getKey(),
                "name", key.getName(),
                "status", key.getStatus().name(),
                "groupId", key.getGroupId() != null ? key.getGroupId() : "",
                "quota", key.getQuota() != null ? key.getQuota() : BigDecimal.ZERO,
                "quotaUsed", key.getQuotaUsed() != null ? key.getQuotaUsed() : BigDecimal.ZERO
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
