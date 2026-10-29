# 커머스 설계 — 6주차 · 확정 주문을 이벤트로 전달하고 구매 활동을 한 번만 남긴다

요구사항 명세(`docs/week6/requirements.md`)의 규칙을 어떤 구조로 지키는지, 그리고 그 이유를 적는다.
5 주차 설계는 `W5` 로 가리킨다. 규칙 ID 는 명세의 것이다. Spring 동작의 근거는 Spring Framework 6.2.5 소스(Boot 3.4.4 가 쓰는 버전)다.

## 1. 무엇이 바뀌는가

```
commerce-api
  TX B (PaymentTransactions.observe → apply)
    CONFIRM_ORDER 효과 → Order.completePayment → OrderConfirmed 값 생성 → publishEvent (예약만)
  commit
  afterCompletion(COMMITTED) → OrderConfirmedKafkaPublisher → KafkaTemplate.send() → 바로 반환
  cleanup (EntityManager 닫기 · 커넥션 반납) → 응답
                                   ┆ Sender 스레드: broker → ack → whenComplete(기록)
                                   ▼
                       topic order-events (key = orderId)
                                   ▼
commerce-streamer
  record listener (purchase-activity group, MANUAL_IMMEDIATE)
    handler TX: 처리 기록 + 품목별 구매 활동 insert → commit
  ack (consumer 스레드)
```

- **확정 TX 와 broker 는 하나로 묶이지 않는다.** commit 뒤에 보내므로 "commit 됐는데 record 가 없는" 구간이 남는다(`EVT-017` · `EXP-007`). 이 구간을 닫는 것은 W7 의 outbox 다.
- **소비 쪽 멱등은 DB 유일 제약이 진다.** Kafka 는 적어도 한 번 전달하므로, 같은 record 가 다시 와도 효과가 한 번이 되게 처리 기록과 활동을 한 TX 에 넣는다(`ACT-003` · `ACT-007`).

## 2. 이벤트 계약

### 2.1 식별자 (`EVT-002` · `EVT-004`)

| 값 | 형태 | 근거 |
|---|---|---|
| key | `orderId` 10 진 문자열 | 같은 주문의 이벤트가 같은 partition 으로 가 순서가 지켜진다. 해시는 producer 가 partition 을 고를 때만 쓴다 |
| `eventId` | `order-confirmed:v1:{orderId}` | "주문 하나에 확정은 평생 한 번" 이라는 사실에서 유도한다. 재발행 · 재시작에도 같은 값이 나와 소비자가 같은 사실로 알아본다 |

- `v1` 은 **식별자를 만드는 규칙의 버전**이다. payload 형식이 바뀌어도(`schemaVersion`) 그대로 둔다. 바뀌면 소비자가 모든 과거 주문을 "같은 `orderId` 에 다른 `eventId`" 로 보고 충돌로 보류한다.
- UUIDv5 처럼 해시로 길이를 고정하지 않는다. 얻는 것은 길이뿐이고, 사람이 로그에서 주문을 알아보지 못하게 된다.
- **뒤집을 조건**: 한 주문이 두 번 확정될 수 있게 될 때(부분 취소 뒤 재확정 등). 그때 `orderId` 만으로는 사실이 구분되지 않으므로, 확정 시점에 저장한 식별자를 쓴다.

### 2.2 payload (`EVT-003` · `EVT-006` · `EVT-007` · `EVT-008`)

```json
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
 "confirmedAt":"2026-10-30T01:02:03.120000Z","items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]}
```

- `confirmedAt` 은 저장된 `Order.confirmedAt` 이다. **저장 전에 마이크로초로 자른다.** MySQL `DATETIME(6)` 은 넘치는 자리를 반올림하므로, 자르지 않으면 메모리의 값(이벤트)과 DB 를 다시 읽은 값이 갈린다.
- 표기는 소수 6 자리로 **고정**한다. `Instant.toString` 은 끝자리 0 을 지워(`.12Z`) 같은 시각이 다른 문자열이 된다. 소비자는 문자열이 아니라 시각으로 비교하지만, 계약 예시 · 로그 · 대사에서 같은 사실이 같은 문자열로 보이게 한다.
- `items` 는 저장된 주문 품목에서 만들고, 같은 상품은 합산해 하나로 둔다. `productId` 오름차순으로 정렬한다 — 의미는 없지만 같은 사실이 같은 바이트가 되어 로그 비교가 쉽다.
- `userId` · 카드 · PG 식별자는 넣지 않는다. 구매 활동에 필요 없고, 이벤트는 저장 · 복제 · 재생되는 동안 지울 수 없다.

### 2.3 직렬화

producer 의 value 직렬화기는 `ByteArraySerializer` 이고, publisher 가 **앱의 `ObjectMapper`** 로 바이트를 만든다.

- 공용 설정의 `JsonSerializer` 는 Spring 의 `ObjectMapper` 가 아니라 자기 것을 만들어 쓴다. 시각 형식 · 모듈 등록이 앱과 달라질 수 있고, `spring.json.add.type.headers` 같은 다른 축의 설정에 기대게 된다.
- 지금 공용 `KafkaTemplate` 을 쓰는 코드가 없어서, 공용 직렬화기를 바꾸는 비용이 0 이다. 전용 template 을 하나 더 두면 같은 broker 에 producer 가 둘이 되고 설정이 두 곳에 생긴다.
- **뒤집을 조건**: 다른 코드가 객체를 그대로 보내는 공용 template 을 필요로 할 때. 그때 이벤트용 template 을 분리한다.

### 2.4 계약을 두 앱이 함께 지키는 법

