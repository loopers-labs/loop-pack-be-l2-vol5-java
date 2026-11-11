-- 쿠폰 수용 설정 행 테이블 생성 (8주차 29절 2단계, D130·D133)
-- ddl-auto를 쓰지 않는 환경에서 수용 코드를 배포하기 전에 실행한다. 열 모양은 CouponAdmissionSettingsJpaEntity와 같다.
-- 테이블이 있으면 건드리지 않아 다시 실행해도 결과가 같다. 행은 넣지 않는다(행이 없으면 닫힘으로 본다).
CREATE TABLE IF NOT EXISTS coupon_admission_settings (
    id BIGINT NOT NULL,
    capacity INT NOT NULL,
    closed_at DATETIME(6) NULL,
    closed_reason VARCHAR(500) NULL,
    open BIT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
