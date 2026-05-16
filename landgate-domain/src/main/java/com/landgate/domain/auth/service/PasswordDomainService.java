package com.landgate.domain.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码加密服务 —— 使用 BCrypt 算法对密码进行哈希和校验。
 * <p>
 * BCrypt 自动处理盐值（salt）嵌入哈希结果中，无需额外管理。
 */
@Slf4j
@Component
public class PasswordDomainService {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 对明文密码进行 BCrypt 哈希。
     *
     * @param password 明文密码
     * @return BCrypt 哈希值
     */
    public String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }

    public boolean checkPassword(String password, String hashedPassword) {
        return passwordEncoder.matches(password, hashedPassword);
    }
}
