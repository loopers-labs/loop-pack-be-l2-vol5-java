-- 이미 생성된 loopers 실습 DB에 좋아요 조회 인덱스를 한 번 적용한다.
-- 새 DB의 테이블 생성에는 init.sql을 사용한다.
ALTER TABLE loopers.likes
    ADD INDEX idx_likes_product_id (product_id);
