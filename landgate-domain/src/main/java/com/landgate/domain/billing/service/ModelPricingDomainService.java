package com.landgate.domain.billing.service;

import com.landgate.domain.billing.adapter.repository.IModelPriceRepository;
import com.landgate.domain.billing.model.entity.ModelPriceEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型价格查询服务 —— 替代硬编码的定价逻辑。
 * <p>
 * 通过 {@link IModelPriceRepository} 从数据库查询模型价格。
 * 内置内存缓存（TTL 5 分钟），避免每次请求都查询数据库。
 * 缓存未命中时返回硬编码默认价格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelPricingDomainService {

    private final IModelPriceRepository priceRepository;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private static final Price DEFAULT_PRICE = new Price(
            new BigDecimal("3"), new BigDecimal("15"),
            new BigDecimal("3.75"), new BigDecimal("0.3"),
            new BigDecimal("3.75"), new BigDecimal("3.75"), false);

    public record Price(BigDecimal inputPrice, BigDecimal outputPrice,
                        BigDecimal cacheWritePrice, BigDecimal cacheReadPrice,
                        BigDecimal cacheWrite5mPrice, BigDecimal cacheWrite1hPrice,
                        boolean supportsCacheBreakdown) {}

    private record CachedPrice(Price price, Instant expiresAt) {}

    private final ConcurrentHashMap<String, CachedPrice> cache = new ConcurrentHashMap<>();

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
     * 解析模型的全套价格（含缓存读写 + 5m/1h），优先查缓存，未命中则查数据库。
     *
     * @param model 模型名称
     * @return 模型价格对象
     */
    public Price resolve(String model) {
        CachedPrice cached = cache.get(model);
        if (cached != null && Instant.now().isBefore(cached.expiresAt)) {
            return cached.price;
        }

        ModelPriceEntity entity = priceRepository.findByModel(model).orElse(null);

        Price price = entity != null
                ? new Price(entity.getInputPrice(), entity.getOutputPrice(),
                            entity.getCacheWritePrice(), entity.getCacheReadPrice(),
                            entity.getCacheWrite5mPrice(), entity.getCacheWrite1hPrice(),
                            Boolean.TRUE.equals(entity.getSupportsCacheBreakdown()))
                : DEFAULT_PRICE;

        cache.put(model, new CachedPrice(price, Instant.now().plus(CACHE_TTL)));
        return price;
    }

    /**
     * 清除指定模型的缓存 —— 管理员修改价格后调用。
     *
     * @param model 模型名称
     */
    public void invalidateCache(String model) {
        cache.remove(model);
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
