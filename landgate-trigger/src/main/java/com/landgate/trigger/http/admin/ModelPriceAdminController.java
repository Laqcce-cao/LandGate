package com.landgate.trigger.http.admin;

import com.landgate.domain.billing.adapter.repository.IModelPriceRepository;
import com.landgate.domain.billing.model.entity.ModelPriceEntity;
import com.landgate.domain.billing.service.ModelPricingDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型价格管理控制器 —— 模型定价的 CRUD 管理接口。
 * <p>
 * 路由前缀：{@code /api/v1/admin/model-prices}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/model-prices")
@RequiredArgsConstructor
public class ModelPriceAdminController {

    private final IModelPriceRepository modelPriceRepository;
    private final ModelPricingDomainService pricingDomainService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        List<ModelPriceEntity> prices = modelPriceRepository.findAll(page, size);
        return ResponseEntity.ok(Map.of("prices", prices, "total", modelPriceRepository.count()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return modelPriceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ModelPriceEntity price) {
        log.info("Create model price: model={}, platform={}",
                price.getModel(), price.getPlatform());
        ModelPriceEntity created = modelPriceRepository.save(price);
        pricingDomainService.invalidateCache(price.getModel());
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ModelPriceEntity updates) {
        log.info("Update model price: id={}, model={}, platform={}", id, updates.getModel(), updates.getPlatform());
        ModelPriceEntity existing = modelPriceRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        updates.setId(id);
        ModelPriceEntity updated = modelPriceRepository.save(updates);
        pricingDomainService.invalidateCache(existing.getModel());
        if (!existing.getModel().equals(updates.getModel())) {
            pricingDomainService.invalidateCache(updates.getModel());
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        log.info("Delete model price: id={}", id);
        ModelPriceEntity existing = modelPriceRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        modelPriceRepository.deleteById(id);
        pricingDomainService.invalidateCache(existing.getModel());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/platform/{platform}")
    public ResponseEntity<?> listByPlatform(@PathVariable String platform) {
        List<ModelPriceEntity> prices = modelPriceRepository.findByPlatform(platform);
        return ResponseEntity.ok(Map.of("prices", prices, "total", prices.size()));
    }
}
