package com.landgate.api.billing;

import com.landgate.api.billing.dto.*;

public interface IBillingService {

    UsageLogDetail getById(Long id);
    PageResult<UsageLogDetail> listByUser(Long userId, int page, int size);
    PageResult<UsageLogDetail> listByApiKey(Long apiKeyId, int page, int size);
    PageResult<UsageLogDetail> listByAccount(Long accountId, int page, int size);
}
