# 6주차 설계 · 주문 확정 이벤트 발행과 구매 활동 소비

- 과제: `docs/week6/6주차-Kafka-과제.md`
- 현재 코드 조사: `docs/week6/설계참고.md`
- 페이지 표기(📄 p.N)는 과제 원본 PDF 기준

---

## 0. 전체 흐름

```
[commerce-api]
 PG SUCCESS 검증
   → PaymentFacade.apply (트랜잭션 없음)
       → PaymentResultService.applyFinal (TX B)
           · 주문 PAYMENT_PENDING → CONFIRMED (조건부 UPDATE, 행 수 확인)
           · confirmedAt·updatedAt = now(clock)
           · 예약 소비
           · 처음 확정이면 이벤트 payload 구성
         ← commit 완료 후 리턴 (처음 확정 여부 + payload)
   → OrderConfirmedPublisher.publish (application 인터페이스)
       → KafkaOrderConfirmedPublisher (infrastructure, 동기 대기)
             │
             ▼
      [Kafka] order-events  (key = orderId 문자열)
             │
             ▼
[commerce-streamer]
 OrderConfirmedListener (interfaces/consumer, record listener)
   → 파싱·검증 (영구 오류면 원본 record 를 {topic}.DLT 로 발행 + ack)
   → 일시 오류 backoff 재시도 → 상한 초과 시 예외 → 컨테이너 중단
   → PurchaseActivityService (application/domain, 별도 빈의 DB TX)
        · processed_order_confirmation 1행
        · product_purchase_activity 품목별 행
        · 같은 TX에서 commit
   → ack (MANUAL_IMMEDIATE)
```

**경계와 실패 시점**

| 구간 | 실패하면 | 결과 |
|---|---|---|
| applyFinal TX 안 | rollback | 주문 확정 없음, 발행 없음 |
| commit 후 ~ send 전 | 이벤트 누락 | 주문은 CONFIRMED, 구매 활동 없음 → **W7 Outbox로 개선** |
| send 실패 | ERROR 로그 | 주문 상태 유지, PG 재호출 없음 |
| consumer DB TX | rollback | 장부·품목 모두 없음, offset 유지 → 재전달 |
| DB commit 후 ~ ack 전 | offset 미커밋 | 재전달 → 장부 조회로 no-op |

**이벤트가 필요한 이유**
주문 확정(결제)과 구매 활동 기록(이후 랭킹 입력)을 분리해, 구매 활동 쪽의 장애·지연이 결제 처리에 영향을 주지 않게 한다. commerce-api는 streamer의 저장을 기다리지 않는다.

---

## 1. 이벤트 계약

| 항목 | 값 |
|---|---|
| topic | `order-events` (테스트는 고유 suffix로 격리) |
| eventType / schemaVersion | `OrderConfirmed` / `1` |
| key | orderId 문자열 (`"42"`), payload의 orderId와 일치 |
| eventId | `order-confirmed:v1:{orderId}` |
| orderId / confirmedAt | 확정 주문 ID / 최초 확정 시각 (DB 값과 일치, ISO-8601 UTC) |
| items | `productId`, `quantity`. 같은 상품 합산, 수량 양수, productId 오름차순 |
| 직렬화 | key `String`, value ObjectMapper로 만든 JSON `byte[]` |

**제외:** PG 카드·비밀정보·불필요한 개인정보. 고객이 이벤트를 발행하는 공개 endpoint 없음.

---

## 2. 발행 설계 (commerce-api)

### D1. eventId → 계산 방식 `order-confirmed:v1:{orderId}`
- 주문 확정은 `finishIfPaymentPending`의 `WHERE status = PAYMENT_PENDING` 가드로 한 주문당 한 번만 일어나므로 계산값이 재발행·재전달 때도 바뀌지 않는다.
- 컬럼 추가 없이 과제 조건을 만족한다.
  > 📄 p.2 "전송 시도 횟수·현재 시각·매번 새 UUID를 넣지 않습니다."
- W7 Outbox에서도 orderId만으로 같은 eventId를 다시 만들 수 있다.

### D2. confirmedAt → `Clock` 빈 주입, 같은 UPDATE의 updatedAt도 같은 값
- 과제가 재현 코드에 고정 clock을 요구한다.
  > 📄 p.4 "고정 clock·품목·수량을 재현 코드에 남깁니다."
