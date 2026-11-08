CREATE TABLE judge_host_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    max_slots INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_judge_host_name (name),
    CHECK (max_slots BETWEEN 1 AND 256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_node (
    id BIGINT NOT NULL AUTO_INCREMENT,
    host_group_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    pool VARCHAR(16) NOT NULL,
    runtime_spec VARCHAR(64) NOT NULL,
    base_url VARCHAR(255) NULL,
    max_slots INT NOT NULL,
    local_slots INT NOT NULL DEFAULT 0,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    revision BIGINT NOT NULL DEFAULT 1,
    enrollment_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    enrollment_until DATETIME(6) NULL,
    credential_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    session_epoch BIGINT NOT NULL DEFAULT 0,
    boot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    heartbeat_at DATETIME(6) NULL,
    healthy BOOLEAN NOT NULL DEFAULT FALSE,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    runtime_version VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_judge_node_name (pool, name),
    UNIQUE KEY uk_judge_node_enroll (enrollment_hash),
    UNIQUE KEY uk_judge_node_credential (credential_hash),
    FOREIGN KEY (host_group_id) REFERENCES judge_host_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_node_boot (
    node_id BIGINT NOT NULL,
    boot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    session_epoch BIGINT NOT NULL,
    PRIMARY KEY (node_id, boot_id),
    FOREIGN KEY (node_id) REFERENCES judge_node(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_node_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_id BIGINT NOT NULL,
    session_epoch BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    runtime_version VARCHAR(100) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    detail VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_node_check (node_id, state, id),
    KEY idx_node_check_expiry (state, expires_at),
    FOREIGN KEY (node_id) REFERENCES judge_node(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_node_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_node_audit (node_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_job_attempt (
    job_id BIGINT NOT NULL,
    lease_epoch BIGINT NOT NULL,
    node_id BIGINT NOT NULL,
    session_epoch BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    outcome VARCHAR(24) NULL,
    receipt_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (job_id, lease_epoch),
    FOREIGN KEY (job_id) REFERENCES judge_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE judge_job
    ADD COLUMN progress_seq BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN progress_phase VARCHAR(16) NULL;

ALTER TABLE judge_settlement_event
    ADD COLUMN last_error VARCHAR(100) NULL;
