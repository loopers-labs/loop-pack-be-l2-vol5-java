-- Explicitly apply only to the prepared benchmark DB. Do not rerun seed/schema creation.
-- MySQL 8.0 atomic DDL; failure must not fall back to a different algorithm or lock mode.
ALTER TABLE week4_r01_index_benchmark.products
    ADD INDEX idx_products_deleted_created
        (deleted, created_at DESC, id DESC),
    ADD INDEX idx_products_deleted_price
        (deleted, price ASC, id DESC),
    ALGORITHM=INPLACE,
    LOCK=NONE;