- 테스트에서 이벤트의 confirmedAt과 consumer 저장값을 정확히 비교할 수 있다.
- 기존 `finishIfPaymentPending` UPDATE 안에서 세팅하므로 가드 덕분에 중복 callback이 덮어쓰지 못한다.
- 한 번의 확정 변경이므로 confirmedAt과 updatedAt을 같은 시각으로 남긴다.
- 이전 POINT 확정 주문은 값이 없으므로 **null 허용 컬럼**. 소급 발행하지 않는다 (📄 p.3).

### D3. 최초 확정 판단 → 주문 UPDATE 바뀐 행 수 == 1
- 과제 조건은 주문이 "처음" CONFIRMED가 된 경우다.
  > 📄 p.3 "검증된 PG SUCCESS를 처음 내부 CONFIRMED로 반영해 DB commit이 성공한 경우에만 발행합니다."
- 현재 `PaymentResultService.java:49`는 반환 행 수를 버린다. `applyFinal`의 boolean은 결제 시도 기준이라 주문 기준과 어긋날 수 있다.
- 기존 `settleIfOpen`의 "행 수로 처음인지 판단" 방식과 일관된다.

### D4. after-commit 발행 → Facade orchestration
- `applyFinal`이 "처음 확정 여부 + payload"를 리턴하고, 트랜잭션이 없는 `PaymentFacade.apply`가 리턴 후 발행한다.
- 기존 구조가 "Facade = 트랜잭션 없음, Service = 트랜잭션"이라 리턴 시점이 곧 commit 완료 시점이다.
  > 📄 p.3 "발행 위치는 after-commit listener 또는 트랜잭션 서비스 호출이 실제 commit된 뒤의 orchestration으로 정합니다."
- payload는 TX 안에서 미리 구성한다 (lazy 접근 금지, 📄 p.3).
- 흐름이 위에서 아래로 보여 누락 실험 hook과 PR 설명이 쉽다.
- 대안 비교: `@TransactionalEventListener`는 트랜잭션 밖에서 publish하면 기본 설정상 조용히 실행되지 않는 누락 위험이 있다. `TransactionSynchronization` 직접 등록은 domain 서비스에 발행 코드가 섞인다.
- **보완:** 발행 시점에 활성 트랜잭션이 없는지 테스트로 확인해, Facade에 `@Transactional`이 붙는 회귀를 막는다.
- **확정 실패(PG 거절) 경로**(`OrderFacade.applyCreateResult`)는 발행 대상이 아니다.

### D5. 발행기 위치 → 인터페이스 `application`, 구현 `infrastructure`
- Facade(application)가 인터페이스를 호출하므로 `application → infrastructure` 금지 ArchUnit 규칙을 지킨다.
- 테스트에서 가짜 구현으로 "send 직전 중단·전송 실패 주입"(📄 p.3)을 재현할 수 있다.

### D6. 직렬화 → 전용 `KafkaTemplate<String, byte[]>` + ObjectMapper 직접 직렬화
- key는 orderId 문자열로 확실히 고정되고, value는 우리가 만든 JSON 그대로 나간다.
  > 📄 p.5 "String key와 선택한 JSON bytes/DTO 계약이 맞음"
- consumer도 `byte[]`로 받아 직접 파싱하므로 양쪽 형식이 코드에서 일치한다.
- 기존 `KafkaTemplate<Object, Object>` + `JsonSerializer`의 타입 헤더·날짜 형식 같은 숨은 변환을 피하고, 공용 template은 건드리지 않는다.

### D7. send 결과 → 동기 대기 `send().get(타임아웃)`
- 이번 주 목적은 발행 결과(topic·partition·offset·실패)를 확실히 관찰·기록하는 것이다.
  > 📄 p.3 "producer의 send 결과를 관찰해 성공한 record의 topic·partition·offset과 전송 오류를 구분합니다."
- 비동기는 앱 종료 시 실패 로그조차 남지 않는 조용한 누락이 생길 수 있다.
- 과제 환경은 트래픽이 낮아 동기 대기 비용이 작다.
- **규칙:** 대기 시간 상한(예: 3초). 발행 실패 예외는 Facade 밖으로 던지지 않고 eventId·orderId와 ERROR 로그로 남긴다. 주문 상태를 되돌리거나 PG를 재호출하지 않는다 (📄 p.3).
- 참고: "동기"는 Kafka 저장까지만 기다리는 것이며, streamer 처리를 기다리지 않으므로 이벤트 구조의 비동기는 유지된다.
- send 결과는 세 가지로 구분한다. 성공(metadata 수신), 실패 확정(future 가 예외로 끝남: metadata 대기 max.block.ms 초과·delivery.timeout.ms 소진·직렬화 실패), 미확인(get(3초) 타임아웃. record 는 producer 버퍼에 남아 delivery.timeout.ms 동안 뒤에서 재시도하므로 실제로는 발행될 수 있다). 미확인을 실패로 적으면 "발행 안 됨"으로 오판해 W7 재발행 판단을 그르치므로 로그와 예외(OrderConfirmedPublishException.outcome)에 구분을 담는다.

