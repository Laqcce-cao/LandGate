package com.landgate.domain.payment.model.entity;

import com.landgate.types.enums.PaymentMode;
import lombok.*;

import java.time.Instant;

/**
 * 支付服务商实例实体 —— 对应数据库 payment_provider_instances 表。
 * <p>
 * 存储各支付渠道（支付宝、微信支付等）的实例配置，支持二维码/网页等多种支付方式。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class PaymentProviderInstanceEntity {

    private Long id;

    /** 支付渠道标识（如 alipay、wechatpay） */
    private String providerKey;

    /** 实例名称 */
    private String name;

    /** 配置信息（JSON 格式） */
    private String config;

    /** 支持的支付方式类型 */
    @Builder.Default private String supportedTypes = "";

    /** 是否启用 */
    @Builder.Default private Boolean enabled = true;

    /** 支付模式（二维码 / 网页等） */
    @Builder.Default
    private PaymentMode paymentMode = PaymentMode.QRCODE;

    /** 排序权重 */
    @Builder.Default private Integer sortOrder = 0;

    /** 限额配置 */
    private String limits;

    /** 是否支持退款 */
    @Builder.Default private Boolean refundEnabled = true;

    /** 是否允许用户自行退款 */
    @Builder.Default private Boolean allowUserRefund = false;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
