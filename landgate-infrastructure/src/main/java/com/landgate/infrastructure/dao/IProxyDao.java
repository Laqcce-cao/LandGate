package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.ProxyPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 代理 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/ProxyMapper.xml
 * 对应表：proxies（软删除）
 */
@Mapper
public interface IProxyDao {

    /** 根据 ID 查询未删除的代理 */
    ProxyPO selectById(@Param("id") Long id);

    /** 插入代理，useGeneratedKeys 回填 ID */
    int insert(ProxyPO proxy);

    /** 更新代理所有字段 */
    int update(ProxyPO proxy);

    /** 查询所有未删除的代理 */
    List<ProxyPO> selectAll();

    /** 统计未删除代理总数 */
    long countAll();

    /** 根据状态查询未删除的代理 */
    List<ProxyPO> selectByStatus(@Param("status") String status);
}
