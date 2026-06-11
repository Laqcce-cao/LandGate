package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.PaymentAuditLogPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 支付审计日志 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/PaymentAuditLogMapper.xml
 * 对应表：payment_audit_logs（无软删除）
 * 注意：当前未被任何 RepositoryImpl 使用，预留接口。
 */
@Mapper
public interface IPaymentAuditLogDao {

    /** 根据 ID 查询日志 */
    PaymentAuditLogPO selectById(@Param("id") Long id);

    /** 插入日志，useGeneratedKeys 回填 ID */
    int insert(PaymentAuditLogPO log);

    /** 查询所有日志 */
    List<PaymentAuditLogPO> selectAll();

    /** 根据订单 ID 查询日志，按创建时间升序 */
    List<PaymentAuditLogPO> selectByOrderId(@Param("orderId") String orderId);

    /** 根据订单 ID 和操作类型查询日志 */
    List<PaymentAuditLogPO> selectByOrderIdAndAction(@Param("orderId") String orderId, @Param("action") String action);

    /** 检查指定订单是否已有指定操作记录 */
    boolean existsByOrderIdAndAction(@Param("orderId") String orderId, @Param("action") String action);
}
