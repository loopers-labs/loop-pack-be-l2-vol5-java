## 💻 Implementation Quest

> **PG로 확정된 주문을 이벤트로 전달하고, 재전달돼도 상품 구매 활동을 한 번만 남깁니다.**
> 

5주차 주문·PG 결과 적용 코드에서 시작합니다. 새로운 결제나 상품 CRUD를 만들지 않습니다. 이번 주에 만드는 **상품 구매 활동**은 확정 주문의 상품별 구매 수량·확정 시각을 저장한 사실이며, 9주차 주간 랭킹의 실제 입력으로 이어집니다. 이번 주에는 랭킹 API나 Outbox를 구현하지 않습니다.

### 🧭 1. 업무 연결과 이벤트 계약

```
검증된 PG SUCCESS
  → 기존 주문 최초 CONFIRMED + 예약 소비 commit
  → OrderConfirmed 직접 발행
  → Kafka order-events
  → commerce-streamer
  → 상품 구매 활동 DB 저장
```

주문·재고·포인트의 기존 규칙을 유지하며 consumer가 결제·재고 차감을 다시 수행하지 않게 합니다. 설계에 producer·consumer·DB 경계와 실패 시점, 이벤트가 필요한 이유를 표시합니다.

| 항목 | 이번 계약 |
| --- | --- |
| topic | `order-events`. 테스트에서는 고유 suffix를 붙여 격리 가능 |
| eventType / schemaVersion | `OrderConfirmed` / `1` |
| key | 내부 orderId의 문자열 표현. 같은 주문에 같은 직렬화·key 사용 |
| eventId | 최초 주문 확정 사실에 고정. 재발행·재전달마다 새 ID 생성 금지 |
| orderId / confirmedAt | 확정 주문 ID / 최초 확정 시각. DB 사실과 일치 |
| items | 상품별 `productId, quantity`. 상품 중복은 합산해 하나로 정규화, 수량 양수 |

eventId는 최초 확정 때 저장하거나 `order-confirmed:v1:{orderId}`처럼 한 주문의 단 한 번인 확정 사실에서 안정적으로 유도할 수 있습니다. 전송 시도 횟수·현재 시각·매번 새 UUID를 넣지 않습니다. 기존 확정 모델에 confirmedAt이 없다면 최초 PG 성공 전이에 함께 저장하고 중복 callback이 바꾸지 못하게 합니다.

- 공통 · 주문 42의 확정 이벤트 예시
    
    ```json
    {
      "eventType": "OrderConfirmed",
      "schemaVersion": 1,
      "eventId": "order-confirmed:v1:42",
      "orderId": 42,
      "confirmedAt": "2026-09-07T01:00:00Z",
      "items": [
        {"productId": 7, "quantity": 2},
        {"productId": 8, "quantity": 1}
      ]
    }
    ```
    
    예시의 시각·ID는 합성 값입니다. 실제 발행은 저장된 주문의 확정 시각과 품목을 사용합니다. key는 `"42"`이며 payload의 orderId와 일치해야 합니다.
    

JSON의 속성 순서·items 배열 순서가 바뀌었다고 새로운 업무 사실로 보지 않습니다. 비교할 때 상품별로 정규화한 값과 고정 필드를 사용합니다. fingerprint를 저장한다면 이 규칙으로 계산합니다.

PG 카드·비밀·불필요한 사용자 개인정보를 이벤트에 넣지 않습니다. 고객이 임의의 OrderConfirmed를 발행하는 공개 endpoint도 만들지 않습니다. 합성 이벤트 주입은 로컬 테스트 도구에서만 허용합니다.

### 📤 2. 기존 주문 확정 뒤 직접 발행

**검증된 PG SUCCESS를 처음 내부 CONFIRMED로 반영해 DB commit이 성공한 경우**에만 발행합니다. 기존 결과 적용 경계를 사용하므로 callback·reconcile 중 어느 경로가 먼저 와도 같은 조건을 적용합니다.

