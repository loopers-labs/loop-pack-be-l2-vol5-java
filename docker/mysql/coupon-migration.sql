-- 기존 DB에는 애플리케이션을 시작하기 전에 적용한다. 새 DB는 init.sql을 사용한다.
-- 캠페인 테이블을 먼저 만들고 쿠폰 테이블을 만든다. 쿠폰 행은 발급할 때 insert하며, seed는 스키마 준비 뒤 로컬 runner에서 캠페인만 만든다.
CREATE TABLE IF NOT EXISTS loopers.coupon_campaigns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    name VARCHAR(255) NOT NULL,
    total_quota BIGINT NOT NULL,
    issued_count BIGINT NOT NULL,
    benefit_description TEXT NOT NULL,
    start_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NOT NULL,
    terminated_at DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 이미 발급된 쿠폰 행은 초기화하지 않는다.
CREATE TABLE IF NOT EXISTS loopers.coupons (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) DEFAULT NULL,
    campaign_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_campaign_user (campaign_id, user_id),
    KEY idx_coupons_owner_issued (user_id, issued_at, id),
    CONSTRAINT fk_coupons_campaign FOREIGN KEY (campaign_id) REFERENCES loopers.coupon_campaigns (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 이전 개발 DB에 user_id FK가 있으면 제거한다. 새 DB와 반복 실행에서는 아무 변경도 하지 않는다.
SET @coupon_user_fk_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = 'loopers' AND table_name = 'coupons'
      AND constraint_name = 'fk_coupons_user' AND constraint_type = 'FOREIGN KEY'
);
SET @coupon_user_fk_ddl = IF(@coupon_user_fk_exists > 0,
    'ALTER TABLE loopers.coupons DROP FOREIGN KEY fk_coupons_user', 'SELECT 1');
PREPARE coupon_user_fk_migration FROM @coupon_user_fk_ddl;
EXECUTE coupon_user_fk_migration;
DEALLOCATE PREPARE coupon_user_fk_migration;

-- 예전 모델(쿠폰 행을 미리 만들어 배정)로 만든 DB를 발급 기록 모델로 바꾼다.
-- 발급된 쿠폰은 그대로 두고 미발급 행만 지운 뒤 사용자와 발급 시각을 필수로 바꾼다. 앱을 멈춘 뒤 적용하며 반복 실행해도 결과가 같다.
DELETE FROM loopers.coupons WHERE user_id IS NULL OR issued_at IS NULL;
SET @coupon_nullable_columns = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = 'loopers' AND table_name = 'coupons'
      AND column_name IN ('user_id', 'issued_at') AND is_nullable = 'YES'
);
SET @coupon_not_null_ddl = IF(@coupon_nullable_columns > 0,
    'ALTER TABLE loopers.coupons MODIFY user_id BIGINT NOT NULL, MODIFY issued_at DATETIME(6) NOT NULL', 'SELECT 1');
PREPARE coupon_not_null_migration FROM @coupon_not_null_ddl;
EXECUTE coupon_not_null_migration;
DEALLOCATE PREPARE coupon_not_null_migration;
