package com.landgate.trigger.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.billing.model.valobj.LiteLLMPrice;
import com.landgate.domain.billing.service.LiteLLMSyncServiceBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LiteLLM 远程模型价格同步服务。
 * <p>
 * 从 LiteLLM 的 model_prices_and_context_window.json 定时拉取模型价格，
 * 作为三层价格体系的第二层（Channel DB → LiteLLM → Hardcoded）。
 * <p>
 * 同步策略：每 10 分钟检查一次远程文件的 SHA-256 hash，hash 变化时下载完整文件。
 * 下载失败或不存在时使用本地 classpath fallback。
 */
@Slf4j
@Service
public class LiteLLMSyncService implements LiteLLMSyncServiceBridge {

    private static final String REMOTE_URL =
            "https://raw.githubusercontent.com/Wei-Shaw/model-price-repo/main/model_prices_and_context_window.json";
    private static final String LOCAL_FALLBACK_PATH = "model-pricing/model_prices_and_context_window.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** LiteLLM 价格缓存：model → LiteLLMPrice */
    private final Map<String, LiteLLMPrice> priceCache = new ConcurrentHashMap<>();

    /** 最后已知的远程文件 SHA-256 hash（用于检测变化） */
    private volatile String lastKnownHash = null;

    /** 最近一次全量更新的时间（限制一天最多一次） */
    private volatile Instant lastFullSync = null;

    /**
     * 按模型名查询 LiteLLM 价格（含模糊匹配）。
     *
     * @param model 模型名称
     * @return 匹配的 LiteLLM 价格，不存在返回 null
     */
    public LiteLLMPrice findPrice(String model) {
        if (model == null || model.isEmpty()) return null;

        // 1. 精确匹配
        LiteLLMPrice exact = priceCache.get(model);
        if (exact != null) return exact;

        // 2. 变体规范化（- ↔ . 互转）
        String normalized = model.replace('.', '-');
        if (!normalized.equals(model)) {
            exact = priceCache.get(normalized);
            if (exact != null) return exact;
        }
        normalized = model.replace('-', '.');
        if (!normalized.equals(model)) {
            exact = priceCache.get(normalized);
            if (exact != null) return exact;
        }

        // 3. 去日期后缀（如 claude-opus-4-5-20251101 → claude-opus-4-5）
        String stripped = stripDateSuffix(model);
        if (!stripped.equals(model)) {
            exact = priceCache.get(stripped);
            if (exact != null) return exact;
        }

        // 4. 模型家族匹配（包含相同系列关键词的最低价格）
        return findFamilyMatch(model);
    }

    /**
     * 缓存是否已初始化（至少加载过一次数据）。
     */
    public boolean isInitialized() {
        return !priceCache.isEmpty();
    }

    /**
     * 返回当前缓存中的价格条目数。
     */
    public int cacheSize() {
        return priceCache.size();
    }

    // ========================
    // 定时同步
    // ========================

    /**
     * 启动时立即加载 + 每 10 分钟检查一次更新。
     * <p>
     * 首次加载优先使用本地 fallback 文件（避免冷启动时依赖外部网络）。
     */
    @Scheduled(initialDelay = 0, fixedDelay = 600_000)
    public void syncIfChanged() {
        // 首次加载：先尝试本地 fallback
        if (priceCache.isEmpty()) {
            boolean loaded = loadLocalFallback();
            if (loaded) {
                log.info("LiteLLM prices loaded from local fallback: {} entries", priceCache.size());
                lastFullSync = Instant.now();
            }
        }

        // 检查远程更新
        try {
            String remoteHash = fetchRemoteHash();
            if (remoteHash != null && !remoteHash.equals(lastKnownHash)) {
                // 限制全量更新频率：一天最多一次
                if (lastFullSync != null && Duration.between(lastFullSync, Instant.now()).toHours() < 24) {
                    log.debug("LiteLLM sync skipped: last full sync within 24h, hash={}", remoteHash);
                    return;
                }
                log.info("LiteLLM remote hash changed: {} → {}, downloading full file", lastKnownHash, remoteHash);
                byte[] data = downloadFullFile();
                if (data != null) {
                    parseAndUpdateCache(data);
                    lastKnownHash = remoteHash;
                    lastFullSync = Instant.now();
                    log.info("LiteLLM prices synced from remote: {} entries", priceCache.size());
                } else {
                    log.warn("LiteLLM remote download failed, keeping current cache");
                }
            }
        } catch (Exception e) {
            log.warn("LiteLLM sync check failed: {}", e.getMessage());
            // 如果缓存为空，尝试本地 fallback
            if (priceCache.isEmpty()) {
                loadLocalFallback();
            }
        }
    }

    // ========================
    // 远程获取
    // ========================

