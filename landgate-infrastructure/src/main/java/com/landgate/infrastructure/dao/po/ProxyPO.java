package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.Status;
import lombok.*;

/**
 * 代理持久化对象 —— 对应 <code>proxies</code> 表。
 * <p>
 * 记录 HTTP/SOCKS5 代理服务器信息，上游账号可通过代理转发请求。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProxyPO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 代理名称 */
    private String name;

    /** 代理协议（http/https/socks5） */
    private String protocol;

    /** 代理主机地址 */
    private String host;

    /** 代理端口 */
    private Integer port;

    /** 代理用户名（可选） */
    private String username;

    /** 代理密码（可选） */
    private String password;

    /** 代理状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;
}
