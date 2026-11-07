CREATE TABLE IF NOT EXISTS read_cache_revision (
    namespace VARCHAR(100) NOT NULL PRIMARY KEY,
    revision VARCHAR(36) NOT NULL
) ENGINE=InnoDB;

-- Run while writes are stopped, before enabling/re-enabling the cache.
INSERT INTO read_cache_revision(namespace, revision) VALUES ('catalog', UUID())
ON DUPLICATE KEY UPDATE revision = VALUES(revision);
