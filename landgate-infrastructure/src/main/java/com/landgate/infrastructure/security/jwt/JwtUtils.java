package com.landgate.infrastructure.security.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 —— 对应 Go 版本 {@code AuthService.GenerateToken / ValidateToken}。
 * <p>
 * 使用 HS256（HMAC-SHA256）签名算法，密钥从配置注入。
 * 支持 access_token（短期）和 refresh_token（长期）的生成和校验。
 * <p>
 * Token 长度限制：最大 8192 字节（防止 DoS 攻击，对应 Go 的 token length check）。
 *
 * @see com.landgate.infrastructure.security.filter.JwtAuthFilter
 */
@Slf4j
@Component
public class JwtUtils {

    /** HS256 签名密钥（从 application.yml 的 landgate.jwt.signing-key 注入）。 */
    private final SecretKey signingKey;

    /** access_token 过期时间（秒），默认 3600（1 小时）。 */
    private final long accessTokenExpireSeconds;

    /** refresh_token 过期时间（秒），默认 604800（7 天）。 */
    private final long refreshTokenExpireSeconds;

    public JwtUtils(
            @Value("${landgate.jwt.signing-key}") String signingKeyStr,
            @Value("${landgate.jwt.access-token-expire-seconds:3600}") long accessTokenExpireSeconds,
            @Value("${landgate.jwt.refresh-token-expire-seconds:604800}") long refreshTokenExpireSeconds) {
        this.signingKey = Keys.hmacShaKeyFor(signingKeyStr.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpireSeconds = accessTokenExpireSeconds;
        this.refreshTokenExpireSeconds = refreshTokenExpireSeconds;
        log.info("JWT initialized: access_ttl={}s, refresh_ttl={}s",
                accessTokenExpireSeconds, refreshTokenExpireSeconds);
    }

    /**
     * 生成 access_token（HS256 JWT）。
     * <p>
     * Payload 包含：
     * <ul>
     *   <li>sub — 用户 ID 字符串</li>
     *   <li>user_id — 用户 ID（Long）</li>
     *   <li>email — 用户邮箱</li>
     *   <li>role — 用户角色（admin/user）</li>
     *   <li>token_version — 令牌版本号（用于吊销检测）</li>
     * </ul>
     *
     * @param userId       用户 ID
     * @param email        用户邮箱
     * @param role         用户角色
     * @param tokenVersion 当前令牌版本号
     * @return HS256 签名的 JWT 字符串
     */
    public String generateAccessToken(Long userId, String email, String role, Long tokenVersion) {
        Date now = new Date();
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("user_id", userId)
                .claim("email", email)
                .claim("role", role)
                .claim("token_version", tokenVersion)
                .issuedAt(now)
                .notBefore(now)
                .expiration(new Date(now.getTime() + accessTokenExpireSeconds * 1000))
                .signWith(signingKey)
                .compact();
        log.debug("Access token generated: user_id={}, expires_in={}s", userId, accessTokenExpireSeconds);
        return token;
    }

    /**
     * 生成 refresh_token。
     * <p>
     * Payload 包含：
     * <ul>
     *   <li>sub — "rt_" + 用户 ID</li>
     *   <li>family_id — 令牌族 ID（用于刷新令牌轮换和重放检测）</li>
     * </ul>
     *
     * @param userId   用户 ID
     * @param familyId 令牌族 ID（UUID，每次全量吊销时重新生成）
     * @return HS256 签名的 JWT 字符串
     */
    public String generateRefreshToken(Long userId, String familyId) {
        Date now = new Date();
        String token = Jwts.builder()
                .subject("rt_" + userId)
                .claim("family_id", familyId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshTokenExpireSeconds * 1000))
                .signWith(signingKey)
                .compact();
        log.debug("Refresh token generated: user_id={}, family_id={}", userId, familyId);
        return token;
    }

    /**
     * 验证并解析 JWT Token。
     * <p>
     * 校验流程：
     * <ol>
     *   <li>检查 Token 长度（最大 8192 字节，防止 DoS）</li>
     *   <li>验证 HS256 签名</li>
     *   <li>解析 Payload Claims</li>
     *   <li>如果 Token 已过期（ExpiredJwtException），仍返回 Claims（用于 Refresh）</li>
     * </ol>
     *
     * @param token JWT 字符串
     * @return JwtClaims 包含 user_id、email、role、token_version
     * @throws JwtException 如果签名无效或格式错误
     */
    public JwtClaims validateToken(String token) {
        if (token == null || token.length() > 8192) {
            log.warn("JWT validation failed: invalid token length: {}", token == null ? 0 : token.length());
            throw new JwtException("invalid token length");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new JwtClaims(
                    claims.get("user_id", Long.class),
                    claims.get("email", String.class),
                    claims.get("role", String.class),
                    claims.get("token_version", Long.class)
            );
        } catch (ExpiredJwtException e) {
            // Token 已过期但签名有效 → 仍返回 Claims，允许 Refresh 操作
            Claims claims = e.getClaims();
            log.debug("JWT expired: user_id={}", claims.get("user_id"));
            return new JwtClaims(
                    claims.get("user_id", Long.class),
                    claims.get("email", String.class),
                    claims.get("role", String.class),
                    claims.get("token_version", Long.class)
            );
        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * JWT Claims 数据类。
     */
    public record JwtClaims(Long userId, String email, String role, Long tokenVersion) {}
}
