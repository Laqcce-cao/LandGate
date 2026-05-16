package com.landgate.api.group;

import com.landgate.api.group.dto.*;
import java.util.List;

public interface IGroupService {

    GroupDetail getById(Long id);
    List<GroupDetail> listAll();
    GroupDetail create(GroupCreateRequest req);
    GroupDetail update(Long id, GroupUpdateRequest req);
    void delete(Long id);

    // Account binding
    List<GroupDetail.AccountBinding> getAccounts(Long groupId);
    void bindAccount(Long groupId, Long accountId, Integer priority);
    void unbindAccount(Long groupId, Long accountId);
    void updateAccountPriority(Long groupId, Long accountId, Integer priority);

    // User authorization
    List<GroupDetail.UserAuthorization> getUsers(Long groupId);
    void authorizeUser(Long groupId, Long userId);
    void revokeUser(Long groupId, Long userId);
}