계약 예시 JSON 한 벌을 `modules:kafka` 의 testFixtures 에 두고, commerce-api 는 "만든 payload 가 이 예시와 같은 모양인가" 를, commerce-streamer 는 "이 예시를 해석해 기대한 활동을 만드는가" 를 각자 테스트한다.
두 앱을 한 실행에 띄우지 않고도 계약이 한쪽에서만 바뀌는 것을 잡는다. 두 앱을 실제로 잇는 실행은 Q-4(7.1)다.

## 3. 발행

### 3.1 어디서 — 결과 반영 경계 (`EVT-010` · `EVT-011`)

`PaymentTransactions.apply` 의 `CONFIRM_ORDER` 분기 하나에서 이벤트를 만든다.

- 생성 응답 · callback · reconcile · sweeper 네 경로가 모두 이 경계를 지난다(W5 5장). 어느 경로가 먼저 와도 발행 조건이 같다.
- `CONFIRM_ORDER` 효과는 `PENDING → CONFIRMED` 최초 전이에서만 나온다. 반복 반영은 효과 `NONE` 이라 이벤트가 생기지 않는다(`EVT-011`). 동시에 온 callback 과 reconcile 은 W5 의 잠금(주문 → 결제)으로 줄 서고, 늦은 쪽은 `NONE` 을 본다.
- 이 분기에서 `OrderConfirmed.of(order)` 로 **값**을 만든다. 엔티티를 넘기지 않는다 — listener 는 정리 단계 직전에 돌고, `@Async` 나 다른 스레드로 넘기는 순간 영속성 컨텍스트가 없다.
- 값을 만들거나 `publishEvent` 를 부르다 예외가 나면 **잡아서 발행 실패로 기록하고 확정은 commit 한다**(`EVT-012`). PG 는 이미 돈을 움직였으므로, 이벤트 하나 때문에 확정을 되돌리면 "결제됐는데 미확정" 이 된다.

**전제: 이 TX 가 가장 바깥 TX 다.** AFTER_COMMIT 은 가장 바깥 TX 가 commit 될 때 돈다. 지금 `PaymentFacade` · `PaymentSweeper` 에는 `@Transactional` 이 없어 `observe` 의 TX 가 가장 바깥이다.
이 전제가 깨지면(누가 바깥에 TX 를 두르면) 발행이 바깥 commit 까지 미뤄지고, 바깥이 rollback 되면 발행되지 않는다. 확인할 테스트 T-1(10장).

### 3.2 언제 — commit 뒤 (`EVT-012`)

`@TransactionalEventListener(phase = AFTER_COMMIT)` 로 보낸다.

| 지금 고를 수 있는 선택지 | 실행 시점 | send 예외가 나면 | 이 설계에서 |
|---|---|---|---|
| TX 본문에서 직접 `send()` | TX 안 | 주문 rollback. 이미 나간 이벤트는 못 되돌림 | 확정 안 된 주문의 이벤트가 남는다(원문 study 80) |
| `@EventListener` | `publishEvent` 줄에서 바로, 같은 TX | 주문 rollback | 직접 호출과 같다. 코드만 떨어져 보인다 |
| `@TransactionalEventListener(BEFORE_COMMIT)` | 커밋 직전, TX 안 | 주문 rollback | 위와 같다. W7 outbox 의 행 쓰기 자리다 |
| **`AFTER_COMMIT`** | **커밋 뒤** | **로그만, 주문 그대로** | **고른다** |
| Facade 가 TX 메서드 반환 뒤 명시 호출 | 커밋 뒤 | 호출자가 정함 | 네 경로(3.1)마다 호출을 넣어야 하고 하나만 빠져도 발행이 빠진다 |

- AFTER_COMMIT listener 는 `afterCommit` 이 아니라 **`afterCompletion(STATUS_COMMITTED)`** 에서 실행된다(`TransactionalApplicationListenerSynchronization`). 여기서 난 예외는 `TransactionSynchronizationUtils.invokeAfterCompletion` 이 ERROR 로그로 남기고 삼킨다. 그래서 발행이 실패해도 `observe` 는 정상 반환하고 응답이 바뀌지 않는다(`EVT-013`).
- 그 대가로 **우리가 잡지 않은 예외는 Spring 로그 한 줄로만 남는다.** publisher 는 동기 예외를 직접 잡아 실패 지표로 보낸다(3.6).
- **뒤집을 조건**: 발행이 확정과 원자적이어야 할 때 — W7 에서 BEFORE_COMMIT(또는 TX 본문)의 outbox 행 쓰기로 바꾼다.

### 3.3 어느 스레드가, 무엇을 쥔 채로

AFTER_COMMIT listener 는 **확정을 요청한 스레드**(Tomcat · sweeper)에서 돈다. 기본 multicaster 에는 executor 가 없고, 트랜잭션 listener 는 `supportsAsyncExecution() = false` 라 예약 등록도 늘 부른 스레드에서 한다.

```
doCommit                 DB commit · 행 잠금 해제
triggerAfterCommit       일반 afterCommit 콜백
triggerAfterCompletion   ← AFTER_COMMIT listener → send()
cleanupAfterCompletion   동기화 비움 · EntityManager 닫기 · 커넥션 반납
```

listener 가 도는 동안 **커넥션이 아직 풀에 돌아오지 않았다**(`AbstractPlatformTransactionManager.processCommit` 의 `finally { cleanupAfterCompletion }`). 그래서 `send()` 가 막히는 시간이 곧 커넥션을 더 쥐는 시간이고, 그 상한이 `max.block.ms` 다(3.7).
행 잠금은 commit 에서 이미 풀렸으므로 같은 주문의 다른 반영을 막지는 않는다.

