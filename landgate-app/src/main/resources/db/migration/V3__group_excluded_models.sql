-- V3: Add excluded_models column to groups
-- Allows groups to block specific models by name

ALTER TABLE `groups`
    ADD COLUMN excluded_models TEXT DEFAULT NULL AFTER rpm_limit;