- DRAFT·PAYMENT_PENDING·UNKNOWN·확정 실패에서는 OrderConfirmed를 발행하지 않습니다.
- 최초 확정 TX가 rollback되면 발행하지 않습니다.
- 이미 종결된 같은 PG 시도의 반복 적용은 새 확정 이벤트를 생성하지 않습니다.
- 이전 POINT 확정 주문은 조회 사실을 유지하며 이번 변경만으로 자동 소급 발행하지 않습니다.
- payload는 확정 주문의 저장 품목·수량·시각에서 만듭니다. 소비 시점의 상품 조회로 과거 사실을 덮어쓰지 않습니다.

발행 위치는 after-commit listener 또는 트랜잭션 서비스 호출이 실제 commit된 뒤의 orchestration으로 정합니다. 같은 메서드 내부의 `@Transactional` 코드 끝에 send를 썼다고 이미 commit된 것은 아닙니다. after-commit listener가 쓰는 payload는 미리 구성해 lazy entity 접근에 의존하지 않습니다.

producer의 send 결과를 관찰해 성공한 record의 topic·partition·offset과 전송 오류를 구분합니다. 주문 commit 뒤 send가 실패해도 **이미 확정된 주문을 실패·미확인으로 되돌리거나 PG create를 재호출하지 않습니다.** 주문 상태는 저장된 결과로 답하고 후속 발행 실패를 별도로 드러냅니다.

**누락 구간 실험**: 실제 주문 확정 commit 뒤 전송 호출 직전을 통제된 hook에서 중단하거나 전송 실패를 주입합니다. 주문 CONFIRMED·예약 소비는 남고 해당 eventId의 broker record·구매 활동은 없음을 확인합니다. 이 관찰은 DB와 broker가 원자적으로 연결되지 않았다는 증거이며, 실패 주입을 OS 강제 종료라고 표현하지 않습니다. 다음 주 Outbox로 개선할 출발점입니다.

**다음 주에 넘길 것은 실행 중인 임시 DB가 아니라 두 사례의 재현 코드와 관찰 사실입니다.** 이번 테스트에 미발행 사례 1건과 정상 발행·소비 사례 1건을 함께 남깁니다.

- 기존 주문 API·로컬 PG fixture·실제 PG 결과 적용 경로로 두 주문을 확정합니다. 첫 사례만 commit 후 send 직전 hook에서 멈추고, 둘째는 실제 broker·consumer까지 처리합니다.
- 각 사례의 역할, 사용한 W6 commit·테스트 실행 방법, 고정 clock·품목·수량을 재현 코드에 남깁니다. 실행 결과에는 실제 `orderId, providerOrderId, transactionKey, eventId, confirmedAt, items`와 주문·예약·broker·구매 활동 상태를 기록합니다. 카드 값·비밀정보는 제외합니다.
- 재현 코드는 준비 단계에서 **W6의 직접 발행 경로**를 다시 실행할 수 있게 보존합니다. 기존 테스트의 작은 준비 함수를 활용하거나 W6 revision의 실행물을 사용하며, W7의 Outbox 경로를 실행한 뒤 행을 지워 누락을 꾸미지 않습니다.

기록한 ID는 W6 원래 실행의 관찰값입니다. W7의 새 격리 실행에서는 실제 생성된 주문에서 새 사건 ID를 얻고, 그 실행의 준비→복구 사이에서 고정합니다. 숫자를 같게 맞췄다고 역사적 원본 행이 보존된 것은 아닙니다. 두 사례와 재현 코드를 저장한 뒤 W6 테스트 자원은 정상 정리합니다. 임시 컨테이너를 일주일 동안 유지할 필요가 없습니다.

### 🧾 3. 구매 활동 소비와 DB 정합성

`commerce-streamer`에 실제 consumer를 연결합니다. 출력만 하는 demo listener는 구매 활동 기능이 아닙니다.