### D8. acks → `acks=all` yml에 명시
- 결제 확정 이벤트라 속도보다 유실 방지를 우선하고, 기본값 의존 대신 명시한다.

### D9. 이벤트 DTO → 각 앱에 따로
- Kafka로 두 앱을 느슨하게 분리하려는 목적과 맞다. 클래스를 공유하면 다시 코드로 묶인다.
- 과제의 주문 42 예시 JSON(📄 p.2)을 양쪽 테스트의 공통 샘플로 사용해 계약 어긋남을 막는다.

---

## 3. 소비 설계 (commerce-streamer)

### D10. 저장 구조 → 장부 + 구매 활동, fingerprint로 비교

```
processed_order_confirmation      (이벤트당 1행)
  event_id      UNIQUE  uk_poc_event_id
  order_id      UNIQUE  uk_poc_order_id
  confirmed_at
  fingerprint   예: "42|2026-09-07T01:00:00Z|7:2,8:1"
  processed_at

product_purchase_activity         (품목당 1행)
  order_id, product_id  UNIQUE  uk_ppa_order_product
  quantity
  confirmed_at
```

- 과제의 저장 규칙을 그대로 따른다.
  > 📄 p.4 "소비 완료 이벤트 | eventId와 orderId의 유일성, 고정 payload의 일치 / 상품 구매 활동 | orderId·productId별 하나, quantity·confirmedAt 보존 / 둘의 반영 | 완료 기록과 모든 품목을 같은 DB TX에서 commit"
- fingerprint = `orderId|confirmedAt|productId:quantity,...`(productId 오름차순). items 순서가 바뀌어도 같은 값이 나온다 (📄 p.2 정규화 규칙).
- 사람이 읽을 수 있는 문자열이라 충돌 시 무엇이 달랐는지 장부에서 바로 보인다.
- productId는 사실의 식별자로 보존하고, 현재 상품 행·가격을 조회하지 않는다 (📄 p.4).
- `processed_at`은 엔티티가 `now()`를 부르지 않고 호출자가 streamer의 `Clock` 빈으로 찍어 넘긴다. 테스트에서 고정하기 위해서다.
- 두 테이블은 리포지터리 하나(`PurchaseActivityRepository`)로 다룬다. 한 TX에서 함께 쓰고 중복 판단 때 함께 읽는 한 묶음이라 나누면 협력 대상만 늘어난다.

| 받은 이벤트 | 처리 |
|---|---|
| 처음 보는 eventId·orderId | 장부 + 품목 저장 |
| 같은 eventId, 같은 fingerprint | no-op, ack |
| 같은 eventId, 다른 fingerprint | 충돌 (영구 오류) |
| 같은 orderId, 다른 eventId | 충돌 (영구 오류) |

### D11. 중복 거르기 → 조회 먼저 + UNIQUE는 최후 방어
- 처리 순서: 장부 조회 → (있음) fingerprint 비교 / (없음) 장부 + 품목 저장.
- 과제 필수 조건을 UNIQUE로 충족한다.
  > 📄 p.4 "중복 판단은 메모리 Set이나 먼저 조회하기만 하는 로직으로 끝내지 않습니다. DB 유일 제약으로 동시 소비를 보호합니다."
- key = orderId라 같은 주문은 같은 partition, 한 consumer가 처리하므로 실제 동시 경쟁은 드물다.
- 경쟁으로 UNIQUE 위반이 나면 일시 오류로 분류해 재시도(D12)하고, 재시도 시 조회 단계에서 no-op으로 정리된다. 잘못 저장되는 일은 없다.
- 중간 품목 저장 실패 시 장부·앞선 품목까지 함께 rollback한다 (📄 p.4).

### D12. 오류 처리 → 영구 오류는 DLT 발행 + ack, 일시 오류는 재시도 후 중단 (DLT 는 2026-10-30 적용 완료)

