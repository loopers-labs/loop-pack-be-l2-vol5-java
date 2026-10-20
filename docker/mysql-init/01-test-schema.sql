-- 테스트 전용 스키마. 테스트는 테이블을 다시 만들고 TRUNCATE하므로, 로컬 실행용 loopers(seed)와 분리한다.
-- 이 스크립트는 MySQL 볼륨이 비어 있을 때(처음 생성)만 실행된다. 기존 볼륨이면 같은 문장을 root로 한 번 실행한다.
CREATE DATABASE IF NOT EXISTS loopers_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
GRANT ALL PRIVILEGES ON loopers_test.* TO 'application'@'%';
