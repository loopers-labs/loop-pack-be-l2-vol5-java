-- 부하 테스트용 사용자를 MySQL 에 직접 넣는다. 관리자 1명 + 일반 사용자 @users 명, 모두 잔액 0 인 포인트 계정과 함께 (DR-12).
-- 앱이 ddl-auto: update 로 테이블을 만든 뒤 실행한다. 이미 사용자가 있으면 아무것도 하지 않는다 (재실행 안전).
-- 실행: run-issue-spike.sh 가 mysql 컨테이너에서 실행한다.

SET @users = COALESCE(@users, 20000); -- 앞에서 SET @users = N; 으로 바꿀 수 있다
SET SESSION cte_max_recursion_depth = 1000000;
SET @now = UTC_TIMESTAMP(6);
SET @empty = (SELECT COUNT(*) = 0 FROM `user`);

INSERT INTO `user` (is_admin, created_at, updated_at)
SELECT TRUE, @now, @now FROM DUAL WHERE @empty;

INSERT INTO `user` (is_admin, created_at, updated_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @users)
SELECT FALSE, @now, @now FROM seq WHERE @empty;

INSERT INTO point (user_id, balance, created_at, updated_at)
SELECT u.id, 0, @now, @now
FROM `user` u LEFT JOIN point p ON p.user_id = u.id
WHERE p.id IS NULL;

SELECT
  (SELECT MIN(id) FROM `user` WHERE is_admin) AS admin_id,
  (SELECT MIN(id) FROM `user` WHERE NOT is_admin) AS user_start,
  (SELECT COUNT(*) FROM `user` WHERE NOT is_admin) AS users,
  (SELECT COUNT(*) FROM point) AS points;
