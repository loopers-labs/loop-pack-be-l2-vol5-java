-- Binary generated keys preserve case, accents and Unicode code-point differences.
-- NULL keys for deleted rows allow reusing their names. No foreign keys are added.
ALTER TABLE brand ADD COLUMN active_name_key VARBINARY(200) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN CONVERT(name USING binary) ELSE NULL END) VIRTUAL;
ALTER TABLE product ADD COLUMN active_name_key VARBINARY(400) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN CONVERT(name USING binary) ELSE NULL END) VIRTUAL;
ALTER TABLE brand ADD CONSTRAINT uk_brand_active_name UNIQUE (active_name_key);
ALTER TABLE product ADD CONSTRAINT uk_product_active_name UNIQUE (brand_id, active_name_key);
