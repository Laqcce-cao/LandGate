package com.landgate.domain.billing.service;

import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.domain.billing.model.valobj.ClaudeUsageVO;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 计费领域服务 —— 计算 API 调用费用并保存用量日志。
 * <p>
 * 从 {@link ModelPricingDomainService} 获取模型单价（$/百万 tokens），
 * 乘以实际 token 数再除以 1,000,000 得出费用。
 * 支持协议无关的统一入口和 Anthropic 协议的适配入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingDomainService {

    private final ModelPricingDomainService pricingService;
    private final IUsageLogRepository usageLogRepository;

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    /**
     * 计算费用并保存用量日志 —— 协议无关的统一入口。
     * <p>
     * 价格单位为 $/百万 tokens，费用 = 价格 × token数 ÷ 1,000,000。
     * 实际费用 = 总费用 × 账号倍率（accountRateMultiplier）。
     *
     * @param usage                  Token 用量统计
     * @param model                  使用的模型名称
     * @param platform               所属平台
     * @param userId                 用户 ID
     * @param apiKeyId               API Key ID
     * @param accountId              上游账号 ID
     * @param groupId                分组 ID
     * @param accountRateMultiplier  账号倍率
     * @param stream                 是否流式请求
     * @param durationMs             请求耗时（毫秒）
     * @param userAgent              客户端 User-Agent
     * @param ipAddress              客户端 IP 地址
     * @return 保存后的用量日志实体
     */
    public UsageLogEntity calculateAndBuildLog(UsageTokens usage, String model,
                                                String platform, Long userId, Long apiKeyId,
                                                Long accountId, Long groupId,
                                                BigDecimal accountRateMultiplier,
                                                boolean stream, long durationMs,
                                                String userAgent, String ipAddress) {
        BigDecimal inputPrice = pricingService.getInputPrice(model, groupId);
        BigDecimal outputPrice = pricingService.getOutputPrice(model, groupId);
        BigDecimal cacheWritePrice = pricingService.getCacheWritePrice(model, groupId);
        BigDecimal cacheReadPrice = pricingService.getCacheReadPrice(model, groupId);

        BigDecimal inputCost = inputPrice.multiply(BigDecimal.valueOf(usage.getInputTokens()))
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal outputCost = outputPrice.multiply(BigDecimal.valueOf(usage.getOutputTokens()))
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal cacheCreationCost = cacheWritePrice.multiply(BigDecimal.valueOf(usage.getCacheCreationTokens()))
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal cacheReadCost = cacheReadPrice.multiply(BigDecimal.valueOf(usage.getCacheReadTokens()))
                .divide(ONE_MILLION, 10, RoundingMode.HALF_UP);
        BigDecimal totalCost = inputCost.add(outputCost).add(cacheCreationCost).add(cacheReadCost);

        BigDecimal acctMultiplier = accountRateMultiplier != null ? accountRateMultiplier : BigDecimal.ONE;
        BigDecimal actualCost = totalCost.multiply(acctMultiplier).setScale(10, RoundingMode.HALF_UP);

        UsageLogEntity logEntry = UsageLogEntity.builder()
                .requestId(UUID.randomUUID().toString())
                .userId(userId).apiKeyId(apiKeyId).accountId(accountId).groupId(groupId)
                .model(model).platform(platform).billingMode("token")
                .inputTokens(usage.getInputTokens()).outputTokens(usage.getOutputTokens())
                .cacheCreationTokens(usage.getCacheCreationTokens()).cacheReadTokens(usage.getCacheReadTokens())
                .inputCost(inputCost.setScale(10, RoundingMode.HALF_UP))
                .outputCost(outputCost.setScale(10, RoundingMode.HALF_UP))
                .cacheCreationCost(cacheCreationCost.setScale(10, RoundingMode.HALF_UP))
                .cacheReadCost(cacheReadCost.setScale(10, RoundingMode.HALF_UP))
                .totalCost(totalCost.setScale(10, RoundingMode.HALF_UP))
                .actualCost(actualCost)
                .rateMultiplier(BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP))
                .accountRateMultiplier(acctMultiplier)
                .stream(stream).durationMs((int) durationMs)
                .userAgent(userAgent).ipAddress(ipAddress)
                .build();

        usageLogRepository.save(logEntry);
        log.info("Billing: platform={}, model={}, tokens={}/{}/{}/{}, total=${}, actual=${}",
                platform, model, usage.getInputTokens(), usage.getOutputTokens(),
                usage.getCacheCreationTokens(), usage.getCacheReadTokens(), totalCost, actualCost);
        return logEntry;
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
     * @param accountRateMultiplier 账号倍率
     * @param stream                是否流式请求
     * @param durationMs            请求耗时（毫秒）
     * @param userAgent             客户端 User-Agent
     * @param ipAddress             客户端 IP 地址
     * @return 保存后的用量日志实体
     */
    public UsageLogEntity calculateAndBuildLog(ClaudeUsageVO usage, String model,
                                                Long userId, Long apiKeyId, Long accountId, Long groupId,
                                                BigDecimal accountRateMultiplier,
                                                boolean stream, long durationMs,
                                                String userAgent, String ipAddress) {
        return calculateAndBuildLog(UsageTokens.fromClaude(usage), model, "ANTHROPIC",
                userId, apiKeyId, accountId, groupId,
                accountRateMultiplier, stream, durationMs, userAgent, ipAddress);
    }
}
