package com.landgate.trigger.http.redeem;

import com.landgate.api.redeem.dto.RedeemDTOs.RedeemRequest;
import com.landgate.api.redeem.dto.RedeemDTOs.ValidatePromoRequest;
import com.landgate.domain.marketing.service.PromoDomainService;
import com.landgate.domain.marketing.service.RedeemDomainService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户兑换控制器 —— 兑换码兑换和优惠码验证接口。
 * <p>
 * 路由前缀：{@code /api/v1/codes}，需要 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/codes")
@RequiredArgsConstructor
public class RedeemUserController {

    private final RedeemDomainService redeemDomainService;
    private final PromoDomainService promoDomainService;

    @PostMapping("/redeem")
    public ResponseEntity<?> redeem(@RequestBody RedeemRequest req,
                                     HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }

        try {
            var result = redeemDomainService.redeem(req.code(), userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Redeem failed: code={}, user_id={}, error={}", req.code(), userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/promo/validate")
    public ResponseEntity<?> validatePromo(@RequestBody ValidatePromoRequest req) {
        try {
            var result = promoDomainService.validateAndCalculate(req.code(), req.amount());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Promo validation failed: code={}, error={}", req.code(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "valid", false));
        }
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId != null) return userId;

        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof com.landgate.domain.auth.model.entity.UserEntity user) {
            return user.getId();
        }
        return 0L;
    }
}
