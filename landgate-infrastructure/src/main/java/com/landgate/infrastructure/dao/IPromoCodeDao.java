package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.PromoCodePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 优惠码 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/PromoCodeMapper.xml
 * 对应表：promo_codes（软删除）
 */
@Mapper
public interface IPromoCodeDao {

    /** 根据 ID 查询未删除的优惠码 */
    PromoCodePO selectById(@Param("id") Long id);

    /** 插入优惠码，useGeneratedKeys 回填 ID */
    int insert(PromoCodePO promoCode);

    /** 更新优惠码所有字段 */
    int update(PromoCodePO promoCode);

    /** 查询所有未删除的优惠码 */
    List<PromoCodePO> selectAll();

    /** 统计未删除优惠码总数 */
    long countAll();

    /** 根据优惠码值查询 */
    PromoCodePO selectByCode(@Param("code") String code);

    /** 查询所有启用且未删除的优惠码 */
    List<PromoCodePO> selectEnabled();
}
