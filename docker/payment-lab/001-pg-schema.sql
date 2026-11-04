-- 로컬 MySQL 관리자 계정으로 명시 실행한다. 기존 commerce 데이터/계정은 변경하지 않는다.
CREATE DATABASE IF NOT EXISTS loopers_test_pg
    CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER IF NOT EXISTS 'test_pg'@'%' IDENTIFIED BY 'test_pg';
GRANT ALL PRIVILEGES ON loopers_test_pg.* TO 'test_pg'@'%';
