-- likes 인덱스 변경. Like 엔티티의 @Table(indexes)와 같은 결과이며, ddl-auto: none 환경(운영 등)에 수동으로 적용할 때 쓴다.
--
-- 적용 전에 반드시 실제 기본키 컬럼 순서를 확인한다. 아래 변경은 기본키가 (product_id, user_id)라는 전제에 기댄다.
--   SHOW INDEX FROM likes;   -- PRIMARY 의 Seq_in_index 1 이 product_id 여야 한다.
-- local·test 는 hibernate.column_ordering_strategy: legacy 와 LikeId 선언으로 이 순서가 되며 스키마 테스트가 고정한다.
-- 운영 DDL 은 저장소에 없으므로 직접 확인한다. 기본키가 (user_id, product_id)라면 product_id 인덱스를 지우면 안 된다.

-- 1) 내 좋아요 목록(user_id 조건)용. 기본키로는 user_id 조건을 받치지 못한다. 사용자당 좋아요가 적어 정렬은 인덱스에 담지 않는다.
CREATE INDEX idx_likes_user_id ON likes (user_id);

-- 2) 기본키 (product_id, user_id) 접두와 겹치는 W2 의 보조 인덱스를 제거한다. 측정상 지연·계획 차이가 없었다.
DROP INDEX idx_likes_product_id ON likes;

-- 되돌리기
-- CREATE INDEX idx_likes_product_id ON likes (product_id);
-- DROP INDEX idx_likes_user_id ON likes;