| 구분 | 예 | 처리 |
|---|---|---|
| 영구 오류 | 충돌, 깨진 JSON, key ≠ orderId, 수량 ≤ 0 | 재시도 없음. 원본 key·value bytes 를 그대로 `{원본 topic}.DLT`(운영 `order-events.DLT`)로 발행하고 ack. 헤더: 원인 예외 클래스·메시지, 원본 topic·partition·offset, 충돌이면 기존/새 fingerprint. 구매 활동 생성 없음 |
| DLT 발행 실패 | broker 불가, DLT topic 없음, 대기 상한 초과 | 일시 오류와 같이 backoff 재시도 → 소진 시 consumer 중단, ack 하지 않음. 원본 record 는 재시작 뒤 다시 온다 |
| 일시 오류 | DB 장애, UNIQUE 경쟁 위반 | backoff 재시도 → 상한 초과 시 consumer 중단 → 원인 제거 후 재시작 |
| 예상 못한 오류(버그) | 엔티티 생성자 검증 실패(`IllegalArgumentException`) 등 영구 오류로 분류하지 않은 모든 예외 | 일시 오류와 같이 재시도 후 중단. 영구 오류로 ack 해 버리면 버그가 조용히 묻히므로 멈춰서 드러낸다 |

- 영구 오류는 다시 해도 결과가 같으므로 멈추면 뒤 메시지까지 막히는 독약 메시지가 된다. 대신 DLT 로 보내 사실을 보존하고 뒤 메시지는 계속 흐르게 한다.
  > 📄 p.4 "충돌로 드러내고 추가 구매를 만들지 않습니다."
- DLT 발행 성공 로그는 WARN 이 아니라 **ERROR** 다. 이번 주에는 DLT 를 읽는 쪽(재처리)이 없어 이 로그가 유일한 알림이기 때문이다.
- DLT topic 은 앱이 만들지 않는다 (운영자 생성, 테스트는 격리 topic 의 `.DLT` 를 직접 생성). DLT 전용 `KafkaTemplate<String, byte[]>` 를 streamer 에 두고 acks=all 로 보낸다.
- 일시 오류의 중단·재시작 흐름은 과제의 listener 실패 실험과 일치한다.
  > 📄 p.9 "오류 제거 후 같은 group으로 컨테이너를 재시작해 재전달·정상 저장·ack까지 확인합니다."
- 재시도 총 대기 시간은 `max.poll.interval.ms`(기본 5분)보다 충분히 짧게 잡는다.
- UNIQUE 위반 외의 DB 오류를 "중복"으로 바꾸지 않는다 (📄 p.4).
- `DataIntegrityViolationException`은 중복으로 바꾸지 않고 재시도만 한다. 진짜 UNIQUE 경쟁이면 다음 조회에서 no-op, 다른 위반이면 소진 후 중단.

### D13. 재시도·중단 구현 → 리스너 안에서 직접 재시도 + `CommonContainerStoppingErrorHandler`
- 과제 예시 handler를 그대로 써서 "실패 record는 건너뛰지 않고 offset이 넘어가지 않는다"를 단순하게 증명한다.
  > 📄 p.5 "실패한 record는 업무 완료로 건너뛰지 않습니다."
- `DefaultErrorHandler` 기본 동작은 재시도 소진 후 record를 건너뛰므로 그대로 쓰면 누락 위험이 있다.
- 다른 handler 선택 시 필요한 `ackAfterHandle`·`commitRecovered` 설명 부담이 없다 (📄 p.7).

### D14. listener factory → commerce-streamer 안 설정 클래스
- `orderActivityKafkaListenerContainerFactory`: `<String, byte[]>`, batch=false, concurrency=1, `MANUAL_IMMEDIATE`, `enable.auto.commit=false`, `CommonContainerStoppingErrorHandler`.
- 쓰는 곳이 streamer뿐이고 기존 factory를 바꾸지 않는다.
  > 📄 p.5 "기존 factory를 무조건 전역 변경하지 않습니다."
- fetch 값(`fetch.min.bytes`, `fetch.max.wait.ms`)을 yml에서 읽고, 테스트 profile에서만 작게 설정한다. 이유: 기존 1MiB·5초 대기로 작은 테스트 메시지가 늦게 보이는 것을 sleep 없이 해결 (📄 p.5).
- 운영 값은 Kafka 기본값(1바이트·500ms)을 쓴다. record listener라 묶음 이득이 없고 튜닝할 측정 근거가 없어 기본값 사용. 기존 1MiB·5초는 batch listener용 템플릿 값이며, 작은 메시지에선 1MiB 조건 때문에 매번 5초를 기다리게 된다.
- groupId: `purchase-activity`.
- `auto.offset.reset=earliest`를 이 factory에서만 지정한다. 이유: 구매 활동 group은 topic에 이미 있는 확정 이벤트도 빠짐없이 읽어야 하고(재처리는 장부로 no-op), 테스트에서 listener 배정 전에 발행한 메시지도 놓치지 않는다. 단점: group을 새로 만들면 retention 안의 과거 이벤트를 한 번 훑는다. 공용 `latest`와 기존 factory는 그대로 둔다.

