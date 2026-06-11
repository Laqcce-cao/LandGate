package com.landgate.domain.billing.service;

import com.landgate.domain.billing.adapter.repository.IModelPriceRepository;
import com.landgate.domain.billing.model.entity.ModelPriceEntity;
import com.landgate.domain.billing.model.valobj.LiteLLMPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型价格查询服务 —— 三层价格解析体系。
 * <p>
 * <b>三层价格解析（按优先级）：</b>
 * <ol>
 *   <li><b>Channel 自定义定价</b>：数据库 model_prices 表，精确匹配 + 通配符匹配（如 claude-opus-*）</li>
 *   <li><b>LiteLLM 远程定价</b>：定时从 LiteLLM GitHub 仓库同步，内存缓存，支持模糊匹配</li>
 *   <li><b>硬编码 Fallback</b>：代码内置的 Claude/GPT 家族默认价格</li>
 * </ol>
 * <p>
 * 内置内存缓存（TTL 5 分钟），避免重复查库/匹配。
 */
@Slf4j
@Service
public class ModelPricingDomainService {

    private final IModelPriceRepository priceRepository;

    /** LiteLLM 同步服务 —— 来自 trigger 模块，可选注入（模块隔离） */
    @Autowired(required = false)
    private LiteLLMSyncServiceBridge liteLLMBridge;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private static final Price DEFAULT_PRICE = new Price(
            new BigDecimal("3"), new BigDecimal("15"),
            new BigDecimal("3.75"), new BigDecimal("0.3"),
            new BigDecimal("3.75"), new BigDecimal("3.75"), false);

    /** 通配符价格规则缓存（启动时加载一次，通过 invalidateCache 刷新） */
    private volatile List<ModelPriceEntity> wildcardRules = null;
    private volatile Instant wildcardRulesLoadedAt = null;
    private static final Duration WILDCARD_RULES_TTL = Duration.ofMinutes(10);

    public record Price(BigDecimal inputPrice, BigDecimal outputPrice,
                        BigDecimal cacheWritePrice, BigDecimal cacheReadPrice,
                        BigDecimal cacheWrite5mPrice, BigDecimal cacheWrite1hPrice,
                        boolean supportsCacheBreakdown) {}

    private record CachedPrice(Price price, Instant expiresAt) {}

    private final ConcurrentHashMap<String, CachedPrice> cache = new ConcurrentHashMap<>();

