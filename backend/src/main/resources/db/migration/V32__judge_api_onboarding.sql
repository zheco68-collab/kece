CREATE TABLE judge_control_identity (
    id TINYINT NOT NULL PRIMARY KEY,
    control_plane_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT ck_judge_control_identity_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO judge_control_identity (id, control_plane_id) VALUES (1, UUID());

CREATE TABLE judge_host_identity (
    host_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    host_group_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_judge_host_identity_group (host_group_id),
    FOREIGN KEY (host_group_id) REFERENCES judge_host_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_node_identity (
    node_id BIGINT NOT NULL PRIMARY KEY,
    instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    public_key VARBINARY(128) NOT NULL,
    key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    binding_generation BIGINT NOT NULL DEFAULT 1,
    revoked_at DATETIME(6) NULL,
    UNIQUE KEY uk_judge_node_identity_instance (instance_id),
    UNIQUE KEY uk_judge_node_identity_key (key_fingerprint),
    FOREIGN KEY (node_id) REFERENCES judge_node(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_node_endpoint (
    endpoint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    normalized_url VARCHAR(1024) NOT NULL,
    node_id BIGINT NULL,
    active_connection_id BIGINT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_judge_node_endpoint_node (node_id),
    FOREIGN KEY (node_id) REFERENCES judge_node(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_node_connection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retry_of_id BIGINT NULL,
    endpoint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    normalized_url VARCHAR(1024) NOT NULL,
    instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    active_instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    node_id BIGINT NULL,
    capability_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    binding_generation BIGINT NULL,
    expected_node_revision BIGINT NULL,
    claim_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    credential_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    check_id BIGINT NULL,
    check_session_epoch BIGINT NULL,
    check_runtime_version VARCHAR(100) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    error_code VARCHAR(64) NULL,
    error_detail VARCHAR(500) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_run_at DATETIME(6) NOT NULL,
    runner_id VARCHAR(64) NULL,
    runner_epoch BIGINT NOT NULL DEFAULT 0,
    runner_lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    UNIQUE KEY uk_judge_connection_idempotency (actor_id, idempotency_key),
    UNIQUE KEY uk_judge_connection_claim (claim_id),
    UNIQUE KEY uk_judge_connection_active_instance (active_instance_id),
    KEY idx_judge_connection_due (status, next_run_at, id),
    KEY idx_judge_connection_node (node_id, id),
    KEY idx_judge_connection_endpoint (endpoint_hash, id),
    FOREIGN KEY (node_id) REFERENCES judge_node(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_claim_nonce (
    nonce_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    expires_at DATETIME(6) NOT NULL,
    KEY idx_judge_claim_nonce_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE judge_node MODIFY base_url VARCHAR(1024) NULL;
