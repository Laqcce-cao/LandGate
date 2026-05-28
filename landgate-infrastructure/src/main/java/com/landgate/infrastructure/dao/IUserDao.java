package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.UserPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/UserMapper.xml
 * 对应表：users（软删除）
 */
@Mapper
public interface IUserDao {

    /** 根据 ID 查询未删除的用户 */
    UserPO selectById(@Param("id") Long id);

    /** 插入用户，useGeneratedKeys 回填 ID */
    int insert(UserPO user);

    /** 更新用户所有字段 */
    int update(UserPO user);

    /** 查询所有未删除的用户 */
    List<UserPO> selectAll();

    /** 统计未删除用户总数 */
    long countAll();

    /** 根据邮箱查询未删除的用户 */
    UserPO selectByEmail(@Param("email") String email);

    /** 检查邮箱是否已存在（未删除） */
    boolean existsByEmail(@Param("email") String email);

    /** 按状态统计未删除用户数 */
    long countByStatus(@Param("status") String status);

    /** 直接更新用户余额（批量刷库，避免全量 update） */
    int updateBalance(@Param("id") Long id, @Param("balance") java.math.BigDecimal balance);

    /** 搜索用户（按用户名或邮箱模糊匹配），分页 */
    List<UserPO> selectBySearch(@Param("search") String search,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    /** 统计搜索匹配的用户总数 */
    long countBySearch(@Param("search") String search);

    /** 统计指定时间之后注册的用户数 */
    long countByCreatedAtAfter(@Param("after") String after);
}
