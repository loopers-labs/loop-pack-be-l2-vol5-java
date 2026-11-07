## Transaction Polling Relay 동작 설계

### 기본 동작

1. 재시도 가능 시각이 지난 `PENDING`·`FAILED` 이벤트를 재시도 가능 시각이 먼저 도래한 순서로 정해진 최대 건수만큼 처리한다.
2. 조회한 행을 하나씩 처리한다. 발행 시도를 시작할 때 attempt_count를 올려 저장한다.
3. 주문 트랜잭션 밖에서 Outbox에 저장된 `topic`, `message_key`, `payload`를 Kafka로 보낸다. Relay의 발행 시도는 Kafka 응답을 받거나 `publish-timeout`에 도달하면 끝난다.
4. 발행 결과를 받아 Outbox 상태를 갱신한다.
5. 성공 응답이면 `PUBLISHED`와 `published_at`을 기록한다. 실패 응답이면 `FAILED`, 성공·실패를 확정할 응답을 받지 못하면 `PENDING`으로 두고 `next_attempt_at`과 `last_error`를 갱신한다.

### 설정값


| 설정 항목    | 설정 키                         | 의미                                                       | 기본값 |
| -------- | ---------------------------- | -------------------------------------------------------- | --- |
| 폴링 간격    | `outbox.relay.poll-interval` | 한 번의 조회·발행 처리가 끝난 뒤 다음 실행까지 기다리는 시간(`fixedDelay`)        | 1초  |
| 재시도 간격   | `outbox.relay.retry-interval` | 발행 실패 또는 결과 미확정 시 `next_attempt_at`까지 기다리는 시간             | 10초 |
| 발행 대기 시간 | `outbox.relay.publish-timeout` | Kafka 발행 응답을 기다리는 최대 시간. 초과하면 결과 미확정으로 처리해 `PENDING`으로 재시도한다. | 20초 |
| 조회 묶음 크기 | `outbox.relay.batch-size`    | 한 번의 실행에서 순차 처리할 최대 Outbox 행 수                           | 10  |
| 최대 시도 횟수 | `outbox.relay.max-attempts`  | 발행 결과가 성공으로 확정되지 않은 행을 `BLOCKED`로 전환하기까지 허용할 전체 발행 시도 횟수 | 3   |


### 상태 정의

- `PENDING`: 발행 대기 중, 시도 대상
- `FAILED`: Kafka 발행 실패 응답을 받은 상태, 재시도 대상
- `BLOCKED`: 최대 발행 시도 횟수에 도달했지만 Kafka 브로커 성공 응답을 받지 못해 자동 재시도를 중단한 상태
- `PUBLISHED`: 이벤트 발행 완료
