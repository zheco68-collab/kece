-- KECE 用户每日 AC 打卡热力图预聚合表 - Schema v15

CREATE TABLE IF NOT EXISTS `user_daily_ac` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT NOT NULL COMMENT '用户ID',
    `ac_date`     DATE NOT NULL COMMENT '打卡日期 (yyyy-MM-dd)',
    `algo_count`  INT NOT NULL DEFAULT 0 COMMENT '当天算法编程题AC数',
    `db_count`    INT NOT NULL DEFAULT 0 COMMENT '当天SQL数据库题AC数',
    `total_count` INT NOT NULL DEFAULT 0 COMMENT '当天AC总数',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `ac_date`),
    KEY `idx_user_date` (`user_id`, `ac_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户每日AC提交汇总表（热力图优化）';

-- 迁移历史算法题目 AC 数据
INSERT INTO `user_daily_ac` (`user_id`, `ac_date`, `algo_count`, `db_count`, `total_count`)
SELECT 
    `user_id`,
    DATE(`created_at`) AS `ac_date`,
    COUNT(*) AS `algo_count`,
    0 AS `db_count`,
    COUNT(*) AS `total_count`
FROM `submission`
WHERE `status` = 'ACCEPTED'
GROUP BY `user_id`, DATE(`created_at`)
ON DUPLICATE KEY UPDATE 
    `algo_count` = VALUES(`algo_count`),
    `total_count` = `total_count` + VALUES(`algo_count`);

-- 迁移历史数据库题目 AC 数据
INSERT INTO `user_daily_ac` (`user_id`, `ac_date`, `algo_count`, `db_count`, `total_count`)
SELECT 
    `user_id`,
    DATE(`created_at`) AS `ac_date`,
    0 AS `algo_count`,
    COUNT(*) AS `db_count`,
    COUNT(*) AS `total_count`
FROM `db_submission`
WHERE `status` = 'ACCEPTED'
GROUP BY `user_id`, DATE(`created_at`)
ON DUPLICATE KEY UPDATE 
    `db_count` = VALUES(`db_count`),
    `total_count` = `total_count` + VALUES(`db_count`);
