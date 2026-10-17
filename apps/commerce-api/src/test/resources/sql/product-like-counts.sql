-- 상품별 좋아요 수 집계 테이블. ProductLikeCount 엔티티의 @Table 과 같은 결과이며, ddl-auto: none 환경(운영 등)에 수동으로 적용할 때 쓴다.
--
-- 좋아요 수의 단일 출처이고 노출 중인 상품만 행을 가진다. 상품 생성 유스케이스가 행을 0으로 만들고 상품·브랜드 삭제
-- 유스케이스가 행을 지우며, 좋아요 등록·취소 때 ±1 된다(증감은 UPDATE 만 쓰고 행을 만들지 않는다).
-- 브랜드 없는 좋아요순 목록이 (like_count DESC, product_id DESC) 인덱스 순서로 읽는다.
--
-- 적용 순서
--   1) 테이블·인덱스 생성
--   2) backfill: 배포 중에도 좋아요순 목록이 비지 않게 미리 채운다.
--   3) 앱 배포: 모든 인스턴스를 새 버전으로 바꾼다.
--   4) backfill 다시 실행 + 남은 행 정리: 2)~3) 사이에는 옛 앱이 집계를 바꾸지 않아, 그동안의 좋아요 등록·취소,
--      새로 만든 상품(행 없음), 삭제한 상품(행 남음)이 집계와 어긋난다. 새 앱만 남은 뒤에 한 번 더 맞춘다.
--   5) 정합성 확인(아래 두 쿼리가 모두 0)
--
-- backfill 을 운영 중에 실행해도 된다. 기본 격리 수준(REPEATABLE READ)에서 INSERT ... SELECT 는 읽는 likes 행에 공유
-- 잠금을 걸어, 그동안의 좋아요는 이 문장이 끝난 뒤에 반영되므로 덮어써 잃지 않는다. 대신 그동안 좋아요 쓰기가 기다린다.

CREATE TABLE product_like_counts (
  product_id bigint NOT NULL,
  like_count bigint NOT NULL,
  PRIMARY KEY (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE INDEX idx_product_like_counts_ranking ON product_like_counts (like_count DESC, product_id DESC);

-- backfill: 노출 상품의 좋아요 수를 좋아요 관계에서 센다. 다시 실행해도 안전하다.
INSERT INTO product_like_counts (product_id, like_count)
SELECT p.id, (SELECT COUNT(*) FROM likes l WHERE l.product_id = p.id)
FROM products p
WHERE p.deleted_at IS NULL
ON DUPLICATE KEY UPDATE like_count = VALUES(like_count);

-- 남은 행 정리: 삭제됐거나 없는 상품의 행을 지운다. backfill 은 노출 상품만 다루므로 따로 실행한다(4단계).
DELETE c FROM product_like_counts c
LEFT JOIN products p ON p.id = c.product_id
WHERE p.id IS NULL OR p.deleted_at IS NOT NULL;

-- 정합성 확인(둘 다 0 이어야 한다)
-- 1) 노출 상품인데 행이 없거나, 삭제 상품인데 행이 남은 개수
-- SELECT COUNT(*) FROM products p LEFT JOIN product_like_counts c ON c.product_id = p.id
--  WHERE (p.deleted_at IS NULL AND c.product_id IS NULL) OR (p.deleted_at IS NOT NULL AND c.product_id IS NOT NULL);
-- 2) 좋아요 관계 수와 다른 행의 개수
-- SELECT COUNT(*) FROM product_like_counts c
--  WHERE c.like_count <> (SELECT COUNT(*) FROM likes l WHERE l.product_id = c.product_id);

-- 되돌리기
-- DROP TABLE product_like_counts;