| 저장할 사실 | 보호할 규칙 |
| --- | --- |
| 소비 완료 이벤트 | eventId와 orderId의 유일성, 고정 payload의 일치 |
| 상품 구매 활동 | orderId·productId별 하나, quantity·confirmedAt 보존 |
| 둘의 반영 | 완료 기록과 모든 품목을 같은 DB TX에서 commit |

테이블·Entity 이름은 기존 구조에 맞게 정합니다. 예를 들어 중복 처리 여부는 `processed_order_confirmation`에, 상품별 구매 결과는 `product_purchase_activity`에 저장할 수 있습니다.

- 같은 eventId·같은 주문 사실의 재전달은 정상 no-op으로 완료합니다.
- 같은 eventId의 다른 주문·수량·확정 시각, 같은 orderId의 다른 eventId·사실은 충돌로 드러내고 추가 구매를 만들지 않습니다.
- 중복 판단은 메모리 Set이나 먼저 조회하기만 하는 로직으로 끝내지 않습니다. DB 유일 제약으로 동시 소비를 보호합니다.
- 중간 품목 저장 실패 시 완료 기록과 앞서 저장한 품목도 rollback합니다.
- 유일 제약 경쟁을 처리한다면 실패한 TX를 rollback한 뒤 별도 TX에서 기존 완료 사실이 같은지 확인합니다. 다른 오류를 모두 “중복”으로 바꾸지 않습니다.
- 소비자는 삭제·비노출 상품을 다시 노출하지 않습니다. 과거 주문의 productId를 사실의 식별자로 보존하며 현재 상품 행의 존재·현재 가격에 매번 의존하지 않습니다.

주문 42가 상품 7을 2개·상품 8을 1개 구매했다면 활동은 2행입니다. 같은 이벤트를 다시 받아도 여전히 2행입니다. 별도 주문 43에서 상품 7을 3개 구매하면 활동은 총 3행, 상품 7의 구매 수량 합은 5입니다. 이는 DB 결과 예시이며 랭킹 API를 이번에 만들라는 요구는 아닙니다.

### ⚙️ 4. 실제 listener 설정

**DB handler commit 성공 후 ack**하도록 연결합니다. 실패한 record는 업무 완료로 건너뛰지 않습니다. 자동 commit·listener ack mode·error handler·실제 committed offset을 함께 확인합니다.

기존 두 트랙의 Kafka 설정은 batch listener·MANUAL·concurrency 3이며 demo는 출력과 ack만 수행합니다. 이번 이벤트용 **record listener factory를 별도로 선택**하면 record별 DB 경계와 ack를 명확히 볼 수 있습니다. 기존 factory를 무조건 전역 변경하지 않습니다.

| 설정 | 확인할 내용 |
| --- | --- |
| bootstrap servers | producer·consumer·admin이 같은 실제 테스트 broker를 사용 |
| key/value 역직렬화 | String key와 선택한 JSON bytes/DTO 계약이 맞음 |
| `enable.auto.commit` | 실제 ConsumerConfig 값 false |
| ack mode | 예: record listener의 MANUAL_IMMEDIATE, consumer 스레드에서 DB 완료 후 ack |
| error handler | 실패 record를 skip하지 않고 중단·원인 표시·복구 후 재시작 가능 |

시작 저장소의 consumer YAML에는 `value-serializer`로 쓰인 항목이 있으므로 **value-deserializer**와 실제 factory 설정을 확인합니다. 사용자 정의 properties의 `enable-auto-commit` 표기만 믿지 말고 Kafka가 읽는 `ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG` 값을 확인합니다.

작은 테스트에서는 기존 `fetch.min.bytes=1MiB`·`fetch.max.wait.ms=5000` 설정 때문에 메시지가 늦게 보일 수 있습니다. 테스트 profile에서 작은 fetch 대기를 선택하고 이유를 남깁니다. 무작정 긴 sleep으로 덮지 않습니다.

