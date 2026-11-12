# 7주차 운영 절차 — Outbox 확인·재개·재처리

로컬 운영 기준이다. backlog 조회문과 PUBLISHED 재처리 UPDATE는 `OrderOutboxRelayTest`에서 실제 MySQL로 실행해 확인했다. 위치 찾기의 주문별 조회문은 같은 형태로 실행하지 않았다. 정책의 근거는 [ADR 0066](../adr/0066-keep-outbox-and-dedup-records-without-automatic-deletion.md)을 따른다.

## 구매 활동이 없을 때 위치 찾기

주문 ID로 순서대로 확인한다. 이미 구매 활동이 있으면 결과를 지우고 다시 시작하지 않는다.

```sql
SELECT event_id, status, attempt_count, next_attempt_at, last_error, published_at
FROM order_outbox WHERE order_id = ?;

SELECT event_id FROM processed_order_confirmation WHERE order_id = ?;
SELECT product_id, quantity FROM product_purchase_activity WHERE order_id = ?;
```

| Outbox | 소비 완료·구매 활동 | 뜻 | 볼 곳 |
| --- | --- | --- | --- |
| 행 없음, 주문은 확정 | 없음 | Outbox 도입 전 누락 후보 | 아래 기존 누락 복구 |
| PENDING | 없음 | 아직 broker 확인 전 | relay 실행 여부, `last_error`, broker |
| PENDING | 있음 | 전송됐지만 PUBLISHED 저장 전. 재발행될 수 있고 업무에 문제없음 | 조치 없음 |
| PUBLISHED | 없음 | 전달 뒤 소비 미완료 | consumer lag, 오류, DLQ |
| PUBLISHED | 있음 | 정상 완료 | 조치 없음 |

`PUBLISHED`는 소비 완료를 뜻하지 않는다.

## backlog 확인

```sql
SELECT COUNT(*) AS pending, MIN(created_at) AS oldest_created_at, MAX(attempt_count) AS max_attempts
FROM order_outbox WHERE status = 'PENDING';

SELECT event_id, order_id, attempt_count, next_attempt_at, last_error
FROM order_outbox WHERE status = 'PENDING' ORDER BY id LIMIT 20;
```

이 값은 발행 backlog만 보여 준다. 소비 지연은 consumer lag로, 업무 결과는 구매 활동으로 따로 확인한다. 전송 실패 로그에는 `eventId`가 남고 성공 로그에는 partition·offset이 남는다.

## 전송 실패 뒤 재개

- 일시 장애는 조치가 필요 없다. relay가 실행 중이면 broker 회복 뒤 기존 PENDING을 같은 eventId로 다시 보낸다. 실패 뒤 다음 시도 시각까지의 대기는 최대 60초다. 실제 재시도에는 회차 간격(10초)과 앞선 처리 대기가 더해지므로 전체 복구 시간의 상한은 아니다.
- `BLOCKED` 상태는 없다. 반복 실패한 행도 PENDING으로 남아 계속 시도한다. 완료가 아니라 사람이 판단할 미해결 작업이다.
- 저장 payload를 고쳐 같은 사건으로 위장하지 않는다. 원인을 고친 뒤 같은 행이 다시 전송되게 둔다.
- relay는 payload를 검사하지 않는다. PUBLISHED인데 구매 활동이 없으면 consumer의 DLT(`order-events-dlt`)에서 같은 eventId를 찾는다.
- relay는 `commerce.order-events.relay.scheduling-enabled=true`인 인스턴스 하나에서만 실행한다. `commerce.order-events.enabled=false`이면 이 값과 관계없이 스케줄러가 등록되지 않는다. 전달을 멈춰도 Outbox 기록은 계속 쌓이므로, 다시 켜면 쌓인 PENDING부터 보낸다.

## 기존 누락 복구

Outbox 행이 없는 확정 주문은 대상을 명시해 시작 인자로 복구한다. 공개 API는 없다.

```bash
--commerce.order-events.recovery.order-ids=1,2 --commerce.order-events.relay.scheduling-enabled=true
```

행이 있으면 내용만 비교하고 추가하지 않는다. 내용이 다르거나 확정 주문이 아니면 앱 시작이 실패한다.

## PUBLISHED 행 재처리

Kafka 보존 기간이 지나 record를 잃었거나 소비 쪽 복구로 다시 받아야 할 때 사용한다. 복구 진입점은 PUBLISHED 행을 다시 보내지 않으므로 대상 eventId를 명시해 상태를 되돌린다. 같은 행의 eventId·key·payload를 그대로 보낸다. 로컬 절차이며 전용 도구는 두지 않았다.

```sql
UPDATE order_outbox SET status = 'PENDING', published_at = NULL
WHERE event_id = ? AND status = 'PUBLISHED';
```

## 보존

| 데이터 | 현재 보존 |
| --- | --- |
| `order_outbox` | 자동 삭제 없음. PENDING은 삭제하면 안 된다 |
| `processed_order_confirmation` | 자동 삭제 없음 |
| Kafka `order-events` | broker 기본 보존(7일). 지나면 record가 사라진다 |

DB를 정리하게 되면 순서가 중요하다. 재발행할 수 있는 사건의 중복 방지 기록을 먼저 지우면 같은 사건이 새 사건처럼 처리될 수 있다.
