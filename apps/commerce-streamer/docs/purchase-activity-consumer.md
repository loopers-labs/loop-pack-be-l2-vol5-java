# 구매 활동 Consumer 설계

`commerce-streamer`는 OrderConfirmed를 소비해 상품별 구매 활동을 저장한다. 이벤트 형식·신원·동일 사실의 의미는 [공통 이벤트 계약](../../../docs/system-design/use-cases/order-confirmed-event.md#이벤트-계약), topic·연결 주소는 [Broker 설계](../../../modules/kafka/docs/broker.md)를 따른다. 주문·상품·결제·포인트 테이블을 조회하거나 변경하지 않는다.

## 책임과 완료 기준

이 consumer의 완료는 **완료 기록과 모든 구매 활동의 DB commit이 끝나거나, 이미 저장된 같은 사실임을 확인한 것**이다. 그 뒤 listener 스레드에서 ack한다. Kafka offset과 MySQL은 별도 commit이므로 재전달을 허용하고, DB 유일 제약과 사실 비교로 같은 구매 효과를 한 번만 남긴다.

실패 정책은 소비 중단이다. 처리하지 못한 record를 완료로 넘기는 대신 원인을 노출하고, 같은 group으로 복구한다. 이 선택은 누락을 감추지 않지만 실패 record가 해결될 때까지 소비가 막히는 비용이 있다. DLQ·자동 재시도 소진 후 skip 정책은 구현하지 않는다.

## 버전별 해석

`OrderConfirmedParser`는 공통 JSON 형식과 eventType을 검증하고 schemaVersion에 등록된 해석기를 선택한다. 변환 결과의 orderId와 Kafka key도 비교한다.

| 구현 | 책임 |
| --- | --- |
| `OrderConfirmedVersionReader` | 지원 버전 번호와 구매 사실 변환 규칙의 계약 |
| `OrderConfirmedV1Reader`, `OrderConfirmedMessageV1` | V1의 필드 타입·필수 값 검증, DTO 구성과 `OrderConfirmation` 변환 |
| `OrderConfirmation` | 양수 ID·수량, 같은 상품 수량 합산·정렬, 합산 overflow 검증과 UTC 마이크로초 정규화 |

Spring이 수집한 버전별 해석기를 버전 번호에 대응하는 불변 맵으로 등록한다. 같은 번호의 해석기가 중복 등록되면 시작 단계에서 거절한다. 현재 실제 명세와 DTO·해석기는 V1이며, 추가 버전은 명세가 정해질 때 기존 해석기와 함께 등록한다. 다른 앱의 DTO를 import하거나 modules에 비즈니스 DTO를 넣지 않는다.

미지원 버전·잘못된 형식은 DB handler 호출 전에 거절하고 ack하지 않는다. listener가 오류를 다시 던지면 아래 [실패와 중단](#실패와-중단) 정책을 적용한다.

## 저장할 사실

| 저장 모델 | 의미 | 유일성 |
| --- | --- | --- |
| `processed_order_confirmation` | 소비를 마친 확정 이벤트의 처리 기록. `event_id`, `order_id`. 이미 처리한 이벤트인지만 표시한다. | `event_id` 유일, `order_id` 유일 |
| `product_purchase_activity` | 확정 주문의 상품별 구매 사실. `order_id`, `product_id`, `quantity`, `confirmed_at` | `(order_id, product_id)` 유일 |

- 구매 활동의 `confirmed_at`은 이벤트의 최초 확정 시각이다. 소비 시각을 쓰지 않는다.
- 소비 모델은 시각을 UTC `Instant`의 마이크로초 정밀도로 정규화하고 `datetime(6)`에 저장한다. Entity는 `@FractionalSeconds(6)`으로 소수초 정밀도를 선언한다.
- 확정 시각과 품목은 구매 활동에만 둔다. 처리 기록은 처리 여부만 표시하고, 같은 사실인지는 구매 활동과 비교해 판정한다.
- `product_id`는 과거 주문의 상품 식별자로 보존한다. 소비할 때 상품의 존재·삭제·가격을 조회하지 않고 상품 테이블에 FK를 두지 않는다. 삭제·비노출 상품을 거르는 일은 활동을 읽는 쪽(이후 랭킹)이 맡는다.

주문 42가 상품 7을 2개, 상품 8을 1개 구매했다면 활동은 2행이다. 같은 이벤트를 다시 받아도 2행이다. 주문 43이 상품 7을 3개 구매하면 활동은 3행이고 상품 7의 구매 수량 합은 5다.

## 사실의 비교

같은 사실인지는 받은 이벤트를 정규화한 값과 이미 저장된 구매 활동을 비교해 정한다. JSON 원문이나 그 해시를 저장해 비교하지 않는다.

1. 받은 이벤트의 품목에서 같은 상품의 수량을 합산해 상품별로 하나로 만든다. 배열 순서는 보지 않는다.
2. 기존 처리 기록의 `orderId`로 구매 활동 행을 읽어 상품별 수량과 확정 시각을 얻는다.
3. 상품 집합, 상품별 수량, 확정 시각(UTC, 마이크로초)이 모두 같으면 같은 사실이다.

`eventId`와 `orderId`는 처리 기록에서 비교한다. `eventType`·`schemaVersion`은 사실이 아니라 해석 계약이며 파싱 단계에서 검증하므로 비교에 넣지 않는다.<br>
다르면 어느 상품의 어떤 값이 다른지 error 로그에 남긴다.

## 판정 규칙

| 받은 이벤트 | 기존 완료 기록 | 처리 | ack |
| --- | --- | --- | --- |
| 새 `eventId`, 새 `orderId` | 없음 | 완료 기록과 모든 품목 저장 | 소비 TX commit 뒤 |
| 같은 `eventId`, 같은 `orderId`, 저장된 구매 활동과 같은 사실 | 있음 | 효과 추가 없이 완료 (정상 재전달) | 판정 뒤 |
| 같은 `eventId`, 다른 `orderId`, 또는 저장된 구매 활동과 다른 사실 | 있음 | 충돌. 저장하지 않음 | 하지 않음 |
| 다른 `eventId`, 같은 `orderId` | 있음 | 충돌. 저장하지 않음 | 하지 않음 |
| key와 `orderId` 불일치, 알 수 없는 `eventType`·`schemaVersion`, 품목 없음, 0 이하의 ID·수량 | 확인하지 않음 | 계약 위반. 저장하지 않음 | 하지 않음 |

충돌과 계약 위반을 `INSERT IGNORE`처럼 조용히 넘기지 않는다. error 로그로 드러내고 [실패와 중단](#실패와-중단)을 따른다.

## 소비 트랜잭션

구현 책임은 streamer 안에서 나눈다. `OrderConfirmedParser`는 메시지를 검증하고 `OrderConfirmation`이 품목을 정규화한다. `PurchaseActivityHandler`는 트랜잭션 호출과 중복 경쟁 복구를, 별도 Bean인 `PurchaseActivityTransaction`은 완료 기록과 품목의 원자적 저장을 맡는다. `PurchaseActivityRepository`의 JPA 구현만 두 테이블에 접근한다. API 애플리케이션이나 현재 상품 모델에 의존하지 않는다.

1. listener가 record의 key와 value를 파싱하고 계약을 검증한다.
2. 소비 TX에서 같은 `eventId` 또는 `orderId`의 완료 기록을 찾는다. 있으면 판정 규칙으로 no-op이나 충돌을 정한다.
3. 없으면 완료 기록과 모든 품목의 구매 활동을 저장하고 commit한다. 중간 품목 저장이 실패하면 완료 기록과 앞선 품목도 함께 rollback한다.
4. 소비 TX가 commit된 뒤에만 listener가 ack한다.

listener는 별도 executor로 품목 처리를 넘기지 않는다. 같은 partition에서는 한 record의 handler와 ack가 끝난 뒤 다음 record를 처리하고, 다른 partition은 다른 consumer 스레드에서 진행한다. Spring 컨테이너가 poll을 반복하며, broker가 record 도착을 계기로 listener를 직접 호출하는 구조는 아니다.

완료 기록만 먼저 commit하면 그 뒤 중단됐을 때 재전달을 이미 처리한 것으로 건너뛰어 구매 활동이 빠진다. 구매 활동을 먼저 commit하고 완료 기록을 다른 트랜잭션에 남기면 그 사이 중단됐을 때 재전달이 활동을 다시 저장한다. 그래서 두 저장을 한 트랜잭션에 둔다.

## 동시 중복

조회만으로는 같은 이벤트의 동시 소비를 막지 못한다. 같은 group에서도 rebalance 중에는 이전 member와 새 member가 같은 record를 처리할 수 있다. 두 소비가 모두 기록 없음을 보고 저장하면 DB 유일 제약이 하나만 통과시킨다.

- 완료 기록의 eventId·orderId 유일 제약 위반이 나면 그 트랜잭션은 rollback한다. 같은 트랜잭션에서 예외를 삼키고 commit하지 않는다.
- rollback 뒤 새 트랜잭션에서 기존 완료 기록을 다시 읽어 판정 규칙을 적용한다. 같은 사실이면 no-op으로 완료하고, 다르면 충돌이다.
- 새 트랜잭션에서도 기존 기록을 찾지 못하면 판정할 수 없으므로 원래 오류로 실패시킨다.
- 중복 키가 아닌 저장 오류는 중복으로 바꾸지 않고 그대로 실패시킨다.

완료 기록을 `saveAndFlush`하는 저장 경계에서 Hibernate `ConstraintViolationException`의 종류가 `UNIQUE`이고, 제약 이름이 `uk_processed_confirmation_event` 또는 `uk_processed_confirmation_order`인 경우에만 `DuplicateOrderConfirmationException`으로 변환한다. 제약 이름의 schema·table 한정자는 제외하고 대소문자를 구분하지 않아 비교한다. MySQL 오류 번호나 SQLState로 중복을 판정하지 않는다. 제약 이름을 알 수 없는 경우, 다른 제약 위반과 구매 활동의 저장 오류는 원래 예외를 전달한다.

제약 이름은 Entity의 유일 제약 선언과 Repository 판정에서 같은 상수를 사용한다. DB별 오류 해석은 Hibernate dialect에 맡기며, DB를 교체할 때는 해당 dialect가 제약 종류와 이름을 추출하는지 실제 DB에서 검증해야 한다. `PurchaseActivityHandler`는 기존 트랜잭션 참여를 금지하고, 별도 Bean의 `REQUIRES_NEW` 메서드가 rollback을 마친 뒤 새 트랜잭션의 비교 메서드를 호출한다.

완료 기록은 구매 활동이 commit됐다는 판정 근거이므로 활동과 별개로 지우지 않는다. 현재 두 테이블의 자동 삭제 기능은 없다. 이후 보관·재구축 정책을 추가할 때는 같은 record의 재처리 범위와 함께 정한다.

## listener 설정

구매 활동 listener는 기존 batch factory(`BATCH_LISTENER_DEFAULT`)를 바꾸지 않고 commerce-streamer의 별도 record listener factory를 쓴다.

| 설정 | 값 | 이유 |
| --- | --- | --- |
| group | `purchase-activity` | 구매 활동 저장 전용이다. 다른 소비 목적은 별도 group으로 같은 로그를 따로 읽는다. |
| listener 형태 | record 단위, `order.confirmed-event.consumer.concurrency=3` | 업무 topic을 partition 3개로 준비했을 때 같은 앱 인스턴스 안의 consumer 3개가 분담한다. partition 간 처리를 병렬화하며, 같은 partition 안의 순서는 유지한다. 기존 topic의 partition 수가 적거나 앱 인스턴스를 추가하면 일부 consumer는 배정받지 못할 수 있다. |
| key / value 역직렬화 | `StringDeserializer` / `ByteArrayDeserializer` | JSON 파싱과 계약 검증을 listener가 직접 해서 형식 오류를 원인과 함께 드러낸다. |
| `enable.auto.commit` | `false` | 주기적 자동 commit이 DB 완료 전에 offset을 넘기지 않게 한다. Kafka consumer가 실제로 받은 설정 값으로 확인한다. |
| ack mode | `order.confirmed-event.consumer.ack-mode=MANUAL_IMMEDIATE` | 소비 TX commit 뒤 listener 스레드에서 해당 record의 offset을 바로 commit한다. |
| `syncCommits` | `order.confirmed-event.consumer.sync-commits=true` | consumer 스레드의 ack가 동기 offset commit을 사용한다. |
| `auto.offset.reset` | `order.confirmed-event.consumer.auto-offset-reset=earliest` | group에 commit된 위치가 없거나 그 위치가 보존 범위 밖이면 남아 있는 가장 이른 record부터 읽는다. 보존 기간이 지난 구매 사실을 복구하는 기능은 아니다. |
| `fetch.min.bytes` | `order.confirmed-event.consumer.fetch-min-bytes=1` | 구매 활동 factory는 기존 batch factory의 1MiB 임계값을 상속하지 않는다. 데이터가 있으면 작은 record도 이 임계값을 만족한다. |
| `fetch.max.wait.ms` | `order.confirmed-event.consumer.fetch-max-wait=500ms`, test `100ms` | broker가 `fetch.min.bytes`를 채우지 못했을 때 기다리는 상한이다. `fetch.min.bytes=1`이면 주로 데이터가 없는 fetch의 대기에 영향을 준다. |
| `allow.auto.create.topics` | `order.confirmed-event.consumer.allow-auto-create-topics=false` | streamer가 먼저 시작해도 업무 topic을 broker 기본 partition 수로 자동 생성하지 않는다. topic 준비는 API의 로컬 초기화나 테스트 AdminClient가 맡는다. |
| error handler | `CommonContainerStoppingErrorHandler` | [실패와 중단](#실패와-중단) |

공통 consumer의 역직렬화기와 `enable-auto-commit=false`는 [kafka.yml](../../../modules/kafka/src/main/resources/kafka.yml)에서 읽는다. 앱의 YAML 값은 `PurchaseActivityConsumerProperties`로 주입하고, 전용 factory는 이를 실제 ConsumerConfig와 컨테이너 설정에 반영한다.

애플리케이션 이름은 `commerce-streamer`이며 공통 Kafka client ID도 이 이름에서 정한다. producer 앱과 로그·client 식별자를 구분한다.

## 실패와 중단

| 시점 | DB·offset 결과 | 복구 |
| --- | --- | --- |
| 파싱·계약 위반, 판정 충돌 | 추가 저장·ack 없음, 컨테이너 중단 | 원인 수정 뒤 같은 group으로 재시작 |
| 소비 TX 중 DB 오류 | 완료 기록·모든 품목 rollback, ack 없음 | 원인 제거 뒤 재시작해 재전달·저장 |
| DB commit 뒤 ack 전 중단 | 구매 활동 저장, offset 미commit | 같은 사실을 no-op 처리한 뒤 ack |
| DB commit 뒤 offset commit 실패·결과 불명 | DB 효과 유지. broker의 commit 반영 여부는 별도 확인 필요 | 같은 group의 실제 committed offset을 확인. 재전달되면 같은 사실로 판정한 뒤 ack |
| rebalance 중 동시 처리 | DB 유일 제약이 한 저장만 통과 | rollback 뒤 새 TX에서 같은 사실인지 판정 |

실패한 record는 건너뛰지 않고 컨테이너를 멈춘다.

선택한 `CommonContainerStoppingErrorHandler`는 중단 후 원래 오류를 감싼 예외를 던진다. 정상 recover/skip으로 반환하는 정책이 아니며 `DefaultErrorHandler`의 `commitRecovered` 설정으로 이 동작을 대신하지 않는다. 테스트는 단순히 `isRunning=false`가 된 순간이 아니라 해당 컨테이너의 `ConsumerStoppedEvent` 이후 실제 group committed offset을 확인한다. 종료가 시작된 것과 consumer 종료가 끝난 것을 구분한다.

- 건너뛰면 구매 활동이 조용히 빠진다. 빠른 재시도를 반복하면 같은 오류를 키운다.
- 실패 partition에서 해당 record와 그 뒤 미처리 record는 ack하지 않는다. 재시작한 consumer는 그 partition의 committed offset부터 다시 읽는다. 이미 ack한 record의 commit까지 되돌리는 정책은 아니다.
- 한 record의 실패는 그 앱 인스턴스의 구매 활동 listener 컨테이너 전체를 중단시킨다. 다른 partition에서 진행 중이던 처리는 중단 완료 전 commit·ack될 수 있으므로 group 전체가 같은 offset에서 멈춘다고 보지 않는다. 다른 인스턴스로 rebalance되면 그 인스턴스도 같은 실패 record에서 멈출 수 있다. 앱 프로세스나 기존 demo listener 전체를 종료시키는 정책은 아니다.

중단형 handler의 manual ack 처리 기준은 [Spring Kafka 3.3 오류 처리 계약](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html)을 따른다. 사용하는 3.3.5 구현은 [CommonContainerStoppingErrorHandler](https://github.com/spring-projects/spring-kafka/blob/v3.3.5/spring-kafka/src/main/java/org/springframework/kafka/listener/CommonContainerStoppingErrorHandler.java)에서 확인한다.

## 원인 확인과 재시작

1. consumer error 로그에서 topic·partition·offset과 예외 원인을 확보한다. 같은 group의 committed offset과 해당 주문의 완료 기록·모든 활동을 새 DB 조회로 확인한다. record를 읽었다는 로그만으로 DB 저장 완료를 판단하지 않는다.
2. 일시적 DB 오류라면 DB를 복구하고, 미지원 버전이라면 합의된 해석기를 먼저 준비한다. 같은 DB·topic·group으로 streamer를 재시작한다. listener 재시작용 공개 HTTP API는 없다.
3. 해당 record의 DB 결과가 기대한 사실과 일치하고 committed offset이 다음 위치로 진행하는지 확인한다. JVM이나 HTTP 서버가 응답하는 것만으로 listener가 정상 소비 중이라고 판단하지 않는다.

위 절차는 **원인이 제거돼 같은 record를 정상 해석·저장할 수 있는 경우**의 복구다. payload 자체가 잘못됐거나 저장된 사실과 충돌하면 재시작만으로 해결되지 않는다. broker record는 그대로 남기 때문에 수정한 이벤트를 뒤에 새로 발행해도 앞의 실패 record가 사라지지 않는다. 현재 구현에는 그 record를 수정·건너뛰거나 DLQ로 옮기는 기능이 없으며, 해당 사건의 처리 방침을 정하기 전까지 소비는 막힌다. group 변경이나 offset reset을 일반적인 복구 절차로 사용하지 않는다.

## 로컬 DB와 검증

local 프로필은 `ddl-auto=validate`로 준비된 스키마를 확인한다. 기존 DB에는 `docker/mysql/purchase-activity-migration.sql`을, 새 DB에는 `init.sql`을 적용한다. 앱 재시작으로 완료 기록·구매 활동을 다시 만들지 않는다. test 프로필의 격리 DB는 공통 `create` 설정을 유지한다. 전체 저장 모델과 이관 순서는 [영속성 매핑 설계](../../../docs/system-design/persistence-mapping.md)를 따른다.

DB 정합성은 `PurchaseActivityIntegrationTest`, 실제 소비는 `PurchaseActivityKafkaIntegrationTest`, listener 실패·ack·재시작은 `PurchaseActivityListenerIntegrationTest`, 같은 record의 offset 미커밋 재전달은 `PurchaseActivityRedeliveryIntegrationTest`로 확인한다. 방법·기대값은 [공통 검증 문서](../../../docs/system-design/use-cases/order-confirmed-event-verification.md), 실제 앱 프로세스 재시작 검증은 [로컬 검증 절차](../../../docs/verification/order-confirmed-event/README.md)를 따른다.

미지원 버전의 handler·ack 미호출은 `OrderConfirmedVersionDispatchTest`가 listener 메서드를 직접 호출해 검증한다. Spring 컨테이너 중단과 실제 committed offset 검증은 DB 오류를 주입하는 별도 listener 통합 테스트의 범위다.

주요 구현은 [OrderConfirmedConsumer](../src/main/java/com/loopers/interfaces/consumer/OrderConfirmedConsumer.java), [PurchaseActivityHandler](../src/main/java/com/loopers/application/purchase/PurchaseActivityHandler.java), [PurchaseActivityTransaction](../src/main/java/com/loopers/application/purchase/PurchaseActivityTransaction.java), [PurchaseActivityKafkaConfig](../src/main/java/com/loopers/infrastructure/purchase/PurchaseActivityKafkaConfig.java)다.