### D15. streamer 패키지 → commerce-api와 같은 4계층

```
interfaces/consumer   리스너, 파싱·검증, 재시도 루프
application           처리 흐름
domain                구매 활동, fingerprint·충돌 규칙
infrastructure        리포지터리 (JpaConfig 스캔 범위상 필수 위치)
```
- 9주차 랭킹 입력으로 streamer가 계속 커지므로 처음부터 역할을 나눈다 (📄 p.1).

---

## 4. 선택이 아닌 필수 변경 (설계참고.md 기준)

**commerce-api**
- `:modules:kafka` 의존과 `kafka.yml` import 추가
- Order에 `confirmed_at`(null 허용) 컬럼, `Clock` 빈
- `finishIfPaymentPending` 반환 행 수 사용

**modules/kafka**
- `kafka.yml` `value-serializer` → `value-deserializer` (consumer)
- `enable-auto-commit` 표기가 Kafka 키로 전달되는지 확인, 실제 `ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG` 값 검증 (📄 p.5)
- `acks: all` 명시

**commerce-streamer**
- `spring.application.name` → `commerce-streamer`
- `src/test` 신설

**테스트 인프라**
- Kafka Testcontainers fixture 신설 (기존 MySQL fixture 패턴: static 블록 + `System.setProperty`)
- `testcontainers:kafka` 의존 전이 수정, commerce-api에도 Kafka testFixtures 연결
- 컨테이너 내부 주소와 호스트 주소를 섞지 않는다 (📄 p.8)

---

## 5. 한계

- **commit~send 사이 누락:** DB와 Kafka가 원자적으로 묶이지 않아 commit 직후 장애 시 이벤트가 사라진다. D4·D7·D8 어느 선택으로도 막을 수 없다 → W7 Outbox.
  > 📄 p.3 "이 관찰은 DB와 broker가 원자적으로 연결되지 않았다는 증거이며 … 다음 주 Outbox로 개선할 출발점입니다."
- **단일 broker:** 복제본이 없어 `acks=all`이 실제로는 `acks=1`과 같게 동작한다. 복제 장애 보장은 이번 실험으로 확인하지 못했다 (📄 p.7).
- **동기 대기(D7):** 처리량이 중요한 운영 환경에는 적합하지 않다.
- **통제된 종료 실험:** 실패 주입은 OS 강제 종료와 다르다 (📄 p.3).

---

## 6. 추후 변경 예약

| 결정 | 지금 | 나중 |
|---|---|---|
| D7 send 방식 | 동기 대기 | W7 Outbox relay 설계 시 비동기 재검토 |
| D11 UNIQUE 경쟁 | 재시도 후 조회로 no-op | 위반 시 rollback → 별도 TX 재확인 (제약 이름으로 구분, `saveAndFlush`, 동시성 테스트) |
| D12 영구 오류 | ~~ERROR 로그 + ack~~ → **적용 완료 (2026-10-30)**: `{topic}.DLT` 발행 + ack | DLT 를 읽는 쪽(재처리 consumer·재투입 도구)은 이번에 없다. W7 이후 |
| D13 재시도·중단 | 리스너 직접 재시도 + 중단 handler | `DefaultErrorHandler` + backoff + DLT recoverer |

나중에 바꾸기 쉽게 지금 해 둘 것: UNIQUE 제약 이름 지정, 저장 로직을 리스너와 다른 빈으로 분리, fingerprint 비교를 메서드 하나로 분리.

---

## 7. 구현하면서 정할 것

- demo listener 유지 여부
- `order-events` 토픽 생성 방식 (admin 주소가 `kafka:9092`인 점 유의)
- Kafka fixture 구조
- 누락 실험 hook 방법 (D5 가짜 발행기 활용)
- 재시도 횟수·backoff 값

---

## 8. 실험 관찰 (4단계, 2026-10-30 실행)

모든 실험은 Testcontainers 가 띄운 실제 broker(`apache/kafka:3.8.1`)·MySQL 로 돌렸다. 아래 숫자는 그 실행의 관찰값이다.

