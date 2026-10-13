-- =====================================================================
-- 고객 상품 조회 캐시의 revision (MySQL, DR-37)
--
-- brand·product 에 revision 컬럼을, 목록 revision 을 담는 catalog_revision 테이블과 그 행을 만든다.
-- 운영은 ddl-auto: none 이라 앱 배포 전에 한 번 실행한다. local 프로필은 ddl-auto: update 가 컬럼·테이블을 만들지만
-- catalog_revision 의 행은 만들지 않으므로 마지막 INSERT 는 local 에서도 필요하다.
--
-- 실행
--   docker exec -i docker-mysql-1 mysql -uroot -proot loopers < docs/week5/cache-revision.sql
-- =====================================================================

SET NAMES utf8mb4;

-- 기존 행은 0 에서 시작한다. 캐시가 비어 있는 상태로 배포되므로 어느 값이든 첫 조회는 miss 다.
ALTER TABLE brand   ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE product ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS catalog_revision (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    revision   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT ux_catalog_revision_name UNIQUE (name)
);

-- latest 첫 페이지 목록의 행. 없으면 상품·브랜드 쓰기와 고객 목록 조회가 실패한다.
INSERT INTO catalog_revision (name, revision, created_at, updated_at, deleted_at)
VALUES ('product_list_latest', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)
ON DUPLICATE KEY UPDATE name = name;
