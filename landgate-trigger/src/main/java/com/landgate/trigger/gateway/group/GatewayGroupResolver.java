package com.landgate.trigger.gateway.group;

import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.oauth.ClaudeCodeOnlyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves gateway group state, including Claude Code fallback group chains.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayGroupResolver {

    private final IGroupRepository groupRepository;

    public GroupEntity loadGroup(Long groupId) {
        if (groupId == null) return null;
        return groupRepository.findById(groupId)
                .filter(g -> g.getDeletedAt() == null)
                .orElse(null);
    }

    /**
     * 解析 claude_code_only 分组链路。
     * <p>
     * 如果 Group 是 claude_code_only 但客户端不是 Claude Code，
     * 沿 fallback_group_id 链路查找可用分组。链路末端无 fallback 时抛出异常。
     * 支持环形检测。
     *
     * @param group        原始分组
     * @param isClaudeCode 客户端是否是 Claude Code
     * @return 解析后的分组（可能是 fallback 链路上的其他分组）
     * @throws ClaudeCodeOnlyException 链路末端无可用 fallback
     */
    public GroupEntity resolveGatewayGroup(GroupEntity group, boolean isClaudeCode) {
        Long currentId = group.getId();
        java.util.Set<Long> visited = new java.util.HashSet<>();

        while (true) {
            if (!visited.add(currentId)) {
                log.error("Claude Code 分组降级环形引用: current={}, visited={}", currentId, visited);
                throw new ClaudeCodeOnlyException(
                        "Fallback group cycle detected for group " + currentId);
            }

            // 重新加载当前 group（链路中每一步都是不同的 group）
            GroupEntity currentGroup = currentId.equals(group.getId())
                    ? group
                    : groupRepository.findById(currentId).orElse(null);

            if (currentGroup == null || currentGroup.getDeletedAt() != null) {
                log.error("Claude Code 降级分组不存在或已删除: group_id={}", currentId);
                throw new ClaudeCodeOnlyException(
                        "Fallback group " + currentId + " not found or deleted");
            }

            // 终止条件：非 claude_code_only 或客户端是 Claude Code
            if (!Boolean.TRUE.equals(currentGroup.getClaudeCodeOnly()) || isClaudeCode) {
                log.debug("Claude Code 分组解析终止: group={}, claude_code_only={}, is_claude_code={}",
                        currentGroup.getName(), currentGroup.getClaudeCodeOnly(), isClaudeCode);
                return currentGroup;
            }

            log.debug("Claude Code 分组降级: {} (claude_code_only=true) -> fallback_group_id={}",
                    currentGroup.getName(), currentGroup.getFallbackGroupId());

            // claude_code_only 且非 CC 客户端：尝试降级
            if (currentGroup.getFallbackGroupId() == null) {
                log.warn("Claude Code 分组降级链路末端: group={}, 无 fallback", currentGroup.getName());
                throw new ClaudeCodeOnlyException(
                        "Group '" + currentGroup.getName() + "' requires Claude Code client.");
            }
            currentId = currentGroup.getFallbackGroupId();
        }
    }
}
