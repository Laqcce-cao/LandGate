package com.landgate.api.account;

import com.landgate.api.account.dto.*;
import com.landgate.types.response.PageResponse;
import java.util.List;

public interface IAccountService {

    AccountDetail getById(Long id);
    List<AccountDetail> listAll();
    List<AccountDetail> listByPlatform(String platform);
    AccountDetail create(AccountCreateRequest req);
    AccountDetail update(Long id, AccountUpdateRequest req);
    void delete(Long id);
    void updateStatus(Long id, String status);
    void setSchedulable(Long id, boolean schedulable);
    String getCredential(Long id, String key);
}
