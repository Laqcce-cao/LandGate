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
     * 根据模型名查询启用的价格。
     */
    ModelPricePO selectByModel(@Param("model") String model);

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