최종 검증 (DLT 적용 뒤, 2026-10-30): `./gradlew :modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check` → modules:kafka 3건, commerce-api 453건, commerce-streamer 43건 통과. 실패 0, skip 0. Checkstyle·ArchUnit 포함.

**PR 요약 (📄 p.10 이 요구한 4가지)**

| 항목 | 관찰값 | 근거 테스트 |
|---|---|---|
| 대표 주문의 활동 결과 | 주문 2 (사례 2): 장부 1행 `order-confirmed:v1:2`, 구매 활동 2행 (상품 1 × 2, 상품 2 × 1), confirmedAt `2026-09-07T01:00:00.123Z` = DB 값. 같은 record 를 다시 받아도 2행 유지 | `OrderConfirmedDeliveryGapTest`, `OrderConfirmedReplayTest`, `PurchaseActivityIntegrationTest` |
| 배정표 (partition 2, 같은 group) | consumer 1개 `c1=[0, 1]` → 2개 `c1=[0] c2=[1]` → 3개 `c1=[0] c2=[1] c3=[]` | `PartitionAssignmentTest` |
| 실제 offset 전후 | 재전달 실험: committed `0 → 0 → 1`, 같은 record(offset 0) 전달 2회. listener 실패 실험: 정상 record 뒤 committed `1` → 실패 record(offset 1) 중단 중 `1` 유지 → 재시작 뒤 `2` | `KafkaDeliveryTest`, `OrderConfirmedListenerFailureTest` |
| 남은 DB→broker 누락 구간 | 주문 1 (사례 1): 주문 CONFIRMED·예약 CONSUMED·시도 SUCCESS 인데 broker record 없음·구매 활동 없음. DB commit 과 send 가 원자적이지 않다 → W7 Outbox | `OrderConfirmedDeliveryGapTest` |

### 8-1. commit 뒤 send 직전 누락 구간 (📄 p.3~4, 과제 2절)

두 주문을 같은 W6 직접 발행 경로(`createOrder → confirmOrder → PG SUCCESS → applyCallback → applyFinal commit → publish`)로 확정했다. 고정 clock `2026-09-07T01:00:00.123456789Z`(밀리초 절단 → `.123Z`), 품목은 상품 A × 2·상품 B × 1. 결과 적용 경로는 실제 코드이고 PG 응답만 인메모리 fixture(`FakePaymentGateway`)다.

| 항목 | 사례 1 누락 | 사례 2 정상 |
|---|---|---|
| orderId / providerOrderId | 1 / `LP-ORD-0000000001` | 2 / `LP-ORD-0000000002` |
| transactionKey | `FAKE-TX-000001` | `FAKE-TX-000002` |
| eventId | `order-confirmed:v1:1` | `order-confirmed:v1:2` |
| confirmedAt | `2026-09-07T01:00:00.123Z` | 같음 |
| items | 상품 1 × 2, 상품 2 × 1 | 같음 |
| 주문 / 시도 / 예약 | CONFIRMED / SUCCESS / CONSUMED ×2 | 같음 |
| callback 응답 | settled=true (발행 실패가 주문을 되돌리지 않음) | settled=true |
| broker record | **없음** (topic 전체를 raw consumer 로 확인) | `order-events-OrderConfirmedDeliveryGapTest` partition 0 offset 0, key `"2"` |
| 구매 활동 | **없음** | 장부 1행 + 활동 2행 (streamer 재생) |

- 사례 1 의 중단은 가짜 발행기의 send 직전 hook 이 던진 예외다. Facade 는 이를 ERROR 로그(`OrderConfirmed 발행 중 예상 못한 오류. eventId=order-confirmed:v1:1, orderId=1. 주문은 CONFIRMED 로 유지한다.`)로 남겼다. **실제 프로세스 종료였다면 이 로그도 남지 않는다. 이번 로그는 통제된 hook 으로 멈췄기 때문이다.** 실패 주입을 OS 강제 종료라고 표현하지 않는다.
- 사례 2 의 소비는 두 앱이 별도 Spring Boot 앱이라 한 JVM 에서 이어지지 않는다. api 테스트가 broker 에서 읽은 record(key·JSON bytes·topic/partition/offset)를 녹화해 `apps/commerce-streamer/src/test/resources/experiments/delivery-gap-case2.json` 에 두었고, streamer 의 `OrderConfirmedReplayTest` 가 같은 bytes 를 자기 격리 topic 에 넣어 실제 listener·parser·DB TX·ack 까지 돌린다. W7 은 이 녹화 bytes 와 `OrderConfirmedDeliveryGapTest` 로 W6 경로를 다시 실행할 수 있다. W7 의 새 격리 실행에서는 새 orderId 가 생기며 위 숫자를 맞추지 않는다.
- 재현: `./gradlew :apps:commerce-api:test --tests '*OrderConfirmedDeliveryGapTest'` → `apps/commerce-api/build/delivery-gap/observation.json`, `./gradlew :apps:commerce-streamer:test --tests '*OrderConfirmedReplayTest'`.

