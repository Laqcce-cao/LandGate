package com.landgate.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 单页应用回退配置。
 * <p>
 * 当前端路由（如 /admin/dashboard、/api-keys）被浏览器直接访问或刷新时，
 * Spring Boot 不认识这些路径，默认返回 404。
 * 该配置将所有非 API、非静态资源的请求回退到 index.html，
 * 交给 React Router 处理。
 */
@Configuration
public class SpaFallbackConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将前端构建产物的 static/ 目录映射到 classpath
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        // 如果请求的资源存在（如 /assets/xxx.js），直接返回
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 否则回退到 index.html，由 React Router 处理前端路由
                        Resource fallback = new ClassPathResource("/static/index.html");
                        if (fallback.exists()) {
                            return fallback;
                        }
                        return null;
                    }
                });
    }
}
