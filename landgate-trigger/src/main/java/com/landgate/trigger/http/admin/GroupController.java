package com.landgate.trigger.http.admin;

import com.landgate.api.admin.dto.AdminDTOs.AllowUserRequest;
import com.landgate.api.admin.dto.AdminDTOs.BindAccountRequest;
import com.landgate.api.admin.dto.AdminDTOs.UpdatePriorityRequest;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.domain.group.model.entity.UserAllowedGroupEntity;
import com.landgate.domain.group.service.GroupDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分组管理控制器 —— 分组的 CRUD、账号绑定、用户授权管理。
 * <p>
 * 路由前缀：{@code /api/v1/admin/groups}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupDomainService groupDomainService;

    @GetMapping
    public ResponseEntity<?> list() {
        log.debug("List all groups");
        List<GroupEntity> groups = groupDomainService.listAll();
        return ResponseEntity.ok(Map.of("groups", groups, "total", groups.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        log.debug("Get group: id={}", id);
        GroupEntity group = groupDomainService.getById(id);
        return ResponseEntity.ok(group);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody GroupEntity group) {
        log.info("Create group: name={}", group.getName());
        GroupEntity created = groupDomainService.create(group);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody GroupEntity updates) {
        log.info("Update group: id={}, name={}", id, updates.getName());
        GroupEntity updated = groupDomainService.update(id, updates);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        log.info("Delete group: id={}", id);
        groupDomainService.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/{id}/accounts")
    public ResponseEntity<?> listAccounts(@PathVariable Long id) {
        log.debug("List accounts in group: group_id={}", id);
        List<AccountGroupEntity> links = groupDomainService.getAccounts(id);
        return ResponseEntity.ok(Map.of("accounts", links, "total", links.size()));
    }

    @PostMapping("/{id}/accounts")
    public ResponseEntity<?> bindAccount(@PathVariable Long id, @RequestBody BindAccountRequest req) {
        log.info("Bind account to group: group_id={}, account_id={}, priority={}", id, req.accountId(), req.priority());
        AccountGroupEntity link = groupDomainService.bindAccount(id, req.accountId(), req.priority());
        return ResponseEntity.ok(link);
    }

    @PutMapping("/{id}/accounts/{accountId}/priority")
    public ResponseEntity<?> updateAccountPriority(@PathVariable Long id,
                                                    @PathVariable Long accountId,
                                                    @RequestBody UpdatePriorityRequest req) {
        log.info("Update account priority: group_id={}, account_id={}, priority={}", id, accountId, req.priority());
        // TODO: update priority via domain service
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{id}/accounts/{accountId}")
    public ResponseEntity<?> unbindAccount(@PathVariable Long id, @PathVariable Long accountId) {
        log.info("Unbind account from group: group_id={}, account_id={}", id, accountId);
        groupDomainService.unbindAccount(id, accountId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/{id}/users")
    public ResponseEntity<?> listAllowedUsers(@PathVariable Long id) {
        log.debug("List allowed users in group: group_id={}", id);
        List<UserAllowedGroupEntity> links = groupDomainService.getUsers(id);
        return ResponseEntity.ok(Map.of("users", links, "total", links.size()));
    }

    @PostMapping("/{id}/users")
    public ResponseEntity<?> allowUser(@PathVariable Long id, @RequestBody AllowUserRequest req) {
        log.info("Authorize user to group: group_id={}, user_id={}", id, req.userId());
        UserAllowedGroupEntity link = groupDomainService.authorizeUser(id, req.userId());
        return ResponseEntity.ok(link);
    }

    @DeleteMapping("/{id}/users/{userId}")
    public ResponseEntity<?> disallowUser(@PathVariable Long id, @PathVariable Long userId) {
        log.info("Revoke user access to group: group_id={}, user_id={}", id, userId);
        groupDomainService.revokeUser(id, userId);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
