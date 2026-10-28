-- ==============================================================
-- KECE V19__question_tags.sql
-- 算法题目分类与知识点标签体系
-- ==============================================================

-- 1. 算法标签字典表
CREATE TABLE IF NOT EXISTS `tag` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '标签ID',
    `name`        VARCHAR(64)  NOT NULL COMMENT '标签名称（唯一）',
    `category`    VARCHAR(32)  NOT NULL DEFAULT 'ALGO' COMMENT '所属一级类目',
    `color`       VARCHAR(16)  DEFAULT NULL COMMENT '标签色值（如 #2b8a3e）',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '排序权重（降序显示）',
    `usage_count` INT          NOT NULL DEFAULT 0 COMMENT '引用该标签的题目数',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_cat_sort` (`category`, `sort_order` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目分类标签字典表';

-- 2. 题目主表新增标签冗余字段（供列表秒级无联表渲染）
ALTER TABLE `question` 
ADD COLUMN `tags` VARCHAR(255) DEFAULT NULL COMMENT '标签列表，逗号分隔（如：基础语法,模拟）' 
AFTER `languages`;

-- 3. 题目与标签多对多关联映射表
CREATE TABLE IF NOT EXISTS `question_tag` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `question_id` BIGINT   NOT NULL COMMENT '题目ID',
    `tag_id`      BIGINT   NOT NULL COMMENT '标签ID',
    `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_q_t` (`question_id`, `tag_id`),
    KEY `idx_tag_q` (`tag_id`, `question_id`),
    KEY `idx_q` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目-标签关联映射表';

-- 4. 初始化预设标准标签库
INSERT IGNORE INTO `tag` (`name`, `category`, `sort_order`) VALUES
('基础语法', 'ALGO_BASIC', 100),
('变量与I/O', 'ALGO_BASIC', 95),
('流程控制', 'ALGO_BASIC', 90),
('模拟', 'ALGO_BASIC', 85),
('数学', 'MATH', 80),
('字符串', 'DATA_STRUCTURE', 75),
('数组', 'DATA_STRUCTURE', 70),
('递归与递推', 'ALGO_ADVANCED', 65),
('二分查找', 'ALGO_BASIC', 60),
('双指针', 'ALGO_BASIC', 55),
('贪心', 'ALGO_BASIC', 50),
('深度优先搜索', 'SEARCH', 45),
('广度优先搜索', 'SEARCH', 40),
('动态规划', 'ALGO_ADVANCED', 35),
('栈与队列', 'DATA_STRUCTURE', 30),
('哈希表', 'DATA_STRUCTURE', 25),
('树与二叉树', 'DATA_STRUCTURE', 20),
('位运算', 'MATH', 15),
('图论', 'DATA_STRUCTURE', 10);

-- 5. 初始化存量题目标签
UPDATE `question` SET `tags` = '基础语法,变量与I/O' WHERE `id` >= 34 AND `id` <= 56;
UPDATE `question` SET `tags` = '基础语法,流程控制' WHERE `id` = 2;
UPDATE `question` SET `tags` = '递归与递推,动态规划' WHERE `id` = 3;
UPDATE `question` SET `tags` = '动态规划,贪心' WHERE `id` = 16;
