-- KECE 题集模块 - Schema v18
-- 沿用全库 0 外键规范，内置元数据快照、软删容错标记与用户进度预聚合表

-- 1. 题集主表
CREATE TABLE IF NOT EXISTS `problem_set` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '题集ID',
    `title`             VARCHAR(255) NOT NULL COMMENT '题集标题',
    `subtitle`          VARCHAR(255) DEFAULT NULL COMMENT '副标题/简短标语',
    `description`       TEXT         DEFAULT NULL COMMENT '题集详细介绍（Markdown）',
    `category`          VARCHAR(32)  NOT NULL DEFAULT 'ALGO' COMMENT '题集专用题型：ALGO(算法编程)/DB(数据库SQL)/OBJECTIVE(客观题)',
    `difficulty`        VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM' COMMENT '综合难度：EASY/MEDIUM/HARD',
    `tags`              VARCHAR(255) DEFAULT NULL COMMENT '标签列表，逗号分隔',
    `item_count`        INT          NOT NULL DEFAULT 0 COMMENT '有效题目总数（冗余字段，避免列表页高频 COUNT 子查询）',
    `algo_count`        INT          NOT NULL DEFAULT 0 COMMENT '算法题数量（冗余统计）',
    `db_count`          INT          NOT NULL DEFAULT 0 COMMENT '数据库题数量（冗余统计）',
    `objective_count`   INT          NOT NULL DEFAULT 0 COMMENT '客观题数量（冗余统计）',
    `participant_count` INT          NOT NULL DEFAULT 0 COMMENT '参与/收藏用户数',
    `is_official`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否官方精选：1是 0否',
    `status`            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1公开 0私有草稿 2归档只读 -1软删除',
    `version`           INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本号（并发编辑防覆盖）',
    `created_by`        BIGINT       NOT NULL COMMENT '创建者用户ID',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_status_official_sort` (`status`, `is_official`, `id`),
    KEY `idx_category_status` (`category`, `status`),
    KEY `idx_created_by` (`created_by`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题集主表';

-- 2. 题集章节分组表（支持题集内按阶段/章节结构化编排，不建章节时 section_id = 0）
CREATE TABLE IF NOT EXISTS `problem_set_section` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '章节ID',
    `set_id`      BIGINT       NOT NULL COMMENT '所属题集ID',
    `title`       VARCHAR(128) NOT NULL COMMENT '章节名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '章节导读说明',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '章节排序（升序）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_set_sort` (`set_id`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题集章节分组表';

-- 3. 题集题目条目关联与快照表
CREATE TABLE IF NOT EXISTS `problem_set_item` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '条目ID',
    `set_id`              BIGINT       NOT NULL COMMENT '题集ID',
    `section_id`          BIGINT       NOT NULL DEFAULT 0 COMMENT '所属章节ID（0表示默认章节）',
    `question_type`       VARCHAR(16)  NOT NULL COMMENT '题型：ALGO / DB / OBJECTIVE',
    `question_id`         BIGINT       NOT NULL COMMENT '原题主键ID',
    `title_snapshot`      VARCHAR(255) NOT NULL COMMENT '题目标题快照（原题被删或不可用时兜底展示）',
    `difficulty_snapshot` VARCHAR(32)  DEFAULT NULL COMMENT '难度或子题型快照',
    `source_alive`        TINYINT      NOT NULL DEFAULT 1 COMMENT '原题存活状态：1正常 0原题已下架或删除',
    `sort_order`          INT          NOT NULL DEFAULT 0 COMMENT '排序（升序）',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_set_type_question` (`set_id`, `question_type`, `question_id`),
    KEY `idx_set_section_sort` (`set_id`, `section_id`, `sort_order`),
    KEY `idx_question_reverse` (`question_type`, `question_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题集题目关联快照表';

-- 4. 用户题集进度与收藏预聚合表
CREATE TABLE IF NOT EXISTS `problem_set_user_progress` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `set_id`          BIGINT   NOT NULL COMMENT '题集ID',
    `user_id`         BIGINT   NOT NULL COMMENT '用户ID',
    `is_favorited`    TINYINT  NOT NULL DEFAULT 0 COMMENT '是否收藏/加入学习计划：1是 0否',
    `solved_count`    INT      NOT NULL DEFAULT 0 COMMENT '该题集内已通过题数',
    `solved_item_ids` JSON     DEFAULT NULL COMMENT '已通过的 problem_set_item.id 列表快照',
    `last_item_id`    BIGINT   DEFAULT NULL COMMENT '最近一次练习的条目ID（断点续刷）',
    `last_active_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近活跃时间',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_set_user` (`set_id`, `user_id`),
    KEY `idx_user_fav_active` (`user_id`, `is_favorited`, `last_active_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户题集进度与收藏预聚合表';

