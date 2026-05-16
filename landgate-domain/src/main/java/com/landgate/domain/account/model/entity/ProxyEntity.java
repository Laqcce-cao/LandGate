package com.landgate.domain.account.model.entity;

import com.landgate.types.enums.Status;
import lombok.*;

import java.time.Instant;

/**
 * 代理配置实体 —— 对应数据库 proxies 表。
 * <p>
 * 上游 API 请求可以通过指定代理服务器转发，支持 HTTP/HTTPS/SOCKS5 协议和基本认证。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ProxyEntity {

    private Long id;

    /** 代理名称 */
    private String name;

    /** 协议类型（http、https、socks5） */
    private String protocol;

    /** 代理主机地址 */
    private String host;

    /** 代理端口 */
    private Integer port;

    /** 认证用户名 */
    private String username;

    /** 认证密码 */
    private String password;

    /** 代理状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
