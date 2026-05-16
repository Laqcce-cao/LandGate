package com.landgate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis client configuration properties for Redisson.
 * Uses custom prefix to avoid collision with spring.data.redis auto-config.
 */
@Data
@ConfigurationProperties(prefix = "redis.sdk.config", ignoreInvalidFields = true)
public class RedisClientConfigProperties {

    private String host = "127.0.0.1";
    private int port = 6379;
    private String password;
    private int poolSize = 64;
    private int minIdleSize = 10;
    private int idleTimeout = 10000;
    private int connectTimeout = 10000;
    private int retryAttempts = 3;
    private int retryInterval = 1000;
    private int pingInterval = 0;
    private boolean keepAlive = true;
}
