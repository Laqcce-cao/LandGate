package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.UserCheckinPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户签到 DAO —— MyBatis Mapper 接口。
 */
@Mapper
public interface IUserCheckinDao {

    UserCheckinPO selectById(@Param("id") Long id);

    UserCheckinPO selectByUserIdAndSignDate(@Param("userId") Long userId, @Param("signDate") LocalDate signDate);

    UserCheckinPO selectLatestByUserId(@Param("userId") Long userId);

    int insert(UserCheckinPO checkin);

    int update(UserCheckinPO checkin);

    int markPending(@Param("id") Long id);

    int markCompleted(@Param("id") Long id, @Param("balanceTransactionId") Long balanceTransactionId);

    int markFailed(@Param("id") Long id, @Param("failureReason") String failureReason);

    List<UserCheckinPO> selectByUserId(@Param("userId") Long userId,
                                       @Param("offset") int offset,
                                       @Param("size") int size);

    long countByUserId(@Param("userId") Long userId);
}
