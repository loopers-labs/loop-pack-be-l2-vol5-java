-- 로컬 전용 사용자 1명 (10주차 리허설 B3). 사용자 생성 API가 없어 고객 주문을 해 보려면 users 행이 필요하다.
-- id 1은 scripts/ops/rehearsal.sh가 X-USER-ID로 쓴다. 행이 이미 있으면 넣지 않는다.
INSERT INTO users (id)
SELECT 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 1);
