# 7주차 Outbox 보존과 운영 경계

발행이 확인되지 않은 이벤트는 삭제하지 않는다. 발행에 세 번 실패하면 `BLOCKED`로 남기고, 원인을 해결한 뒤 수동으로 재개한다. 발행이 확인된 이벤트는 7일 후 정리 대상이 된다.

## 기록과 상태

주문 확정, 예약 소비, Outbox 저장은 같은 DB 트랜잭션에서 처리한다. 커밋 후 relay가 저장된 이벤트를 Kafka에 보낸다.

| 상태 | 의미 | 다음 동작 |
| --- | --- | --- |
| `PENDING` | 발행 확인이 Outbox에 기록되지 않은 이벤트 | 재시도 시간이 되면 relay가 조회 |
| `PUBLISHED` | Kafka의 발행 성공을 확인하고 Outbox에 기록한 이벤트 | consumer 처리 여부를 별도로 확인 |
| `BLOCKED` | 재시도 횟수를 소진했거나 마지막 시도의 결과를 확인하지 못한 이벤트 | 원인을 확인한 뒤 수동 재개 |

`PENDING`이라도 Kafka에 이미 전달됐을 수 있다. broker의 응답이 유실되거나 응답을 받은 뒤 Outbox 상태를 저장하기 전에 중단될 수 있기 때문이다.

`PUBLISHED`는 Kafka 발행 확인이지 구매 활동 저장 완료는 아니다. consumer는 처리 완료 기록과 상품별 구매 활동을 같은 트랜잭션에 저장하고, 완료 기록으로 중복 이벤트를 구분한다.

## 미발행 이벤트 보존

- `PENDING`과 `BLOCKED`는 시간이 지나도 자동 삭제하지 않는다. Kafka 장애가 길어져도 발행해야 할 이벤트와 실패 정보가 남는다.
- `PUBLISHED`는 `published_at`을 기준으로 7일이 지나면 정리 대상이 된다. 정리 스케줄러는 relay 스케줄러가 활성화된 경우 기본 1시간 간격으로 실행되므로, 정확히 7일이 되는 순간 삭제되지는 않는다.
- 7일은 이 과제에서 선택한 보존 기간이다. Outbox가 정리됐다고 consumer 처리가 완료된 것은 아니다. Kafka 보존 기간과 재발행 가능 기간을 고려해 consumer의 중복 처리 기록도 보존해야 한다. 현재 consumer에는 이 기록을 자동 삭제하는 정책이 없다.

## 조회와 재개

`BLOCKED` 이벤트는 원인을 해결한 뒤 수동으로 재개한다. 재개 명령은 상태만 `PENDING`으로 바꾸고, 실행 중인 기존 relay가 발행한다.

1. 다음 SQL로 대기 건수와 가장 오래된 대기 시각을 확인한다. 두 번째 조회에서 `BLOCKED` 이벤트와 실패 정보를 확인한다.

   ```sql
   SELECT COUNT(*) AS pending_count, MIN(created_at) AS oldest_pending
   FROM order_outbox
   WHERE status = 'PENDING';

   SELECT event_id, order_id, attempt_count, cycle_attempt_count,
          next_attempt_at, last_error, created_at
   FROM order_outbox
   WHERE status IN ('PENDING', 'BLOCKED')
   ORDER BY created_at, event_id;
   ```

2. 실패 원인을 해결한 뒤, 조회한 `event_id` 전체를 복사해 로컬에서 재개 명령을 실행한다. 아래 ID는 예시다. DB·Kafka 등 로컬 실행 환경은 미리 준비한다.

   ```bash
   ./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local --order-events.outbox.resume-event-id=order-confirmed:v1:123'
   ```

3. 기존 relay가 실행 중인지 확인하고, 해당 이벤트가 `PUBLISHED`로 바뀌는지 조회한다. relay가 꺼져 있으면 정상 앱을 다시 실행한다. 복구용 앱은 스케줄러를 켜지 않고 로컬 임의 포트를 사용한다.
4. 복구용 앱은 작업 후 자동 종료되지 않으므로 확인 후 종료한다. consumer 처리는 `processed_order_confirmation`과 `product_purchase_activity`에서 확인하고, 필요하면 consumer group의 lag와 오류도 살펴본다. 현재 별도의 운영 알림은 구성하지 않았다.

## 단일 relay와 재시도의 한계

- **relay는 한 대만 실행한다.** 로컬에서는 스케줄러가 기본으로 활성화된다. 실행 중복 방지는 프로세스 내부에만 있어 여러 relay의 소유권이나 이벤트 순서를 보장하지 않는다.
- **세 번 실패하면 수동 재개가 필요하다.** relay는 한 번에 최대 50개를 조회하고, 이전 실행이 끝난 뒤 기본 1초를 기다린다. 실패하면 지터를 더한 간격으로 재시도하며 최초 시도를 포함해 세 번 실패하면 `BLOCKED`로 전환한다. Kafka가 복구돼도 `BLOCKED`는 자동으로 재개되지 않는다.
- **중복 발행이 가능하다.** 전송 응답이 유실되거나 broker 확인 후 `PUBLISHED` 저장 전에 중단되면 이미 Kafka에 기록된 이벤트를 다시 보낼 수 있다. 마지막 시도 중 중단돼 결과를 알 수 없으면 `AttemptOutcomeUnknown`으로 격리될 수 있다.
- **테스트는 강제 종료를 재현하지 않는다.** 실제 MySQL·Kafka와 consumer를 연결하고, 예외 주입 및 relay 객체 재생성으로 중단을 검증한다. OS 프로세스를 강제 종료한 시험은 아니다.
