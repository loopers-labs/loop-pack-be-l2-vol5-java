-- Apply after 016 in the same database as the Spring Batch metadata.
USE loopers;

CREATE TABLE weekly_ranking_members (
    node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    heartbeat_at DATETIME(6) NOT NULL,
    local_grid INT NOT NULL DEFAULT 2 CHECK (local_grid > 0),
    INDEX ix_weekly_members_alive (heartbeat_at)
) ENGINE=InnoDB;

CREATE TABLE weekly_ranking_schedule_cursor (
    schedule_id TINYINT NOT NULL PRIMARY KEY CHECK (schedule_id=1),
    last_period DATE NOT NULL CHECK (DAYOFWEEK(last_period)=2)
) ENGINE=InnoDB;

CREATE TABLE weekly_ranking_plans (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    period_start DATE NOT NULL,
    revision INT NOT NULL,
    order_partition_count INT NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
    UNIQUE KEY uk_weekly_plan_revision (period_start, revision),
    CONSTRAINT fk_weekly_plan_contract FOREIGN KEY (period_start, revision, snapshot_id)
        REFERENCES weekly_ranking_snapshots (period_start, revision, snapshot_id),
    CHECK (order_partition_count > 0),
    CHECK (state IN ('OPEN','PUBLISHED','SUPERSEDED','CONFLICT'))
) ENGINE=InnoDB;

CREATE TABLE weekly_ranking_order_partitions (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_partition_id INT NOT NULL,
    order_min BIGINT NULL,
    order_max BIGINT NULL,
    is_empty BOOLEAN NOT NULL DEFAULT FALSE,
    preferred_node VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    local_grid INT NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    owner_session CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    execution_id BIGINT NULL,
    frozen_at DATETIME(6) NULL,
    input_count BIGINT NULL,
    input_checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error VARCHAR(512) NULL,
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (snapshot_id, order_partition_id),
    CONSTRAINT fk_weekly_order_partition_plan FOREIGN KEY (snapshot_id) REFERENCES weekly_ranking_plans (snapshot_id),
    INDEX ix_weekly_order_partition_state (state, preferred_node),
    CHECK (order_partition_id >= 0 AND local_grid > 0 AND generation >= 0 AND attempts >= 0),
    CHECK (order_min IS NULL OR order_min > 0),
    CHECK (order_max IS NULL OR order_max > 0),
    CHECK (order_min IS NULL OR order_max IS NULL OR order_min < order_max),
    CHECK (is_empty IN (0,1) AND (is_empty=0 OR (order_min IS NULL AND order_max IS NULL))),
    CHECK (state IN ('READY','RUNNING','RECOVERY_REQUIRED','DONE','CONFLICT','BLOCKED')),
    CHECK ((frozen_at IS NULL AND input_count IS NULL AND input_checksum IS NULL)
        OR (frozen_at IS NOT NULL AND input_count IS NOT NULL AND input_count >= 0 AND input_checksum IS NOT NULL
            AND CHAR_LENGTH(input_checksum)=64))
) ENGINE=InnoDB;
