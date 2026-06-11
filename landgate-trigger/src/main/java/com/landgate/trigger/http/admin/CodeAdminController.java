package com.landgate.trigger.http.admin;

import com.landgate.domain.marketing.adapter.repository.IRedeemCodeRepository;
import com.landgate.domain.marketing.adapter.repository.IPromoCodeRepository;
import com.landgate.domain.marketing.model.entity.PromoCodeEntity;
import com.landgate.domain.marketing.model.entity.RedeemCodeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 码券管理控制器 —— 优惠码和兑换码的创建与管理。
 * <p>
 * 路由前缀：{@code /api/v1/admin/codes}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/codes")
@RequiredArgsConstructor
public class CodeAdminController {

    private final IRedeemCodeRepository redeemCodeRepository;
    private final IPromoCodeRepository promoCodeRepository;

    @GetMapping("/redeem")
    public ResponseEntity<?> listRedeemCodes() {
        var codes = redeemCodeRepository.findAll();
        return ResponseEntity.ok(Map.of("codes", codes, "total", codes.size()));
    }

    @PostMapping("/redeem")
    public ResponseEntity<?> createRedeemCode(@RequestBody RedeemCodeEntity code) {
        log.info("Admin create redeem code: code={}, type={}, amount={}", code.getCode(), code.getType(), code.getAmount());
        RedeemCodeEntity saved = redeemCodeRepository.save(code);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/redeem/{id}")
    public ResponseEntity<?> updateRedeemCode(@PathVariable Long id, @RequestBody RedeemCodeEntity updates) {
        RedeemCodeEntity existing = redeemCodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Redeem code not found: " + id));
        if (updates.getEnabled() != null) existing.setEnabled(updates.getEnabled());
        if (updates.getMaxUses() != null) existing.setMaxUses(updates.getMaxUses());
        if (updates.getExpiresAt() != null) existing.setExpiresAt(updates.getExpiresAt());
        if (updates.getNotes() != null) existing.setNotes(updates.getNotes());
        redeemCodeRepository.save(existing);
        log.info("Admin update redeem code: id={}", id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/redeem/{id}")
    public ResponseEntity<?> deleteRedeemCode(@PathVariable Long id) {
        RedeemCodeEntity code = redeemCodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Redeem code not found: " + id));
        code.setDeletedAt(Instant.now());
        redeemCodeRepository.save(code);
        log.info("Admin delete redeem code: id={}", id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/promo")
    public ResponseEntity<?> listPromoCodes() {
        var codes = promoCodeRepository.findAll();
        return ResponseEntity.ok(Map.of("codes", codes, "total", codes.size()));
    }

    @PostMapping("/promo")
    public ResponseEntity<?> createPromoCode(@RequestBody PromoCodeEntity code) {
        log.info("Admin create promo code: code={}, type={}, value={}", code.getCode(), code.getDiscountType(), code.getDiscountValue());
        PromoCodeEntity saved = promoCodeRepository.save(code);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/promo/{id}")
    public ResponseEntity<?> updatePromoCode(@PathVariable Long id, @RequestBody PromoCodeEntity updates) {
        PromoCodeEntity existing = promoCodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Promo code not found: " + id));
        if (updates.getEnabled() != null) existing.setEnabled(updates.getEnabled());
        if (updates.getMaxUses() != null) existing.setMaxUses(updates.getMaxUses());
        if (updates.getStartsAt() != null) existing.setStartsAt(updates.getStartsAt());
        if (updates.getExpiresAt() != null) existing.setExpiresAt(updates.getExpiresAt());
        if (updates.getNotes() != null) existing.setNotes(updates.getNotes());
        promoCodeRepository.save(existing);
        log.info("Admin update promo code: id={}", id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/promo/{id}")
    public ResponseEntity<?> deletePromoCode(@PathVariable Long id) {
        PromoCodeEntity code = promoCodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Promo code not found: " + id));
        code.setDeletedAt(Instant.now());
        promoCodeRepository.save(code);
        log.info("Admin delete promo code: id={}", id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
