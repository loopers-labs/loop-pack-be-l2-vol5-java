-- 4단계 고정 요청: brandId=7, page=0, size=20, sort=price_asc.
-- 상품·브랜드 조회는 3단계와 같다. 좋아요 조회만 IN + GROUP BY로 바꾸고, 바인딩값을 대입했다.

SELECT l.product_id, COUNT(l.id) AS like_count
FROM likes l
WHERE l.product_id IN (5406, 105406, 205406, 305406, 405406, 505406, 605406, 705406, 805406, 905406,
                       2706, 102706, 202706, 302706, 402706, 502706, 602706, 702706, 802706, 902706)
GROUP BY l.product_id;

-- 좋아요 product_id 인덱스가 없는 2단계 이전 상태는 인덱스를 삭제하지 않고 아래 힌트로 재현했다.
-- UNION ALL 비교는 1단계 queries.sql의 각 분기에 같은 힌트를 붙여 실행했다.

SELECT l.product_id, COUNT(l.id) AS like_count
FROM likes l IGNORE INDEX (idx_likes_product_id)
WHERE l.product_id IN (5406, 105406, 205406, 305406, 405406, 505406, 605406, 705406, 805406, 905406,
                       2706, 102706, 202706, 302706, 402706, 502706, 602706, 702706, 802706, 902706)
GROUP BY l.product_id;