### 3.4 `@Async` 를 두지 않는다

`send()` 는 이미 비동기다 — 버퍼에 넣고 future 를 돌려준다. `@Async` 가 응답 경로에서 더 빼 주는 것은 `send()` 의 동기 구간뿐이다.

- 동기 구간이 길어지는 경우는 둘이다: topic metadata 가 없을 때(기동 직후 · 오래 안 보낸 topic), 버퍼(32 MiB)가 가득 찼을 때. metadata 가 캐시에 있으면 broker 가 죽어 있어도 `send()` 는 바로 반환하고 실패는 나중에 Sender 스레드로 온다.
- 첫 경우는 기동 시 metadata 확보(3.7)로, 둘째는 `max.block.ms` 1 초로 상한을 둔다.

`@Async` 가 가져오는 것:

- **메모리 큐가 하나 더 생긴다.** 재시작하면 큐의 이벤트는 실패 콜백 없이 사라져 실패 지표에도 남지 않는다. 장애가 "응답이 조금 늦음 + 실패 지표" 에서 "보이지 않는 큐" 로 옮겨 간다.
- Boot 기본 executor 는 큐가 무제한이라 broker 장애 동안 끝없이 쌓인다. 제한하면 거절 정책을 골라야 하는데, `CallerRunsPolicy` 는 결국 요청 스레드에서 돌아 `@Async` 를 안 붙인 것과 같아지고(종료 중이면 조용히 버린다), `AbortPolicy` 는 이벤트를 버린다.
- 큐 크기 · 거절 정책 · 종료 시 비우기 · 큐 길이 지표를 정해야 한다.

**뒤집을 조건**: `max.block.ms` 1 초에서도 broker 장애 실험에서 응답 지연이나 커넥션 대기가 문제로 측정될 때. 그때는 크기 제한 executor 와 큐 길이 지표를 함께 둔다. 응답 시간에 엄격한 SLO 가 생기면 W7 outbox 를 앞당긴다 — 요청은 같은 TX 에 행 하나만 쓰고 broker 와 무관해진다.

### 3.5 listener 에 `@Transactional` 을 두지 않는다

listener 는 `send()` 만 하고 DB 를 읽지도 쓰지도 않는다. 그래서 TX 가 필요 없다.

- AFTER_COMMIT 시점에는 TX 표시 · EntityManager 가 아직 스레드에 붙어 있다(정리 전). 여기서 기본 전파 `REQUIRED` 로 DB 를 쓰면 **이미 commit 된 TX 에 참여해 쓰기가 조용히 사라진다.**
- 이 함정은 listener 가 **부르는** `@Transactional` 서비스에도 똑같다. Spring 6.1+ 의 `RestrictedTransactionalEventListenerFactory` 는 listener 메서드와 **그 클래스**에 붙은 `@Transactional` 이 `REQUIRES_NEW` · `NOT_SUPPORTED` 가 아니면 기동을 거절하지만, 호출되는 서비스는 보지 않는다.
- 그래서 listener 는 `@Transactional` 이 없는 **별도 클래스**에 두고, 서비스를 부르지 않는다.
- **뒤집을 조건**: listener 가 DB 에 써야 할 때(발행 결과를 테이블에 남기는 등). `REQUIRES_NEW` 가 필요하지만, 주문 commit 과 그 쓰기 사이에 또 공백이 생기므로 사실상 W7 outbox 로 간다.

### 3.6 결과 관찰 (`EVT-015`)

```java
try {
    kafkaTemplate.send(topic, key, bytes).whenComplete((result, ex) -> {
        if (ex == null) metrics.published(eventId, result.getRecordMetadata());
        else metrics.publishFailed(eventId, ex);
    });
} catch (RuntimeException e) {
    metrics.publishFailed(eventId, e);
}
```

- **`.get()` 으로 기다리지 않는다.** ack 가 실패로 와도 요청이 할 수 있는 일이 없다 — 주문은 commit 됐고(되돌리지 않음), 응답은 200 이며, 재시도는 W7 범위다. 기다리면 응답만 `delivery.timeout.ms` 까지 늦어진다.
- **ack 는 받는다**(`acks=all`). 받지 않으면 실패를 셀 수 없고, 손으로 재발행할 단서(`eventId`)와 PR 에 적을 실제 offset 을 얻지 못한다.
- `whenComplete` 람다는 future 를 완료시킨 스레드에서 돈다. 보통 **Sender 스레드**라서 지표 · 로그만 둔다 — 여기서 DB 를 쓰거나 막히면 모든 발행이 멈춘다. 버퍼에 들어가기 전에 실패하면(metadata 시간 초과) future 가 이미 실패한 채로 돌아와 람다가 호출 스레드에서 바로 돈다. 어느 쪽이든 같은 기록 코드를 탄다.
- 결과를 받는 다른 방법과 비교:

| 방법 | 범위 | 이 설계에서 |
|---|---|---|
| **`CompletableFuture.whenComplete`** | 호출 하나 | **고른다.** `eventId` 가 클로저에 있고, 발행 코드 옆에 결과 처리가 있다 |
| `ProducerListener` | template 전체 | template 이 공용이라 다른 발행에도 걸리고, `eventId` 를 record 에서 다시 꺼내야 한다 |
| Kafka `Callback` | 호출 하나 | `KafkaTemplate.send` 에 넘길 자리가 없다. 원시 producer 를 꺼내면 observation · 기본 topic 을 잃는다. 같은 순간 · 같은 스레드에 불리므로 얻는 것이 없다 |