- Java · record listener 연결의 최소 예시
    
    `configuredConsumerFactory`는 위 유효 설정을 적용한 기존/별도 factory입니다. DTO 파싱·업무 handler는 직접 구현합니다.
    
    ```java
    var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
    factory.setConsumerFactory(configuredConsumerFactory);
    factory.setBatchListener(false);
    factory.setConcurrency(1);
    factory.getContainerProperties()
        .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
    // 이 factory를 orderActivityKafkaListenerContainerFactory Bean으로 등록
    ```
    
    ```java
    @KafkaListener(topics = "order-events", groupId = "purchase-activity",
        containerFactory = "orderActivityKafkaListenerContainerFactory")
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        var event = parser.parseAndValidate(record.key(), record.value());
        purchaseActivityHandler.consume(event); // 별도 Bean의 DB TX가 commit 후 반환
        ack.acknowledge();
    }
    ```
    
- Kotlin · record listener 연결의 최소 예시
    
    ```kotlin
    val factory = ConcurrentKafkaListenerContainerFactory<String, ByteArray>()
    factory.consumerFactory = configuredConsumerFactory
    factory.setBatchListener(false)
    factory.setConcurrency(1)
    factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
    factory.setCommonErrorHandler(CommonContainerStoppingErrorHandler())
    // 이 factory를 orderActivityKafkaListenerContainerFactory Bean으로 등록
    ```
    
    ```kotlin
    @KafkaListener(
        topics = ["order-events"], groupId = "purchase-activity",
        containerFactory = "orderActivityKafkaListenerContainerFactory"
    )
    fun listen(record: ConsumerRecord<String, ByteArray>, ack: Acknowledgment) {
        val event = parser.parseAndValidate(record.key(), record.value())
        purchaseActivityHandler.consume(event) // DB TX가 commit된 후 반환
        ack.acknowledge()
    }
    ```
    
    두 예시는 필요한 import·Bean·설정을 생략한 연결 예시입니다. handler 자기 호출로 트랜잭션을 우회하지 않는지 확인합니다.
    

중단형 error handler는 기본 선택 예시이며, 실제 listener 테스트에서 실패 record가 commit되지 않음을 확인해야 합니다. 다른 handler를 선택했다면 재시도 소진·recover·`ackAfterHandle`까지 설명합니다. `commitRecovered=false`만으로 컨테이너의 모든 commit이 막힌다고 결론 내리지 않습니다.

### 👥 5. Partition·consumer group 실험

실제 Kafka에 **partition 2개인 격리된 topic**을 만듭니다. 실제 broker를 Testcontainers 또는 직접 시작한 로컬 구성으로 띄우며 endpoint와 정리 책임을 명시합니다.

1. 같은 group의 consumer를 1→2→3개로 바꾸며 안정된 assignment를 관찰합니다.
2. 각 partition이 group 안에서 한 member에게 배정되고, 3개일 때 적어도 하나가 배정 없이 남는지 확인합니다.
3. 명시적으로 두 partition에 테스트 record를 보내 읽히는 것을 확인합니다. 실험 record는 업무 topic과 분리해 가짜 구매 활동을 만들지 않습니다.
4. key 매핑 실험에서는 producer가 partition을 고르게 하고 같은 key의 실제 전송 metadata를 비교합니다. 고정 partition 수에서 같은 key가 같은 partition으로 가는지 확인합니다.
5. `acks`의 선택과 단일 broker 실험으로 확인하지 못하는 복제 장애 보장을 설명합니다.

partitioner 실험에서 서로 다른 key가 반드시 서로 다른 partition으로 간다고 assert하지 않습니다. 배정 전에 poll 없이 잠만 자거나, consumer 객체 수만 세어 실제 group 참여를 주장하지 않습니다.

