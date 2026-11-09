ALTER TABLE judge_node
    ADD COLUMN retired_at DATETIME(6) NULL,
    ADD KEY idx_judge_node_retired (retired_at, id);

ALTER TABLE judge_host_group
    ADD COLUMN retired_at DATETIME(6) NULL,
    ADD KEY idx_judge_host_retired (retired_at, id);