    public ModelPricingDomainService(IModelPriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    // ---- Public API ----

    /**
     * 获取模型输入价格（$/百万 tokens）。
     */
    public BigDecimal getInputPrice(String model) {
        return resolve(model).inputPrice;
    }

    /**
     * 获取模型输出价格（$/百万 tokens）。
     */
    public BigDecimal getOutputPrice(String model) {
        return resolve(model).outputPrice;
    }

    /**
     * 获取缓存写入价格（$/百万 tokens）。
     */
    public BigDecimal getCacheWritePrice(String model) {
        return resolve(model).cacheWritePrice;
    }

    /**
     * 获取缓存读取价格（$/百万 tokens）。
     */
    public BigDecimal getCacheReadPrice(String model) {
        return resolve(model).cacheReadPrice;
    }

    /**
     * 获取 5 分钟有效期缓存写入价格（$/百万 tokens）。
     */
    public BigDecimal getCacheWrite5mPrice(String model) {
        return resolve(model).cacheWrite5mPrice;
    }

    /**
     * 获取 1 小时有效期缓存写入价格（$/百万 tokens）。
     */
    public BigDecimal getCacheWrite1hPrice(String model) {
        return resolve(model).cacheWrite1hPrice;
    }

    /**
     * 是否支持缓存写入 5m/1h 分类计费。
     */
    public boolean supportsCacheBreakdown(String model) {
        return resolve(model).supportsCacheBreakdown;
    }

    /**
     * 三层价格解析 —— 按优先级：Channel DB → LiteLLM → Hardcoded。
     * <p>
     * 第一层：数据库精确匹配 → 通配符匹配（如 claude-opus-*）<br>
     * 第二层：LiteLLM 远程价格缓存（含模糊匹配）<br>
     * 第三层：硬编码默认价格（$3/$15 per M tokens）
     *
     * @param model 模型名称
     * @return 模型价格对象
     */
    public Price resolve(String model) {
        // 0. 检查内存缓存
        CachedPrice cached = cache.get(model);
        if (cached != null && Instant.now().isBefore(cached.expiresAt)) {
            return cached.price;
        }

        // 1. Channel DB：精确匹配
        ModelPriceEntity entity = priceRepository.findByModel(model).orElse(null);
        if (entity != null) {
            Price price = entityToPrice(entity);
            cache.put(model, new CachedPrice(price, Instant.now().plus(CACHE_TTL)));
            return price;
        }

        // 2. Channel DB：通配符匹配
        Price wildcardPrice = tryWildcardMatch(model);
        if (wildcardPrice != null) {
            cache.put(model, new CachedPrice(wildcardPrice, Instant.now().plus(CACHE_TTL)));
            return wildcardPrice;
        }

        // 3. LiteLLM 远程价格
        if (liteLLMBridge != null && liteLLMBridge.isInitialized()) {
            LiteLLMPrice litePrice = liteLLMBridge.findPrice(model);
            if (litePrice != null) {
                Price price = litePrice.toPrice();
                cache.put(model, new CachedPrice(price, Instant.now().plus(CACHE_TTL)));
                return price;
            }
        }

        // 4. 硬编码 Fallback
        cache.put(model, new CachedPrice(DEFAULT_PRICE, Instant.now().plus(CACHE_TTL)));
        return DEFAULT_PRICE;
    }

    /**
     * 从数据库通配符规则中匹配模型名。
     * <p>
     * 通配符规则示例：claude-opus-* 匹配 claude-opus-4-5-20251101。
     * 多个规则命中时取最低输入价格。
     */
    private Price tryWildcardMatch(String model) {
        List<ModelPriceEntity> rules = getWildcardRules();
        if (rules == null || rules.isEmpty()) return null;

        ModelPriceEntity bestMatch = null;
        for (ModelPriceEntity rule : rules) {
            String pattern = rule.getModel();
            if (pattern == null) continue;

            // 将 SQL LIKE 风格的通配符 * 转换为正则
            String regex = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*");
            if (model.matches(regex)) {
                if (bestMatch == null
                        || rule.getInputPrice().compareTo(bestMatch.getInputPrice()) < 0) {
                    bestMatch = rule;
                }
            }
        }
        return bestMatch != null ? entityToPrice(bestMatch) : null;
    }

    /** 加载通配符规则（带缓存，TTL 10 分钟） */
    private List<ModelPriceEntity> getWildcardRules() {
        if (wildcardRules != null && wildcardRulesLoadedAt != null
                && Duration.between(wildcardRulesLoadedAt, Instant.now()).compareTo(WILDCARD_RULES_TTL) < 0) {
            return wildcardRules;
        }
        wildcardRules = priceRepository.findByWildcard();
        wildcardRulesLoadedAt = Instant.now();
        log.debug("Loaded {} wildcard price rules from DB", wildcardRules.size());
        return wildcardRules;
    }

    /** 将数据库实体转换为 Price 记录 */
    private static Price entityToPrice(ModelPriceEntity entity) {
        return new Price(
                entity.getInputPrice(), entity.getOutputPrice(),
                entity.getCacheWritePrice(), entity.getCacheReadPrice(),
                entity.getCacheWrite5mPrice(), entity.getCacheWrite1hPrice(),
                Boolean.TRUE.equals(entity.getSupportsCacheBreakdown()));
    }

    /**
     * 清除指定模型的缓存 + 通配符规则缓存 —— 管理员修改价格后调用。
     *
     * @param model 模型名称
     */
    public void invalidateCache(String model) {
        cache.remove(model);
        wildcardRules = null;
        wildcardRulesLoadedAt = null;
        log.debug("Price cache invalidated: model={}", model);
    }

    /**
     * 定时清理过期缓存条目 —— 每 5 分钟执行一次。
     */
    @Scheduled(fixedDelay = 300_000)
    public void evictExpired() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt));
    }
}
