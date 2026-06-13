package com.landgate.domain.billing.service;

import com.landgate.domain.auth.adapter.repository.IApiKeyRepository;
import com.landgate.domain.auth.model.entity.ApiKeyEntity;
import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.domain.billing.model.valobj.ClaudeUsageVO;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.types.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.landgate.domain.group.model.entity.GroupEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * 计费领域服务 —— 计算 API 调用费用并保存用量日志。
 * <p>
 * 从 {@link ModelPricingDomainService} 获取模型单价（$/百万 tokens），
 * 乘以实际 token 数再除以 1,000,000 得出费用。
 * 支持协议无关的统一入口和 Anthropic 协议的适配入口。
 * <p>
 * <b>计费公式</b>：Anthropic API 的 {@code input_tokens} 不包含缓存读写 token，
 * 三者是<b>互斥</b>的独立类别。因此计费为：
 * <pre>totalCost = inputCost + outputCost + cacheCreationCost + cacheReadCost
 * 其中 inputCost = inputPrice × inputTokens / 1,000,000（全新输入）
 * cacheReadCost = cacheReadPrice × cacheReadTokens / 1,000,000（缓存命中）
 * cacheCreationCost 按 5m/1h 分类或统一单价</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingDomainService {

    private final ModelPricingDomainService pricingService;
    private final IUsageLogRepository usageLogRepository;
    private final IApiKeyRepository apiKeyRepository;

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    /**
     * 计算费用并保存用量日志 —— 协议无关的统一入口。
     * <p>
     * 价格单位为 $/百万 tokens，费用 = 价格 × token数 ÷ 1,000,000。
     * 实际费用 = 总费用 × 分组倍率（groupRateMultiplier）。
     *
     * @param usage                  Token 用量统计
     * @param model                  使用的模型名称
     * @param platform               所属平台
     * @param userId                 用户 ID
     * @param apiKeyId               API Key ID
     * @param accountId              上游账号 ID
     * @param groupId                分组 ID
     * @param groupRateMultiplier    分组倍率（业务加价）
     * @param stream                 是否流式请求
     * @param durationMs             请求耗时（毫秒）
     * @param userAgent              客户端 User-Agent
     * @param ipAddress              客户端 IP 地址
     * @return 保存后的用量日志实体
     */
    public UsageLogEntity calculateAndBuildLog(UsageTokens usage, String model,
                                                String platform, Long userId, Long apiKeyId,
                                                Long accountId, Long groupId,
                                                BigDecimal groupRateMultiplier,
                                                boolean stream, long durationMs,
                                                String userAgent, String ipAddress) {
        return calculateAndBuildLog(usage, model, platform, userId, apiKeyId, accountId, groupId,
                groupRateMultiplier, stream, durationMs, userAgent, ipAddress, false);
    }

    /**
     * 计算费用并保存用量日志，附带客户端断开审计标记。
     */
    public UsageLogEntity calculateAndBuildLog(UsageTokens usage, String model,
                                                String platform, Long userId, Long apiKeyId,
                                                Long accountId, Long groupId,
                                                BigDecimal groupRateMultiplier,
                                                boolean stream, long durationMs,
                                                String userAgent, String ipAddress,
                                                boolean clientDisconnected) {
        return calculateAndBuildLog(usage, model, platform, userId, apiKeyId, accountId, groupId,
                groupRateMultiplier, stream, durationMs, userAgent, ipAddress, clientDisconnected, null);
    }

    /**
     * 计算费用并保存用量日志，使用外部请求 ID 便于和网关应用日志关联。
     */
    public UsageLogEntity calculateAndBuildLog(UsageTokens usage, String model,
                                                String platform, Long userId, Long apiKeyId,
                                                Long accountId, Long groupId,
                                                BigDecimal groupRateMultiplier,
                                                boolean stream, long durationMs,
                                                String userAgent, String ipAddress,
                                                boolean clientDisconnected,
                                                String requestId) {
        BigDecimal inputPrice = pricingService.getInputPrice(model);
        BigDecimal outputPrice = pricingService.getOutputPrice(model);
        BigDecimal cacheWritePrice = pricingService.getCacheWritePrice(model);
        BigDecimal cacheReadPrice = pricingService.getCacheReadPrice(model);

        BigDecimal inputCost = inputPrice.multiply(BigDecimal.valueOf(usage.getInputTokens()))
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputPrice.multiply(BigDecimal.valueOf(usage.getOutputTokens()))
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);

        // 缓存创建费用：优先 5m/1h 分类计费，回退统一单价（对齐 sub2api computeCacheCreationCost）
        BigDecimal cacheCreationCost;
        BigDecimal cacheCreation5mCost = BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP);
        BigDecimal cacheCreation1hCost = BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP);

        boolean supportsBreakdown = pricingService.supportsCacheBreakdown(model);
        BigDecimal cacheWrite5mPrice = pricingService.getCacheWrite5mPrice(model);
        BigDecimal cacheWrite1hPrice = pricingService.getCacheWrite1hPrice(model);

        if (supportsBreakdown && (cacheWrite5mPrice.compareTo(BigDecimal.ZERO) > 0
                || cacheWrite1hPrice.compareTo(BigDecimal.ZERO) > 0)) {
            if (usage.getCacheCreation5mTokens() == 0 && usage.getCacheCreation1hTokens() == 0
                    && usage.getCacheCreationTokens() > 0) {
                // API 未返回 ephemeral 明细，总缓存创建按 5m 单价计费（保守策略）
                cacheCreation5mCost = cacheWrite5mPrice.multiply(BigDecimal.valueOf(usage.getCacheCreationTokens()))
                        .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
                cacheCreation1hCost = BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP);
            } else {
                cacheCreation5mCost = cacheWrite5mPrice.multiply(BigDecimal.valueOf(usage.getCacheCreation5mTokens()))
                        .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
                cacheCreation1hCost = cacheWrite1hPrice.multiply(BigDecimal.valueOf(usage.getCacheCreation1hTokens()))
                        .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
            }
            cacheCreationCost = cacheCreation5mCost.add(cacheCreation1hCost);
        } else {
            cacheCreationCost = cacheWritePrice.multiply(BigDecimal.valueOf(usage.getCacheCreationTokens()))
                    .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
        }

        BigDecimal cacheReadCost = cacheReadPrice.multiply(BigDecimal.valueOf(usage.getCacheReadTokens()))
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal totalCost = inputCost.add(outputCost).add(cacheCreationCost).add(cacheReadCost);

        BigDecimal groupMultiplier = groupRateMultiplier != null ? groupRateMultiplier : BigDecimal.ONE;
        BigDecimal actualCost = totalCost.multiply(groupMultiplier).setScale(10, RoundingMode.HALF_UP);

        UsageLogEntity logEntry = UsageLogEntity.builder()
                .requestId(requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString())
                .userId(userId).apiKeyId(apiKeyId).accountId(accountId).groupId(groupId)
                .model(model).platform(platform).billingMode("token")
                .inputTokens(usage.getInputTokens()).outputTokens(usage.getOutputTokens())
                .cacheCreationTokens(usage.getCacheCreationTokens()).cacheReadTokens(usage.getCacheReadTokens())
                .cacheCreation5mTokens(usage.getCacheCreation5mTokens())
                .cacheCreation1hTokens(usage.getCacheCreation1hTokens())
                .inputCost(inputCost.setScale(10, RoundingMode.HALF_UP))
                .outputCost(outputCost.setScale(10, RoundingMode.HALF_UP))
                .cacheCreationCost(cacheCreationCost.setScale(10, RoundingMode.HALF_UP))
                .cacheReadCost(cacheReadCost.setScale(10, RoundingMode.HALF_UP))
                .cacheCreation5mCost(cacheCreation5mCost.setScale(10, RoundingMode.HALF_UP))
                .cacheCreation1hCost(cacheCreation1hCost.setScale(10, RoundingMode.HALF_UP))
                .totalCost(totalCost.setScale(10, RoundingMode.HALF_UP))
                .actualCost(actualCost)
                .rateMultiplier(groupMultiplier.setScale(4, RoundingMode.HALF_UP))
                .stream(stream).durationMs((int) durationMs)
                .userAgent(userAgent).ipAddress(ipAddress)
                .billingStatus("PENDING")
                .clientDisconnected(clientDisconnected)
                .build();

        UsageLogEntity savedLog = usageLogRepository.save(logEntry);
        log.debug("Billing: platform={}, model={}, tokens={}/{}/{}/{}(5m={},1h={}), total=${}, actual=${}",
                platform, model, usage.getInputTokens(), usage.getOutputTokens(),
                usage.getCacheCreationTokens(), usage.getCacheReadTokens(),
                usage.getCacheCreation5mTokens(), usage.getCacheCreation1hTokens(),
                totalCost, actualCost);
        return savedLog;
    }

    /**
     * 记录一次成功请求，但上游响应中没有可解析 token 用量。
     * <p>
     * 这类日志不参与扣费和额度累计，只用于审计和排查“请求成功但无用量日志”的情况。
     */
    public UsageLogEntity recordNoUsageLog(String requestId,
                                           String model,
                                           String platform,
                                           Long userId,
                                           Long apiKeyId,
                                           Long accountId,
                                           Long groupId,
                                           BigDecimal groupRateMultiplier,
                                           boolean stream,
                                           long durationMs,
                                           String userAgent,
                                           String ipAddress,
                                           boolean clientDisconnected,
                                           String reason) {
        BigDecimal multiplier = groupRateMultiplier != null ? groupRateMultiplier : BigDecimal.ONE;
        UsageLogEntity logEntry = UsageLogEntity.builder()
                .requestId(requestId != null && !requestId.isBlank() ? requestId : UUID.randomUUID().toString())
                .userId(userId).apiKeyId(apiKeyId).accountId(accountId).groupId(groupId)
                .model(model).platform(platform).billingMode("token")
                .inputTokens(0).outputTokens(0)
                .cacheCreationTokens(0).cacheReadTokens(0)
                .cacheCreation5mTokens(0).cacheCreation1hTokens(0)
                .inputCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .outputCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .cacheCreationCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .cacheReadCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .cacheCreation5mCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .cacheCreation1hCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .totalCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .actualCost(BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP))
                .rateMultiplier(multiplier.setScale(4, RoundingMode.HALF_UP))
                .stream(stream).durationMs((int) durationMs)
                .userAgent(userAgent).ipAddress(ipAddress)
                .billingStatus("NO_USAGE")
                .billingError(truncateBillingError(reason))
                .clientDisconnected(clientDisconnected)
                .build();

        UsageLogEntity savedLog = usageLogRepository.save(logEntry);
        log.warn("Billing no-usage: request_id={}, platform={}, model={}, account_id={}, reason={}",
                savedLog.getRequestId(), platform, model, accountId, savedLog.getBillingError());
        return savedLog;
    }

    /** 图片 1K 默认单价（USD） */
    private static final BigDecimal DEFAULT_IMAGE_PRICE_1K = new BigDecimal("0.04");
    /** 图片 2K 尺寸倍率 */
    private static final BigDecimal SIZE_MULTIPLIER_2K = new BigDecimal("1.5");
    /** 图片 4K 尺寸倍率 */
    private static final BigDecimal SIZE_MULTIPLIER_4K = new BigDecimal("2.0");

    /**
     * 图片生成计费 —— 按图片数量和尺寸等级计算费用。
     * <p>
     * 定价优先级：
     * <ol>
     *   <li>组级自定义价格（imagePrice1k / imagePrice2k / imagePrice4k）</li>
     *   <li>硬编码默认价格 $0.04 / 张（1K）</li>
     * </ol>
     * 尺寸倍率：1K = 1x, 2K = 1.5x, 4K = 2x。
     * 实际费用 = 总费用 × 倍率（rateMultiplier）。
     *
     * @param model           模型名称
     * @param imageSize       图片尺寸等级（"1K" / "2K" / "4K"）
     * @param imageCount      生成的图片数量
     * @param group           分组实体（用于获取组级图片定价）
     * @param rateMultiplier  费率倍率
     * @param userId          用户 ID
     * @param apiKeyId        API Key ID
     * @param accountId       上游账号 ID
     * @param requestId       请求唯一标识
     * @param stream          是否流式请求
     * @param durationMs      请求耗时（毫秒）
     * @param userAgent       客户端 User-Agent
     * @param ipAddress       客户端 IP 地址
     * @return 保存后的用量日志实体
     */
    public UsageLogEntity calculateImageCost(String model, String imageSize, int imageCount,
                                              GroupEntity group, BigDecimal rateMultiplier,
                                              Long userId, Long apiKeyId, Long accountId,
                                              String requestId, boolean stream, long durationMs,
                                              String userAgent, String ipAddress) {
        // 获取图片单价（优先组级定价，兜底默认价格）
        BigDecimal unitPrice = resolveImageUnitPrice(group, imageSize);

        // 计算尺寸倍率
        BigDecimal sizeMultiplier = getSizeMultiplier(imageSize);

        // 总费用 = 单价 × 图片数量 × 尺寸倍率
        BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(imageCount))
                .multiply(sizeMultiplier)
                .setScale(10, RoundingMode.HALF_UP);

        // 实际费用 = 总费用 × 倍率
        BigDecimal multiplier = rateMultiplier != null && rateMultiplier.compareTo(BigDecimal.ZERO) >= 0
                ? rateMultiplier : BigDecimal.ZERO;
        BigDecimal actualCost = totalCost.multiply(multiplier).setScale(10, RoundingMode.HALF_UP);

        UsageLogEntity logEntry = UsageLogEntity.builder()
                .requestId(requestId != null ? requestId : UUID.randomUUID().toString())
                .userId(userId).apiKeyId(apiKeyId).accountId(accountId)
                .groupId(group != null ? group.getId() : null)
                .model(model).platform("OPENAI").billingMode("image")
                .imageCount(imageCount).imageSize(imageSize)
                .totalCost(totalCost).actualCost(actualCost)
                .rateMultiplier(multiplier.setScale(4, RoundingMode.HALF_UP))
                .stream(stream).durationMs((int) durationMs)
                .userAgent(userAgent).ipAddress(ipAddress)
                .billingStatus("PENDING")
                .clientDisconnected(false)
                .build();

        UsageLogEntity savedLog = usageLogRepository.save(logEntry);
        log.debug("Image billing: model={}, size={}, count={}, unit_price=${}, total=${}, actual=${}",
                model, imageSize, imageCount, unitPrice, totalCost, actualCost);
        return savedLog;
    }

    /**
     * 解析图片单价 —— 优先组级定价，兜底默认价格。
     */
    private BigDecimal resolveImageUnitPrice(GroupEntity group, String imageSize) {
        if (group != null) {
            BigDecimal groupPrice = getGroupImagePrice(group, imageSize);
            if (groupPrice != null && groupPrice.compareTo(BigDecimal.ZERO) > 0) {
                return groupPrice;
            }
        }
        // 兜底：1K 默认 $0.04，其他尺寸通过倍率调整
        return DEFAULT_IMAGE_PRICE_1K;
    }

    /**
     * 从分组配置获取指定尺寸的图片单价。
     */
    private BigDecimal getGroupImagePrice(GroupEntity group, String imageSize) {
        if (imageSize == null) return null;
        return switch (imageSize) {
            case "1K" -> group.getImagePrice1k();
            case "2K" -> group.getImagePrice2k();
            case "4K" -> group.getImagePrice4k();
            default -> null;
        };
    }

    /**
     * 获取尺寸倍率：1K = 1x, 2K = 1.5x, 4K = 2x。
     */
    private BigDecimal getSizeMultiplier(String imageSize) {
        if (imageSize == null) return BigDecimal.ONE;
        return switch (imageSize) {
            case "2K" -> SIZE_MULTIPLIER_2K;
            case "4K" -> SIZE_MULTIPLIER_4K;
            default -> BigDecimal.ONE;
        };
    }

    /**
     * Anthropic 协议适配入口 —— 将 ClaudeUsageVO 转换为通用 UsageTokens 后计费。
     *
     * @param usage                 Claude/Anthropic 协议的用量统计
     * @param model                 模型名称
     * @param userId                用户 ID
     * @param apiKeyId              API Key ID
     * @param accountId             上游账号 ID
     * @param groupId               分组 ID
     * @param groupRateMultiplier   分组倍率（业务加价）
     * @param stream                是否流式请求
     * @param durationMs            请求耗时（毫秒）
     * @param userAgent             客户端 User-Agent
     * @param ipAddress             客户端 IP 地址
     * @return 保存后的用量日志实体
     */
    public UsageLogEntity calculateAndBuildLog(ClaudeUsageVO usage, String model,
                                                Long userId, Long apiKeyId, Long accountId, Long groupId,
                                                BigDecimal groupRateMultiplier,
                                                boolean stream, long durationMs,
                                                String userAgent, String ipAddress) {
        return calculateAndBuildLog(UsageTokens.fromClaude(usage), model, "ANTHROPIC",
                userId, apiKeyId, accountId, groupId,
                groupRateMultiplier, stream, durationMs, userAgent, ipAddress);
    }

    /**
     * 尝试抢占可自动扣费日志，只有从 PENDING/FAILED 成功切到 SETTLING 的调用方才能继续扣费。
     */
    public boolean tryMarkLogSettling(Long logId) {
        if (logId == null) return false;
        return usageLogRepository.updateBillingStatusFromPendingOrFailed(logId, "SETTLING", null);
    }

    /**
     * 标记用量日志已完成余额扣减但后续处理失败，需要人工对账，不能自动重扣。
     */
    public void markLogSettlingFailed(Long logId, String reason) {
        if (logId == null) return;
        usageLogRepository.updateBillingStatus(logId, "SETTLING", truncateBillingError(reason));
    }

    /**
     * 标记用量日志已完成扣费。
     */
    public void markLogDeducted(Long logId) {
        if (logId == null) return;
        usageLogRepository.updateBillingStatus(logId, "DEDUCTED", null);
    }

    /**
     * 标记用量日志扣费失败，保留错误原因用于对账。
     */
    public void markLogFailed(Long logId, String reason) {
        if (logId == null) return;
        usageLogRepository.updateBillingStatus(logId, "FAILED", truncateBillingError(reason));
    }

    /**
     * 查询超时未完成扣费的日志。
     */
    public java.util.List<UsageLogEntity> findBillingLogsByStatusBefore(String status, Instant cutoff, int limit) {
        return usageLogRepository.findByBillingStatusBefore(status, cutoff, limit);
    }

    /** 限制失败原因长度，避免异常信息过长导致写库失败。 */
    private String truncateBillingError(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    // ---- API Key 配额管理 ----

    /**
     * 校验 API Key 配额 —— 若配置了限额（quota > 0）且已用额度 >= 限额，则拒绝请求。
     *
     * @param apiKeyId API Key ID
     * @throws AuthenticationException 配额已用完
     */
    public void checkQuota(Long apiKeyId) {
        if (apiKeyId == null) return;
        ApiKeyEntity apiKey = apiKeyRepository.findById(apiKeyId).orElse(null);
        if (apiKey == null) return;

        BigDecimal quota = apiKey.getQuota();
        BigDecimal quotaUsed = apiKey.getQuotaUsed();
        if (quota != null && quota.compareTo(BigDecimal.ZERO) > 0) {
            if (quotaUsed != null && quotaUsed.compareTo(quota) >= 0) {
                throw new AuthenticationException("API key quota exceeded. Limit: $" + quota
                        + ", Used: $" + quotaUsed);
            }
        }
    }

    /**
     * 累加 API Key 已用额度 —— 始终累加，无论是否配置限额。
     *
     * @param apiKeyId   API Key ID
     * @param actualCost 本次请求的实际费用（USD）
     */
    public void accumulateQuota(Long apiKeyId, BigDecimal actualCost) {
        if (apiKeyId == null || actualCost == null) return;
        apiKeyRepository.findById(apiKeyId).ifPresent(apiKey -> {
            BigDecimal current = apiKey.getQuotaUsed() != null ? apiKey.getQuotaUsed() : BigDecimal.ZERO;
            apiKey.setQuotaUsed(current.add(actualCost));
            apiKey.setLastUsedAt(Instant.now());
            apiKeyRepository.save(apiKey);
            log.debug("API key quota accumulated: api_key_id={}, used=${}, total=${}",
                    apiKeyId, actualCost, apiKey.getQuotaUsed());
        });
    }
}
