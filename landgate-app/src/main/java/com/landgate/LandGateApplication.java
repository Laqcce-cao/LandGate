package com.landgate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LandGate 应用入口 —— AI API 网关的 Spring Boot 启动类。
 * <p>
 * LandGate 是 Sub2API（Go 版本）的 Java 复现，同时融合了学习、思考和创新。
 * <p>
 * 技术栈：Java 17 + Spring Boot 3.4 + Spring Data JPA + Redisson + MySQL 8.0 + Redis
 * <p>
 * 注解说明：
 * <ul>
 *   <li>{@link EnableScheduling} — 启用定时任务（ChannelMonitor 健康检查）</li>
 *   <li>{@link EnableAsync} — 启用异步方法（用量日志异步写入）</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.landgate.config")
@EnableScheduling
@EnableAsync
public class LandGateApplication {

    public static void main(String[] args) {
        SpringApplication.run(LandGateApplication.class, args);
    }
}
