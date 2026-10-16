-- Existing populated DB: JPA ddl-auto is disabled. Keep previous indexes.
ALTER TABLE perf_skewed_1m_v1.product
    ADD INDEX idx_product_price (deleted_at, price ASC, id ASC),
    ADD INDEX idx_product_brand_price (brand_id, deleted_at, price ASC, id ASC),
    ALGORITHM=INPLACE,
    LOCK=NONE;
