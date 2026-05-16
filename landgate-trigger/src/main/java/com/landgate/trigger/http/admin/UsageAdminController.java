package com.landgate.trigger.http.admin;

import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用量管理控制器 —— 查询和管理 API 调用用量日志。
 * <p>
 * 路由前缀：{@code /api/v1/admin/usage}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/usage")
@RequiredArgsConstructor
public class UsageAdminController {

    private final IUsageLogRepository usageLogRepository;

    @GetMapping
    public ResponseEntity<?> listUsage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var logs = usageLogRepository.findAll(page, size);
        long total = usageLogRepository.count();
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "page", page,
                "size", size
        ));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> listByUser(@PathVariable Long userId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        var logs = usageLogRepository.findByUserId(userId, page, size);
        long total = usageLogRepository.countByUserId(userId);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "user_id", userId
        ));
    }

    @GetMapping("/key/{apiKeyId}")
    public ResponseEntity<?> listByApiKey(@PathVariable Long apiKeyId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        var logs = usageLogRepository.findByApiKeyId(apiKeyId, page, size);
        long total = usageLogRepository.countByApiKeyId(apiKeyId);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "api_key_id", apiKeyId
        ));
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<?> listByAccount(@PathVariable Long accountId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        var logs = usageLogRepository.findByAccountId(accountId, page, size);
        long total = usageLogRepository.countByAccountId(accountId);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "account_id", accountId
        ));
    }
}