- Java·Kotlin · 현재 테스트 기반에서 추가할 것
    
    두 트랙 `modules:kafka`에 Kafka·Testcontainers 테스트 의존성은 있으나 **제공된 Kafka fixture 소스는 없습니다.** 기존 의존성 선언을 실행 가능한 broker fixture로 착각하지 않습니다.
    
    - 테스트에 직접 필요한 Kafka Testcontainers 의존성과 작은 broker fixture를 추가할 수 있습니다. 현재 프로젝트 의존성 관리와 호환되는 API·이미지를 선택합니다.
    - Java streamer의 Kafka testFixtures 참조만으로 이미 fixture 클래스가 생기지는 않습니다. Kotlin streamer에는 해당 참조도 없으므로 필요한 테스트 의존성을 연결합니다.
    - producer·consumer·admin에 fixture의 실제 bootstrap address를 주입합니다. 컨테이너 내부 `kafka:9092`와 호스트의 `localhost:19092`를 섞지 않습니다.
    - 고유 topic·group을 사용하고 준비·배정·소비 대기를 유한하게 제한합니다.

### 🔁 6. DB commit 뒤 offset 미커밋 재전달

실제 OrderConfirmed 한 건과 **실제 구매 활동 handler**로 검증합니다. 같은 broker·topic·group·DB를 유지하고 consumer만 새로 만듭니다.

아래는 low-level KafkaConsumer를 사용하는 통제된 방법입니다. 애플리케이션의 실제 listener와 동일한 파싱·업무 handler를 호출하되, **첫 실행의 offset commit만 테스트에서 보류**합니다.

| 순서 | 행동 | 확인할 사실 |
| --- | --- | --- |
| 준비 | 격리 topic에 target 발행, send metadata 확보 | 실제 target partition·offset n |
| 시작 위치 | 첫 consumer가 구독·배정된 뒤 target의 다음 읽을 위치 n을 commit하고 n에서 읽기 | group committed offset n |
| 첫 처리 | target poll → 실제 handler DB commit | 구매 활동·완료 기록 존재 |
| 종료 전 | target 완료 offset n+1을 요청하지 않음 | committed offset은 여전히 n |
| 종료 | auto commit false, pending commit·종료 commit callback 없이 bounded close | 첫 consumer 종료 |
| 재시작 | 새 consumer, 같은 group으로 구독·배정·poll. offset 재설정 금지 | 동일 topic·partition·offset n 재전달 |
| 두 번째 처리 | handler가 같은 사실로 no-op, 그 뒤 n+1 commit | 구매 효과 동일, committed offset n+1 |

테스트 준비의 시작 offset 지정과 재전달을 혼동하지 않습니다. **두 번째 consumer에서는 seek·offset reset으로 강제 재생하지 않습니다.** 처음 저장한 group offset에서 실제로 다시 읽어야 합니다. broker의 offset 값은 metadata에서 구하며 임의로 41·42 같은 값을 고정하지 않습니다.

관찰값은 전달 2회, 동일 record 위치, 한 주문의 활동 행 수·수량 불변, committed offset `n → n → n+1`입니다. poll의 position이나 테스트 로그만으로 성공 판정하지 않습니다. 첫 consumer의 close가 자동·대기 중 commit을 수행하지 않도록 설정과 호출 이력을 확인합니다.

**실제 Spring listener의 실패 경로도 검증합니다.** 별도 격리 topic/group에서 DB commit 전 오류를 주입해 활동·완료 기록 전체 rollback, 실패 record 이후로 offset이 진행하지 않음, 선택한 중단·오류 표시를 확인합니다. 오류 제거 후 같은 group으로 컨테이너를 재시작해 재전달·정상 저장·ack까지 확인합니다.

두 실험의 목적은 다릅니다. 첫 실험은 **DB 효과 성공 후 offset 공백**, 두 번째는 **실제 listener의 실패·ack 정책**을 검증합니다. handler를 두 번 직접 호출한 단위 테스트만으로 둘을 대체하지 않습니다.

선택 심화로 실제 프로세스를 갑자기 종료하는 상황과 비교할 수 있습니다. 종료 전에 DB commit과 미커밋 offset이 관찰됐는지 확인하고, 자신이 만든 프로세스만 정리합니다.