- 기본 `LoggingProducerListener` 는 실패를 ERROR 로 한 번 더 찍는다. 빈 `ProducerListener` 를 등록해 실패 기록을 한 곳에 모은다.
- **뒤집을 조건**: 발행하는 이벤트 종류가 늘어 같은 기록 코드가 여러 `whenComplete` 에 복사될 때 — `ProducerListener` 하나로 모은다.
- 원문은 "send 결과를 관찰해 성공 record 의 topic · partition · offset 과 오류를 구분" 하라고만 한다(원문 67). 방법은 `정함` 이다.

### 3.7 producer 설정 (`EVT-014` · `EVT-016`)

설정은 공용 `kafka.yml` 한 곳에 둔다. `spring.config.import` 로 불러온 파일은 불러온 쪽보다 우선하므로, 앱 yml 에 같은 키를 두면 조용히 진다.

| 설정 | 값 | 근거 |
|---|---|---|
| `acks` | `all` (명시) | 기본값이지만, 충돌 설정이 들어오면 멱등이 조용히 꺼지므로 명시한다 |
| `enable.idempotence` | `true` (명시) | 내부 재시도로 생기는 broker 쪽 중복을 PID · sequence 로 막는다. in-flight 5 이하에서 partition 안 순서도 지켜진다 |
| `retries` | **지정 안 함**(기본 무한) | 횟수 대신 `delivery.timeout.ms` 로 상한을 건다. `retries: 3` · backoff 500ms 면 몇 초 안에 포기해, leader 교체 같은 정상 지연에도 실패한다 |
| `delivery.timeout.ms` | 120s (기본) | 버퍼에 든 뒤 ack 까지의 상한. Sender 스레드의 일이라 응답과 무관하다. "실패를 언제 알게 되나" 를 정한다. `request.timeout.ms`(20s) + `linger.ms` 보다 커야 한다 |
| `max.block.ms` | **1s** (기본 60s) | 아래 |
| value 직렬화기 | `ByteArraySerializer` | 2.3 |

**`max.block.ms` = 1 초.** 이 값은 broker 장애 때 사용자 응답 · 커넥션 점유에 더해지는 최악의 시간이다(3.3). 이미 정한 시간들에 맞춘다.

| 설정 | 값 |
|---|---|
| PG 연결 · 결과 조회 · 생성 | 500ms · **1s** · 2s (`pg.yml`) |
| Hikari 커넥션 대기 · 행 잠금 대기 | **3s** · 3s (`jpa.yml`) |

- **Hikari 대기(3 초)보다 확실히 짧아야 한다.** 막힌 `send()` 가 커넥션을 쥐므로, 이 값이 3 초에 가까우면 장애 때 다른 요청이 커넥션을 기다리다 줄줄이 실패한다 — 발행 장애가 주문 API 장애로 번진다.
- **PG 호출보다 길 이유가 없다.** 발행은 결제보다 덜 중요한 후속 작업이다.
- 정상 broker 의 metadata 응답은 수 ms 다. 1 초를 넘기면 이미 장애다.
- **반론: "기동 직후 첫 metadata 는 느릴 수 있으니 짧게 잡으면 정상 상황에서 포기한다."** 이 경우는 **기동 시 `order-events` 의 metadata 를 미리 받아**(`partitionsFor`) 없앤다. 시간 초과는 기동을 막지 않고 로그만 남기며(첫 발행 때 다시 받는다), 설정 오류(`ConfigException`)는 기동을 막는다.
- 다른 선택지: 500ms 는 응답에 더 유리하지만 GC · leader 교체 같은 짧은 지연도 누락으로 만든다(재시도 없음). 2 ~ 3 초는 Hikari 대기에 가까워진다.
- **뒤집을 조건**: 장애 실험에서 1 초 안에 metadata 를 못 받는 정상 상황(leader 교체 등)이 관찰될 때. Hikari 대기 시간을 바꿀 때.

### 3.8 실패 · 누락 · 알림 (`EVT-013` · `EVT-015` · `EVT-017`)

| 누락 원인 | 실패 콜백 | 지표 · 알림이 잡나 |
|---|---|---|
| broker 장애 · 시간 초과 · 거절 · 직렬화 오류 | 온다 | 잡는다 |
| payload 생성 · 등록 실패 | 직접 잡음(3.1) | 잡는다 |
| 정상 종료 때 버퍼에 남은 record | close 대기를 넘기면 실패 콜백 | 잡는다 |
| **커밋 뒤 send 전 · 버퍼 안에서 프로세스 강제 종료** | **안 온다** | **못 잡는다** |

- 실패 지표에 알림 규칙을 건다("5 분 동안 실패 N 건 초과"). 알림은 **감지**이지 복구가 아니다. 복구는 로그의 `eventId` 로 사람이 재발행하는 것이고, W7 전까지 유일한 수단이다.
- 마지막 줄은 흔적이 없어 알림으로 풀리지 않는다. W7 outbox 가 막을 구간이다.
- 정상 종료는 producer 의 close 대기(`DefaultKafkaProducerFactory` 의 physical close timeout, 기본 30s)까지 버퍼를 비운다. 이 값은 graceful shutdown 상한(기본 30s) 안에 들어가야 한다.
- 애플리케이션 재시도는 하지 않는다. `whenComplete` 의 실패는 producer 가 `delivery.timeout.ms` 동안 이미 재시도하고 포기했다는 뜻이고, 메모리에서 다시 보내 봐야 재시작하면 사라진다. ack 만 잃은 경우라면 새 `send()` 는 broker 에 다른 record 로 하나 더 쌓인다(멱등 producer 는 같은 batch 의 내부 재시도만 거른다).
- **뒤집을 조건**: W7 relay 가 생기면 relay 가 ack 를 기다려(`.get()`) 내구성 있는 재시도를 맡는다. 알림이 너무 늦다는 운영 요구가 생기면 `delivery.timeout.ms` 를 줄인다.