### 8-2. DB commit 뒤 offset 미커밋 재전달 (📄 p.6~7, 과제 6절)

격리 topic·group 에서 low-level `KafkaConsumer` 두 개가 실제 `OrderConfirmedEventParser`·`PurchaseActivityService` 를 호출했다. 두 번째 consumer 는 seek·offset reset 을 하지 않았다.

| 순서 | 관찰값 |
|---|---|
| 준비 | 주문 46 (상품 7 × 2) 발행 → partition 0, offset n = 0 |
| 시작 위치 | 첫 consumer 배정 콜백에서 n commit + seek → committed 0 |
| 첫 처리 | poll → DB commit (장부 1행·활동 1행 수량 2). n+1 commit 안 함 → committed 0 |
| 종료 | auto commit false, `close(5초)` → committed 0 (close 가 commit 하지 않음) |
| 재시작 | 같은 group 의 새 consumer → 같은 topic·partition·offset 0 재전달, bytes 동일 |
| 두 번째 처리 | 장부 조회로 no-op → commit 1. 활동 1행·같은 id·수량 2 불변 |

관찰값: 전달 2회(`order-events-KafkaDeliveryTest-0@0` ×2), committed `0 → 0 → 1`. 일부러 `enable.auto.commit=true` 로 바꾸면 close 가 1을 commit 해 재전달이 사라지고 테스트가 실패한다.

### 8-3. 실제 Spring listener 의 실패·재시작 (📄 p.7, 과제 6절 후반)

격리 topic·group 의 실제 컨테이너(record factory, MANUAL_IMMEDIATE, `CommonContainerStoppingErrorHandler`). 정상 주문 46 을 먼저 처리해 committed 1 을 만든 뒤, 주문 47(상품 7 × 1, 상품 99 × 1)의 둘째 품목 저장에 오류를 주입했다.

| 순서 | 관찰값 |
|---|---|
| 실패 | `구매 활동 반영 일시 오류, 100ms 후 재시도 (1/2)` → `200ms 후 재시도 (2/2)` → `구매 활동 반영 일시 오류 재시도 소진 (3회 시도). 컨테이너를 중단합니다` → `Stopped container`. 장부·활동 0행(전체 rollback), committed 1 = 실패 record 의 offset (미진행), `isRunning=false` |
| 오류 제거·재시작 | 같은 컨테이너 `start()` → 같은 record 재전달 1회 → 장부 1행·활동 2행 → committed 2. 주문 46 은 다시 처리되지 않음 |

committed offset 을 먼저 실제 값으로 만들었으므로 재전달은 `auto.offset.reset=earliest` 와 무관하게 저장된 group offset 에서 일어난다. 오류를 제거하지 않고 재시작하면 다시 중단돼 committed 가 2 가 되지 않는다(테스트로 확인).

### 8-4. partition 2개·consumer group 1→2→3 (📄 p.5~6, 과제 5절)

`modules/kafka` 의 순수 JUnit. 격리 topic `partition-test-{uuid}`(partition 2)·격리 group. 여러 consumer 를 한 스레드에서 번갈아 poll 해 rebalance 를 끝냈다.

- 배정표: 1개 `c1=[0, 1]` → 2개 `c1=[0] c2=[1]` → 3개 `c1=[0] c2=[1] c3=[]`. 각 partition 은 한 member 에게만, 3개일 때 하나는 배정 없음.
- partition 0·1 에 명시 전송한 record 는 각 partition 의 담당 consumer(c1, c2)가 읽었다.
- key 매핑: key=42 10회 → 전부 partition 0, key=43 10회 → 전부 partition 0. 같은 key 는 같은 partition 으로 가지만, 다른 key 가 다른 partition 으로 간다고 단언하지 않는다(실제로 둘 다 0 이었다).
- `acks=all` 로 보냈지만 broker 가 1개(복제 1)라 `acks=1` 과 같게 동작한다. 복제 장애 보장은 이 실험으로 확인하지 못했다.

