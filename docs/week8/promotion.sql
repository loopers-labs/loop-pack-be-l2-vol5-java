-- =====================================================================
-- 프로모션·쿠폰 발급 테이블 (MySQL, TB-15~TB-18)
--
-- promotion(범용 프로모션), coupon_issue_promotion(선착순 쿠폰 발급 설정, promotion 과 1:1),
-- coupon_issue_stock(남은 발급 수량, 1:1), issued_coupon(발급된 쿠폰 = 사용자 쿠폰)을 만든다. 행은 넣지 않는다.
--
-- 순서
--   운영(ddl-auto: none)은 새 앱(프로모션 엔티티)을 배포하기 전에 실행한다.
--   local 프로필은 ddl-auto: update 가 같은 테이블을 만든다.
--
-- 실행
--   docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docs/week8/promotion.sql
--
-- 이미 있으면 그대로 둔다 (IF NOT EXISTS). 행을 지우지 않는다.
-- =====================================================================

SET NAMES utf8mb4;

-- 엔티티(PromotionModel)와 같은 이름·컬럼
CREATE TABLE IF NOT EXISTS promotion (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(30)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    starts_at   DATETIME(6)  NOT NULL,
    ends_at     DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    deleted_at  DATETIME(6)  NULL,
    PRIMARY KEY (id)
);

-- 엔티티(CouponIssuePromotionModel)와 같은 이름·컬럼. promotion_id 는 FK 없이 ID 만 든다.
CREATE TABLE IF NOT EXISTS coupon_issue_promotion (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    promotion_id    BIGINT      NOT NULL,
    total_quantity  INT         NOT NULL,
    per_user_limit  INT         NOT NULL,
    coupon_spec     JSON        NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    deleted_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_coupon_issue_promotion_promotion UNIQUE (promotion_id)
);

-- 엔티티(CouponIssueStockModel)와 같은 이름·컬럼. 발급 TX 의 조건 갱신(remaining > 0 일 때만 -1)만 줄인다.
CREATE TABLE IF NOT EXISTS coupon_issue_stock (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    promotion_id  BIGINT      NOT NULL,
    remaining     INT         NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    deleted_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_coupon_issue_stock_promotion UNIQUE (promotion_id)
);

-- 엔티티(IssuedCouponModel)와 같은 이름·컬럼. (user_id, promotion_id) 고유: 사용자당 한 장.
-- 고유 인덱스의 선두가 user_id 라 내 쿠폰 조회(user_id 조건)도 이 인덱스를 쓴다.
CREATE TABLE IF NOT EXISTS issued_coupon (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    promotion_id  BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    deleted_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_issued_coupon_user_promotion UNIQUE (user_id, promotion_id)
);
