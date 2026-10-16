-- 좋아요 하드 삭제 전환 전, 기존 DB에 한 번 적용한다.
-- 적용할 DB를 선택하고 애플리케이션의 읽기·쓰기를 중단한 상태에서 실행한다.
-- 활성 관계와 삭제된 상품에 남은 활성 좋아요는 보존한다.
-- 이 파일은 애플리케이션 시작 시 자동 실행되지 않는다.

START TRANSACTION;

DELETE FROM product_like
WHERE deleted_at IS NOT NULL;

SELECT COUNT(*) AS remaining_canceled_likes
FROM product_like
WHERE deleted_at IS NOT NULL;

COMMIT;
