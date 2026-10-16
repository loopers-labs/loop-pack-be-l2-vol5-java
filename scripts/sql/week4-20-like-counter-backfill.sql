-- 기존 DB 전환: 상품·좋아요 쓰기 앱을 중지한 상태에서 같은 연결로 전체 실행.
-- 대상 DB는 연결 시 지정. 절차·결과: ../../docs/week4/implementation-log.md#counter-production

-- 준비: 새 코드의 validate가 요구하는 카운터 스키마.
CREATE TABLE IF NOT EXISTS product_like_count (
    product_id BIGINT NOT NULL PRIMARY KEY,
    like_count BIGINT NOT NULL,
    CONSTRAINT chk_product_like_count_nonnegative CHECK (like_count >= 0)
) ENGINE=InnoDB;

-- 초기 채움 동안 원본 쓰기를 차단한다(DDL·LOCK은 implicit commit).
LOCK TABLES product READ, product_like READ, product_like_count WRITE;

-- 삭제 상품·좋아요 0개도 포함. 재실행 시 원본 COUNT로 덮어써 중복 증가를 막는다.
INSERT INTO product_like_count (product_id, like_count)
SELECT product.id, COUNT(product_like.id)
FROM product LEFT JOIN product_like ON product_like.product_id = product.id
GROUP BY product.id
ON DUPLICATE KEY UPDATE like_count = VALUES(like_count);

-- 새 코드 기동 전 잠금 해제. 이전 버전 쓰기 앱은 재기동하지 않는다.
UNLOCK TABLES;

-- 검증: 누락·불일치 카운터 수(기대값 0).
SELECT COUNT(*) AS missing_or_mismatched
FROM product p
LEFT JOIN product_like_count c ON c.product_id = p.id
LEFT JOIN (SELECT product_id, COUNT(*) AS n FROM product_like GROUP BY product_id) l
    ON l.product_id = p.id
WHERE c.product_id IS NULL OR c.like_count <> COALESCE(l.n, 0);

-- 검증: 상품이 없는 고아 카운터 수(기대값 0).
SELECT COUNT(*) AS orphan_counters
FROM product_like_count c LEFT JOIN product p ON p.id = c.product_id
WHERE p.id IS NULL;
