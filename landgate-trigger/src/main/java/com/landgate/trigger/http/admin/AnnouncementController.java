package com.landgate.trigger.http.admin;

import com.landgate.domain.marketing.adapter.repository.IAnnouncementRepository;
import com.landgate.domain.marketing.model.entity.AnnouncementEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 公告管理控制器 —— 系统公告的创建、编辑、发布和过期管理。
 * <p>
 * 路由前缀：{@code /api/v1/announcements}。
 */
public class AnnouncementController {

    @RestController
    @RequestMapping("/api/v1/announcements")
    @RequiredArgsConstructor
    public static class UserAnnouncementController {
        private final IAnnouncementRepository announcementRepository;

        @GetMapping
        public ResponseEntity<?> listActive() {
            List<AnnouncementEntity> active = announcementRepository.findActive();
            return ResponseEntity.ok(Map.of("announcements", active, "total", active.size()));
        }
    }

    @Slf4j
    @RestController
    @RequestMapping("/api/v1/admin/announcements")
    @RequiredArgsConstructor
    public static class AdminAnnouncementController {
        private final IAnnouncementRepository announcementRepository;

        @GetMapping
        public ResponseEntity<?> listAll() {
            List<AnnouncementEntity> all = announcementRepository.findAll();
            return ResponseEntity.ok(Map.of("announcements", all, "total", all.size()));
        }

        @PostMapping
        public ResponseEntity<?> create(@RequestBody AnnouncementEntity announcement) {
            log.info("Admin create announcement: title={}", announcement.getTitle());
            AnnouncementEntity saved = announcementRepository.save(announcement);
            return ResponseEntity.ok(saved);
        }

        @PutMapping("/{id}")
        public ResponseEntity<?> update(@PathVariable Long id,
                                         @RequestBody AnnouncementEntity updates) {
            AnnouncementEntity existing = announcementRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Announcement not found: " + id));
            if (updates.getTitle() != null) existing.setTitle(updates.getTitle());
            if (updates.getContent() != null) existing.setContent(updates.getContent());
            if (updates.getType() != null) existing.setType(updates.getType());
            if (updates.getPublished() != null) existing.setPublished(updates.getPublished());
            if (updates.getPublishAt() != null) existing.setPublishAt(updates.getPublishAt());
            if (updates.getExpiresAt() != null) existing.setExpiresAt(updates.getExpiresAt());
            if (updates.getSortOrder() != null) existing.setSortOrder(updates.getSortOrder());
            log.info("Admin update announcement: id={}", id);
            return ResponseEntity.ok(announcementRepository.save(existing));
        }

        @PostMapping("/{id}/publish")
        public ResponseEntity<?> publish(@PathVariable Long id) {
            AnnouncementEntity announcement = announcementRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Announcement not found: " + id));
            announcement.setPublished(true);
            if (announcement.getPublishAt() == null) {
                announcement.setPublishAt(Instant.now());
            }
            announcementRepository.save(announcement);
            log.info("Admin publish announcement: id={}", id);
            return ResponseEntity.ok(Map.of("success", true));
        }

        @PostMapping("/{id}/unpublish")
        public ResponseEntity<?> unpublish(@PathVariable Long id) {
            AnnouncementEntity announcement = announcementRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Announcement not found: " + id));
            announcement.setPublished(false);
            announcementRepository.save(announcement);
            log.info("Admin unpublish announcement: id={}", id);
            return ResponseEntity.ok(Map.of("success", true));
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<?> delete(@PathVariable Long id) {
            AnnouncementEntity announcement = announcementRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Announcement not found: " + id));
            announcement.setDeletedAt(Instant.now());
            announcementRepository.save(announcement);
            log.info("Admin delete announcement: id={}", id);
            return ResponseEntity.ok(Map.of("success", true));
        }
    }
}
