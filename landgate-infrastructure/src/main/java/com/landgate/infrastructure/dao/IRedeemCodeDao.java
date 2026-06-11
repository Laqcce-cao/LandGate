package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.RedeemCodePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 兑换码 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/RedeemCodeMapper.xml
 * 对应表：redeem_codes（软删除）
 */
@Mapper
public interface IRedeemCodeDao {

    /** 根据 ID 查询未删除的兑换码 */
    RedeemCodePO selectById(@Param("id") Long id);

    /** 插入兑换码，useGeneratedKeys 回填 ID */
    int insert(RedeemCodePO redeemCode);

    /** 更新兑换码所有字段 */
    int update(RedeemCodePO redeemCode);

    /** 查询所有未删除的兑换码 */
    List<RedeemCodePO> selectAll();

    /** 统计未删除兑换码总数 */
    long countAll();

    /** 根据兑换码值查询 */
    RedeemCodePO selectByCode(@Param("code") String code);

    /** 查询所有启用且未删除的兑换码 */
    List<RedeemCodePO> selectEnabled();

    /** 根据创建人 ID 查询未删除的兑换码 */
    List<RedeemCodePO> selectByCreatedBy(@Param("createdBy") Long createdBy);
}