### 3.9 TX 없이 `publishEvent` 를 부르면

`TransactionalApplicationListenerMethodAdapter` 는 예약할 TX 가 없으면 **DEBUG 로그 한 줄만 남기고 이벤트를 버린다**(`fallbackExecution=false`).
결과 반영 경로는 늘 TX 안이라 해당하지 않지만, 누가 확정 로직을 TX 밖에서 부르면 발행이 조용히 사라진다. 확인할 테스트 T-2 로 고정한다. `fallbackExecution=true` 는 두지 않는다 — TX 없이 확정되는 경로 자체가 버그다.

## 4. 소비

### 4.1 저장 모델 (`ACT-002` · `ACT-005` · `ACT-011`)

commerce-api 와 같은 스키마를 쓰되 주문 · 상품 테이블을 읽지 않고 외래키를 걸지 않는다(Q-2).

**`order_confirmation_receipt` — 처리 기록**

| 컬럼 | 제약 | 뜻 |
|---|---|---|
| `event_id` | `uk_receipt_event` | 같은 사실의 재전달을 막는다 |
| `order_id` | `uk_receipt_order` | 같은 주문에 다른 `eventId` 가 오는 충돌을 잡는다 |
| `confirmed_at` · `items_digest` | | "같은 사실" 비교 값. `items_digest` 는 `productId` 오름차순 `7:2,8:1` |
| `topic` · `partition_no` · `offset_no` · `received_at` | | 처음 반영한 record 의 위치 |

**`purchase_activity` — 구매 활동**

| 컬럼 | 제약 |
|---|---|
| `order_id` · `product_id` | `uk_activity_order_product` |
| `quantity` · `confirmed_at` | 이벤트의 값(`ACT-012`) |

**`order_event_hold` — 보류 기록**

| 컬럼 | 제약 | 뜻 |
|---|---|---|
| `topic` · `partition_no` · `offset_no` | `uk_hold_position` | 같은 record 가 재전달돼도 한 줄 |
| `reason` · `event_id` · `order_id` · `payload` · `held_at` | | 원본 바이트를 그대로 남긴다 |

- 처리 기록을 지우지 않는다(`ACT-014`). 늦은 중복(offset 되감기 · 재발행)은 언제든 올 수 있다. **뒤집을 조건**: 보관 기간이 정해지고, 그보다 오래된 record 를 다시 읽지 않는다는 운영 보장이 생길 때.
- 활동을 `(order_id, product_id)` 로 유일하게 두는 것은 처리 기록과 별개의 두 번째 방어선이다. 처리 기록 없이 활동만 남는 버그가 생겨도 같은 품목이 두 번 쌓이지 않는다.

### 4.2 handler 의 흐름 (`ACT-003` · `ACT-004` · `ACT-008`)

```
listener (consumer 스레드)
  parse · 검증 ── 해석 불가(ACT-009) ──────────────→ hold TX → ack
  handler TX: receipt insert + 품목별 activity insert → commit → ack
     └ 유일 제약 위반 → rollback
         제약 이름이 uk_receipt_event · uk_receipt_order 가 아니면 → 원래 예외(ACT-010)
         새 readOnly TX 에서 기존 receipt 를 읽는다
           uk_receipt_event 면 event_id 로, uk_receipt_order 면 order_id 로
           없음 → 원래 예외(ACT-010)
           같은 사실 → no-op → ack
           다른 사실 → hold TX → ack
  hold TX 의 uk_hold_position 위반 → 이미 보류된 record → ack
  그 밖의 예외 → ack 하지 않음 → error handler(4.4)
```

- 보류한 뒤 ack 전에 죽으면 같은 record 가 다시 와서 hold insert 가 `uk_hold_position` 에 걸린다. 이것은 실패가 아니라 "이미 보류함" 이다. 이 분기가 없으면 위반이 error handler 로 가 컨테이너가 멈추고, 진행해야 할 재전달(`ACT-005`)에서 소비가 묶인다.

- **조회 먼저 하지 않고 insert 부터 한다.** 조회 뒤 insert 는 동시 소비에서 둘 다 "없음" 을 보고 둘 다 들어간다. 유일 제약이 판정의 근거고, 조회는 진 쪽의 사후 분류다(`ACT-007`).
- 실패한 TX 안에서 예외를 삼키고 commit 하지 않는다. JPA 는 제약 위반 뒤 그 영속성 컨텍스트를 믿을 수 없게 둔다. rollback 뒤 **새 TX** 에서 읽는다.
- 중복 판정은 **제약 이름**으로 한다. 예외 종류(`DataIntegrityViolationException`)만 보면 NOT NULL · CHECK 위반도 중복으로 바뀌어 실패가 ack 된다.
- 다시 읽었는데 receipt 가 없으면 중복이 아니다. 예: 고아 활동 행과 `uk_activity_order_product` 가 부딪힌 경우 — 그대로 실패시켜 멈춘다.
- handler 는 listener 와 다른 Bean 이다. 자기 호출로 `@Transactional` 프록시를 우회하지 않는다(`KAFKA-005`).

### 4.3 판정 — 진행할 것과 멈출 것 (Q-1)