### 8-5. 영구 오류의 DLT 전환 (D12 적용, 6절 예약분)

격리 topic `order-events-OrderConfirmedDeadLetterTest` 와 그 `.DLT` 를 테스트가 만들고, 실제 리스너·실제 DLT producer 로 돌렸다.

| 장면 | 관찰값 |
|---|---|
| 충돌 (주문 48: 7 × 3 저장 뒤 같은 eventId 로 7 × 4) | DLT `…DLT-0@0`, key `48`, value = 원본 bytes. 헤더 `dlt.exception.class=PermanentEventException`, `dlt.exception.message=같은 eventId 에 다른 사실…`, `dlt.original.topic/partition/offset=…Test/0/1`, `dlt.existing.fingerprint=48\|2026-09-07T01:00:00Z\|7:3`, `dlt.new.fingerprint=48\|…\|7:4`. 활동 1행(수량 3) 유지, committed 전진, 컨테이너 실행 중 |
| 깨진 JSON (주문 49) | DLT `…DLT-0@1`, key `49`, value = 원본 bytes, 예외 헤더만 있고 fingerprint 헤더 없음. 장부 없음 |
| DLT 발행 실패 주입 (주문 50 충돌) | `DLT 발행 일시 오류, 100ms 후 재시도 (1/2)` → `200ms 후 재시도 (2/2)` → `DLT 발행 일시 오류 재시도 소진 (3회 시도). 컨테이너를 중단합니다`. committed = 그 record 의 offset(4) 그대로, DLT 추가 없음, 활동 1행(수량 1) 유지 |
| 기존 통합 테스트의 충돌 2건 (주문 43) | `order-events-PurchaseActivityIntegrationTest.DLT` 에 key `43` 2건(수량 4, 다른 eventId), 활동 1행(수량 3) 유지, ack |

로그 예: `영구 오류 record 를 DLT 로 보냈습니다 (구매 활동 없음). dlt=order-events-OrderConfirmedDeadLetterTest.DLT-0@0, original=order-events-OrderConfirmedDeadLetterTest-0@1, key=48, cause=PermanentEventException: 같은 eventId 에 다른 사실이 왔습니다. …` (ERROR). 일부러 DLT 실패를 삼키고 ack 하도록 바꾸면 "중단·offset 미진행" 테스트가 실패한다.

### 8-6. 공통 사항과 한계

- Testcontainers 가 실행 뒤 컨테이너(Kafka·MySQL·Redis)를 자동 정리한다. 임시 DB·topic 을 일주일 유지하지 않으며, 넘기는 것은 재현 코드와 위 관찰값이다.
- 실험 record 는 모두 격리 topic(`order-events-…Test`, `partition-test-…`)에 보냈다. 업무 topic 과 분리돼 가짜 구매 활동을 만들지 않았다.
- 결과 적용 경로(`PaymentFacade → PaymentResultService → OrderConfirmedPublisher`)는 실제 코드이고, PG 응답만 인메모리 fixture 다.
- 통제된 종료의 한계: hook 예외, `close()`, 컨테이너 `start()` 는 OS 강제 종료·프로세스 재기동과 다르다. 특히 `close()` 는 LeaveGroup 을 보내 즉시 rebalance 되지만, 강제 종료면 session timeout 뒤에야 재배정된다.
- 발행 결과 "미확인(UNKNOWN)"은 실제 broker 로 재현하지 않고 mock future 로만 검증했다 (D7).
- 가짜 발행기를 쓰지 않는 기존 api 테스트는 실제 Kafka publisher 로 test broker 의 `order-events` 에 발행한다. 이 topic 은 fixture 가 미리 만든다(앱은 topic 을 만들지 않음).
- 단일 broker 라 복제·ISR 관련 보장은 설명만 가능하고 관찰하지 못했다 (5절 한계 참고).
- DLT 를 읽는 쪽(재처리 consumer, 재투입 도구)은 이번에 없다. DLT 발행 성공의 ERROR 로그가 유일한 알림이다.
- DLT 발행 후 ack 전에 종료되면 같은 record 가 다시 와서 DLT 에 중복으로 들어갈 수 있다. 원본 topic·partition·offset 헤더로 중복을 구분할 수 있다.
- 운영 배포 전 `order-events`·`order-events.DLT` 토픽을 미리 생성해야 한다. 없으면 첫 영구 오류에서 DLT 발행이 실패해 consumer 가 멈춘다 (메시지 손실은 없음).