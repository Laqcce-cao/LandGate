package com.landgate.trigger.http.auth;

import com.landgate.api.auth.dto.AuthDTOs.CreateApiKeyRequest;
import com.landgate.api.auth.dto.AuthDTOs.LoginRequest;
import com.landgate.api.auth.dto.AuthDTOs.RegisterRequest;
import com.landgate.domain.auth.model.entity.ApiKeyEntity;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.auth.service.AuthDomainService;
import com.landgate.infrastructure.security.jwt.JwtUtils;
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

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        log.info("Register request: email={}", req.email());
        UserEntity user = authDomainService.register(req.email(), req.password());
        String accessToken = jwtUtils.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name(), user.getTokenVersion());
        String refreshToken = jwtUtils.generateRefreshToken(user.getId(), UUID.randomUUID().toString());
        log.debug("Register success: user_id={}", user.getId());
        return ResponseEntity.ok(Map.of(
                "access_token", accessToken,
                "refresh_token", refreshToken,
                "user", toUserMap(user)
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        log.info("Login request: email={}", req.email());
        UserEntity user = authDomainService.login(req.email(), req.password());
        String accessToken = jwtUtils.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name(), user.getTokenVersion());
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

    // ==================== 工具方法 ====================

    private Map<String, Object> toUserMap(UserEntity u) {
        return Map.of(
                "id", u.getId(),
                "email", u.getEmail(),
                "username", u.getUsername(),
                "role", u.getRole().name(),
                "status", u.getStatus().name(),
                "balance", u.getBalance(),
                "concurrency", u.getConcurrency()
        );
    }
}
