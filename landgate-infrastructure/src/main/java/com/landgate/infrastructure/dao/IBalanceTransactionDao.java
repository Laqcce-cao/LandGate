package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.AdminBalanceTransactionPO;
import com.landgate.infrastructure.dao.po.BalanceTransactionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 余额流水 DAO —— MyBatis Mapper 接口。
 */
@Mapper
public interface IBalanceTransactionDao {

    BalanceTransactionPO selectById(@Param("id") Long id);

    BalanceTransactionPO selectBySource(@Param("sourceType") String sourceType,
                                        @Param("sourceId") String sourceId,
                                        @Param("transactionType") String transactionType);

    int insert(BalanceTransactionPO transaction);

    int update(BalanceTransactionPO transaction);

    int markPending(@Param("id") Long id);

    int markCompleted(@Param("id") Long id,
                      @Param("balanceBefore") BigDecimal balanceBefore,
                      @Param("balanceAfter") BigDecimal balanceAfter,
                      @Param("completedAt") Instant completedAt);

    int markFailed(@Param("id") Long id, @Param("failureReason") String failureReason);

    List<BalanceTransactionPO> selectByUserId(@Param("userId") Long userId,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    long countByUserId(@Param("userId") Long userId);

    /** 后台分页查询全站余额流水 */
    List<AdminBalanceTransactionPO> selectAdmin(@Param("keyword") String keyword,
                                                @Param("transactionType") String transactionType,
                                                @Param("fundingType") String fundingType,
                                                @Param("status") String status,
                                                @Param("offset") int offset,
                                                @Param("size") int size);

    /** 统计后台筛选条件下的余额流水数量 */
    long countAdmin(@Param("keyword") String keyword,
                    @Param("transactionType") String transactionType,
                    @Param("fundingType") String fundingType,
                    @Param("status") String status);
}
