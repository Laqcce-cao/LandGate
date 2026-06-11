package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.UserAllowedGroupPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户-分组关联 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/UserAllowedGroupMapper.xml
 * 对应表：user_allowed_groups（复合主键，硬删除）
 */
@Mapper
public interface IUserAllowedGroupDao {

    /** 根据用户 ID 查询关联记录 */
    List<UserAllowedGroupPO> selectByUserId(@Param("userId") Long userId);

    /** 根据分组 ID 查询关联记录 */
    List<UserAllowedGroupPO> selectByGroupId(@Param("groupId") Long groupId);

    /** 插入关联记录 */
    int insert(UserAllowedGroupPO userAllowedGroup);

    /** 更新关联记录 */
    int update(UserAllowedGroupPO userAllowedGroup);

    /** 根据用户 ID 硬删除所有关联 */
    void deleteByUserId(@Param("userId") Long userId);

    /** 根据分组 ID 硬删除所有关联 */
    void deleteByGroupId(@Param("groupId") Long groupId);
}
