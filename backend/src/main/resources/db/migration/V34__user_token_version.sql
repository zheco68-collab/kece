-- KECE 令牌版本号扩展，支持改密/换绑邮箱/后台重置或禁用账号时全端强制下线
ALTER TABLE `user`
    ADD COLUMN `token_version` INT NOT NULL DEFAULT 0 COMMENT 'JWT 令牌版本号' AFTER `status`;
