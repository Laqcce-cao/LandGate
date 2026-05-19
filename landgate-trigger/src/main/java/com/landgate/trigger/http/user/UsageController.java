package com.landgate.trigger.http.user;

import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/my")
    public ResponseEntity<?> myUsage(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error_code", "UNAUTHORIZED",
                    "message", "User not authenticated"
            ));
        }
        var logs = usageLogRepository.findByUserId(userId, page, size);
        long total = usageLogRepository.countByUserId(userId);
        return ResponseEntity.ok(Map.of(
                "logs", logs,
                "total", total,
                "page", page,
                "size", size
        ));
    }
}