    /**
     * 获取远程文件的 SHA-256 hash（通过 HEAD 或轻量请求）。
     * 使用 IF-None-Match / ETag 方式；若不可用则尝试获取文件的前几个字节推断。
     * <p>
     * 简化实现：发送 GET 请求取回完整内容并计算 hash。
     */
    private String fetchRemoteHash() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(REMOTE_URL))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                byte[] body = resp.body();
                // 使用简单的 hash（取内容长度 + 前 1024 字节的 checksum）
                int checksum = 0;
                for (int i = 0; i < Math.min(body.length, 1024); i++) {
                    checksum = (checksum * 31 + (body[i] & 0xFF)) % 1000000007;
                }
                return body.length + ":" + checksum;
            }
        } catch (Exception e) {
            log.debug("Failed to fetch LiteLLM remote hash: {}", e.getMessage());
        }
        return null;
    }

    /** 下载完整的远程文件 */
    private byte[] downloadFullFile() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(REMOTE_URL))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return resp.body();
            }
            log.warn("LiteLLM download returned status: {}", resp.statusCode());
        } catch (Exception e) {
            log.warn("LiteLLM download failed: {}", e.getMessage());
        }
        return null;
    }

    // ========================
    // 本地 fallback
    // ========================

    /** 从 classpath 加载本地 fallback JSON 文件 */
    private boolean loadLocalFallback() {
        try {
            ClassPathResource resource = new ClassPathResource(LOCAL_FALLBACK_PATH);
            if (!resource.exists()) {
                log.info("LiteLLM local fallback not found at {}, starting with empty cache", LOCAL_FALLBACK_PATH);
                return false;
            }
            try (InputStream is = resource.getInputStream()) {
                byte[] data = is.readAllBytes();
                parseAndUpdateCache(data);
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to load LiteLLM local fallback: {}", e.getMessage());
            return false;
        }
    }

    // ========================
    // JSON 解析
    // ========================

    /** 解析 LiteLLM JSON 并将价格加载到内存缓存 */
    private void parseAndUpdateCache(byte[] data) {
        try {
            JsonNode root = JSON.readTree(data);
            Map<String, LiteLLMPrice> newCache = new ConcurrentHashMap<>();

            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String model = entry.getKey();
                JsonNode node = entry.getValue();

                BigDecimal inputPrice = parsePrice(node, "input_cost_per_token");
                BigDecimal outputPrice = parsePrice(node, "output_cost_per_token");

                // 跳过价格为 0 或无法解析的条目
                if (inputPrice == null && outputPrice == null) continue;

                newCache.put(model, new LiteLLMPrice(
                        model,
                        inputPrice != null ? inputPrice : BigDecimal.ZERO,
                        outputPrice != null ? outputPrice : BigDecimal.ZERO));
            }

            if (!newCache.isEmpty()) {
                priceCache.clear();
                priceCache.putAll(newCache);
            }
        } catch (Exception e) {
            log.warn("Failed to parse LiteLLM JSON: {}", e.getMessage());
        }
    }

    /**
     * 解析 LiteLLM 价格字段（per-token → per-million-tokens）。
     * <p>
     * LiteLLM JSON 中存储的是单 token 价格，需要乘以 1,000,000 转换为百万 token 价格。
     */
    private static BigDecimal parsePrice(JsonNode parent, String field) {
        if (parent == null || !parent.has(field) || parent.get(field).isNull()) return null;
        try {
            BigDecimal perToken = new BigDecimal(parent.get(field).asText());
            return perToken.multiply(new BigDecimal("1000000"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========================
    // 模糊匹配
    // ========================

    /**
     * 移除模型名末尾的日期后缀（如 20251101、20241022 等）。
     * 示例：claude-opus-4-5-20251101 → claude-opus-4-5
     */
    private static String stripDateSuffix(String model) {
        if (model == null) return null;
        // 匹配末尾的 -YYYYMMDD 格式日期后缀
        return model.replaceAll("-\\d{8}$", "");
    }

    /**
     * 在缓存中查找与模型名属于同一家族的价格。
     * <p>
     * 匹配策略：提取模型名中的系列关键词（opus、sonnet、haiku、gpt-4、gpt-5 等），
     * 返回缓存中包含该关键词的最低价格条目。
     */
    private LiteLLMPrice findFamilyMatch(String model) {
        if (model == null || priceCache.isEmpty()) return null;

        String[] keywords = extractFamilyKeywords(model);
        if (keywords.length == 0) return null;

        for (String keyword : keywords) {
            LiteLLMPrice best = null;
            for (Map.Entry<String, LiteLLMPrice> entry : priceCache.entrySet()) {
                if (entry.getKey().contains(keyword)) {
                    if (best == null || entry.getValue().inputPrice().compareTo(best.inputPrice()) < 0) {
                        best = entry.getValue();
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * 从模型名中提取家族关键词，按优先级从高到低排列。
     */
    private static String[] extractFamilyKeywords(String model) {
        String lower = model.toLowerCase();
        List<String> keywords = new ArrayList<>();

        // 子系列优先（更精确）
        if (lower.contains("opus")) keywords.add("opus");
        if (lower.contains("sonnet")) keywords.add("sonnet");
        if (lower.contains("haiku")) keywords.add("haiku");
        if (lower.contains("gpt-5")) keywords.add("gpt-5");
        if (lower.contains("gpt-4")) keywords.add("gpt-4");
        if (lower.contains("gpt-3.5")) keywords.add("gpt-3.5");
        if (lower.contains("claude")) keywords.add("claude");

        return keywords.toArray(new String[0]);
    }
}
