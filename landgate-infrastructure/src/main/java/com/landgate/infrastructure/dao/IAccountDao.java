package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.AccountPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 账号 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/AccountMapper.xml
 * 对应表：accounts（软删除）
 */
@Mapper
public interface IAccountDao {

    /** 根据 ID 查询未删除的账号 */
    AccountPO selectById(@Param("id") Long id);

    /** 批量根据 ID 查询未删除的账号 */
    List<AccountPO> selectByIds(@Param("ids") List<Long> ids);

    /** 插入账号，useGeneratedKeys 回填 ID */
    int insert(AccountPO account);

    /** 更新账号所有字段 */
    int update(AccountPO account);

    /** 查询所有未删除的账号 */
    List<AccountPO> selectAll();

    /** 统计未删除账号总数 */
    long countAll();

    /** 根据平台查询未删除的账号 */
    List<AccountPO> selectByPlatform(@Param("platform") String platform);

    /** 根据平台和认证类型查询未删除的账号 */
    List<AccountPO> selectByPlatformAndType(@Param("platform") String platform, @Param("type") String type);

    /** 根据状态查询未删除的账号 */
    List<AccountPO> selectByStatus(@Param("status") String status);

    /** 根据代理 ID 查询未删除的账号 */
    List<AccountPO> selectByProxyId(@Param("proxyId") Long proxyId);

    /** 查询指定平台、激活且可调度的账号 */
    List<AccountPO> selectSchedulableByPlatformAndStatus(@Param("platform") String platform, @Param("status") String status);
}
