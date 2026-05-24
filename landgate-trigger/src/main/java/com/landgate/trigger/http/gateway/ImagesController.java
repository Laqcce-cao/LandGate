package com.landgate.trigger.http.gateway;

import com.landgate.api.images.dto.OpenAIImagesRequest;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.domain.images.service.ImageGenerationIntent;
import com.landgate.trigger.images.ImagesService;
import com.landgate.trigger.gateway.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 图片网关控制器 —— 处理 OpenAI Images API 的代理转发。
 * <p>
 * 端点：POST /images/generations、POST /images/edits。
 * 已在 SecurityConfig.gatewayFilterChain 中配置 API Key 认证。
 * <p>
 * 与文本网关不同，图片请求不走 AbstractGatewayHandler 模板
 * （该模板为 token 计费设计），而是使用独立的 ImagesService 链路。
 * <p>
 * 关键设计：
 * <ul>
 *   <li>用原始 HttpServletRequest 读取 byte[] body，支持 multipart/form-data</li>
 *   <li>图片数据仅在内存中保留，不持久化到磁盘</li>
 *   <li>复用现有的 AccountSelector、ConcurrencyService、SessionHashService 等基础设施</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ImagesController {

    private final ImagesService imagesService;
    private final AccountSelector accountSelector;
    private final GetAccessTokenService getAccessTokenService;
    private final ConcurrencyService concurrencyService;
    private final SessionHashService sessionHashService;
    private final BillingDomainService billingDomainService;
    private final BalanceDomainService balanceDomainService;
    private final IGroupRepository groupRepository;

    /** 最大 failover 切换次数 */
    private static final int MAX_FAILOVER_SWITCHES = 3;

    /** 图片并发槽位的虚拟 accountId（全局限制） */
    private static final long IMAGE_CONCURRENCY_ID = -1L;
    /** 图片全局最大并发数 */
    private static final int IMAGE_MAX_CONCURRENCY = 10;

    /**
     * 处理图片生成请求（JSON 或 multipart/form-data）。
     * <p>
     * 请求路径：POST /images/generations
     */
    @PostMapping("/images/generations")
    public void generations(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleImageRequest(request, response);
    }

    /**
     * 处理图片编辑请求（JSON 或 multipart/form-data）。
     * <p>
     * 请求路径：POST /images/edits
     */
    @PostMapping("/images/edits")
    public void edits(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handleImageRequest(request, response);
    }

    /**
     * 统一的图片请求处理流程。
     * <ol>
     *   <li>读取原始字节流（支持 JSON 和 multipart）</li>
     *   <li>鉴权：提取 api_key_id、user_id、group_id</li>
     *   <li>权限检查：group.allowImageGeneration</li>
     *   <li>解析请求 → OpenAIImagesRequest</li>
     *   <li>会话粘性 + 并发控制</li>
     *   <li>账户选择（failover 循环）</li>
     *   <li>上游转发 + 响应透传</li>
     *   <li>图片计费 + 余额扣减</li>
     * </ol>
     */
    private void handleImageRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Instant startTime = Instant.now();
        String path = request.getServletPath();

        // ---- Step 1: 读取原始请求体 ----
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to read request body: path={}", path, e);
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":{\"message\":\"Failed to read request body\"}}");
            return;
        }

        String contentType = request.getContentType();
        log.info("Image request: path={}, content_type={}, size={} bytes", path, contentType, body.length);

        // ---- Step 2: 鉴权 ----
        Long apiKeyId = (Long) request.getAttribute("api_key_id");
        Long userId = (Long) request.getAttribute("user_id");
        Long groupId = (Long) request.getAttribute("group_id");
        String requestId = (String) request.getAttribute("request_id");

        if (apiKeyId == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Missing API key\",\"type\":\"authentication_error\",\"param\":null,\"code\":null}}");
            return;
        }

        // ---- Step 3: 加载分组 + 权限检查 ----
        GroupEntity group = groupRepository.findById(groupId)
                .filter(g -> g.getDeletedAt() == null)
                .orElse(null);
        if (group == null) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"API key has no group assigned.\",\"type\":\"permission_error\",\"param\":null,\"code\":null}}");
            return;
        }

        if (!ImageGenerationIntent.groupAllowsImageGeneration(group)) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Image generation is not enabled for this group.\",\"type\":\"permission_error\",\"param\":null,\"code\":null}}");
            return;
        }

        // ---- Step 4: 解析请求 ----
        OpenAIImagesRequest parsed = imagesService.parseImagesRequest(body, contentType, path);

        // ---- Step 5: 并发控制（全局图片并发槽位） ----
        boolean slotAcquired = concurrencyService.tryAcquire(IMAGE_CONCURRENCY_ID, IMAGE_MAX_CONCURRENCY);
        if (!slotAcquired) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Image generation is currently overloaded. Please try again later.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}");
            return;
        }

        try {
            // ---- Step 6: 会话粘性（包含 model，不同模型粘到不同账号） ----
            String capability = imagesService.classifyCapability(parsed);
            String model = parsed.getModel();
            String sessionHash = sessionHashService.generateHash(request, userId, null, model);
            Long stickyAccountId = sessionHashService.getBoundAccount(sessionHash);

            // ---- Step 7: 账户选择 + failover 循环 ----
            AccountEntity account = null;
            Set<Long> excludedAccountIds = new HashSet<>();

            for (int failoverCount = 0; failoverCount < MAX_FAILOVER_SWITCHES; failoverCount++) {
                // 7a. 优先使用粘性账户
                if (stickyAccountId != null && failoverCount == 0) {
                    account = accountSelector.getById(stickyAccountId);
                }
                // 7b. 正常选择
                if (account == null) {
                    account = accountSelector.selectAccountForImages(
                            group, model, capability, excludedAccountIds);
                }

                if (account == null) {
                    log.warn("No available image account: group_id={}, model={}, capability={}",
                            groupId, model, capability);
                    break;
                }

                // 7c. 获取访问令牌
                String accessToken = getAccessTokenService.getAccessToken(account);
                if (accessToken == null || accessToken.isEmpty()) {
                    log.warn("Failed to get access token for image account: account_id={}", account.getId());
                    excludedAccountIds.add(account.getId());
                    account = null;
                    continue;
                }

                // 7d. 账户级并发控制
                boolean accountSlot = concurrencyService.tryAcquire(
                        account.getId(), account.getConcurrency());
                if (!accountSlot) {
                    log.debug("Account concurrency full for image: account_id={}", account.getId());
                    excludedAccountIds.add(account.getId());
                    account = null;
                    continue;
                }

                try {
                    // 7e. 转发到上游
                    HttpResponse<InputStream> upstreamResp = imagesService.forwardImages(
                            account, parsed, accessToken);

                    int statusCode = upstreamResp.statusCode();

                    // 7f. 处理成功响应
                    if (statusCode >= 200 && statusCode < 300) {
                        // 设置响应状态码和 Content-Type
                        response.setStatus(statusCode);
                        response.setContentType(
                                upstreamResp.headers().firstValue("Content-Type")
                                        .orElse("application/json"));
                        response.setCharacterEncoding("UTF-8");

                        // 写响应头（透传上游关键头）
                        upstreamResp.headers().map().forEach((headerName, headerValues) -> {
                            if (!"Content-Type".equalsIgnoreCase(headerName)
                                    && !"Transfer-Encoding".equalsIgnoreCase(headerName)) {
                                headerValues.forEach(value ->
                                        response.addHeader(headerName, value));
                            }
                        });

                        long durationMs = Duration.between(startTime, Instant.now()).toMillis();
                        int imageCount;
                        String imageSize = parsed.getSizeTier();

                        if (parsed.isStream()) {
                            // 流式响应：设置 SSE 头 + 逐行透传
                            response.setContentType("text/event-stream");
                            response.setHeader("Cache-Control", "no-cache");
                            response.setHeader("Connection", "keep-alive");
                            response.setHeader("X-Accel-Buffering", "no");
                            String sseData = imagesService.handleStreamingResponse(
                                    upstreamResp, response.getOutputStream());
                            imageCount = imagesService.extractImageCount(sseData);
                        } else {
                            // 非流式响应：直接写入
                            byte[] respBody = imagesService.handleNonStreamingResponse(
                                    upstreamResp, response.getOutputStream());
                            imageCount = imagesService.extractImageCount(
                                    new String(respBody, java.nio.charset.StandardCharsets.UTF_8));
                        }

                        // 绑定会话
                        sessionHashService.bindSession(sessionHash, account.getId());
                        // 更新最后使用时间
                        accountSelector.updateLastUsed(account.getId());

                        // 记录用量日志
                        log.info("Image request completed: model={}, account={}, images={}, size={}, stream={}, duration={}ms",
                                model, account.getName(), imageCount, imageSize, parsed.isStream(), durationMs);

                        // ---- Step 8: 图片计费 + 余额扣减 ----
                        // 解析费率倍率（优先图片独立倍率）
                        BigDecimal rateMultiplier = resolveImageRateMultiplier(group);

                        billingDomainService.calculateImageCost(
                                model, imageSize, imageCount,
                                group, rateMultiplier,
                                userId, apiKeyId, account.getId(),
                                requestId, parsed.isStream(), durationMs,
                                request.getHeader("User-Agent"),
                                request.getRemoteAddr());

                        // 扣减余额
                        if (imageCount > 0) {
                            BigDecimal actualCost = calculateActualImageCost(
                                    group, imageSize, imageCount, rateMultiplier);
                            balanceDomainService.deduct(userId, actualCost);
                        }

                        return; // Success
                    }

                    // 7g. 处理错误响应
                    if (statusCode == 401) {
                        // 401 认证失败 → 排除该账户重试
                        log.warn("Image account auth failed: account_id={}", account.getId());
                        excludedAccountIds.add(account.getId());
                        account = null;
                        continue;
                    }

                    if (statusCode == 429 || statusCode == 529 || statusCode >= 500) {
                        // 限流/过载/服务端错误 → 标记不健康 + 降级
                        log.warn("Image upstream error: status={}, account_id={}", statusCode, account.getId());
                        if (statusCode == 429) {
                            accountSelector.markRateLimited(account.getId(),
                                    Instant.now().plusSeconds(60));
                        } else if (statusCode == 529) {
                            accountSelector.markOverloaded(account.getId(),
                                    Instant.now().plusSeconds(30));
                        }
                        excludedAccountIds.add(account.getId());
                        account = null;
                        continue;
                    }

                    // 其他错误 → 直接透传错误响应
                    response.setStatus(statusCode);
                    response.setContentType(
                            upstreamResp.headers().firstValue("Content-Type")
                                    .orElse("application/json"));
                    response.setCharacterEncoding("UTF-8");
                    try (InputStream errIn = upstreamResp.body()) {
                        byte[] errBody = errIn.readAllBytes();
                        response.getOutputStream().write(errBody);
                    }
                    return;

                } finally {
                    concurrencyService.release(account.getId());
                }
            }

            // Failover 循环耗尽 → 503
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"No available upstream account for image generation. Please try again later.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(502);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Request was interrupted.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}");
        } catch (IOException e) {
            log.error("Image upstream IO error: path={}", path, e);
            // 如果响应尚未提交，写入错误
            if (!response.isCommitted()) {
                response.setStatus(502);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"error\":{\"message\":\"Upstream connection failed.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}");
            }
        } finally {
            concurrencyService.release(IMAGE_CONCURRENCY_ID);
        }
    }

    /**
     * 解析图片计费倍率 —— 优先使用分组的图片独立倍率。
     * <p>
     * 如果分组设置了 imageRateIndependent=true，则使用 imageRateMultiplier；
     * 否则使用分组通用 rateMultiplier。
     *
     * @param group 分组实体
     * @return 图片计费倍率
     */
    private BigDecimal resolveImageRateMultiplier(GroupEntity group) {
        if (group != null && Boolean.TRUE.equals(group.getImageRateIndependent())) {
            BigDecimal imageRate = group.getImageRateMultiplier();
            if (imageRate != null) {
                if (imageRate.compareTo(BigDecimal.ZERO) < 0) {
                    return BigDecimal.ZERO; // 负倍率按 0 处理
                }
                return imageRate;
            }
        }
        // 回退到分组通用倍率
        return group != null ? group.getRateMultiplier() : BigDecimal.ONE;
    }

    /**
     * 计算图片实际扣费金额。
     * <p>
     * 与 BillingDomainService.calculateImageCost 保持一致的定价逻辑。
     *
     * @param group          分组实体
     * @param imageSize      尺寸等级
     * @param imageCount     图片数量
     * @param rateMultiplier 倍率
     * @return 实际扣费金额
     */
    private BigDecimal calculateActualImageCost(GroupEntity group, String imageSize,
                                                  int imageCount, BigDecimal rateMultiplier) {
        if (imageCount <= 0) return BigDecimal.ZERO;

        // 获取单价（优先组级定价）
        BigDecimal unitPrice = new BigDecimal("0.04"); // 默认 $0.04
        if (group != null) {
            BigDecimal groupPrice = switch (imageSize != null ? imageSize : "2K") {
                case "1K" -> group.getImagePrice1k();
                case "2K" -> group.getImagePrice2k();
                case "4K" -> group.getImagePrice4k();
                default -> null;
            };
            if (groupPrice != null && groupPrice.compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = groupPrice;
            }
        }

        // 尺寸倍率
        BigDecimal sizeMultiplier = switch (imageSize != null ? imageSize : "2K") {
            case "2K" -> new BigDecimal("1.5");
            case "4K" -> new BigDecimal("2.0");
            default -> BigDecimal.ONE;
        };

        BigDecimal multiplier = rateMultiplier != null && rateMultiplier.compareTo(BigDecimal.ZERO) >= 0
                ? rateMultiplier : BigDecimal.ZERO;

        return unitPrice.multiply(BigDecimal.valueOf(imageCount))
                .multiply(sizeMultiplier)
                .multiply(multiplier);
    }
}
