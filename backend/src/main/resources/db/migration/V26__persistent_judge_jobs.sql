CREATE TABLE judge_pool_route (
    pool VARCHAR(16) NOT NULL,
    scene VARCHAR(16) NOT NULL,
    dispatch_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (pool, scene),
    CHECK (dispatch_mode IN ('LEGACY', 'PERSISTENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    submission_type VARCHAR(16) NOT NULL,
    submission_id BIGINT NOT NULL,
    generation INT NOT NULL,
    pool VARCHAR(16) NOT NULL,
    scene VARCHAR(16) NOT NULL,
    route_revision BIGINT NOT NULL,
    runtime_spec VARCHAR(64) NOT NULL,
    request_snapshot MEDIUMTEXT NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    status_version BIGINT NOT NULL DEFAULT 1,
    total_cases INT NOT NULL,
    finished_cases INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    node_id BIGINT NULL,
    host_group_id BIGINT NULL,
    session_epoch BIGINT NULL,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    result_json JSON NULL,
    result_digest CHAR(64) CHARACTER SET ascii NULL,
    last_error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_judge_job_generation (submission_type, submission_id, generation),
    KEY idx_judge_job_claim (pool, state, available_at, id),
    KEY idx_judge_job_expiry (state, lease_until, id),
    KEY idx_judge_job_node (node_id, state),
    KEY idx_judge_job_host (host_group_id, state),
    CHECK (submission_type IN ('ALGO', 'DATABASE')),
    CHECK (JSON_VALID(request_snapshot))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE submission ADD COLUMN current_judge_job_id BIGINT NULL;
ALTER TABLE db_submission ADD COLUMN current_judge_job_id BIGINT NULL;

CREATE TABLE judge_settlement_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_judge_settlement_event (job_id, event_type),
    KEY idx_judge_settlement_due (state, available_at, id),
    FOREIGN KEY (job_id) REFERENCES judge_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
