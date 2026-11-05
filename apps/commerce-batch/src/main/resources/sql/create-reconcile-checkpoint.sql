-- 대사 워터마크 (설계 27절). batch 전용 테이블이며 위치는 (confirmed_at, order_id) 두 값이다.
-- ddl-auto를 쓰지 않는 환경에서 대사 Job을 처음 실행하기 전에 실행한다. 없을 때만 만들어 다시 실행해도 결과가 같다.
-- local·test 프로필은 시작할 때 자동 실행한다(spring.sql.init).
CREATE TABLE IF NOT EXISTS reconcile_checkpoint (
    name VARCHAR(100) NOT NULL,
    watermark_confirmed_at DATETIME(6) NOT NULL,
    watermark_order_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (name)
);
