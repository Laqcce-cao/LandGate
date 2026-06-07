package com.landgate.config;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.auth.service.PasswordDomainService;
import com.landgate.types.enums.Role;
import com.landgate.types.enums.SignupSource;
import com.landgate.types.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the initial administrator from environment-backed configuration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final IUserRepository userRepository;
    private final PasswordDomainService passwordService;

    @Value("${landgate.admin.email:}")
    private String adminEmail;

    @Value("${landgate.admin.password:}")
    private String adminPassword;

    @Value("${landgate.admin.username:Administrator}")
    private String adminUsername;

    @Value("${landgate.admin.reset-password:false}")
    private boolean resetPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = trimToNull(adminEmail);
        String password = trimToNull(adminPassword);
        if (email == null && password == null) {
            log.info("Initial admin account is not configured; skip initialization");
            return;
        }
        if (email == null || password == null) {
            log.warn("Initial admin account is partially configured; set both LANDGATE_ADMIN_EMAIL and LANDGATE_ADMIN_PASSWORD");
            return;
        }

        userRepository.findByEmail(email).ifPresentOrElse(
                user -> ensureAdmin(user, password),
                () -> createAdmin(email, password)
        );
    }

    private void createAdmin(String email, String password) {
        String username = trimToNull(adminUsername);
        if (username == null) {
            username = email.contains("@") ? email.substring(0, email.indexOf('@')) : "Administrator";
        }

        UserEntity user = UserEntity.builder()
                .email(email)
                .emailVerified(true)
                .passwordHash(passwordService.hashPassword(password))
                .role(Role.ADMIN.getKey())
                .status(Status.ACTIVE.getKey())
                .username(username)
                .signupSource(SignupSource.EMAIL.getKey())
                .build();
        UserEntity saved = userRepository.save(user);
        log.info("Initial admin account created: id={}, email={}", saved.getId(), saved.getEmail());
    }

    private void ensureAdmin(UserEntity user, String password) {
        boolean changed = false;
        if (!Role.ADMIN.getKey().equals(user.getRole())) {
            user.setRole(Role.ADMIN.getKey());
            changed = true;
        }
        if (!Status.ACTIVE.getKey().equals(user.getStatus())) {
            user.setStatus(Status.ACTIVE.getKey());
            changed = true;
        }
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            user.setEmailVerified(true);
            changed = true;
        }
        if (resetPassword) {
            user.setPasswordHash(passwordService.hashPassword(password));
            user.setTokenVersion(user.getTokenVersion() == null ? 1L : user.getTokenVersion() + 1);
            changed = true;
        }
        if (changed) {
            userRepository.save(user);
            log.info("Initial admin account synchronized: id={}, email={}, reset_password={}",
                    user.getId(), user.getEmail(), resetPassword);
        } else {
            log.info("Initial admin account already exists: id={}, email={}", user.getId(), user.getEmail());
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
