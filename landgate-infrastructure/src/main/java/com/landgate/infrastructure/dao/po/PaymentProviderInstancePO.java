package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.PaymentMode;
import lombok.*;

/**
 * 支付提供商实例持久化对象 —— 对应 <code>payment_provider_instances</code> 表。
 * <p>
 * 配置支付服务提供商的接入参数（支付宝/微信/Stripe 等），支持多实例。
 * 不使用软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentProviderInstancePO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 提供商标识（如 alipay/wxpay/stripe） */
    private String providerKey;

    /** 实例名称 */
    private String name;

    /** 配置（JSON，含 key/secret/回调URL 等） */
    private String config;

    /** 支持的支付类型（逗号分隔） */
    @Builder.Default
    private String supportedTypes = "";

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    /** 支付模式（二维码/跳转/弹窗） */
    @Builder.Default
    private PaymentMode paymentMode = PaymentMode.QRCODE;

    /** 排序序号 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 限额配置（JSON） */
    @Builder.Default
    private String limits = "{}";

    /** 是否支持退款 */
    @Builder.Default
    private Boolean refundEnabled = true;

    /** 是否允许用户自行退款 */
    @Builder.Default
    private Boolean allowUserRefund = false;

    public boolean isAvailable() { return enabled != null && enabled; }

    public boolean supportsType(String paymentType) {
        if (supportedTypes == null || supportedTypes.isEmpty()) return true;
        for (String t : supportedTypes.split(",")) {
            if (t.trim().equalsIgnoreCase(paymentType)) return true;
        }
        return false;
    }
}
