CREATE TABLE judge_artifact (
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_size BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_manifest (
    id BIGINT NOT NULL AUTO_INCREMENT,
    submission_type VARCHAR(16) NOT NULL,
    question_id BIGINT NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document JSON NOT NULL,
    total_cases INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_judge_manifest_version (submission_type, question_id, digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_manifest_artifact (
    manifest_id BIGINT NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (manifest_id, digest),
    FOREIGN KEY (manifest_id) REFERENCES judge_manifest(id),
    FOREIGN KEY (digest) REFERENCES judge_artifact(digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE judge_question_manifest (
    submission_type VARCHAR(16) NOT NULL,
    question_id BIGINT NOT NULL,
    manifest_id BIGINT NOT NULL,
    PRIMARY KEY (submission_type, question_id),
    FOREIGN KEY (manifest_id) REFERENCES judge_manifest(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE judge_job
    ADD COLUMN manifest_id BIGINT NULL,
    ADD COLUMN artifact_token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD FOREIGN KEY (manifest_id) REFERENCES judge_manifest(id);
