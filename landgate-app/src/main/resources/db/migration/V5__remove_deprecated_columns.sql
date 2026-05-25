ALTER TABLE `groups` DROP COLUMN `platform`;
ALTER TABLE `groups` DROP COLUMN `supported_model_scopes`;
ALTER TABLE `accounts` DROP COLUMN `rate_multiplier`;
ALTER TABLE `usage_logs` DROP COLUMN `account_rate_multiplier`;