### 🧪 7. 누적 기능 검증과 제출

| 검증 | 기대 결과 |
| --- | --- |
| 실제 PG 확정→Kafka→streamer | 저장된 품목·수량·confirmedAt과 동일한 구매 활동 |
| 대기·UNKNOWN·실패·확정 rollback | OrderConfirmed 발행 없음 |
| 중복 callback/reconcile | 새 논리 이벤트·추가 재고 차감 없음 |
| 같은 record 재전달 | 전달은 반복돼도 구매 효과 한 번 |
| 같은 ID의 payload 충돌 | 추가 효과 없이 오류 노출 |
| 동시 중복·품목 저장 중 실패 | 유일성 보호·전체 TX rollback |
| commit 후 발행 공백 | 확정 주문은 남지만 활동 누락, W7 개선 대상 기록 |
| 기존 기능 | PG·예약·이전 POINT 사실·소유권·삭제 비노출 보존 |

예상 결과를 먼저 정하고 실제 브로커 위치와 새 DB 조회로 비교합니다. AI에게 “DB 저장 뒤 commit 전에 멈춤”, “handler가 예외를 삼킴”, “같은 ID로 다른 수량 수신”의 반례를 검토하게 하고 채택한 보호 규칙을 코드·테스트로 확인합니다.

- Java·Kotlin · 관련 검사 예시
    
    아래 이름은 직접 만든 테스트에 맞춥니다.
    
    ```bash
    ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublicationTest'
    ./gradlew :apps:commerce-streamer:test --tests '*PurchaseActivityIntegrationTest'
    ./gradlew :apps:commerce-streamer:test --tests '*KafkaDeliveryTest'
    
    # Java: W2부터 유지한 Checkstyle·ArchUnit·관련 회귀
    ./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
    
    # Kotlin
    ./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-streamer:ktlintCheck
    ./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
    ```
    
    테스트 0건·skip·broker 연결 실패는 성공이 아닙니다. 대기 deadline을 두고, 시작한 consumer·컨테이너·HTTP fixture를 정리합니다. 다른 실행의 topic·group·DB를 삭제하지 않습니다.
    

**제출**: 주문 이벤트 발행·구매 활동 소비 코드, 이벤트/트랜잭션 설계, 관련 테스트, PR과 기술 글. PR에는 대표 주문의 활동 결과, 배정표, 실제 offset 전후, 남아 있는 DB→broker 누락 구간을 짧게 기록합니다. 단일 broker·통제 종료 실험의 한계를 명시합니다.

---

## ✅ Checklist

### 📬 업무 연결

- [ ]  실제 PG 확정 주문에서 stable 이벤트를 발행하고 구매 활동으로 저장한다.
- [ ]  대기·실패·rollback·중복 결과 적용에서 거짓 이벤트나 새 결제가 생기지 않는다.
- [ ]  저장된 구매 사실을 이후 랭킹 입력으로 사용할 수 있고 민감정보를 보내지 않는다.

### 🔁 전달과 효과

- [ ]  partition 2개와 같은 group의 consumer 1→2→3 실제 배정을 확인했다.
- [ ]  같은 record가 DB commit 뒤 미커밋 offset에서 재전달됨을 관찰했다.
- [ ]  중복 방지와 모든 품목 저장이 원자적이며 같은 사실·충돌을 구분한다.
- [ ]  실제 listener의 DB 실패·error handler·ack·재시작 결과를 확인했다.

### 🧪 경계와 검증

- [ ]  직접 after-commit 발행의 누락 구간을 재현하고 주문 상태를 되돌리지 않는다.
- [ ]  미발행 1건·이미 소비된 1건의 재현 코드와 실제 사건 사실을 남기고 임시 DB는 정리했다.
- [ ]  실제 설정·broker 주소·단일 broker 한계를 설명할 수 있다.
- [ ]  관련 회귀·lint·ArchUnit을 실행하고 테스트 자원을 정리했다.
