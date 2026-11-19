-- 쿠폰 수용 설정 행 테이블 생성 (8주차 29절 2·3단계, D130·D133·D138)
-- ddl-auto를 쓰지 않는 환경에서 수용 코드를 배포하기 전에 실행한다. 열 모양은 CouponAdmissionSettingsJpaEntity와 같다.
-- 다시 실행해도 결과가 같다. 행은 넣지 않는다(행이 없으면 닫힘으로 본다).
CREATE TABLE IF NOT EXISTS coupon_admission_settings (
    id BIGINT NOT NULL,
    capacity INT NOT NULL,
    closed_at DATETIME(6) NULL,
    closed_reason VARCHAR(500) NULL,
    epoch BIGINT NOT NULL DEFAULT 1,
    open BIT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

-- 2단계 스크립트로 만든 테이블에는 epoch 열이 없다. MySQL 8에는 ADD COLUMN IF NOT EXISTS가 없어 information_schema로 확인한다.
-- 기존 행은 1이 된다.
SET @add_coupon_admission_epoch = (
    SELECT IF(COUNT(*) = 0, 'ALTER TABLE coupon_admission_settings ADD COLUMN epoch BIGINT NOT NULL DEFAULT 1', 'DO 0')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'coupon_admission_settings'
      AND column_name = 'epoch'
);
PREPARE add_coupon_admission_epoch FROM @add_coupon_admission_epoch;
EXECUTE add_coupon_admission_epoch;
DEALLOCATE PREPARE add_coupon_admission_epoch;
