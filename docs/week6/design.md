# 6주차 설계 — 주문 확정 이벤트와 구매 활동

이 문서는 **주문 확정 이벤트와 상품 구매 활동의 계약·책임·정책과 선택 이유**를 적는다. 구현 전인 결정은 적용·검증 상태를 표시한다. 주문 기능의 구현·검증 과정은 [구현 기록](implementation-log.md)에 남긴다.

기준 코드는 원본 저장소의 `hyeon-aa`에 5주차 PR #76이 병합된 `086f761`까지 반영했다. 5주차의 후속 PG 조회 연결 부족 분류·계측 개선을 포함하며, 주문 최초 확정과 `AFTER_COMMIT` 발행·구매 활동의 DB 원자성·멱등 처리·BATCH 경계는 이 문서의 설계를 따른다. 통합 범위와 재검증 결과는 [구현 기록 39절](implementation-log.md#volume5-base-integration)에 남긴다.

## 현재 상태

| 구분 | 상태 |
| --- | --- |
| 메시지 key·eventId | key는 내부 orderId 문자열, eventId는 `order-confirmed:v1:{orderId}`로 적용 |
| 최초 확정 시각 | PG 최초 성공 전이에서 `orders.confirmed_at`에 저장 |
| 로컬 Kafka 기반 | 브로커 3개·KRaft·UI. 자동 topic 생성 끔·offset topic 복제 3, init이 `order-events`(3/3/2)·`order-events-dlt`(1/3/2) 생성. local profile에서만 발행·소비 켬. [아래 절](#local-topics-and-activation) |
| 발행 순서·연결 | Spring 내부 이벤트 준비와 `AFTER_COMMIT`의 발행 인터페이스 호출 구현 |
| Kafka 전송 | Kafka 구현체·성공 metadata·동기/비동기 오류 관찰 구현. 격리 broker 검증, 로컬 발행은 기본 비활성 |
| 구매 활동 | 완료 이벤트 기록·모든 품목의 한 DB TX, 동일 사실 no-op·충돌 대조·동시 경쟁 복구 구현. 실제 주문용 BATCH listener에 연결(DB commit 뒤 정상 반환) |
| 수신 기본 준비 | parser·어댑터·주문용 record factory·@KafkaListener 연결 구현. 기본값·test profile은 자동 구독 0개, local profile에서만 주문 listener를 켬. 실제 Kafka/MySQL의 정상·논리적 중복 처리 검증 |
| 주문용 ack 방식 | BATCH·earliest·purchase-activity를 주문용 factory/listener에 연결. DB commit/no-op 뒤 정상 반환하고 container가 위치 기록. 로컬은 streamer 1대·concurrency 3. [ADR 0054](../adr/0054-commit-order-consumer-offsets-per-poll.md) |
| 동일 이벤트의 사실 충돌 | 기존 사실 유지·DLQ 분리 후 정상 주문 계속 처리. 업무용 error handler에 연결·실제 listener로 검증 |
| DLQ 전송 실패 | 원본 offset 보호·listener 전체 중단·사람이 원인 해결 후 같은 group 재시작. 업무용 error handler에 연결·실제 listener로 검증 |
| 주문용 error handler | 메시지 오류·충돌 → `order-events-dlt`, 그 밖의 오류 → 1초부터 2배·한 간격 최대 30초·합 5분 재시도 후 listener 전체 중단. [ADR 0049](../adr/0049-connect-order-consumer-error-handler.md)·[ADR 0053](../adr/0053-retry-transient-consumer-failures-for-minutes-before-stopping.md), [아래 절](#consumer-error-handler) |
| Kafka 설정 비교·재전달 검증 | (당시 선택) 2회·1초/2초·소진 후 중단, DLQ 발행 성공/실패, partition 3개·purchase-activity 멤버 3개의 DB 저장, RF3·최소 ISR2·acks=all 장애·복구와 내부 offset RF3을 격리 검증. 이후 업무용 설정·listener 연결은 적용했다 |

PG 주문 확정→Kafka 전송과 streamer의 DB 원자성·멱등 처리를 격리 검증했다. 이후 실제 주문용 @KafkaListener·record factory→어댑터→DB→ack의 정상·논리적 중복 경로도 확인했다. 별도 설정 비교 실험의 오류·중단·재전달 범위와 구분한다. 이어서 업무용 error handler를 연결하고 실제 listener로 DLQ·DB 재시도 후 중단·DLQ 실패 후 중단과 재시작을 확인했다. 로컬 topic 생성과 local profile 활성화도 반영했고, 두 앱을 잇는 미발행/정상 사례를 [재현](#w6-delivery-cases)했다.

## 업무 흐름

```text
검증한 PG SUCCESS
  → 기존 TX C에서 주문 최초 CONFIRMED·예약 CONSUMED commit
  → 주문 확정 사실을 Kafka로 전달
  → commerce-streamer가 상품별 구매 활동 저장
```

메시지 하나는 **주문 하나가 확정됐다는 사실**을 나타내며 해당 주문의 여러 상품·수량을 함께 담는다. 구매 활동은 상품별 행으로 저장하며 처리 완료 기록과 한 주문의 모든 품목 저장을 하나의 DB 트랜잭션으로 묶는다. 결제나 재고 차감을 다시 수행하지 않는다. 주문용 소비·DB·ack·오류 처리기 연결은 기본 비활성으로 구현했고, local profile에서만 켠다.

메시지는 orderId뿐 아니라 구매 활동에 필요한 확정 당시의 시각·품목·수량도 담는다. ID만 알리고 consumer가 주문 API로 상세를 조회하는 대안과 달리, 수신한 메시지로 업무를 처리할 수 있어 조회 API의 가용성에 덜 의존한다. 주문 전체 Entity나 불필요한 정보를 그대로 내보내는 뜻은 아니다. 주문 서비스는 확정 사실을 발행하고 후속 소비처는 각자 그 사실에 반응한다. Kafka 전송 결과와 각 소비처의 업무 완료는 별도로 관찰한다.

<a id="message-key"></a>

## 메시지 key: 주문 ID

**결정:** 주문 확정 메시지의 key는 내부 orderId의 문자열 표현으로 사용한다. 예를 들어 주문 42의 key는 `"42"`다. 실제 Kafka record에서 key·payload·재전송 시 partition을 확인했다. 수신 parser·DB·ack와 조건부 주문용 listener/factory를 연결했으며 업무용 자동 구독은 기본 비활성이다.

이벤트의 단위가 주문 하나이고 한 메시지에 여러 상품이 포함되므로, 주문 ID로 그 업무 단위를 식별한다. 같은 주문의 메시지를 같은 partition에 모으는 기준으로 삼는다.

```text
key: "42"
value: 주문 42 확정, 상품 A 2개·상품 B 1개
       → 메시지 전체를 한 partition에 기록
```

상품 ID를 key로 삼으려면 여러 품목 중 무엇을 key로 할지 정해야 한다. 상품별로 메시지를 나누면 전달하는 사실의 단위도 상품별로 바뀐다. 이번에는 주문 전체의 확정 사실을 한 메시지로 유지한다.

### 보장 범위와 확인할 점

- 같은 partitioner·key 직렬화·partition 수에서 같은 key가 같은 partition으로 연결되는 것을 전제로 한다. 설정이나 partition 수를 바꿀 때 매핑도 다시 확인한다.
- 하나의 partition에는 여러 주문의 메시지가 함께 들어갈 수 있다. 주문 하나마다 전용 partition을 만드는 결정이 아니다.
- 순서는 partition 안에서 다룬다. 실제 handler의 처리 완료 순서는 소비 코드의 실행 방식도 함께 확인해야 한다.
- key의 선택은 함께 모아 처리할 업무 단위와 필요한 순서를 기준으로 한다. 같은 주문을 다시 보내면 같은 partition으로 가는 것은 실제 전송 metadata로 확인했다([구현 기록](implementation-log.md#kafka-producer)). 서로 다른 key가 다른 partition으로 간다고는 보장하지 않는다.

<a id="event-identity"></a>

## eventId: 주문 확정 사실의 식별자

**결정:** `order-confirmed:v1:{orderId}`로 고정 생성한다. 주문 42의 key는 `"42"`, eventId는 `"order-confirmed:v1:42"`다. key는 partition을 고르는 기준이고 eventId는 재전달된 확정 사실을 식별하는 기준이다.

이 형식은 Kafka가 강제하는 규칙이 아니다. 이번처럼 주문당 확정이 한 번인 계약에서는 orderId만으로도 만들 수 있지만, 사건 종류를 포함해 이후 다른 사건과 구별하도록 사용자가 선택했다. `OrderConfirmed.eventId()`가 같은 주문에서 항상 같은 값을 만들므로 별도 ID 저장이나 매번 새 UUID 생성은 필요하지 않다. 이유·대안은 [ADR 0036](../adr/0036-derive-order-confirmation-event-id-from-order-id.md)에 둔다.

최종 JSON은 `OrderConfirmedMessage`가 `eventType=OrderConfirmed`, `schemaVersion=1`, eventId, orderId, confirmedAt, items를 담는다. confirmedAt은 저장된 Instant의 UTC ISO-8601 문자열이며 주문의 저장 품목·수량을 사용한다. 현재 상품·가격을 다시 조회하거나 카드·PG 비밀·사용자 개인정보를 넣지 않는다.

<a id="publish-after-commit"></a>

## 발행 순서: 주문 DB commit 성공 후 Kafka 전송

**결정:** 검증된 PG SUCCESS를 처음 주문 CONFIRMED·예약 CONSUMED로 반영한 DB 트랜잭션이 commit된 뒤, `AFTER_COMMIT` listener에서 주문 확정 메시지 전송을 연결한다. 현재 내부 이벤트 준비·발행 인터페이스·Kafka 구현체를 연결했다. [ADR 0035](../adr/0035-publish-order-confirmation-after-db-commit.md)에 결정과 한계를 정리한다.

### 기존 코드의 연결 지점

기존 `OrderFacade`는 PG 생성 응답·callback·reconcile·복구에서 저장된 결제 결과를 `OrderPaymentTransactions.applyRecordedResult()`(TX C)로 전달한다. TX C는 주문과 결제 시도를 잠가 읽고, SUCCESS이면 `OrderModel.completePayment(confirmedAt)`로 주문·예약을 확정한다. 이미 완료된 경우에는 변경 없이 반환한다. 이 기존 경계를 유지한다([ADR 0032](../adr/0032-persist-payment-result-before-order-application.md)).

`completePayment(confirmedAt)` 호출 직후와 `@Transactional` 메서드 본문 끝은 아직 commit 성공이 확인된 시점이 아니다. 이 위치에서 Kafka로 직접 전송하지 않는다. 프로젝트의 `PricePageInvalidator`와 같은 `AFTER_COMMIT` 패턴으로 연결한다.

### AFTER_COMMIT의 책임 배치

| 위치 | 책임 |
| --- | --- |
| `OrderModel` — domain | 확정 가능한 상태를 검증하고 주문·예약·최초 확정 시각을 변경한다. |
| `OrderPaymentTransactions` — application | 최초 성공 확정에서 메시지 데이터를 준비하고 Spring 내부 이벤트로 전달한다. |
| `OrderConfirmationListener` — application | `AFTER_COMMIT`에 실행돼 발행 인터페이스를 호출한다. |
| `OrderConfirmationPublisher` — domain | 주문 확정 사실의 전달 계약을 정의한다. Kafka 구현을 알지 않는다. |
| `KafkaOrderConfirmedPublisher` — infrastructure | 발행 인터페이스를 구현해 실제 전송과 결과 관찰을 담당한다. |

Spring 내부 이벤트 전달과 Kafka 전송은 서로 다른 단계다. 주문 모델에 Spring 이벤트 발행이나 Kafka 호출을 넣지 않는다. application은 infrastructure 구현체 대신 domain의 발행 인터페이스에 의존한다.

직접 호출 방식도 같은 책임 분리를 지킬 수 있다. 이번에는 여러 주문 처리 입구가 모이는 최초 확정 지점에서 후속 전송을 연결하고, 기존 프로젝트의 커밋 후 처리 패턴과 일치시키기 위해 `AFTER_COMMIT`을 선택했다. 이 선택만으로 메시지 누락 방지나 별도 스레드 실행을 보장하지는 않는다.

<a id="prepare-message"></a>

### 메시지 데이터 준비와 실제 전송의 흐름

아래는 구현할 흐름이다. **준비는 주문에서 필요한 값을 복사해 메시지 객체를 만드는 단계이고, 전송은 그 내용을 실제 Kafka에 보내는 단계다.**

```text
TX C 시작 — DB 트랜잭션 안
  │
  ├─ 저장된 PG SUCCESS와 아직 미확정인 주문 상태 확인
  │
  ├─ ① 주문 확정
  │     completePayment(confirmedAt): CONFIRMED·CONSUMED·확정 시각 저장
  │
  └─ ② 보낼 데이터 준비 — 아직 Kafka로 보내지 않음
        주문 ID·최초 확정 시각·상품별 수량 등 필요한 값을
        주문에서 복사해 메시지 객체에 담기
        → Spring 내부 이벤트로 전달
        │
        ▼
DB commit 결과
  ├─ 실패·rollback → 준비한 메시지도 Kafka로 보내지 않음
  │
  └─ 성공
       │
       ▼
     ③ 실제 전송 — DB commit 성공 후
       Spring이 AFTER_COMMIT listener 실행
         → 발행 인터페이스 호출
         → Kafka 전송 구현체가 준비한 내용을 보내기
```

예를 들어 주문 42가 상품 7을 2개, 상품 8을 1개 구매했다면 준비하는 주문 데이터는 다음과 같다. 전체 메시지 계약 중 주문 데이터 부분을 설명하는 예시이며, 실제 발행 결과는 아니다.

```text
orderId: 42
confirmedAt: 주문 42의 최초 확정 시각
items:
  - productId: 7, quantity: 2
  - productId: 8, quantity: 1
```

주문 Entity 자체를 넘겨 커밋 후에 품목을 다시 읽는 대신, 트랜잭션 안에서 필요한 값을 복사해 둔다. 그러면 커밋 후 lazy loading이나 현재 상품 재조회에 의존하지 않고, **그 주문이 확정된 순간의 사실**을 전달할 수 있다.

### 현재 구현된 준비·호출 경계

- `OrderModel.completePayment(Instant)`가 PG 성공 전이에서 최초 확정 시각을 주문·예약 상태와 함께 저장한다. 아직 확정되지 않은 주문과 기존 POINT 확정은 이 필드가 null이며 기존 POINT 주문의 시각을 소급 생성하지 않는다.
- application이 주입받은 `Clock`으로 시각을 얻고 MySQL `datetime(6)`과 일치하도록 마이크로초 단위로 절삭한다. 같은 값을 주문에 저장하고 이벤트에 복사한다. `updatedAt`을 최초 확정 시각 대신 사용하지 않는다.
- domain의 `OrderConfirmed(orderId, confirmedAt, items)`는 Spring 내부 이벤트로 전달할 불변 스냅샷이다. `Item(productId, quantity)`를 상품 ID순으로 정리하고 같은 상품의 수량을 합산한다. 빈 품목·잘못된 ID·0 이하 수량·합산 overflow는 허용하지 않는다.
- 최초 SUCCESS 적용 때만 내부 이벤트를 준비한다. 이미 완료된 주문·결제 대기·UNKNOWN·FAILED는 새 이벤트를 준비하지 않는다. 주문 행 잠금으로 동시 결과 적용도 직렬화한다. 준비 후 TX C가 rollback되면 시각·주문 전이가 함께 취소되고 listener도 실행되지 않는다. TX B의 결제 SUCCESS는 기존 규칙대로 보존된다.
- `OrderConfirmationListener`와 Kafka 구현체는 `commerce.order-events.enabled=true`일 때 등록된다. 업무용 토픽 구성을 사용자가 결정하기 전이라 **기본 설정은 false**다. 포트 대역 테스트와 실제 Kafka 통합 테스트에서 명시적으로 활성화했다. 실제 구현체도 연결돼 있다.
- listener에서 발행 인터페이스의 동기 예외를 eventId·주문 ID와 함께 오류 로그로 남긴다. 이미 commit된 주문 상태를 되돌리거나 결제를 재생성하지 않는다.

### 실제 Kafka 전송과 결과 관찰

API에 `modules:kafka` 의존과 kafka.yml import를 추가했다. Kafka 구현체는 준비한 스냅샷을 JSON 경계 모델로 변환하고, 기존 KafkaTemplate으로 `topic / orderId 문자열 key / JSON value`를 전송한다. 기본 topic 이름은 과제 계약의 `order-events`이며 테스트에서는 고유 suffix를 사용한다. topic 자동 생성 코드는 추가하지 않았다.

send가 반환한 future에 완료 callback을 붙인다. 성공 완료의 broker metadata에서 eventId·orderId·topic·partition·offset을 기록하며, 비동기 오류는 별도 오류 로그로 기록한다. send 호출 반환만으로 성공 로그를 남기지 않는다. `@Async`나 future `get/join`은 추가하지 않았다. 요청 스레드는 send 호출을 수행하므로 metadata 확보 등 기존 producer의 내부 대기는 여전히 발생할 수 있고, API 응답은 broker 저장 완료 보장을 뜻하지 않는다. [Spring Kafka 전송 문서](https://docs.spring.io/spring-kafka/reference/3.3/kafka/sending-messages.html)를 따른다.

producer는 기존 kafka.yml과 클라이언트 기본값을 사용한다. 이번 검증에서 관찰한 값은 retries=3(기존 설정), acks=all·enable.idempotence=true·max.block.ms=60000·delivery.timeout.ms=120000(클라이언트 값)이다. 사용자가 튜닝 실험으로 채택한 값으로 간주하지 않는다. 이 발행 검증 당시에는 consumer ack·error handler와 업무용 partition·복제 수를 정하지 않았다. 이후 선택과 적용 상태는 현재 상태·소비 설정 절을 따른다.

<a id="producer-settings"></a>

현재 producer 설정은 [ADR 0051](../adr/0051-limit-producer-metadata-wait-and-keep-default-retries.md)을 따른다. `max.block.ms=5000`으로 브로커 전체 장애 때 요청 스레드가 막히는 시간을 5초로 제한하고, `retries`는 지워 클라이언트 기본에 두며 실패 시점은 `delivery.timeout.ms`(120초)가 정한다. acks=all·idempotence는 클라이언트 기본 그대로다. 공통 모듈 값이라 streamer의 DLQ producer에도 적용된다.

AFTER_COMMIT listener는 트랜잭션 정리(커넥션 반납) 전에 실행된다. 그래서 브로커가 모두 꺼진 동안에는 주문 확정 요청마다 요청 스레드와 함께 **DB 커넥션도 최대 5초** 붙잡는다. 이 시간에 확정 요청이 몰리면 커넥션 풀(최대 40)이 먼저 바닥날 수 있다. W7 Outbox로 send가 요청 경로에서 빠지면 사라지는 한계로 남긴다.

측정([구현 기록 32절](implementation-log.md#max-block-measurement)): 브로커가 꺼져도 이미 metadata를 기억하는 producer는 요청을 50ms에 돌려보냈다. metadata가 없는 새 producer는 5,048ms를 막았고, 그동안 사용 중 커넥션이 1개였다. 그래서 5초 묶임은 앱이 막 뜬 직후처럼 metadata가 없을 때의 상한이다. 동시 요청이 몰릴 때의 풀 고갈 시점은 재지 않았다.

`OrderConfirmedPublicationTest`는 실제 MySQL·PG fixture와 Kafka 3.5.1을 사용한다. 고유 topic의 partition 2·복제 수 1은 격리 검증용 조건이며 업무용 설정 결정이 아니다. record 점검에는 수동 partition 배정과 String 역직렬화를 쓰는 테스트 reader를 사용했다. 구매 활동 consumer나 consumer offset 재전달 검증으로 표현하지 않는다. 시험 종료 시 격리 broker·DB 자원을 정리한다.

### 선택 이유와 남는 실패 구간

| 순서 | 중간 실패 시 결과 |
| --- | --- |
| Kafka 전송 성공 → 주문 DB commit 실패 | DB에 확정되지 않은 주문의 확정 메시지가 전달될 수 있다. |
| 주문 DB commit 성공 → Kafka 전송 실패 | 주문은 확정돼 있지만 메시지·구매 활동이 누락될 수 있다. |

이번 메시지는 확정된 주문 사실을 전달하므로 DB commit 성공을 먼저 확인한다. DB 트랜잭션만으로 DB와 Kafka의 성공·실패를 하나로 묶지는 못한다.

후속 구현은 다음 과제 계약을 지켜야 한다.

- TX C rollback에서는 전송하지 않는다. 결제 대기·UNKNOWN·FAILED와 이미 완료된 주문의 반복 적용도 새 확정 이벤트를 만들지 않는다. 기존 POINT 확정 주문을 소급 발행하지 않는다.
- commit 이후 전송이 실패해도 저장된 주문 확정을 되돌리거나 PG 생성을 재요청하지 않는다. 전송 실패를 주문 상태와 구분해 관찰한다.
- commit과 전송 사이의 중단·실패로 남는 누락을 이번 주에 재현한다. Outbox 구현은 다음 주 범위다. 현재 주문 복구 배치가 이벤트 누락도 복구한다고 간주하지 않는다.

내부 이벤트·발행 포트 대역 검사와 실제 broker record 검사를 [구현 기록](implementation-log.md#kafka-producer)에 구분해 남긴다. commit 후 send 직전에 테스트 전용 hook으로 특정 주문의 전송을 건너뛰는 누락 재현은 실제 MySQL·Kafka에서 통과했다. 주문 A의 CONFIRMED·CONSUMED는 남고, topic에는 정상 전송한 주문 B만 존재한다. 이는 OS 강제 종료가 아닌 통제된 중단이다([실행 기록](implementation-log.md#publication-gap-verification)). 이후 두 앱을 이은 사례에서 주문 A의 구매 활동이 없고 B만 저장되는 것도 확인했다([두 앱 사례](#w6-delivery-cases)). broker 중단에 의한 비동기 전송 실패(2단계 실패)는 실행하지 않았다.

<a id="purchase-activity-storage"></a>

## 구매 활동과 완료 이벤트 기록: 한 DB 트랜잭션과 동일 사실 no-op

**결정:** 상품별 활동의 `(order_id, product_id)` 유일성과 주문 단위 원자적 저장([ADR 0038](../adr/0038-store-order-purchase-activities-in-one-db-transaction.md))을 유지하면서 완료 이벤트 기록·동일 사실 no-op·경쟁 복구를 추가했다([ADR 0039](../adr/0039-record-order-confirmation-and-purchases-atomically.md)). 완료 테이블의 eventId와 orderId는 각각 유일하다.

```text
메시지 1건: eventId order-confirmed:v1:42, orderId 42, items [상품 7×5, 상품 8×3]
  → OrderConfirmedKafkaListener가 record를 읽음 (활성화 조건 충족 시)
  → OrderConfirmedRecordAdapter → parser → application 입력
  → OrderConfirmationHandler.handle(OrderPurchase)
  → PurchaseActivityRecorder.record — 새 DB TX 시작
      기존 완료 기록 발견 → 동일 사실이면 no-op / 불일치하면 충돌
      최초 처리라면:
      processed_order_confirmation: eventId·orderId·고정 사실 snapshot
      product_purchase_activity: (42, 7, 5, confirmedAt)
      product_purchase_activity: (42, 8, 3, confirmedAt)
      전부 성공 → 함께 commit / 하나라도 실패 → 완료 기록·활동 전체 rollback
  → 완료 기록의 유일 제약 경쟁이면:
      쓰기 TX rollback 완료 → 새 읽기 TX에서 승자의 사실 확인
      같은 사실 → no-op / 다른 사실 → 충돌 / 기록 없음 → 경쟁 예외 전파
  → DB commit/확인된 no-op 뒤 listener 정상 반환
      BATCH container가 poll 처리 경계에서 다음 읽을 위치 기록
      파싱·DB 오류 → 예외 전파 → 전용 오류 처리기
      기본 비활성, local profile에서 주문 listener 활성화
```

| 위치 | 책임 |
| --- | --- |
| domain `OrderPurchase` | eventId·주문 ID·시각·품목의 불변 입력. 유효성 검증, 상품별 수량 합산·정렬, 비교용 고정 사실 표현 |
| domain `ProductPurchaseActivity` | 한 주문의 특정 상품 구매 기록. orderId·productId·quantity·confirmedAt 보존 |
| domain `ProcessedOrderConfirmation` | eventId·orderId·고정 사실 snapshot을 보존하고 동일 사실을 대조. 불일치는 명시적 충돌 |
| application `OrderConfirmationHandler` | 쓰기 TX 완료·rollback 이후 경쟁 결과 확인을 조정. 확인된 동일 사실만 정상 처리 |
| application `PurchaseActivityRecorder` | 완료 기록과 모든 품목을 새 DB TX로 저장. 경쟁 실패 뒤 확인도 별도의 새 읽기 TX에서 수행 |
| infrastructure JPA 구현 | 실제 저장·식별자 유일성 보호. 완료 테이블의 알려진 두 중복 제약만 경쟁 예외로 변환 |

확정 시각은 소비 시각으로 다시 만들지 않고 입력된 최초 confirmedAt을 저장한다. 현재 상품·주문을 조회하거나 FK로 존재를 요구하지 않는다. 주문 API의 확정 트랜잭션과 이 저장 트랜잭션은 각각 별도로 실행된다.

같은 eventId·orderId·confirmedAt·상품별 수량이 모두 일치하면 추가 DB 변경 없이 정상 종료한다. 같은 eventId의 다른 주문·시각·수량·품목, 같은 orderId의 다른 eventId는 충돌이다. 비교 snapshot은 `orderId|Instant|productId:quantity,...`로 만들고 longtext에 저장한다. ID·수량은 양수이며 상품을 합산·정렬하므로 배열 순서나 중복 품목 표현 차이는 새 사실로 보지 않는다. eventId는 비어 있을 수 없고 저장 컬럼 범위인 255자 이내다. producer의 고정 ID 생성 방식과 wire 계약은 변경하지 않는다.

단순 선조회만으로 경쟁을 막지 않는다. 완료 기록 insert의 MySQL 오류 1062와 알려진 eventId/orderId 제약 이름을 함께 확인한 경우만 `DuplicateOrderConfirmationException`으로 전달한다. 활동 테이블의 제약 오류·일반 DB 오류·런타임 오류는 중복으로 숨기지 않는다. handler는 실패한 쓰기 TX 밖에서 `verifyRecorded()`의 새 읽기 TX로 승자의 사실을 확인한다. 확인할 기록이 없으면 원래 경쟁 예외를 전파한다.

**완료 기록은 MySQL의 업무 반영 기록이며 Kafka committed offset이 아니다.** 업무용 listener는 handle(record)로 DB handler를 동기 호출하고, 정상 반환한 뒤 BATCH container가 위치를 관리한다. 두 인자 수동 ack 메서드는 과거 비교 실험을 위해 남기며 업무용 listener는 사용하지 않는다. 기본 자동 구독은 0개이고 local profile에서 켠다. 실제 listener 검증과 별도 비교용 container·합성 ConsumerRecord 호출은 구분한다.

실제 MySQL에서 정상 저장·정규화된 재전달 no-op·다른 사실 충돌·중간 실패 전체 rollback과 재시도·동시 경쟁 뒤 새 TX 확인을 검증한다. 테스트는 DB 구성만 로드하며 Kafka listener를 등록하지 않는다. 이전 기초 검증은 [8절](implementation-log.md#purchase-activity-storage), 이번 실행 결과는 [후속 구현 기록](implementation-log.md#idempotent-purchase-handling)에 둔다.

<a id="consumer-preparation"></a>

## 수신 계약과 BATCH 완료 위치 연결

수신 계약·DB·조건부 주문용 factory/listener와 전용 error handler를 연결했다. 기본값·test profile의 자동 구독은 0개([ADR 0040](../adr/0040-prepare-order-event-receiving-without-auto-subscription.md))이며 local profile에서 streamer 1대·concurrency 3으로 켠다. 현재 BATCH 결정은 [ADR 0054](../adr/0054-commit-order-consumer-offsets-per-poll.md)를 따른다.

`OrderConfirmedMessageParser`가 key와 JSON bytes를 읽어 application의 `OrderConfirmationCommand`를 만든다. 수신 계층이 domain을 직접 참조하지 않으며, command가 기존 `OrderPurchase`로 유효성·정규화를 재사용한다. record 어댑터는 검증된 command를 `OrderConfirmationHandler`에 전달한다.

| 어댑터 진입점 | 책임 |
| --- | --- |
| handle(record) | 업무용 BATCH 경로. 파싱·동기 DB 처리 후 정상 반환하거나 예외 전파 |
| handle(record, acknowledgment) | 과거 수동 ack 비교 실험의 호환 경로. 인자 확인 후 기존 처리·ack 호출. 업무용 listener는 사용하지 않음 |

업무용 경로는 Acknowledgment 인자가 없다. 파싱·DB 실패는 예외로 전파하고, 성공·확인된 동일 사실 no-op 뒤에만 정상 반환한다. 위치는 BATCH container가 관리한다. 두 인자 비교용 경로의 null ack 검사·처리 후 ack·ack 실패 전파 계약은 보존한다. 어댑터에 별도 @Transactional·재시도·DLQ 분기를 넣지 않았다.

- eventType=OrderConfirmed·정수 schemaVersion=1·필수 문자열과 양의 정수 범위를 확인한다. key는 orderId 문자열과 정확히 같아야 한다.
- 최초 확정 시각은 Instant로 읽고 원본 주문의 datetime(6)에 맞는 마이크로초 정밀도를 확인한다. 소수 수량을 정수로 줄이거나 시각 정밀도를 조용히 버리지 않는다.
- 잘못된 JSON·연속된 JSON 문서·빈 본문·잘못된 시각·빈 품목·합산 overflow는 실패한다. 알려진 필드를 읽고 추가 JSON 필드는 업무 사실에 사용하지 않는다. 다른 이벤트 종류·버전 수용은 후속 계약 변경 때 결정한다.
- 주문용 factory와 listener는 commerce.order-events.consumer.enabled=true일 때만 등록된다. 기본 false이며 기존 demo도 demo-kafka.enabled=false다. 공유 batch factory는 유지한다.
- YAML import는 classpath로 명시했다. 기본값·test profile은 주문 자동 구독 0개, local profile은 주문 parent listener 1개·concurrency 3이다. 기존 demo listener는 비활성이다.

group.id=purchase-activity·BATCH·earliest를 주문용 factory/listener에 연결했다. 전용 error handler는 [오류 처리기 절](#consumer-error-handler)에서 연결했다. DB 재시도(현재 [ADR 0053](../adr/0053-retry-transient-consumer-failures-for-minutes-before-stopping.md): 1초부터 2배·한 간격 최대 30초·합 5분)와 topic partition 3개·group 전체 consumer 3개 선택은 유지한다. 이번 ack 변경에서 공통 kafka.yml·broker·producer·재시도 시간은 변경하지 않았다. local profile 활성화는 적용돼 있다.

설정 선택은 한 번에 한 항목씩 실제 비교→실험 문서에 결과·선택 대기 기록→사용자 선택→ADR에 결정·근거 기록→업무 설정 반영 순서로 진행한다. 이미 고른 설정과 새로 선택할 항목은 구분하고 실험 조건을 업무용 결정으로 취급하지 않는다.

<a id="consumer-baseline"></a>

### 사용자 선택을 반영한 소비 기본 구성

[ADR 0044](../adr/0044-adopt-earliest-and-three-order-consumers.md)의 첫 읽기 위치 earliest와 [ADR 0045](../adr/0045-use-purchase-activity-consumer-group.md)의 group.id=purchase-activity를 주문용 factory/listener에 연결했다. 업무용 topic partition 3개·같은 group 전체 consumer 목표 3개·기존 orderId 문자열 key는 유지한다. 로컬 topic 생성과 local profile 활성화는 [아래 절](#local-topics-and-activation)을 따른다.

유효한 committed offset이 있으면 저장 위치부터 이어 읽는다. consumer 3개는 group 전체의 목표이며 모든 앱 인스턴스에 각각 concurrency=3을 적용하는 뜻은 아니다. 로컬은 streamer 1대에 concurrency 3으로 맞췄고(ADR 0050), 운영 배포·스레드 구성은 정하지 않았다. 4번째 consumer·처리율 한도·static membership·producer batch 값은 채택하지 않았다.

선택한 설정과 현재 테스트 범위는 [검증 상태 표](kafka-settings-experiments.md#selected-verification-status)에 구분한다. 격리 테스트에서 실제 purchase-activity group의 STABLE 멤버 3개와 partition 3개의 배정·DB 저장·같은 group 재시작을 확인했다. 당시에는 업무용 topic 생성·listener 활성화를 하지 않았고, 이후 로컬에 적용했다(ADR 0050). 처리량·lag·운영 부하는 별도 측정 대상이다.

<a id="initial-offset-experiment"></a>

### 첫 읽기 위치 비교 — earliest factory 반영·기본 비활성

사용자의 요청으로 earliest/latest를 실제 격리 Kafka에서 비교했다. **실험 당시에는 설정 채택 전**이었고 이후 사용자가 earliest를 선택했다. 이후 주문용 factory에 earliest를 반영했으며 기본 비활성은 유지한다. 결과와 업무 의미는 [Kafka 설정 비교 실험](kafka-settings-experiments.md#initial-offset-experiment)에 둔다.

처리 위치 기록이 없는 새 그룹에서 미리 저장한 3건은 earliest만 읽었다. 두 그룹의 배정·latest position 초기화 이후 추가한 2건은 둘 다 읽었다. 다른 실험에서 같은 그룹의 유효한 committed offset이 3이면 두 설정 모두 offset 3부터 이어 읽었다. 단일 broker·partition 1의 비교 조건이며 실제 주문 소비·DB 반영·broker 장애를 검증한 것은 아니다.

비교한 업무 질문은 “읽기 위치 기록이 없는 새 group의 첫 구독에서 기존 주문 확정도 구매 활동에 반영할 것인가?”였고, 사용자는 반영하도록 earliest를 선택했다. 기존 주문까지 읽으려면 earliest, 처음 초기화된 위치 뒤부터 수집하려면 latest가 해당 시작 동작이다. 기존 group의 유효한 committed offset이 남아 있는 재시작은 그 위치부터 이어 읽는다. 선택한 earliest는 실제 주문용 factory에 반영했다. 이후 전용 오류 처리기와 local profile 활성화도 적용했으며 현재 상태는 아래 절을 따른다.

earliest의 비용도 있다. group의 committed offset은 오래 쓰이지 않으면 broker에서 만료된다(`offsets.retention.minutes`, 기본 7일). 그 뒤 다시 붙으면 보존 기간(`order-events` 기본 7일) 안의 record를 처음부터 다시 읽는다. 같은 사실은 no-op이라 구매 활동은 늘지 않지만, 그동안 DB 조회와 처리량이 한꺼번에 몰린다. 이번 주에는 측정하지 않았고 한계로 남긴다.

<a id="consumer-count-experiment"></a>

### 같은 그룹의 consumer 수 비교 — group 전체 3개 채택

이번에는 consumer 수 한 항목만 실제로 비교했다. 격리 topic의 partition 2개에서 같은 group의 멤버를 1→2→3개로 늘리고, STABLE 상태의 broker 배정과 각 consumer의 assignment가 일치하는지 확인했다.

| consumer 수 | 실제 배정 |
| --- | --- |
| 1개 | C1이 P0·P1 담당 |
| 2개 | C1이 P0, C2가 P1 담당 |
| 3개 | C1이 P0, C2가 P1, C3는 할당 없이 대기 |

이후 같은 테스트에 멤버 2개일 때 P0·P1로 record를 하나씩 명시적으로 보내, C1은 P0의 record만, C2는 P1의 record만 읽는 것을 추가로 확인했다([구현 기록 30절](implementation-log.md#explicit-partition-probe)). 구체적인 멤버별 배정은 이 실행의 관찰값이다. 처리량·lag·DB 부하를 측정하거나 실제 Spring factory의 concurrency를 바꾼 것은 아니다. 업무용 partition 3개 계획에 이 표의 대기 조건을 그대로 적용하지 않는다. 관찰 결과는 [Kafka 설정 비교 실험](kafka-settings-experiments.md#consumer-count-experiment)에 남겼다. 사용자는 별도로 업무용 partition 3개·같은 group 전체 consumer 3개를 시작 구성으로 선택했으며 그 구성의 처리량 검증은 아직이다.

<a id="failure-handling-experiment"></a>

### DB 저장 실패 처리 비교 — 과거 비교와 후속 조합 검증

기존 parser·handler·DB TX에 테스트 전용 Spring Kafka container를 붙여, `DefaultErrorHandler`의 2회 재시도·기본 recoverer와 `CommonContainerStoppingErrorHandler`의 중단·재시작을 비교했다. 그 비교 당시에는 업무용 ack mode를 선택하지 않았다. 이후 MANUAL_IMMEDIATE를 거쳐 현재 BATCH를 주문용 listener·factory에 연결했다. 기본 비활성·local profile 활성화다. 조건과 흐름도·실제 값은 [Kafka 설정 비교 실험](kafka-settings-experiments.md#failure-handling-experiment)에 둔다.

주문 42 (offset 0)의 두 번째 상품 저장 직전에 예외를 주입했다. 첫 상품과 완료 기록이 모두 rollback되고 committed offset은 0인 것을 공통으로 확인했다. 한 번의 오류는 자동 재시도로 성공했지만, 지속 오류는 이번 기본 recoverer가 주문 42를 건너뛴 뒤 정상 주문 43 (offset 1)을 저장해 committed offset 2로 진행했다. 이 결과를 모든 재시도 방식의 동작으로 일반화하지 않는다.

중단 방식은 두 오류 조건 모두 container를 멈추고 위치 0을 유지했다. 오류 원인을 제거하고 같은 group으로 명시적으로 재시작하면 주문 42·43을 저장하고 위치 2로 진행했다. 이 비교는 실제 Kafka·MySQL과 저장 경로의 오류 주입을 사용했다. 실제 DB 서버 중단은 재현하지 않았고 DB commit 뒤 중단은 아래 실험에서 별도로 확인했다. 이후 선택한 조합은 [1초/2초 재시도 실험](kafka-settings-experiments.md#selected-db-retry-experiment), DLQ 경로는 [발행 성공/실패 실험](kafka-settings-experiments.md#dlq-delivery-experiment)에서 추가 검증했다. 기존 비교의 고정 200ms·기본 skip 결과는 당시 조건으로 보존한다.

<a id="consumer-failure-policy"></a>

### 소비 오류 처리 정책

[ADR 0041](../adr/0041-separate-invalid-messages-from-db-retry-exhaustion.md)에서 메시지 자체 오류와 DB 오류의 소진 후 처리를 분리했다.

| 오류 | 채택한 방향 |
| --- | --- |
| orderId 누락·잘못된 수량 등 메시지 자체 오류 | DLQ로 분리. 전송 성공 확인 전 원본 offset을 진행하지 않고 업무 완료와 구분 |
| 메시지 문제가 아닌 모든 실패(DB 등) | 1초부터 2배씩 늘리며 재시도. 한 간격 최대 30초, 간격의 합 5분 ([ADR 0053](../adr/0053-retry-transient-consumer-failures-for-minutes-before-stopping.md)). 그 안에 풀리면 스스로 이어 감 |
| 재시도를 다 써도 DB 오류가 계속됨 | 소비 중단·원인 노출. 실패 record를 지나가지 않도록 offset 유지 |
| DLQ 전송이 실패로 판정됨 | 원본 offset 보호·소비 중단. 사람이 원인 해결 후 같은 group 재시작 |
| 원인 해결 후 | 같은 group으로 재시작해 저장된 위치부터 다시 처리 |

DB 재시도 소진만으로 메시지 자체 오류라고 판단하거나 DLQ로 보내지 않는다. 사실 충돌의 DLQ 분리는 ADR 0047, DLQ 전송 실패 시 중단·사람의 복구 후 재시작은 ADR 0048을 따른다. 재시도 분류·중단 범위·DLQ topic은 [ADR 0049](../adr/0049-connect-order-consumer-error-handler.md)에서 정했다([오류 처리기 절](#consumer-error-handler)). 자동 재개 방법과 DLQ 관찰·운영 재처리는 후속이다.

adapter는 예외를 전파한다. DB 원자성·동일 사실 no-op은 유지한다. 처음에는 재시도를 최대 2회([ADR 0042](../adr/0042-use-two-db-retries-before-stopping-consumer.md))·1초/2초([ADR 0043](../adr/0043-increase-db-retry-delays-from-one-to-two-seconds.md))로 정했지만, 3초만으로는 DB failover 같은 일시 장애에도 소비가 멈춰 사람이 재시작해야 했다. 지금은 [ADR 0053](../adr/0053-retry-transient-consumer-failures-for-minutes-before-stopping.md)에 따라 1초부터 2배씩, 한 간격 최대 30초, 간격의 합 5분까지 재시도하고 그 뒤에만 멈춘다. 주문용 factory는 `max.poll.interval.ms`를 따로 지정하지 않아 기본 5분을 쓴다. 공유 batch factory의 2분은 이 factory에 적용되지 않는다. DB 처리·재시도 대기 등을 합한 실제 poll 사이 시간을 이 제한과 비교해야 한다. 이 재시도는 consumer DB 저장에 대한 것이며 producer 전송 재시도와 구분한다.

여기서 DB 저장은 Kafka에서 받은 주문 확정 이벤트로 구매 활동과 처리 완료 기록을 저장하는 작업이다. 저장 실패 시 같은 이벤트로 다시 시도한다. 매 재시도마다 Kafka에서 새로 가져와야 한다는 뜻은 아니며, 재수신·재호출 방식은 error handler 구현에서 정한다.

선택한 조합의 격리 테스트에서는 같은 offset 0의 세 번째 시도 성공과 세 번 실패 뒤 중단을 모두 확인했다. 지속 실패의 DB 행·committed offset은 0이고 후속 주문도 저장하지 않았다. 오류 제거 후 같은 group 재시작으로 두 주문을 저장했다. DLQ도 실제 broker의 성공·크기 제한 거절을 사용해, 성공 확인 전 offset 보호와 잘못된 주문의 업무 완료 기록 미생성을 확인했다. 테스트용 AckMode.RECORD·단일 container 중단과 DLQ 발행 실패 시 중단은 관찰 조건이다. 업무용 ack·오류 분류·중단 범위·재시작·DLQ 운영 정책을 새로 채택한 것은 아니다.

<a id="db-commit-replay-experiment"></a>

### DB commit 뒤 offset 미커밋 재전달

테스트 전용 record container에서 주문 42 (partition 0·offset 0)를 한 번 전송하고 기존 parser·handler의 DB TX가 성공한 직후 hook에서 멈췄다. 별도 스레드의 조회로 완료 기록 1행·활동 2행의 commit을 확인했다. 당시 consumer position은 1, committed offset은 0이었다.

listener가 반환하기 전에 hook 예외와 중단 error handler로 첫 container를 종료했다. DB 행은 그대로 남고 committed offset은 0이었다. 별도 container·consumer를 같은 group으로 시작하자 seek·offset 재설정 없이 같은 topic·partition·offset·key·본문의 record를 다시 받았다. 기존 동일 사실 no-op 뒤 committed offset은 1로 진행했다.

기존 DB 행 ID·수량·확정 시각·완료 snapshot은 그대로이며 활동 저장 포트 2회·완료 저장 포트 1회 외의 추가 저장 호출도 없었다. OS 강제 종료·broker 장애를 재현한 것은 아니다. 당시 실험 조건인 AckMode.RECORD·중단 error handler를 업무용으로 채택하지 않았다. 이후 MANUAL_IMMEDIATE의 DB commit 후 ack 전 재전달을 검증했고, 현재는 BATCH로 변경해 실제 주문 listener의 같은 offset 재전달도 확인했다([37절](implementation-log.md#batch-adoption)). 업무용 error handler 연결은 [오류 처리기 절](#consumer-error-handler)에 있다. 조건·가로 흐름도·관찰 값은 [재전달 실험](kafka-settings-experiments.md#db-commit-replay-experiment), 추가 config 후보는 [설정 목록](kafka-settings-experiments.md#config-candidates)에 둔다.

<a id="consumer-ack-mode"></a>

### 주문 record의 완료 위치 — 동기 DB 처리 뒤 BATCH

[ADR 0054](../adr/0054-commit-order-consumer-offsets-per-poll.md)에 따라 주문용 ack mode를 MANUAL_IMMEDIATE에서 BATCH로 바꿨다. record listener와 주문 한 건의 DB TX는 유지한다. 메시지를 List로 받거나 여러 주문을 한 DB TX로 저장하는 변경이 아니다. 기존 공유 demo용 batch factory의 MANUAL과 과거 비교 실험은 유지한다.

consumer 스레드에서 OrderConfirmedRecordAdapter.handle(record)로 parser·DB handler를 동기 호출한다. 완료 기록·모든 품목의 commit 또는 확인된 동일 사실 no-op 뒤 listener가 정상 반환하면 container가 poll 처리 경계에서 위치를 기록한다. 파싱·DB 오류는 전파하고, 예외를 삼켜 완료로 만들지 않는다. DLT 성공·실패와 재시도 소진은 [업무용 오류 처리기](#consumer-error-handler)가 처리한다.

선택 근거인 [격리 비교](kafka-settings-experiments.md#batch-ack-experiment)에서 첫 주문 저장 뒤 중단하자 BATCH는 그 주문을 다시 읽고 MI는 뒤의 두 주문만 읽었다. 최종 완료 기록 3·활동 6행은 같았다. 다시 읽어도 추가 저장이 없는 현재 구조에 맞춰 직접 ack를 관리하는 책임을 줄였다. commit 호출 수·처리량·지연은 측정하지 않았으므로 속도 향상을 검증한 결정으로 표현하지 않는다.

DB commit과 offset commit은 여전히 원자적이지 않다. 이미 저장한 메시지가 다시 올 수 있고, BATCH는 중단 시 재전달 범위가 더 커질 수 있다. 업무 중복은 기존 DB 유일 제약·동일 사실 대조·no-op이 막는다. 수동 ack 비교의 당시 결과는 [9절](kafka-settings-experiments.md#manual-ack-experiment)에 보존한다.

현재 실제 BATCH listener의 정상·논리적 중복·DB commit 뒤 같은 offset 재전달과 업무용 오류 처리기의 재시도·DLT·중단/복구는 [구현 기록 37절](implementation-log.md#batch-adoption)에 둔다. 운영 다중 consumer 배포·성능 검증과 구분한다.

<a id="order-listener-wiring"></a>

### 주문용 listener·factory — 코드 연결·기본 비활성

OrderConfirmedKafkaListener는 record 1건을 받아 어댑터의 한 인자 handle(record)에 전달한다. 별도 TX·비동기 executor·예외 삼키기를 추가하지 않는다. OrderConfirmedKafkaConfiguration은 공유 KafkaProperties에서 접속 정보를 읽되 별도 consumer factory를 만들고 record·BATCH·earliest·String key/byte[] value·자동 commit 끔·consumer의 topic 자동 생성 요청 끔을 명시한다. 동기 commit 기본 동작을 유지하고 배정 시 offset commit은 하지 않아 정상 완료/오류 처리 경로에서 진행 위치를 결정한다.

| 설정·bean | 현재 계약 |
| --- | --- |
| commerce.order-events.topic | 기본 order-events |
| commerce.order-events.consumer.enabled | 기본 false. true일 때만 주문용 factory·listener bean 등록 |
| listener ID / group.id | order-confirmed-purchase-activity / purchase-activity |
| commerce.order-events.consumer.concurrency | 활성화 시 앱 인스턴스별 수를 반드시 입력. group 전체 목표 3개를 모든 앱에 자동 적용하지 않음 |
| orderConfirmedKafkaErrorHandler | 활성화 시 필요한 전용 CommonErrorHandler bean. 미정 정책 대신 framework 기본 recoverer를 쓰지 않음 |
| commerce.order-events.consumer.auto-startup | enabled 상태에서 기본 true. 테스트에서는 false로 등록 후 관찰 시점에 start |

최종 group.id는 listener의 purchase-activity를 따른다. 공유 batch의 전용 fetch 튜닝값(`fetch.min.bytes=1MiB`·`fetch.max.wait.ms=5000`)은 새 record factory에 복사하지 않아 클라이언트 기본(1 byte·500ms)을 쓴다. 주문은 한 건씩 DB에 저장하므로 모아 받을 이유가 없고, 1MiB를 채울 때까지 기다리면 적은 양의 주문이 최대 5초 늦게 보인다. 테스트도 같은 이유로 별도 fetch 설정 없이 이 기본값을 쓴다. 전용 error handler와 앱별 수가 빠진 채 enabled=true로 실행하면 context가 실패한다. 기본 false에서는 둘 다 요구하지 않고 자동 구독 0개를 유지한다. 업무용 error handler는 [오류 처리기 절](#consumer-error-handler)에서 연결했다.

설정 검증은 parent concurrency=3·선택한 group/topic·BATCH·실효 consumer 설정과 기존 공유 batch MANUAL 보존을 확인했다. 실제 @KafkaListener 통합 검증은 별도 Kafka/MySQL·partition 1/RF1·concurrency=1에서 정상 주문 42(offset 0)와 동일 사실 재발행(offset 1)을 처리했다. 이어 주문 43(offset 2)을 DB에 저장한 뒤 listener 반환 전 통제된 예외로 중단하고 같은 group으로 다시 시작했다. offset 2가 두 번 전달됐지만 기존 행 ID·수량은 같고 최종 완료 2·활동 4·위치 3이었다. 이 중단 hook은 테스트 전용 오류 처리기를 쓴다. 실제 업무용 오류 처리기의 네 실패/복구 경로는 별도 통합 검사로 확인했으며, 운영 3-consumer 배포나 OS 강제 종료는 검증하지 않았다([37절](implementation-log.md#batch-adoption)).

<a id="consumer-conflict-handling"></a>

### 같은 식별자의 다른 사실 — DLQ 분리

동일 사실 재전달의 정상 no-op과 구분한다. 현재 handler는 같은 eventId·orderId에 저장된 수량·품목·시각 등이 다르면 OrderConfirmationConflictException을 전파하고 기존 구매 사실을 유지한다. 사용자는 [ADR 0047](../adr/0047-quarantine-conflicting-order-confirmation-facts.md)에서 충돌 record를 DLQ로 분리하고 정상 주문을 계속 처리하는 방향을 채택했다. 수신 계층에는 application의 `ConflictingOrderConfirmationException`으로 드러나며, 업무용 error handler가 이를 DLQ로 분리한다.

[충돌 비교 실험](kafka-settings-experiments.md#conflicting-fact-experiment)은 정상 주문 42 (offset 0), 같은 eventId에 수량을 바꾼 충돌 record (offset 1), 정상 주문 43 (offset 2)을 준비했다. 두 후보 모두 기존 주문 42의 수량 5개·활동 행 ID·완료 기록 ID를 유지했다. DLQ 후보는 충돌 원문을 1건 보존한 뒤 43도 저장해 위치 3까지 진행했다. 중단 후보는 43을 저장하지 않고 위치 1을 유지했으며, 원인 해결 없는 같은 group 재시작은 같은 충돌로 다시 멈췄다.

위 두 후보 결과는 비교 당시 조건으로 보존한다. 채택한 방향은 충돌 DLQ 분리이며 임시 DLQ topic은 채택하지 않았다. DLQ 실패 대응은 이후 ADR 0048에서 중단·수동 복구로 정했다. adapter의 예외 전파와 DB 재시도 정책(현재 ADR 0053)은 유지한다.

<a id="dlq-failure-recovery"></a>

### DLQ 전송 실패 — 원본 보호·중단·사람의 복구 후 재시작

[ADR 0048](../adr/0048-stop-consumption-on-dlq-publication-failure-and-resume-manually.md)에서 사용자는 DLQ 전송 실패 후 소비를 중단하고 사람이 원인을 해결해 같은 group으로 재시작하는 방향을 선택했다. 사람이 메시지를 수작업으로 옮기는 대신, 재시작한 consumer가 Kafka 원본 record를 다시 읽어 DLQ 전송을 재시도한다. 전송 성공 확인 전에는 원본 record를 지나가도록 offset을 진행하지 않는다.

DLQ 설정·브로커 상태 등 원인을 고친 뒤 재시작한다. 원인이 남아 있으면 같은 위치에서 다시 실패·중단할 수 있다. 원인·원본 topic/partition/offset/group 등의 복구 정보를 드러내고 자동 소비 재개 루프는 추가하지 않는다. 기존 producer 내부 재시도·timeout과 DB 재시도 정책(현재 ADR 0053)은 변경하지 않는다.

이 정책은 [오류 처리기 절](#consumer-error-handler)의 업무용 error handler에 연결했다. 기존 RECORD 실험의 중단·수동 원인 제거·같은 group 재시작은 근거로 보존한다. 당시 MANUAL_IMMEDIATE의 실패/복구는 [26절](implementation-log.md#consumer-error-handler)에 보존하고, 현재 BATCH 연결 뒤 같은 업무용 오류/복구 경로는 [37절](implementation-log.md#batch-adoption)에서 다시 검증했다. 중단 범위는 이 앱의 주문 listener 전체이며 DLQ topic은 order-events-dlt다. 여러 앱의 재배정·배포 재시작과 운영 재처리 절차는 별도로 정한다. 전송 실패 판정이 모든 상황에서 DLQ 미저장을 뜻하거나, 재전송이 정확히 한 번만 남는다는 보장은 하지 않는다.

<a id="consumer-error-handler"></a>

### 주문용 error handler — 재시도 분류·listener 전체 중단·`-dlt` topic

[ADR 0049](../adr/0049-connect-order-consumer-error-handler.md)에서 남은 세 가지를 정하고 `OrderConfirmedKafkaErrorHandling`으로 연결했다. 주문용 listener가 켜질 때만 등록된다.

이 문서의 DLQ(Dead Letter Queue)는 처리할 수 없는 메시지를 원래 흐름에서 빼 따로 모아 두는 곳을 말한다. Kafka에서는 그 장소가 topic이라 Spring Kafka는 DLT(Dead Letter Topic)라고 부르며, 실제 topic 이름은 `order-events-dlt`다. 둘은 같은 것을 가리킨다.

| 실패 | 처리 | 원본 offset |
| --- | --- | --- |
| 메시지 오류 (`InvalidOrderConfirmedMessageException`) | 재시도 없이 `{topic}-dlt`로 전송 | DLQ 전송 성공 뒤 진행 |
| 사실 충돌 (`ConflictingOrderConfirmationException`) | 재시도 없이 `{topic}-dlt`로 전송. 기존 DB 사실 유지 | DLQ 전송 성공 뒤 진행 |
| 그 밖의 모든 오류 (DB 등) | 1초부터 2배씩, 한 간격 최대 30초, 간격의 합 5분까지 재시도. 그 안에 풀리면 이어 가고, 넘기면 listener 전체 중단 ([ADR 0053](../adr/0053-retry-transient-consumer-failures-for-minutes-before-stopping.md)) | 그대로 |
| DLQ 전송 실패 (결과 대기 예산 125초, 기본 delivery timeout 120초를 받아들임, [ADR 0055](../adr/0055-accept-default-delivery-timeout-for-dead-letter-publishing.md)) | listener 전체 중단 | 그대로 |

BATCH에서는 ackAfterHandle=true·commitRecovered=false로 둔다. DLT 전송 성공 뒤 recoverer가 정상 반환하면 container가 해당 record의 완료 위치를 관리한다. DLT 실패·재시도 소진의 중단은 예외로 끝나므로 실패 record를 지나지 않는다. 이미 성공한 앞 record의 위치는 오류 처리 전에 기록될 수 있다.

`setWaitForSendResultTimeout(10초)`는 최소 대기 시간이다. producer의 기본 delivery timeout 120초와 recoverer buffer 5초 때문에 현재 결과 대기 예산은 125초다. [실험 12절](kafka-settings-experiments.md#dlt-timeout-experiment)에서 실제 listener가 약 120.1초 뒤 중단되고 원본 offset 0을 유지함을 확인했다. [ADR 0055](../adr/0055-accept-default-delivery-timeout-for-dead-letter-publishing.md)에서 이 기본 대기를 받아들이기로 했으며, DLT producer의 timeout을 별도로 줄이지 않는다.

- 메시지 오류: adapter가 parser의 `IllegalArgumentException`을 `InvalidOrderConfirmedMessageException`으로 감싼다. parser 안에서 만드는 domain 구매 사실의 검증 실패도 여기에 포함된다.
- 사실 충돌: `OrderConfirmationHandler.handle(command)`가 domain의 `OrderConfirmationConflictException`을 application의 `ConflictingOrderConfirmationException`으로 감싼다. 수신 계층이 domain을 직접 참조하지 않는 계층 규칙을 지킨다.
- DLQ는 원본 bytes를 그대로 보내도록 별도 producer(String key·byte[] value)를 쓴다. partition은 원본 key로 정하고, 원본 topic·partition·offset·group·예외는 DLT header에 남는다.
- 중단은 `CommonContainerStoppingErrorHandler`로 listener container 전체(`order-confirmed-purchase-activity`)를 멈추고 예외로 끝난다. 그래서 recoverer가 성공하지 못한 원본은 commit되지 않는다. 중단 로그에 원본 topic·partition·offset·group을 남긴다.
- DB 오류 등은 재시도 시간(간격의 합 5분) 안에 풀리면 사람 없이 그 record부터 이어 간다. 그 시간을 넘겨 멈춘 뒤에는 자동 재시작이 없다. 사람이 원인을 해결하고 같은 group으로 다시 시작하면 저장된 위치부터 다시 읽는다.
- DLQ 전송 실패를 멈춤으로 처리한 이유: Spring Kafka의 기본 동작에서는 recoverer(DLQ 전송)가 실패하면 offset을 commit하지 않고 같은 record로 되돌아가 다시 시도한다. 유실은 없지만, 원인(예: DLQ topic 이름 불일치·크기 제한)이 남아 있는 동안 그 partition이 로그만 남기며 같은 자리를 계속 돈다. 이 조용한 정지를 드러내려고 listener를 멈추고 원본 위치를 ERROR 로그에 남긴다.
- 메시지 문제를 DLQ로 빼고 뒤의 주문을 계속 처리해도 되는 이유는 **순서 의존이 없기 때문**이다. 주문 하나에 이벤트가 하나(`OrderConfirmed`)뿐이고, 서로 다른 주문의 구매 활동은 서로의 결과에 기대지 않는다. 같은 주문에 순서가 중요한 이벤트(예: 확정 뒤 취소)가 생기면, DLQ로 앞 이벤트를 빼는 순간 뒤 이벤트가 먼저 반영될 수 있으므로 이 선택을 다시 검토한다.
- DLQ에 들어간 것을 알리는 수단은 ERROR 로그와 DLT header뿐이다. 알림·lag 지표는 이번 범위 밖이다.

실제 Kafka·MySQL과 실제 `@KafkaListener`로 네 장면을 차례로 검증했다(partition 1·RF1·concurrency 1): 메시지 문제의 DLQ, 일시 장애의 자동 회복, 지속 장애의 중단·재시작, DLQ 전송 실패의 중단·재시작. concurrency 3에서 모든 스레드가 함께 멈추는 것은 실행으로 확인하지 않았다. 현재 BATCH 재검증 결과는 [구현 기록 37절](implementation-log.md#batch-adoption), 당시 MI 결과는 [26절](implementation-log.md#consumer-error-handler)·[34절](implementation-log.md#long-consumer-retry)에 보존한다.

<a id="local-topics-and-activation"></a>

### 로컬 topic 생성과 local profile 활성화

[ADR 0050](../adr/0050-create-order-topics-in-compose-and-enable-local-events.md)에 따라 `docker/kafka-cluster-compose.yml`을 바꿨다.

| 항목 | 값 |
| --- | --- |
| 브로커 3대 | `KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE=false`, `KAFKA_CFG_OFFSETS_TOPIC_REPLICATION_FACTOR=3` |
| `kafka-init` | 브로커 3대가 healthy가 되면 `--if-not-exists`로 `order-events`(partition 3·복제 3·min ISR 2), `order-events-dlt`(partition 1·복제 3·min ISR 2)를 만들고 끝난다 |
| commerce-api `local` | `commerce.order-events.enabled=true` |
| commerce-streamer `local` | `commerce.order-events.consumer.enabled=true`, `concurrency=3` |

기본값과 `test` profile은 발행·소비 모두 꺼져 있다. init은 이미 있는 topic의 설정을 바꾸지 않는다. offset topic 복제 수는 내부 topic이 처음 만들어질 때만 적용되므로, 설정을 바꾼 뒤에는 volume을 지우고 클러스터를 다시 만든다.

<a id="w6-delivery-cases"></a>

### 두 앱을 잇는 W6 사례 — 미발행 1건·정상 발행/소비 1건

로컬 브로커 3대에서 두 단계로 재현한다. commerce-api `W6DeliveryCasesPublishTest`가 실제 PG 결과 적용으로 두 주문을 확정하고, A는 send 직전 hook에서 멈추며 B만 `order-events-w6-cases`로 보낸다. commerce-streamer `W6DeliveryCasesConsumeTest`가 실제 주문 listener로 그 topic을 소비한다. 두 테스트는 `experiment` tag라 평소 `check`에서 빠진다.

결과: A는 CONFIRMED·예약 CONSUMED이지만 broker record와 구매 활동이 없다. B는 record 1건(partition 2·offset 0)과 구매 활동 1행이 생겼다. 실제 ID와 실행 방법은 [구현 기록 28절](implementation-log.md#w6-delivery-cases)에 있다. 이 차이가 W7 Outbox가 메울 DB→broker 누락 구간이다.

<a id="db-driver-timeouts"></a>

### MySQL 드라이버 timeout — 응답 없는 DB에서도 예외가 나게 한다

[ADR 0052](../adr/0052-set-mysql-driver-timeouts-per-app.md)에 따라 commerce-api와 commerce-streamer의 `application.yml`에 `connectTimeout=3000`, `socketTimeout=10000`을 둔다(공통 `jpa.yml`이 아닌 앱 설정, batch 제외). 기본값은 무한 대기라서, 응답이 없는 DB에서는 예외가 나지 않아 오류 처리기의 재시도·중단이 시작되지 않았다.

- 응답 없는 DB에서 예외까지는 socketTimeout의 약 두 배(20초)다. 드라이버가 읽기를 포기한 뒤 연결 정리에서 한 번 더 기다린다.
- streamer: 별도 SLEEP 실험에서 예외까지 약 20초를 관찰했다. 모든 DB 작업의 상한으로 일반화하지 않으며 DB 처리·재시도 대기 등을 합한 실제 poll 사이 시간을 주문용 기본 제한 5분과 비교한다. 재시도 예산 5분은 대기 간격의 합이므로 전체 종료 시간과 다르다.
- commerce-api 요청은 최대 약 20초 뒤 실패 응답으로 끝나 스레드와 커넥션을 놓는다.
- 10초를 넘는 정상 쿼리·잠금 대기는 실패한다. 이번 범위의 쿼리는 행 몇 개짜리이고 PG 호출은 잠금을 쥔 TX 밖에서 한다.

<a id="three-brokers"></a>

## 로컬 실험 기반: 브로커 3개

**결정:** 사용자가 브로커 3개로 실험 기반을 구성하고, 이후 설정은 하나씩 결정하기로 했다. [ADR 0037](../adr/0037-use-three-brokers-for-local-kafka-experiments.md)에 범위와 이유를 남겼다.

`docker/kafka-cluster-compose.yml`의 별도 프로젝트 `week6-kafka`에서 기존과 같은 Kafka 3.5.1을 실행한다. 각 서버가 broker·controller를 겸하고 동일 KRaft 클러스터에 참여한다. 기존 단일 브로커 구성·데이터는 보존하며 새 환경은 서로 다른 새 volume을 사용한다.

| 서버 | node ID | 호스트 접속 | 클러스터 내부 |
| --- | --- | --- | --- |
| `week6-kafka-1` | 1 | `localhost:19092` | `kafka-1:9092` |
| `week6-kafka-2` | 2 | `localhost:29092` | `kafka-2:9092` |
| `week6-kafka-3` | 3 | `localhost:39092` | `kafka-3:9092` |

대시보드는 `http://localhost:9099`의 `week6` 클러스터다. 최초 세팅에서는 서버 실행·메타데이터·UI 연결만 확인했으며 topic·소비 값은 미리 정하지 않았다. 이후 사용자는 order-events의 partition 3·복제 수 3·최소 ISR 2·acks=all, broker 자동 생성 끔·compose init 생성·내부 offset 복제 수 3을 선택했다. 이 선택은 이후 로컬 Compose에 반영했고, local profile에서만 주문 발행·소비를 켠다([로컬 topic 절](#local-topics-and-activation)).

브로커 수와 topic의 복제 수는 별개 설정이며 사용자는 각각 3으로 선택했다. KRaft 관리 quorum과 사용자 topic의 복제도 구분한다. 특히 현재 로컬 구성은 세 노드가 controller를 겸하므로 두 노드를 내리면 관리 quorum도 과반수를 잃는다. 데이터 replica의 최소 ISR만 분리해서 확인하려면 관리 controller를 살아 있게 둔 별도 실험 조건이 필요하다.

[복제 장애 실험](kafka-settings-experiments.md#replication-failure-experiment)은 데이터 broker 3개·별도 controller 1개로 관리 quorum을 유지했다. RF3·최소 ISR2·acks=all에서 follower 1대 중단과 leader 교체 후 발행 성공, ISR 1에서 NotEnoughReplicasException·끝 위치 불변, ISR 2 복귀 뒤 새 producer의 발행 성공·기존 기록 보존을 확인했다. 같은 idempotent producer의 재시도 소진 뒤 자동 복구를 보장한 결과는 아니다. 내부 __consumer_offsets의 RF3·ISR3과 전체 복구 후 commit 1 유지도 확인했다. 로컬 Compose·init 생성 경로·controller 장애·업무용 전체 흐름은 별도 검증이다.

### 호스트 앱의 접속 주소와 설정 키 정정

공통 kafka.yml의 local·test 프로필에서 producer·consumer·admin은 `localhost:19092,localhost:29092,localhost:39092`를 bootstrap 후보로 사용한다. admin만 `kafka:9092`로 덮어쓰던 이전 항목은 제거해 공통 주소를 따르게 했다. bootstrap은 최초 클러스터 발견에 사용하는 후보 목록이며, 연결 후에는 metadata에서 다른 broker 주소를 발견한다.

consumer의 `value-serializer` 오타는 `value-deserializer`로 정정해 기존에 적혀 있던 `ByteArrayDeserializer`가 적용되게 했다. `properties.enable-auto-commit`은 Spring Boot의 정식 `spring.kafka.consumer.enable-auto-commit=false`로 옮겨 실제 Kafka 키 `enable.auto.commit=false`가 전달되게 했다. 이는 템플릿의 선언 의도를 바로잡는 수정이며, 주문용 consumer의 ack·error handler·동시성 정책을 새로 채택한 것은 아니다.

### 선택하지 않은 항목에도 실효 기본값은 존재한다

아래는 브로커 3대를 처음 띄운 뒤, 아무 설정도 바꾸기 전에 실행 중인 broker에서 조회한 값이다. **명시적으로 선택하지 않았다는 말은 설정값 없이 대기한다는 뜻이 아니다.** broker는 다음 기본값·이미지 생성 설정을 사용하고 있다.

| broker 설정 | 조회한 실효값 | 의미 |
| --- | --- | --- |
| `auto.create.topics.enable` | true | 존재하지 않는 topic에 대한 요청으로 자동 생성이 일어날 수 있음 |
| `num.partitions` | 1 | 자동 생성 시 사용할 기본 partition 수 |
| `default.replication.factor` | 1 | 자동 생성 시 사용할 기본 복제 수 |
| `min.insync.replicas` | 1 | 별도 topic 설정이 없을 때 적용할 최소 ISR |
| `offsets.topic.replication.factor` | 1 | 이 이미지가 생성한 설정. Kafka 자체 기본값 3과 다름 |

이 표는 당시 조회한 값이며 현재 채택한 업무용 값이 아니다. 이후 자동 생성 끔·compose init 생성을 선택해 Compose에 적용했다([아래 절](#local-topics-and-activation)). 업무용 topic이 없는 상태에서 자동 생성을 허용한 채 활성화하면 partition 1·복제 수 1의 topic이 만들어질 수 있다. client kafka.yml의 `auto.create.topics.enable=false`는 이 broker 동작을 제어하지 않으며, 해당 항목에 주석으로 구분했다.

당시 로컬 `__consumer_offsets`도 아직 생성되지 않았다. 이후 내부 offset 복제 수 3을 broker 설정에 적용했고, 처음 group이 쓰일 때 partition 50·복제 3으로 생성된 것을 확인했다. 사용자 topic에 복사본이 있어도 해당 group의 offset partition이 사용 불가능하면 commit·group 조정에 영향을 줄 수 있다. 복제 수 1에서의 acks=all도 leader 확인은 받지만 다중 broker 복제 장애 보장을 증명하지 못한다.

기존 단일 Compose와 새 구성은 호스트 19092·9099 포트를 공유하므로 동시에 실행할 수 없다. 현재는 새 `docker/kafka-cluster-compose.yml`을 사용하고 기존 단일 환경은 중지 상태로 보존한다.

## 이후에 결정·검증할 것

| 항목 | 현재 상태 |
| --- | --- |
| 업무용 토픽·메시지 계약 | `order-events`·`OrderConfirmed`·schemaVersion 1 메시지 구현. partition 3·복제 수 3·최소 ISR 2로 로컬 생성 |
| 토픽 자동 생성·생성 주체 | 자동 생성 끔·compose init 생성, 로컬 적용·확인 |
| 내부 offset topic 복제 | 복제 수 3을 브로커 설정에 적용. 실제 `__consumer_offsets`가 partition 50·복제 3으로 생성된 것 확인 |
| 주문 이벤트의 분배·소비 구성 | 실제 전송·수신 parser/어댑터/조건부 listener/factory·업무용 error handler 연결과 정상/논리적 중복·실패 경로 검증, 두 앱을 잇는 사례 재현. local profile 활성화, 운영 배포 구성은 정하지 않음 |
| 이벤트 ID | `order-confirmed:v1:{orderId}`로 고정 생성·실제 재전송의 동일성 검증 |
| 최초 확정 시각 | Clock 기반 `Instant`를 최초 PG 확정 전이에 저장. 중복 적용·rollback 경계 검증 대상 |
| commit 이후 발행 연결·발행 실패 처리 | listener·발행 포트·Kafka 구현체 연결. 성공 metadata와 동기/비동기 오류 로그 처리 구현 |
| producer 설정·consumer ack·error handler | producer `max.block.ms=5000`·`retries` 기본([ADR 0051](../adr/0051-limit-producer-metadata-wait-and-keep-default-retries.md)). 주문용 factory에 BATCH와 업무용 error handler 적용([ADR 0054](../adr/0054-commit-order-consumer-offsets-per-poll.md)) |
| 구매 활동 모델·DB 중복 보호 | 완료 이벤트와 상품별 활동의 같은 TX 저장, 식별자 유일성·동일 사실 no-op·충돌 대조·rollback 뒤 경쟁 확인 구현. 실제 listener에 연결 |

각 항목은 역할과 선택지·필수 계약을 확인하고 사용자의 판단 뒤 적용한다. 구현 시 현재 설계와 실제 코드·테스트를 대조하고, 기존 ADR의 결정이 확장되거나 새 설계 결정이 생기면 관련 ADR도 함께 정리한다.

## 문서의 역할과 ADR 보존

이 문서는 현재 구조·정책을, implementation-log.md는 구현·실행 결과를 갱신한다. 채택한 ADR은 결정 당시의 본문·근거·검증을 보존한다. 새 결정은 새 ADR로 작성하고 이전 ADR의 상태·메타데이터에는 확장·대체 관계와 후속 링크만 표시한다. 실제 대체일 때만 Superseded로 표시하며, 이번처럼 확장한 기존 결정은 Accepted를 유지한다. 이 구분을 AGENTS.md에도 반영했다.
