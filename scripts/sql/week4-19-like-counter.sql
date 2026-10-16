-- 대상: 10만 건 hotspot. 결과: ../../docs/week4/implementation-log.md#counter-experiment
-- 임시 테이블 사용: 같은 연결에서 블록별 실행한다. 원본 데이터 변경 없음.
USE loopers_benchmark_100k_hotspot;

-- 준비: 관계에서 계산한 값을 보관할 세션 전용 카운터.
CREATE TEMPORARY TABLE w4_like_counter_probe (
    product_id BIGINT PRIMARY KEY,
    like_count BIGINT NOT NULL CHECK (like_count >= 0)
) ENGINE=InnoDB;

-- 초기 채움: 좋아요 0개인 상품도 카운터 0으로 만든다(측정 밖).
INSERT INTO w4_like_counter_probe
SELECT p.id, COUNT(l.id)
FROM product p LEFT JOIN product_like l ON l.product_id = p.id
GROUP BY p.id;

-- 기존 집계: 페이지의 좋아요 50,093행을 읽는지.
EXPLAIN ANALYZE
SELECT product_id, COUNT(*)
FROM product_like
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
)
GROUP BY product_id;

-- 카운터 조회: 동일 상품의 저장된 값 20행만 읽는지.
EXPLAIN ANALYZE
SELECT product_id, like_count
FROM w4_like_counter_probe
WHERE product_id IN (
    97407,94407,91407,88407,85407,82407,79407,76407,73407,70407,
    67407,64407,61407,58407,55407,52407,49407,46407,43407,40407
);

-- 정합성: 초기 카운터와 실제 관계 집계가 다른 상품 수(기대값 0).
SELECT COUNT(*) AS mismatches
FROM w4_like_counter_probe c
LEFT JOIN (SELECT product_id, COUNT(*) AS n FROM product_like GROUP BY product_id) l
    ON l.product_id = c.product_id
WHERE c.like_count <> COALESCE(l.n, 0);

-- 정리: 이 연결에서 생성한 임시 카운터만 제거.
DROP TEMPORARY TABLE w4_like_counter_probe;