| 경우 | 다시 읽으면 | 처리 |
|---|---|---|
| 같은 사실 재전달 | 같다 | no-op, ack |
| 충돌(같은 `eventId` 다른 사실 · 같은 `orderId` 다른 `eventId`) | 같다 | 보류, ack |
| 해석 불가(JSON · key 불일치 · 빈 값 · 0 이하 수량 · 상품 중복) | 같다 | 보류, ack |
| **모르는 `schemaVersion`** | **소비자를 배포하면 성공** | **멈춤** |
| DB 연결 · 잠금 대기 · 무응답 | 성공할 수 있다 | 멈춤 |

- 기준은 "다시 읽으면 결과가 바뀌는가" 다. 바뀌지 않는 것을 멈추면 사람이 offset 을 손으로 넘길 때까지 뒤의 모든 주문이 묶인다(poison pill).
- 모르는 버전을 보류하면, 소비자를 고쳐 배포해도 그 record 는 이미 ack 돼 다시 오지 않는다. 멈추면 배포 뒤 재시작이 그 record 부터 읽는다.
- `eventId` 의 형식은 검사하지 않는다. 형식 검사가 "같은 `orderId` 에 다른 `eventId`" 를 먼저 가로채 충돌이 보이지 않게 된다. 빈 값만 거른다.
- `items` 안 같은 상품은 insert 전에 거른다. 거르지 않으면 `uk_activity_order_product` 위반이 4.2 의 재분류로 가는데, receipt 도 rollback 돼 비교할 기록이 없다.
- 보류는 업무 완료가 아니다. 보류 건수를 지표로 내고 알림을 건다.
- **뒤집을 조건**: 같은 key 의 뒤 이벤트(`OrderCancelled` 등)가 앞 이벤트의 반영을 전제하게 될 때. 보류된 앞 이벤트 위에 뒤 이벤트가 잘못 반영되므로 key 단위로 멈추는 장치를 둔다.

### 4.4 listener 설정 (`KAFKA-002` ~ `KAFKA-008`)

구매 활동 전용 record factory 를 둔다. 기존 batch factory 는 그대로 둔다(demo 용).

| 설정 | 값 | 근거 |
|---|---|---|
| 리스너 형태 | record, concurrency 1 | record 마다 DB 경계와 ack 가 보인다. partition 수만큼 늘리는 것은 처리량 문제가 생긴 뒤 |
| ack mode | `MANUAL_IMMEDIATE` | handler commit 뒤 consumer 스레드에서 바로 commit |
| `enable.auto.commit` | `false` (Kafka 가 읽는 키) | 공유 설정의 `enable-auto-commit` 은 Kafka 가 읽지 않는 키다 |
| value | `ByteArrayDeserializer` | 깨진 바이트를 문자열로 바꾸면(U+FFFD) 원본이 사라져 보류에 남길 수 없다 |
| `auto.offset.reset` | `earliest` | 처음 배정되기 전에 쌓인 확정을 건너뛰지 않는다. 처리 기록이 재처리를 막는다 |
| `allow.auto.create.topics` | `false` | 없는 topic 을 consumer 가 만들지 않는다 |
| fetch | Kafka 기본(`1` byte · 500ms) | batch factory 의 `1MiB` · `5s` 는 그 factory 안에서만 넣는 값이라 물려받지 않는다 |
| error handler | `CommonContainerStoppingErrorHandler` | 실패 record 를 건너뛰지 않고 컨테이너를 멈춘다 |

- 멈춤의 판정은 `isRunning` 이 아니라 **실제 group 의 committed offset** 이다(`KAFKA-006`). 재시도 뒤 recover 하는 handler 는 `commitRecovered` · `ackAfterHandle` 에 따라 실패 record 를 commit 할 수 있어 고르지 않는다.
- 멈춘 consumer 는 health 에 드러나지 않고 lag 로만 보인다. lag 지표에 알림을 건다.
- **뒤집을 조건**: 일시 오류가 잦아 사람이 매번 재시작하는 비용이 커질 때 — 짧은 재시도 뒤 멈추는 handler 로 바꾸되, recover 가 commit 하지 않음을 committed offset 으로 확인한다.

### 4.5 DB 무응답 상한 (`ACT-010`)

listener 가 기다리는 DB 호출에 상한이 없으면 예외가 나지 않아 error handler 가 돌지 않고, `max.poll.interval.ms`(이 factory 는 기본 5 분)를 넘겨 group 에서 빠진 뒤 rebalance 만 난다.

- streamer 의 JDBC URL 에 `socketTimeout=10000`(10 초)을 둔다. 정상 쿼리는 ms 단위이고, 행 잠금 대기(3 초)보다 길어 잠금 대기는 잠금 대기 예외로 먼저 끝난다. `max.poll.interval.ms` 보다 충분히 짧다.
- commerce-api 에는 넣지 않는다. 공유 `jpa.yml` 이 아니라 streamer 의 설정에 둔다.
- 확인할 테스트 T-5.
- **뒤집을 조건**: 정상 배치성 쿼리가 10 초를 넘게 될 때.

## 5. 테스트 fixture (Q-5)

`modules:kafka` 의 testFixtures 에 static Kafka 컨테이너를 두고 모든 통합 테스트가 공유한다.

