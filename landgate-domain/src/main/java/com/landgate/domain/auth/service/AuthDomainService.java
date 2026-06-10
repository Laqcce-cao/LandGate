package com.landgate.domain.auth.service;

import com.landgate.api.admin.dto.ApiKeyAdminDTOs.CreateApiKeyAdminRequest;
import com.landgate.api.admin.dto.ApiKeyAdminDTOs.UpdateApiKeyAdminRequest;
import com.landgate.api.auth.dto.AuthDTOs.UpdateApiKeyRequest;
import com.landgate.domain.auth.adapter.port.IEmailPort;
import com.landgate.domain.auth.adapter.port.IVerificationCodePort;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.adapter.repository.IApiKeyRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.auth.model.entity.ApiKeyEntity;
import com.landgate.types.enums.Role;
import com.landgate.types.enums.Status;
import com.landgate.types.enums.SignupSource;
import com.landgate.types.exception.AuthenticationException;
import com.landgate.types.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 认证与授权领域服务 —— 用户注册、登录、API Key 管理。
 * <p>
 * 负责用户身份认证流程，以及 API 密钥的创建、查询和删除。
 * 密码加密委托给 {@link PasswordDomainService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthDomainService {

    private final IUserRepository userRepository;
    private final IApiKeyRepository apiKeyRepository;
    private final PasswordDomainService passwordService;
    private final IVerificationCodePort verificationCodePort;
    private final IEmailPort emailPort;

    /**
     * 用户注册 —— 创建新用户并返回用户实体。
     * 默认角色为 USER，初始余额为 0，并发限制为 5。
     *
     * @param email    邮箱
     * @param password 明文密码
     * @return 新创建的用户实体
     * @throws AuthenticationException 邮箱已存在时抛出
     */
    @Transactional
    public UserEntity register(String email, String password, String username) {
        if (userRepository.existsByEmail(email)) {
            throw new AuthenticationException("Email already registered");
        }
        String displayName = (username != null && !username.isBlank()) ? username.trim() : email.split("@")[0];
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordService.hashPassword(password))
                .username(displayName)
                .role(Role.USER.getKey())
                .status(Status.ACTIVE.getKey())
                .emailVerified(false)
                .signupSource(SignupSource.EMAIL.getKey())
                .balance(BigDecimal.ONE)
                .concurrency(5)
                .tokenVersion(0L)
                .build();
        user = userRepository.save(user);

        // 生成6位数字验证码存入Redis(5分钟TTL)，发送邮件
        String code = verificationCodePort.generateCode(user.getEmail(), IVerificationCodePort.PURPOSE_VERIFY_EMAIL);
        emailPort.sendVerificationCode(user.getEmail(), user.getUsername(), code);

        log.info("User registered (pending verification): id={}, email={}", user.getId(), user.getEmail());
        return user;
    }

    /**
     * 用户登录 —— 校验邮箱和密码，更新最后登录时间。
     *
     * @param email    邮箱
     * @param password 明文密码
     * @return 登录成功的用户实体
     * @throws AuthenticationException 邮箱或密码错误、账号被禁用时抛出
     */
    @Transactional
    public UserEntity login(String email, String password) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationException("Invalid email or password"));
        if (!user.isActive()) throw new AuthenticationException("Account is disabled");
        if (!user.getEmailVerified()) throw new AuthenticationException(
                "Please verify your email first. Check your inbox for the verification code.");
        if (!passwordService.checkPassword(password, user.getPasswordHash()))
            throw new AuthenticationException("Invalid email or password");
        user.setLastLoginAt(Instant.now());
        user.setLastActiveAt(Instant.now());
        userRepository.save(user);
        log.info("User logged in: id={}, email={}", user.getId(), user.getEmail());
        return user;
    }

    /**
     * 验证邮箱 —— 校验验证码并标记邮箱为已验证。
     *
     * @param email 邮箱地址
     * @param code  6位数字验证码
     * @throws AuthenticationException 验证码无效或过期时抛出
     */
    @Transactional
    public void verifyEmail(String email, String code) {
        if (!verificationCodePort.validateCode(email, code, IVerificationCodePort.PURPOSE_VERIFY_EMAIL)) {
            throw new AuthenticationException("Invalid or expired verification code");
        }
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationException("User not found"));
        if (user.getEmailVerified()) {
            return; // already verified, idempotent
        }
        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("Email verified: user_id={}, email={}", user.getId(), user.getEmail());
    }

    /**
     * 重新发送邮箱验证码 —— 冷却期内会抛出异常。
     *
     * @param email 邮箱地址
     * @throws AuthenticationException 冷却期内时抛出
     */
    public void resendVerificationCode(String email) {
        String code = verificationCodePort.generateCode(email, IVerificationCodePort.PURPOSE_VERIFY_EMAIL);
        String username = email.split("@")[0];
        emailPort.sendVerificationCode(email, username, code);
        log.info("Verification code resent to: {}", email);
    }

    /**
     * 申请忘记密码验证码。
     * <p>
     * 为避免泄露邮箱是否已注册，邮箱不存在时直接返回成功，不发送邮件。
     */
    public void requestPasswordResetCode(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            try {
                String code = verificationCodePort.generateCode(user.getEmail(), IVerificationCodePort.PURPOSE_RESET_PASSWORD);
                emailPort.sendPasswordResetCode(user.getEmail(), user.getUsername(), code);
                log.info("Password reset code sent: user_id={}, email={}", user.getId(), user.getEmail());
            } catch (AuthenticationException e) {
                // 忘记密码申请接口必须保持统一成功响应，避免通过冷却期错误枚举已注册邮箱。
                log.info("Password reset code suppressed: user_id={}, email={}, reason={}",
                        user.getId(), user.getEmail(), e.getMessage());
            } catch (Exception e) {
                // 邮件服务异常也不能透出到接口层，否则可通过失败响应推断邮箱是否存在。
                log.error("Password reset code send failed: user_id={}, email={}",
                        user.getId(), user.getEmail(), e);
            }
        });
    }

    /**
     * 通过邮箱验证码重置密码，并吊销旧登录态。
     */
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        if (!verificationCodePort.validateCode(email, code, IVerificationCodePort.PURPOSE_RESET_PASSWORD)) {
            throw new AuthenticationException("Invalid or expired verification code");
        }
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationException("Invalid or expired verification code"));
        user.setPasswordHash(passwordService.hashPassword(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        log.info("Password reset: user_id={}, email={}", user.getId(), user.getEmail());
    }

    /**
     * 吊销用户的所有 JWT Token —— 递增 tokenVersion 使已签发的 Token 全部失效。
     *
     * @param userId 用户 ID
     */
    @Transactional
    public void revokeAllUserTokens(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setTokenVersion(user.getTokenVersion() + 1);
            userRepository.save(user);
            log.info("All tokens revoked for user: id={}", userId);
        });
    }

    /**
     * 获取当前用户信息。
     *
     * @param userId 用户 ID
     * @return 用户实体
     * @throws AuthenticationException 用户不存在时抛出
     */
    public UserEntity getCurrentUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException("User not found"));
    }

    /**
     * 修改密码 —— 验证旧密码后更新为新密码。
     *
     * @param userId      用户 ID
     * @param oldPassword 旧密码（明文）
     * @param newPassword 新密码（明文）
     * @throws AuthenticationException 旧密码错误时抛出
     */
    @Transactional
    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        UserEntity user = getCurrentUser(userId);
        if (!passwordService.checkPassword(oldPassword, user.getPasswordHash())) {
            throw new AuthenticationException("Current password is incorrect");
        }
        user.setPasswordHash(passwordService.hashPassword(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1); // 使旧 token 失效
        userRepository.save(user);
        log.info("Password updated: user_id={}", userId);
    }

    /**
     * 修改用户名 —— 昵称不为空时更新。
     *
     * @param userId   用户 ID
     * @param username 新昵称
     * @throws AuthenticationException 昵称为空时抛出
     */
    @Transactional
    public void updateUsername(Long userId, String username) {
        if (username == null || username.isBlank()) {
            throw new AuthenticationException("Username cannot be empty");
        }
        if (username.length() > 100) {
            throw new AuthenticationException("Username must be at most 100 characters");
        }
        UserEntity user = getCurrentUser(userId);
        user.setUsername(username.trim());
        userRepository.save(user);
        log.info("Username updated: user_id={}, username={}", userId, username);
    }

    /**
     * 创建 API Key —— 生成以 "ak-" 开头的随机密钥。
     *
     * @param userId  用户 ID
     * @param name    API Key 名称
     * @param groupId 关联分组 ID（可选）
     * @return 新创建的 API Key 实体
     */
    @Transactional
    public ApiKeyEntity createApiKey(Long userId, String name, Long groupId, BigDecimal quota) {
        UserEntity user = getCurrentUser(userId);
        String keyStr = "ak-" + generateSecureToken(32);
        ApiKeyEntity.ApiKeyEntityBuilder builder = ApiKeyEntity.builder()
                .userId(user.getId()).key(keyStr).name(name).groupId(groupId);
        if (quota != null && quota.compareTo(BigDecimal.ZERO) > 0) {
            builder.quota(quota);
        }
        ApiKeyEntity apiKey = apiKeyRepository.save(builder.build());
        log.info("API key created: id={}, user_id={}, name={}, quota={}", apiKey.getId(), userId, name, quota);
        return apiKey;
    }

    /**
     * 查询用户的所有 API Key。
     *
     * @param userId 用户 ID
     * @return API Key 列表
     */
    public List<ApiKeyEntity> listApiKeys(Long userId) {
        return apiKeyRepository.findByUserId(userId);
    }

    /**
     * 删除用户指定的 API Key。
     *
     * @param userId   用户 ID
     * @param apiKeyId API Key ID
     * @throws AuthenticationException API Key 不存在或不属于该用户时抛出
     */
    @Transactional
    public void deleteApiKey(Long userId, Long apiKeyId) {
        ApiKeyEntity key = apiKeyRepository.findByUserId(userId).stream()
                .filter(k -> k.getId().equals(apiKeyId))
                .findFirst()
                .orElseThrow(() -> new AuthenticationException("API key not found"));
        apiKeyRepository.deleteById(apiKeyId);
        log.info("API key deleted: id={}, user_id={}", apiKeyId, userId);
    }

    /**
     * 用户更新自己的 API Key —— 按非 null 字段部分更新（name、groupId、quota）。
     *
     * @param userId   用户 ID
     * @param apiKeyId API Key ID
     * @param req      更新请求（仅非 null 字段会更新）
     * @return 更新后的 API Key 实体
     * @throws AuthenticationException API Key 不存在或不属于该用户时抛出
     */
    @Transactional
    public ApiKeyEntity updateApiKey(Long userId, Long apiKeyId, UpdateApiKeyRequest req) {
        ApiKeyEntity key = apiKeyRepository.findById(apiKeyId)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new AuthenticationException("API key not found"));

        if (req.name() != null) key.setName(req.name());
        if (req.groupId() != null) key.setGroupId(req.groupId());
        if (req.quota() != null) key.setQuota(req.quota());

        ApiKeyEntity updated = apiKeyRepository.save(key);
        log.info("API key updated: id={}, user_id={}", apiKeyId, userId);
        return updated;
    }

    /**
     * 管理员创建 API Key —— 支持设置完整字段（配额、速率限制、IP 黑白名单等）。
     *
     * @param userId 管理员用户 ID
     * @param req    创建请求（含完整字段）
     * @return 新创建的 API Key 实体
     */
    @Transactional
    public ApiKeyEntity createApiKeyAdmin(Long userId, CreateApiKeyAdminRequest req) {
        UserEntity user = getCurrentUser(userId);
        String keyStr = "ak-" + generateSecureToken(32);
        ApiKeyEntity.ApiKeyEntityBuilder builder = ApiKeyEntity.builder()
                .userId(user.getId()).key(keyStr).name(req.name()).groupId(req.groupId());

        if (req.status() != null) {
            builder.status(Status.valueOf(req.status().toUpperCase()));
        }
        if (req.quota() != null) builder.quota(req.quota());
        if (req.ipWhitelist() != null) builder.ipWhitelist(req.ipWhitelist());
        if (req.ipBlacklist() != null) builder.ipBlacklist(req.ipBlacklist());
        if (req.expiresAt() != null) builder.expiresAt(req.expiresAt());

        ApiKeyEntity apiKey = apiKeyRepository.save(builder.build());
        log.info("Admin API key created: id={}, user_id={}, name={}", apiKey.getId(), userId, req.name());
        return apiKey;
    }

    /**
     * 管理员更新 API Key —— 按非 null 字段部分更新。
     *
     * @param userId    管理员用户 ID
     * @param apiKeyId  API Key ID
     * @param req       更新请求（仅非 null 字段会更新）
     * @throws AuthenticationException API Key 不存在或不属于该用户时抛出
     */
    @Transactional
    public ApiKeyEntity updateApiKey(Long userId, Long apiKeyId, UpdateApiKeyAdminRequest req) {
        ApiKeyEntity key = apiKeyRepository.findById(apiKeyId)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(() -> new AuthenticationException("API key not found"));

        if (req.name() != null) key.setName(req.name());
        if (req.groupId() != null) key.setGroupId(req.groupId());
        if (req.status() != null) key.setStatus(Status.valueOf(req.status().toUpperCase()));
        if (req.quota() != null) key.setQuota(req.quota());
        if (req.ipWhitelist() != null) key.setIpWhitelist(req.ipWhitelist());
        if (req.ipBlacklist() != null) key.setIpBlacklist(req.ipBlacklist());
        if (req.expiresAt() != null) key.setExpiresAt(req.expiresAt());

        ApiKeyEntity updated = apiKeyRepository.save(key);
        log.info("Admin API key updated: id={}, user_id={}", apiKeyId, userId);
        return updated;
    }

    private String generateSecureToken(int byteLength) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
