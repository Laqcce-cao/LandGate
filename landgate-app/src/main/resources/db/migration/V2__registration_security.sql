-- V2: Registration security enhancements
-- Add email verification support

ALTER TABLE users
    ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 0 AFTER email;

CREATE INDEX idx_users_email_verified ON users (email_verified);