- **이미지는 compose 와 같은 3.5 계열이다.** broker 버전이 달라지면 assignor · 오류 코드가 달라질 수 있다.
- broker 주소는 `@DynamicPropertySource` 또는 Initializer 로 넣는다. test yml 에 쓰면 import 된 `kafka.yml` 이 이긴다(3.7). producer · consumer · admin 세 클라이언트가 같은 주소를 받는다(`KAFKA-001`).
- broker 자동 생성을 끄고, fixture 가 업무 topic 을 만든 뒤 metadata 를 받아 둔다. 그러지 않으면 첫 확정이 `max.block.ms` 1 초에 걸려 실패로 기록된다.
- **격리** (`EXP-001`): DB 정리가 `TRUNCATE` 라 주문 ID 가 매 테스트 1 부터 다시 나온다. topic 은 남는다.
  - broker 를 읽는 검증은 동작 **전의 end offset** 을 잡아 두고 그 뒤만 읽는다.
  - 이 규칙은 앞 테스트의 발행이 끝났다는 전제 위에 있다. `send()` 는 ack 전에 반환하므로, 앞 테스트의 record 가 다음 테스트의 offset 확보 뒤에 도착하면 같은 key `"1"` · 같은 `eventId` 라 내 것으로 읽힌다. 그래서 fixture 의 정리 단계에서 `KafkaTemplate.flush()` 로 버퍼를 비운다.
  - sweeper 는 `payment.sweeper.enabled=true` 일 때만 돈다. 테스트에서 켜지 않으므로 검증 중에 다른 발행이 끼지 않는다.
  - 처음부터 읽는 consumer(earliest · 새 group)는 실행마다 고유한 topic · group 을 쓴다. streamer 테스트와 실험이 여기에 든다.
- 테스트 더블을 두지 않는다. 발행이 실제로 broker 까지 가므로 발행 경로가 깨지면 423 개 중 어디서든 드러난다.
- **뒤집을 조건**: 공유 컨테이너가 테스트 시간을 크게 늘리거나, 남은 topic 때문에 불안정해질 때.

## 6. 실험 — 실패를 실제로 만드는 수단

| 대상 | 수단 | 왜 이것인가 |
|---|---|---|
| `EXP-002` 배정 | 격리 topic(partition 2) · 같은 group 의 consumer 1 → 2 → 3, `poll` 을 돌며 안정된 assignment 를 유한 대기 | consumer 객체 수가 아니라 broker 가 준 배정을 본다 |
| `EXP-003` key 매핑 | 같은 key 두 번의 send metadata 비교 | 다른 key 가 다른 partition 이라는 assert 는 하지 않는다 |
| `EXP-005` 재전달 | target 앞에 준비용 record, 첫 consumer 는 commit 없이 bounded close, 둘째는 seek 없이 같은 group | committed offset 을 잃고 처음부터 읽으면 준비용 record 도 와서 실험이 실패한다. 활동은 행 ID · 수정 시각까지 비교한다 |
| `EXP-006` listener 실패 | 둘째 품목만 거절하는 `CHECK` 제약을 잠시 건다(`product_id <> 8`) | 실제 DB 오류이고, 첫 품목이 들어간 뒤 실패해 `ACT-003` 의 전체 rollback 을 함께 본다. 멈춤은 컨테이너 정지 이벤트 뒤의 committed offset 으로 판정한다 |
| `EVT-014` 막힘 | 자동 생성을 끈 broker 에 없는 topic 으로 발행 | metadata 를 얻을 수 없어 실제로 막힌다. broker 를 멈추는 것만으로는 metadata 캐시 때문에 막히지 않는다(T-4) |
| `EXP-007` 첫째 | Q-6 (7.2) | |

각 실험은 실제 offset · partition · committed 값을 기록해 PR 에 옮긴다. 단일 broker 로 확인하지 못하는 것(leader 장애 · ISR 축소 · `min.insync.replicas`)은 설명으로 남긴다(`EXP-004`). 실제로 보는 것은 선택 심화(broker 3 대)다.

## 7. 아직 고르지 않은 것

### 7.1 Q-4 — 두 앱을 한 실행에서 잇기

| 지금 고를 수 있는 선택지 | 장점 | 비용 |
|---|---|---|
| ① 별도 sourceSet 에 streamer 출력을 넣어 한 JVM · 한 컨텍스트 | 가장 단순하다 | 두 앱의 Bean · 엔티티 · `com.loopers` 스캔이 한 컨텍스트에 섞여 실제 배포와 다르다 |
| ② streamer `bootJar` 를 자식 JVM 으로 띄운다 | 실제 배포와 같은 격리. 부모 fixture 의 broker · DB 주소를 인자로 넘긴다 | Gradle 에 jar 입력을 등록하고 기동 · 종료(SIGTERM)를 관리한다 |
| ③ 스크립트로 두 프로세스 | 가장 실제에 가깝다 | 테스트 실행 밖이라 결과를 따로 기록해야 한다 |

계약 자체는 2.4 의 공유 예시로 각 앱에서 지킨다. ② 가 유력하지만 Gradle 설정을 시험해 본 뒤 고른다.

### 7.2 Q-6 — `EXP-007` 첫째 주문의 누락 만들기

원문은 "commit 후 send 직전 hook 에서 멈춘다" 고 한다.

| 지금 고를 수 있는 선택지 | 끝 상태 | 비용 |
|---|---|---|
| ① 테스트가 발행 hook 을 갈아 끼움 | record 0 · 흔적 없음 | 테스트 더블이다 |
| ② main 코드에 발행 차단 스위치 | record 0 · 흔적 없음 | 시험용 장치가 운영 코드에 들어간다 |
| ③ topic 의 `max.message.bytes=1` 로 broker 가 실제로 거절 | record 0 · **실패 지표 1** | 원문의 수단과 다르고 "흔적 있는 누락" 이다. 값을 되돌리면 둘째 주문은 정상 |

③ 은 더블 없이 같은 끝 상태(주문 `CONFIRMED` · 예약 소비 · record 0 · 활동 0)를 만든다. 흔적 없는 누락이 W7 에서 꼭 필요한지가 기준이다.

