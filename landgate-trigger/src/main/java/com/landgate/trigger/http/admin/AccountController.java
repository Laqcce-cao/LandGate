package com.landgate.trigger.http.admin;

import com.landgate.api.admin.dto.AdminDTOs.SetSchedulableRequest;
import com.landgate.api.admin.dto.AdminDTOs.UpdateStatusRequest;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.account.service.AccountDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 账号管理控制器 —— 上游 AI 平台账号的 CRUD 管理接口。
 * <p>
 * 路由前缀：{@code /api/v1/admin/accounts}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountDomainService accountDomainService;

    @GetMapping
    public ResponseEntity<?> list() {
        log.debug("List all accounts");
        List<AccountEntity> accounts = accountDomainService.listAll();
        return ResponseEntity.ok(Map.of("accounts", accounts, "total", accounts.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        log.debug("Get account: id={}", id);
        AccountEntity account = accountDomainService.getById(id);
        return ResponseEntity.ok(account);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AccountEntity account) {
        log.info("Create account: name={}, platform={}, type={}",
                account.getName(), account.getPlatform(), account.getType());
        AccountEntity created = accountDomainService.create(account);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody AccountEntity updates) {
        log.info("Update account: id={}, name={}", id, updates.getName());
        AccountEntity updated = accountDomainService.update(id, updates);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        log.info("Delete account: id={}", id);
        accountDomainService.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/platform/{platform}")
    public ResponseEntity<?> listByPlatform(@PathVariable String platform) {
        log.debug("List accounts by platform: {}", platform);
        List<AccountEntity> accounts = accountDomainService.listByPlatform(platform);
        return ResponseEntity.ok(Map.of("accounts", accounts, "total", accounts.size()));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id,
                                          @RequestBody UpdateStatusRequest req) {
        log.info("Update account status: id={}, status={}, error={}", id, req.status(), req.errorMessage());
        accountDomainService.updateStatus(id, req.status(), req.errorMessage());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{id}/schedulable")
    public ResponseEntity<?> setSchedulable(@PathVariable Long id,
                                            @RequestBody SetSchedulableRequest req) {
        log.info("Set account schedulable: id={}, schedulable={}", id, req.schedulable());
        accountDomainService.setSchedulable(id, req.schedulable());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/{id}/credential/{key}")
    public ResponseEntity<?> getCredential(@PathVariable Long id, @PathVariable String key) {
        log.debug("Get credential: account_id={}, key={}", id, key);
        String value = ""; // TODO: credential service
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }
}
