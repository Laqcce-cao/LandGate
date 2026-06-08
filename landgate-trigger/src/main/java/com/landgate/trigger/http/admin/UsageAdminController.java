package com.landgate.trigger.http.admin;

import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        LocalDate startDate = parseDate(start);
        LocalDate endDate = parseInclusiveEndDate(end);
        var logs = usageLogRepository.findByFilters(null, null, null, startDate, endDate, page, size);
        long total = usageLogRepository.countByFilters(null, null, null, startDate, endDate);
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
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String start,
                                         @RequestParam(required = false) String end) {
        LocalDate startDate = parseDate(start);
        LocalDate endDate = parseInclusiveEndDate(end);
        var logs = usageLogRepository.findByFilters(userId, null, null, startDate, endDate, page, size);
        long total = usageLogRepository.countByFilters(userId, null, null, startDate, endDate);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "user_id", userId,
                "page", page,
                "size", size
        ));
    }

    @GetMapping("/key/{apiKeyId}")
    public ResponseEntity<?> listByApiKey(@PathVariable Long apiKeyId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) String start,
                                           @RequestParam(required = false) String end) {
        LocalDate startDate = parseDate(start);
        LocalDate endDate = parseInclusiveEndDate(end);
        var logs = usageLogRepository.findByFilters(null, apiKeyId, null, startDate, endDate, page, size);
        long total = usageLogRepository.countByFilters(null, apiKeyId, null, startDate, endDate);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "api_key_id", apiKeyId,
                "page", page,
                "size", size
        ));
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<?> listByAccount(@PathVariable Long accountId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String start,
                                            @RequestParam(required = false) String end) {
        LocalDate startDate = parseDate(start);
        LocalDate endDate = parseInclusiveEndDate(end);
        var logs = usageLogRepository.findByFilters(null, null, accountId, startDate, endDate, page, size);
        long total = usageLogRepository.countByFilters(null, null, accountId, startDate, endDate);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "account_id", accountId,
                "page", page,
                "size", size
        ));
    }

    private static LocalDate parseDate(String value) {
        return value != null && !value.isBlank() ? LocalDate.parse(value) : null;
    }

    private static LocalDate parseInclusiveEndDate(String value) {
        LocalDate date = parseDate(value);
        return date != null ? date.plusDays(1) : null;
    }
}