## 8. 알려진 한계 · 지금 고르지 않은 선택지

| 선택지 | 지금 두지 않는 이유 | 뒤집을 조건 |
|---|---|---|
| Outbox · relay | W7 범위 | W7 |
| 발행 재시도 | 메모리 재시도는 내구성이 없다 | W7 relay |
| DLT | 이 설계는 해석 불가를 보류 테이블에 둔다. DLT 는 다른 topic 이라 재처리 경로가 하나 더 생긴다 | 보류 건을 다른 소비자가 처리해야 할 때 |
| Kafka 트랜잭션 | DB 와 broker 를 묶지 못한다(소비 쪽 DB 효과는 Kafka TX 밖) | — |
| concurrency 3 | 처리량 문제가 아직 없다 | lag 가 꾸준히 쌓일 때 |
| static membership · cooperative assignor | 재시작 중 재배정 비용이 아직 문제 아니다 | 배포 중 rebalance 가 측정될 때 |

## 9. 코드 배치

```
commerce-api
  domain/order/OrderConfirmed            값 · 품목 합산 · 정렬 (EVT-007)
  application/payment/PaymentTransactions  CONFIRM_ORDER 분기에서 publishEvent (EVT-010 · EVT-012)
  application/order/OrderEventMetrics    발행 성공 · 실패 지표
  infrastructure/order/OrderConfirmedKafkaPublisher  @TransactionalEventListener(AFTER_COMMIT) · send · whenComplete
  infrastructure/order/OrderEventsTopicWarmup        기동 시 metadata 확보

commerce-streamer
  domain/activity/…                      판정(같은 사실 · 충돌 · 해석 불가)
  application/activity/PurchaseActivityHandler  @Transactional handler
  infrastructure/activity/…              JPA 엔티티 · 저장소 (package-private)
  interfaces/consumer/OrderConfirmedListener    record listener · ack

modules:kafka
  KafkaConfig                            purchase-activity record factory
  testFixtures                           공유 Kafka 컨테이너 · 계약 예시 JSON
```

- application 은 infrastructure 를 모른다(ArchUnit). 그래서 이벤트 값은 domain 에, Kafka 를 아는 listener 는 infrastructure 에 둔다. infrastructure 가 domain 의 값을 받는다.
- 판정은 DB 없이 테스트한다. 저장 · 동시성 · 재전달은 실제 DB · broker 통합 테스트로 본다.
- streamer 에도 commerce-api 와 같은 Checkstyle · ArchUnit 을 붙인다.

## 10. 확인할 테스트 — 메커니즘에서 추론했고 아직 우리 테스트로 보지 않은 것

| # | 주장 | 확인 방법 |
|---|---|---|
| T-1 | 결과 반영 TX 가 가장 바깥 TX 다 | 확정 경로 네 개에서 listener 가 `observe` 반환 전에 실행되는지 |
| T-2 | TX 없이 `publishEvent` 하면 발행이 없다 | TX 밖에서 이벤트를 내고 broker record 0 |
| T-3 | producer 는 첫 send 에서 만들어지므로, `ConfigException` 이 기동에서 나는 것은 metadata 확보 덕이다 | `acks: 1` 을 넣고 기동 실패. 확보 단계를 빼면 기동이 성공하는지도 본다 |
| T-4 | metadata 가 캐시된 producer 는 broker 를 멈춰도 `send()` 가 막히지 않는다 | 발행 한 번 뒤 broker 정지 → 다음 `send()` 반환 시간 |
| T-5 | JDBC 상한이 없으면 DB 무응답에서 listener 가 예외 없이 멈춘다 | 상한 없이 · 있이 DB 를 멈추고 error handler 호출 여부와 rebalance 를 본다 |
| T-6 | AFTER_COMMIT listener 동안 커넥션이 반납되지 않는다 | `send()` 를 막아 둔 채 Hikari active 수 |
| T-7 | 정상 종료 때 close 대기 안의 record 는 전송되거나 실패 콜백이 온다 | 버퍼에 record 를 둔 채 컨텍스트 종료 |

## 11. 작업 단계

각 단계는 red → green → 커밋이다.

1. `kafka.yml` · compose 정리 — consumer 키 · `acks` · 멱등 · `max.block.ms` · 직렬화기, broker 자동 생성 끄기 · init topic. 유효 설정 테스트(`KAFKA-002` · `KAFKA-003` · `EVT-016`).
2. 공유 Kafka fixture(Q-5)와 계약 예시 JSON.
3. `Order.confirmedAt` 마이크로초 자르기(`EVT-006`).
4. `OrderConfirmed` 값과 품목 합산(`EVT-003` · `EVT-007` · `EVT-008`) — DB 없는 테스트.
5. 결과 반영 경계에서 발행 · AFTER_COMMIT publisher · 결과 관찰(`EVT-010` ~ `EVT-015`), T-1 · T-2 · T-6.
6. 기동 시 metadata 확보, T-3 · T-4.
7. streamer 저장 모델과 판정(`ACT-002` ~ `ACT-009`) — 판정은 DB 없는 테스트.
8. handler TX · 유일 제약 재분류(`ACT-003` · `ACT-007` · `ACT-008`), 동시 소비 2 건.
9. record listener · error handler · ack(`KAFKA-004` ~ `KAFKA-008`), `socketTimeout` · T-5.
10. 실험 `EXP-002` · `EXP-003` · `EXP-005` · `EXP-006`.
11. Q-4 · Q-6 결정 뒤 `EXP-007` · `EXP-008`.
