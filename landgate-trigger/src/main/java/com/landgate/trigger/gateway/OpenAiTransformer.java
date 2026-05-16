package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 协议请求转换器 —— 构建转发至 OpenAI API 的上游请求。
 * <p>
 * 负责：设置认证头 → 配置代理 → 处理模型映射。
 */
@Slf4j
@Component
public class OpenAiTransformer {

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    public HttpRequest buildChatCompletionsRequest(String body, AccountEntity account, String accessToken) {
        String targetUrl = OPENAI_API_URL;
        if (account.getExtra() != null && !account.getExtra().equals("{}")) {
            try {
                var extra = new com.fasterxml.jackson.databind.ObjectMapper().readTree(account.getExtra());
                if (extra.has("base_url") && !extra.get("base_url").asText().isEmpty()) {
                    targetUrl = extra.get("base_url").asText() + "/v1/chat/completions";
                }
            } catch (Exception e) {
                log.warn("Failed to parse base_url for OpenAI account: account_id={}", account.getId());
            }
        }

        var headers = new ArrayList<String>();
        headers.addAll(List.of("Authorization", "Bearer " + accessToken));
        headers.addAll(List.of("Content-Type", "application/json"));

        return HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .headers(headers.toArray(new String[0]))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }
}
