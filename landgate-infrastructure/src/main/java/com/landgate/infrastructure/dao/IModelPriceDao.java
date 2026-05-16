package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.ModelPricePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型价格 DAO —— MyBatis Mapper 接口。
 * XML 映射文件：mapper/ModelPriceMapper.xml
 * 对应表：model_prices（软删除）
 */
@Mapper
public interface IModelPriceDao {

    /** 根据 ID 查询未删除的价格 */
    ModelPricePO selectById(@Param("id") Long id);

    /**
     * 查询模型价格：优先分组覆盖，回退全局默认。
     * 返回一条记录，按 group_id IS NULL 升序（分组覆盖优先）。
     */
    ModelPricePO selectByModelAndGroup(@Param("model") String model, @Param("groupId") Long groupId);

    /** 按平台查询所有未删除的启用价格 */
    List<ModelPricePO> selectByPlatform(@Param("platform") String platform);

    /** 查询所有未删除的价格（分页） */
    List<ModelPricePO> selectAll(@Param("offset") int offset, @Param("size") int size);

    /** 统计价格总数 */
    long countAll();

    /** 插入价格 */
    int insert(ModelPricePO po);

    /** 更新价格 */
    int update(ModelPricePO po);

    /** 软删除 */
    int softDelete(@Param("id") Long id);
}
