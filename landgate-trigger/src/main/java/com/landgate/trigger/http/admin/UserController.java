package com.landgate.trigger.http.admin;

import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.auth.service.UserDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理控制器 —— 管理员对用户的查询、编辑、状态变更接口。
 * <p>
 * 路由前缀：{@code /api/v1/admin/users}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserDomainService userDomainService;

    /**
     * 分页搜索用户列表。
     *
     * @param page     页码（0-based，默认 0）
     * @param pageSize 每页条数（默认 20）
     * @param search   搜索关键词（匹配用户名或邮箱），可选
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int pageSize,
                                  @RequestParam(required = false) String search) {
        log.debug("List users: page={}, pageSize={}, search={}", page, pageSize, search);
        String keyword = search != null ? search.trim() : "";
        List<UserEntity> users = userDomainService.listBySearch(keyword, page, pageSize);
        long total = userDomainService.countBySearch(keyword);
        return ResponseEntity.ok(Map.of("users", users, "total", total));
    }

    /**
     * 获取单个用户详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        log.debug("Get user: id={}", id);
        UserEntity user = userDomainService.getById(id);
        return ResponseEntity.ok(user);
    }

    /**
     * 更新用户信息（仅更新非空字段）。
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UserEntity updates) {
        log.info("Update user: id={}", id);
        UserEntity updated = userDomainService.update(id, updates);
        return ResponseEntity.ok(updated);
    }

    /**
     * 启用/禁用用户。
     */
    @PostMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        log.info("Update user status: id={}, status={}", id, status);
        userDomainService.updateStatus(id, status);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
