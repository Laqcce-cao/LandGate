package com.landgate.domain.account.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证加密服务 —— 使用 AES-256-GCM 对上游账号的 API Key 进行加解密。
 * <p>
 * 加密后的密文以 Base64 编码存储到数据库。nonce（12字节随机数）附加在密文头部。
 * 密钥通过配置项 {@code landgate.security.credential-encryption-key} 指定，
 * 未配置时自动生成随机开发密钥并警告。
 */
@Slf4j
@Component
public class CredentialDomainService {

    private static final String AES_GCM_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_NONCE_LENGTH = 12;

    private final SecretKeySpec secretKey;

    public CredentialDomainService(
            @Value("${landgate.security.credential-encryption-key:}") String encryptionKeyHex) {
        if (encryptionKeyHex == null || encryptionKeyHex.isEmpty()) {
            log.warn("Credential encryption key not configured! Generating random dev key.");
            byte[] randomKey = new byte[32];
            new SecureRandom().nextBytes(randomKey);
            this.secretKey = new SecretKeySpec(randomKey, "AES");
        } else {
            byte[] keyBytes = hexToBytes(encryptionKeyHex);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Credential encryption key must be 32 bytes (64 hex chars)");
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("CredentialDomainService initialized with AES-256-GCM");
        }
    }

    /**
     * AES-256-GCM 加密明文。
     *
     * @param plaintext 明文凭证
     * @return Base64 编码的密文（nonce + 密文），空字符串输入返回空字符串
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * AES-256-GCM 解密密文。
     *
     * @param ciphertext Base64 编码的密文
     * @return 明文凭证，空字符串输入返回空字符串
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return "";
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < GCM_NONCE_LENGTH + 1) return "";
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            System.arraycopy(combined, 0, nonce, 0, GCM_NONCE_LENGTH);
            byte[] encrypted = new byte[combined.length - GCM_NONCE_LENGTH];
            System.arraycopy(combined, GCM_NONCE_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(AES_GCM_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
