-- W10 스키마 변경 (plan.md D-12 (나)). 운영처럼 DDL 파일로 적용한다 — run.sh는 ddl-auto=update가 같은 열을 만든다.
-- 결과를 반영한 요청의 trace 문맥을 Outbox 행에 남겨, relay가 그 문맥을 부모로 발행하게 한다. 옛 행은 NULL(문맥 없음)로 둔다.
ALTER TABLE `order_outbox`
  ADD COLUMN `trace_parent` varchar(64) DEFAULT NULL
  COMMENT '결과를 반영한 요청의 trace 문맥(W3C traceparent). relay가 이 문맥을 부모로 발행한다. trace가 없었으면 NULL (W10 D-12)';
