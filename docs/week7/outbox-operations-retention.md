# W7 Outbox 실행·재개·보존 안내

2026-11-06 현재 구현을 기준으로 한 운영 절차다. [전체 설계](../week2/commerce-erd-draft.md#w7-outbox와-단일-relay-설계)·[정책 Q03~06](../week2/commerce-policy-decisions.md#w7-q03--api-내부의-단일-자동-실행)·[스키마 적용 안내](../week2/commerce-schema-operations.md)와 함께 읽는다. **이 문서를 작성하며 앱·DB·Kafka를 실행하거나 설정·데이터를 변경하지 않았다.** 명령은 대상 환경을 확인한 뒤 사용할 예시이며 실행 완료 증거가 아니다.

## 1. 무엇이 완료됐는지 구분하기

| 관찰 대상 | 의미 | 이것만으로 알 수 없는 것 |
| --- | --- | --- |
| Outbox `PENDING` | 아직 발행 확인을 DB에 저장하지 못했다. 같은 행이 재시도 대상이다. | broker가 한 번도 받지 않았는지는 알 수 없다. |
| `outbox_broker_ack` 로그 | producer future가 topic·partition·offset을 반환했다. | 뒤따르는 PUBLISHED 저장·소비 DB commit 성공 여부는 별도다. |
| Outbox `PUBLISHED` | broker 확인 후 발행 상태 TX가 커밋됐다. | 소비자가 구매 활동을 저장했는지는 알 수 없다. |
| 소비 DB의 완료 기록 + 전체 구매 품목 | 저장된 사건의 업무 효과가 반영됐다. | 그 직후 Kafka offset commit까지 성공했는지는 별도다. |
| 소비자 `APPLIED` / `DUPLICATE` 완료 로그, group offset | 신규 저장 또는 동일 사실 검증 뒤 ack한 처리 결과다. | group lag 0만으로 특정 주문의 품목이 맞는지 판단하지 않는다. 대상 사건의 DB 사실도 대조한다. |

`attempt_count`는 **DB에 커밋된 전송 시도 시작 횟수**다. broker record 수·consumer 수신 수·구매 활동 수와 다르다. [실제 장애 실험](outbox-kafka-outage-tdd-log.md)에서는 시도 3회에 업무 record 2개가 관찰됐다. PUBLISHED 저장과 소비 DB 저장 사이에는 선후 보장이 없다.

## 2. relay 실행·중지·인계

| 설정·조건 | 현재 값·동작 | 근거 |
| --- | --- | --- |
| 자동 실행 | `commerce.outbox-relay.enabled`, 환경변수 `OUTBOX_RELAY_ENABLED`, 기본 `false` | [API 설정](../../apps/commerce-api/src/main/resources/application.yml) |
| 실행 위치 | commerce-api 내부 전용 스레드 1개. 지정한 API 인스턴스 **한 개만** 활성화 | [relay 설정](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/outbox/OutboxRelayConfiguration.java) |
| 회차 | 이전 회차 종료 후 1초 뒤, due PENDING 최대 10개를 `next_attempt_at, id` 순서로 순차 처리 | [scheduler](../../apps/commerce-api/src/main/java/com/loopers/interfaces/scheduler/OutboxRelayScheduler.java)·[조회 구현](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/outbox/OutboxJpaRepository.java) |
| 전송 대기 | 한 건의 접수·future 대기 예산 12초. DB 조회·상태 저장 시간은 별도 | [relay](../../apps/commerce-api/src/main/java/com/loopers/application/outbox/OutboxRelay.java) |
| Kafka producer | `acks=all`, idempotence ON, retries 3, max block 1초, request timeout 3초, delivery timeout 10초 | API 설정. 장애 테스트 전용 1초/3초와 구분 |
| 재시도 | 실패 후 5→10→20→40→60초, 이후 60초. PENDING 보존, BLOCKED 없음 | [재시도 정책](../../apps/commerce-api/src/main/java/com/loopers/application/outbox/OutboxRetryPolicy.java) |
| 실행 중첩 | 같은 singleton의 `runOnce()`는 AtomicBoolean으로 겹친 실행을 건너뜀 | 다른 JVM·별도 생성 runner 사이의 분산 잠금은 아님 |
| 수동 실행 | 내부 Spring bean의 `runOnce()`만 존재. 공개 HTTP·배포 CLI 없음 | 이미 활성화된 runner와 별개로 새 runner를 띄우는 운영 절차를 추가하지 않음 |

### 시작 전 확인과 실행 예시

1. 기존 API DB·프로파일을 확인하고 [012 Outbox DDL](../../apps/commerce-api/src/main/resources/db/schema/012-outbox.sql)의 적용 여부를 확인한다. Outbox는 주문·결제·예약과 같은 데이터 소스에 있어야 한다. 스키마 준비는 별도 작업이며 재시작 때 CREATE를 반복하지 않는다.
2. **API의 local 기본값은 `ddl-auto=create`이므로 보존할 DB에서 그대로 시작하지 않는다.** 기존 스키마에서는 `none` 등 스키마 안내의 보존 설정을 명시한다. 이번 문서는 기존 설정을 바꾸지 않는다.
3. producer·consumer·관찰 도구가 같은 broker를 가리키는지, 기존 Outbox의 저장 topic이 실제로 존재하는지 확인한다. 현재 기본 신규 목적지는 `order-events`지만 기존 행은 저장된 topic으로 보낸다.
4. 다른 모든 API 인스턴스의 relay가 꺼져 있거나 종료됐는지 확인한 뒤 지정한 한 인스턴스만 켠다. `commerce.order-events.enabled=false`는 W7 Outbox 기록이나 relay의 정지 스위치가 아니다.

아래는 **기존 로컬 DB·스키마·broker 준비가 끝난 경우**의 프로젝트 루트 실행 예시다. 해당 환경의 연결 대상은 실행자가 확인한다. relay 전송에는 PG 재호출이 필요하지 않으며 신규 주문 결제용 PG 설정은 이 절차와 별개다.

```shell
OUTBOX_RELAY_ENABLED=true \
BOOTSTRAP_SERVERS=localhost:19092 \
./gradlew :apps:commerce-api:bootRun \
  --args='--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none'
```

다른 인스턴스는 `OUTBOX_RELAY_ENABLED=false`로 시작한다. OFF여도 최초 확정 TX의 Outbox 저장은 계속된다. 설정은 기동 시 적용되므로 실행 중 셸의 환경변수만 바꾸어 정지·재개할 수는 없다.

### 중지와 다른 인스턴스로 인계

기존 활성 인스턴스를 정상 종료하고 **기존 프로세스가 실제로 종료된 뒤** 다음 한 인스턴스를 켠다. API를 계속 서비스해야 한다면 기존 인스턴스를 relay OFF로 재시작하는 방식도 가능하지만 무중단 인계를 구현한 것은 아니다. 두 활성 인스턴스가 겹치는 rolling 배포는 현재 단일 실행 조건을 만족하지 않는다.

scheduler에 15초 종료 대기 설정이 있지만 전체 애플리케이션 종료의 15초 상한은 아니다. 진행 중 회차·Spring lifecycle·DB 작업에 따라 더 걸릴 수 있다. 중단 시점의 PENDING은 저장된 `next_attempt_at`을 기준으로 새 runner가 이어받는다. 새 인스턴스가 켜졌다고 즉시 모든 backlog가 처리되는 것은 아니다.

## 3. 장애 확인과 같은 행 재개

먼저 대상 `event_id`, 상태, 시도 수, 다음 시각과 아래 로그를 함께 확인한다. `last_error`는 `TIMEOUT`·`SEND_FAILED`·`INTERRUPTED` 같은 안전한 코드이며 상세 원인은 관련 broker·DB 상태와 대조한다. 카드·비밀키·불필요한 전체 payload를 로그에 추가하지 않는다.

| 관찰 | 조치·재개 방법 | 상태를 해석할 때 주의할 점 |
| --- | --- | --- |
| `outbox_send_unconfirmed`, PENDING | broker 연결·설정·권한·목적지를 확인하고 원인을 고친다. 활성 relay는 기한 후 같은 행을 자동 재시도한다. 중지돼 있었다면 같은 DB를 쓰는 한 인스턴스를 다시 켠다. | 실패·timeout은 broker 미수신의 증거가 아니다. |
| `outbox_state_write_failed` | DB 원인 해결 후 실제 행을 재조회한다. PENDING이면 저장된 기한 뒤 재개, PUBLISHED면 재발행 대상에서 제외한다. | commit 예외는 성공 여부가 불확실할 수 있으므로 PENDING으로 강제 되돌리지 않는다. |
| `outbox_retry_write_failed` | DB를 복구하고 시도 시작 TX에서 미리 커밋한 `next_attempt_at`을 확인한다. 그 시각 뒤 재개한다. | 실패 정보 저장이 실패해도 기존 행을 새로 만들지 않는다. |
| `outbox_relay_round_failed` 또는 시도 로그가 늘지 않음 | DB 접근, relay ON 인스턴스·프로세스, 다음 시각, 관련 로그를 확인한다. | 설정 OFF·DB 실패·아직 기한 전인 상황을 broker 장애와 구분한다. |
| 같은 원인으로 계속 실패 | PENDING을 보존하고 원인을 수정한다. 잘못된 저장 사실·목적지가 의심되면 현재 자동 재시도만으로 해결할 수 없음을 기록하고 사실 확인·별도 수정 설계를 한다. | 현재 자동 격리·DLQ·수동 BLOCKED 해제 기능은 없다. 행 삭제·payload 교체·강제 성공으로 숨기지 않는다. |
| PUBLISHED인데 구매 활동이 없음 | 아래 소비자 절차에서 listener·offset·lag·오류·소비 DB를 확인한다. | PG 결제나 주문 확정을 다시 호출해 해결하지 않는다. |

전송 전 시도 TX는 `시작 시각 + 12초 + backoff`를 임시 재개 시각으로 저장한다. 실패 상태 TX까지 성공하면 `실패 관찰 시각 + backoff`로 갱신한다. 따라서 재시작 때는 임의로 계산한 시각보다 **저장된 next_attempt_at**을 따른다. 실제 시작은 polling·앞선 작업 시간만큼 늦어질 수 있다. 첫 실패 시 해당 회차는 끝나지만 다음 회차에는 다른 due 행이 먼저 처리될 수 있으므로 업무 전체 순서를 보장하지 않는다.

자동 재개·내부 수동 호출 모두 현재 row를 사용한다. eventId·topic·key·payload·createdAt을 바꾸거나, 시도 횟수·기한을 초기화하거나, PUBLISHED를 수동 SQL로 PENDING으로 바꾸는 절차는 제공하지 않는다. [W6 backfill](w6-outbox-backfill-tdd-log.md)은 Outbox가 없던 확정 사실을 보충하는 내부 서비스이며, 이미 존재하는 일치 행은 `ALREADY_PRESENT`로 보존한다.

### API DB의 읽기 전용 관찰

Outbox DATETIME(6)은 UTC로 저장한다. 아래 경과 시간도 UTC 기준이다. 큰 테이블의 전체 집계 비용을 측정한 것은 아니므로 새 polling 작업으로 추가하지 않는다.

```sql
SELECT status, COUNT(*) AS message_count, MIN(created_at) AS oldest_created_at
FROM outbox_message GROUP BY status;

SELECT COUNT(*) AS pending_count, MIN(created_at) AS oldest_pending_at,
       TIMESTAMPDIFF(SECOND, MIN(created_at), UTC_TIMESTAMP(6)) AS oldest_pending_age_seconds
FROM outbox_message WHERE status = 'PENDING';

SELECT event_id, status, attempt_count, next_attempt_at, last_error,
       created_at, published_at
FROM outbox_message WHERE event_id = 'order-confirmed:v1:42';
```

`42`는 설명용 주문 ID이며 실제 대상 사건으로 바꾼다. [장애 테스트](outbox-kafka-outage-tdd-log.md)는 격리 DB에서 COUNT·MIN·시도/오류 조회를 실행했다. 위 경과 시간 SQL 예시까지 새 실행 검증했다고 주장하지 않는다.

### 소비자 확인·재개

구매 활동 listener의 기본값은 topic `order-events`, group `purchase-activity`, 자동 시작 `true`다. [전용 설정](../../apps/commerce-streamer/src/main/resources/purchase-activity.yml)은 `ddl-auto=validate`로 두 소비 테이블을 보존한다. 실행 대상 streamer DB와 [001 소비 스키마](../../apps/commerce-streamer/src/main/resources/db/schema/001-purchase-activity.sql)를 확인하며 API DB와 혼동하지 않는다.

1. `purchase_activity_processing_failed`의 topic·partition·offset·오류 종류를 확인한다. 처리 오류는 전용 listener를 멈춘다. 앱 프로세스가 살아 있어도 소비가 중단됐을 수 있다.
2. DB·형식·사실 충돌 등의 원인을 해결하고 **같은 DB·topic·group으로 streamer를 재시작**한다. 그룹 변경·offset 건너뛰기·완료 기록 삭제는 복구 절차로 사용하지 않는다. 실행 설정을 바꿨다면 `commerce.purchase-activity.auto-startup=true`인지 확인한다.
3. `purchase_activity_completed`의 `APPLIED`/`DUPLICATE`, group의 committed offset·lag, 대상 주문의 두 DB 테이블을 함께 확인한다. 완료 기록과 모든 품목 저장은 공동 TX이고 그 뒤 ack한다. DB commit 뒤 offset 저장만 실패했다면 재전달 시 같은 사실을 비교해 DUPLICATE로 처리한다.

전용 consumer의 `earliest`는 유효한 offset이 없을 때 **현재 남아 있는 가장 이른 메시지**부터 읽는 설정이다. 매 재시작 때 전체 이력을 다시 읽거나 이미 만료된 메시지를 복구하는 설정이 아니다. [Kafka consumer 설정](https://kafka.apache.org/35/generated/consumer_config.html#consumerconfigs_auto.offset.reset)

Kafka CLI가 설치된 환경의 읽기 전용 예시다. 실제 broker·topic·group 값으로 대체한다. 기존 group에 별도 console consumer를 붙여 관찰하지 않는다.

```shell
kafka-consumer-groups.sh --bootstrap-server localhost:19092 \
  --describe --group purchase-activity

kafka-configs.sh --bootstrap-server localhost:19092 \
  --entity-type topics --entity-name order-events --describe --all
```

첫 명령의 CURRENT-OFFSET·LOG-END-OFFSET·LAG를 참고하고, 둘째 명령에서 유효 `cleanup.policy`·`retention.ms`·`retention.bytes`와 설정 출처를 확인한다. 현재 저장된 topic이 다르면 그 topic도 확인한다. CLI 구문 근거는 [Kafka 운영 안내](https://kafka.apache.org/35/operations/basic-kafka-operations/)와 [3.5.1 ConfigCommand](https://github.com/apache/kafka/blob/3.5.1/core/src/main/scala/kafka/admin/ConfigCommand.scala)다. 이 명령을 사용자 broker에 실행한 것은 아니다.

streamer DB에서 다음 두 조회로 사건과 전체 품목을 확인한다. API의 현재 상품 정보로 과거 수량을 재구성하지 않는다.

```sql
SELECT id, event_id, order_id, event_type, schema_version,
       confirmed_at_second, confirmed_at_nano
FROM processed_order_confirmation WHERE event_id = 'order-confirmed:v1:42';

SELECT id, order_id, product_id, quantity, confirmed_at_second, confirmed_at_nano
FROM product_purchase_activity WHERE order_id = 42 ORDER BY product_id;
```

## 4. Outbox·Kafka·소비 기록의 보존 관계

| 기록 | 남기는 이유 | 현재 구현·설정 | 먼저 없어질 때의 문제 |
| --- | --- | --- | --- |
| Outbox PENDING | 아직 확인되지 않은 전달을 같은 사실로 재시도 | 자동 삭제·시도 횟수 초과 삭제 없음. 해결 전 보존 | 전달할 사실과 재개 위치를 잃는다. |
| Outbox PUBLISHED | 무엇을 보냈는지 조사하고 원본 전송 사실을 확인 | 자동 삭제 없음. **자동 재발행 대상은 아님** | 조사·별도 복구 설계에 쓸 자료가 줄어든다. 남겨둔다고 소비 완료를 보장하지도 않는다. |
| Kafka record | consumer가 읽거나 offset을 기준으로 다시 읽는 전달 이력 | 저장소 compose·앱에 보존 기간/크기·cleanup 정책의 명시적 고정값 없음. 실제 broker/topic 값 미확인 | 미소비 record가 만료되면 같은 group 재시작만으로 읽을 수 없다. |
| `processed_order_confirmation` | 처리한 eventId·orderId·사건 사실 확인 | 자동 삭제 없음. eventId/orderId 각각 유일 | 현재 품목 FK 때문에 단독 삭제가 제한된다. 중복 판단의 근거이기도 하다. |
| `product_purchase_activity` | 상품별 구매 사실과 중복 입력의 전체 내용 비교 | 자동 삭제 없음. `(orderId, productId)` 유일, 완료 기록에 FK | 활동만 삭제하면 원본 품목과의 중복 비교가 달라져 충돌할 수 있다. 두 테이블을 함께 보존해야 한다. |

Kafka의 시간·크기 기반 보존은 소비 완료를 기다려주는 정책이 아니다. 실제 삭제 시점은 cleanup 정책과 segment 등에도 영향을 받으므로 보존 일수 하나를 업무 완료 보장으로 해석하지 않는다. [Kafka topic 설정](https://kafka.apache.org/35/generated/topic_config.html)

**중복 처리 기록은 Kafka 보존 기간만 보고 지울 수 없다.** 오래 남아 있던 PENDING의 재개, 기존 Outbox·원본 주문 사실을 이용한 늦은 복구에서도 같은 사건이 다시 올 수 있다. 재전달이 가능한 가장 오래된 사건까지 비교 근거가 남아야 한다. 현재 재시도 종료 기한·역사적 복구 허용 기간이 없으므로, 유한한 dedup 보존 일수를 안전하다고 확정할 근거는 없다.

예를 들어 오래된 주문 이벤트를 오늘 다시 보낼 수 있다면, Kafka가 그 새 record를 보관하는 기간과 별도로 **그 주문의 기존 소비 기록**이 필요하다. 소비 기록을 먼저 지우면 중복 판별 근거가 사라지고, 업무 결과까지 함께 지웠다면 과거 사건이 신규 처리될 수 있다. 현재 dedup는 별도 TTL 캐시가 아니라 구매 활동과 연결된 DB 사실이다. [소비 저장·비교 구현](../../apps/commerce-streamer/src/main/java/com/loopers/application/purchase/PurchaseActivityTransactions.java)·[FK/유일 제약](../../apps/commerce-streamer/src/main/resources/db/schema/001-purchase-activity.sql)

### Kafka에서 이미 만료된 경우의 현재 한계

`PUBLISHED` 뒤 consumer가 읽기 전에 Kafka record가 만료되면, 현재 `runOnce()`는 그 Outbox를 다시 보내지 않는다. backfill도 기존 일치 행을 그대로 반환한다. **Outbox가 보존되어 있어도 이 경우의 자동 재발행 기능은 없다.** 사실을 보존하고 누락 범위를 확인한 뒤 별도의 재처리 절차를 설계해야 한다. 수동 SQL 변경이나 새로운 eventId 생성으로 현재 계약을 우회하지 않는다.

이는 이미 검증한 **PENDING의 장애 후 재개**와 다른 상황이다. 현재 기능의 보장 범위로 Kafka 보존 기한을 넘긴 모든 미소비 사건의 복구를 주장하지 않는다.

## 5. 현재 정책과 향후 결정의 구분

| 구분 | 이번 문서에서 정리한 범위 |
| --- | --- |
| 현재 동작 | Outbox·소비 DB 기록의 자동 정리 없음. 미발행 사실을 삭제하지 않고 같은 행 재개. **영구 보존 SLA를 새로 확정했다는 의미는 아님** |
| 현재 미확인 환경값 | 실제 broker/topic의 Kafka retention·cleanup 설정. 소스의 미지정 상태를 무제한 보존이나 특정 기본 일수로 단정하지 않음 |
| 정리 정책을 도입할 때 결정 | 허용 가능한 소비 지연·장애 복구 시간, 과거 사건의 재처리 가능 범위, 구매 이력 보존 요구, DB/메시지 용량, 백업 복구 후 같은 식별자를 보존할 범위 |
| 기간을 정하기 전의 조건 | Kafka 전달 이력과 Outbox 복구 가능 범위, 소비 완료·전체 품목 보존을 함께 검토. PUBLISHED 정리 기준을 broker ACK만으로 선택하지 않음 |
| 범위 밖 | 다중 relay·CDC·공개 재처리 API·자동 정리 job·PUBLISHED 재발행 기능은 이번 문서에서 추가하지 않음 |

새 삭제 기한이나 정책 변경 없이 현재 구현을 설명할 수 있어 이번 문서 작업에 추가 사용자 결정은 필요하지 않다. 실제 자동 정리·보존 기간 변경을 요청받으면 위 조건과 비용을 비교하고 먼저 결정한다.

## 6. 근거와 남은 최종 검사

실제 장애·재개는 [Kafka 장애 기록](outbox-kafka-outage-tdd-log.md), 두 중단·중복 소비는 [중단 E2E](outbox-republication-e2e-tdd-log.md), 같은 실행의 과거 누락 복구는 [W6 backfill](w6-outbox-backfill-tdd-log.md)을 따른다. 단일 broker 실험과 JVM 내부 실행 가드는 다중 broker 내구성·다중 relay 선점·업무 전체 순서 보장이 아니다. polling에 따른 DB 조회와 순차 처리의 처리량 한계도 있다.

이번 변경은 문서와 코드 정합성 점검이다. 새 테스트 실행·사용자 DB/기존 topic·group 접근·설정 적용·커밋·푸시는 하지 않았다. 운영·보존 문서 정리는 완료했으며 **다음은 양 앱 전체 검사와 W7 공식 11항목 최종 대조**다. 문서 작성으로 해당 실행까지 통과한 것으로 표시하지 않는다.
