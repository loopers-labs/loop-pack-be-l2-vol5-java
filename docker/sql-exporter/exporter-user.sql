-- sql exporter 가 쓰는 읽기 전용 계정. DB 관리자 계정으로 한 번 실행한다.
-- 읽을 수 있는 것은 아래 두 테이블의 적힌 컬럼뿐이고, 연결은 2 개로 묶는다.
-- 비밀번호와 접속 허용 범위('%')는 로컬용이다. 운영에서는 비밀번호를 비밀 저장소에서 받고,
-- 접속 허용 범위를 exporter 가 있는 주소로 좁힌다.
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'exporter' WITH MAX_USER_CONNECTIONS 2;
GRANT SELECT (id, status, requested_at) ON loopers.payment TO 'exporter'@'%';
GRANT SELECT (id, status) ON loopers.order_event_outbox TO 'exporter'@'%';
