package com.landgate.trigger.http.user;

import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * 用户用量管理控制器 —— 查询当前用户的 API 调用用量日志。
 * <p>
 * 路由前缀：{@code /api/v1/user/usage}，需要 JWT 认证。
 * 自动从 JWT 中提取当前用户 ID，只返回该用户的日志。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user/usage")
@RequiredArgsConstructor
public class UsageController {

    private final IUsageLogRepository usageLogRepository;

    /**
     * 分页查询当前用户的用量日志列表，支持可选日期范围过滤。
     *
     * @param start 起始日期（可选，格式 yyyy-MM-dd，包含）
     * @param end   结束日期（可选，格式 yyyy-MM-dd，不包含）
     */
    @GetMapping("/my")
    public ResponseEntity<?> myUsage(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error_code", "UNAUTHORIZED",
                    "message", "User not authenticated"
            ));
        }
        LocalDate startDate = (start != null && !start.isBlank()) ? LocalDate.parse(start) : null;
        LocalDate endDate = (end != null && !end.isBlank()) ? LocalDate.parse(end) : null;

        boolean hasDateFilter = startDate != null || endDate != null;
        var logs = hasDateFilter
                ? usageLogRepository.findByUserIdWithDate(userId, page, size, startDate, endDate)
                : usageLogRepository.findByUserId(userId, page, size);
        long total = hasDateFilter
                ? usageLogRepository.countByUserIdWithDate(userId, startDate, endDate)
                : usageLogRepository.countByUserId(userId);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "page", page,
                "size", size
        ));
    }

    /**
     * 按天聚合当前用户的 Token 用量统计（指定日期范围）。
     * <p>
     * 用于前端 Dashboard 页面的 Token 用量趋势图表。
     * 后端直接返回按 DATE(created_at) 分组聚合的结果，
     * 走 {@code idx_usage_logs_user_created} 复合索引，
     * 避免前端拉取大量原始日志后二次聚合。
     *
     * @param start 起始日期（包含，如 2026-05-16）
     * @param end   结束日期（不包含，如 2026-05-23）
     */
    @GetMapping("/my/stats")
    public ResponseEntity<?> myUsageStats(
            HttpServletRequest request,
            @RequestParam String start,
            @RequestParam String end) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error_code", "UNAUTHORIZED",
                    "message", "User not authenticated"
            ));
        }
        LocalDate startDate = LocalDate.parse(start);
        LocalDate endDate = LocalDate.parse(end);
        var stats = usageLogRepository.aggregateByUserAndDate(userId, startDate, endDate);
        return ResponseEntity.ok(stats);
    }
}
