package com.landgate.infrastructure.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * 上游 HTTP 客户端 —— 将网关请求转发至上游 AI API。
 * <p>
 * 基于 JDK HttpClient（HTTP/2 协议），使用缓存线程池。
 * 支持流式响应（InputStream）和字节数组两种读取方式。
 */
@Slf4j
@Component
public class UpstreamHttpClient {

    private final HttpClient httpClient;

    public UpstreamHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newCachedThreadPool())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("UpstreamHttpClient initialized: HTTP/2, cached threads, connect_timeout=10s");
    }

    public HttpResponse<java.io.InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        log.debug("Sending upstream request: method={}, uri={}", request.method(), request.uri());
        long start = System.currentTimeMillis();
        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        long elapsed = System.currentTimeMillis() - start;
        log.debug("Upstream response: status={}, elapsed={}ms", response.statusCode(), elapsed);
        return response;
    }

    public HttpResponse<byte[]> sendBytes(HttpRequest request) throws IOException, InterruptedException {
        log.debug("Sending upstream request (bytes): method={}, uri={}", request.method(), request.uri());
        long start = System.currentTimeMillis();
        HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray());
        long elapsed = System.currentTimeMillis() - start;
        log.debug("Upstream response: status={}, body_size={}, elapsed={}ms",
                response.statusCode(), response.body().length, elapsed);
        return response;
    }
}
