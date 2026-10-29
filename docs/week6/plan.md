# 주문 확정 이벤트와 구매 활동 — Kafka (W6)

> **이 문서의 자리** — W6의 Plan·Design 산출물이다. W5 `docs/week5/plan.md`(PG 결과 적용 경계 `PaymentResultApplier`, ADR-W5-04)에서 이어진다.
>
> **목표** — PG로 처음 확정된 주문을 `OrderConfirmed` 이벤트로 `order-events`에 발행하고, `commerce-streamer`가 상품 구매 활동으로 저장한다. 같은 record가 다시 전달돼도 구매 활동은 한 번만 남긴다. 랭킹 API와 Outbox는 이번 범위가 아니다(Outbox는 W7).
>
> **표기** — ✅ 합의 · 🔧 AI 기본값 · 🤔 결정 필요.
>
> **진행 방식** — 설계 항목 D-1 ~ D-9를 하나씩 학습자와 정한다. 아직 다루지 않은 항목은 비워 둔다.

## 0. 체크리스트 — 모두 통과해야 한다

| # | 체크리스트 (과제 원문 요약) | 증거 | 상태 (10-29 저녁) |
|---|---|---|---|
| C-1 | 실제 PG 확정 주문에서 stable 이벤트를 발행하고 구매 활동으로 저장한다 | api `OrderConfirmedPublishIntegrationTest`(PG 결과 적용 → 발행 기록 1건 · payload = DB 사실 · 견본과 같은 모양 · broker에 원문 그대로 · partition·offset 표시). streamer `OrderConfirmedListenerIntegrationTest#consumesTheSharedFixture`(견본 원문 → 활동 2행 · ack). 공유 견본 `modules/kafka` testFixtures `fixtures/order-confirmed-v1.json` | ✅ 자동 테스트 통과 + 전체 흐름 실행(10-29, 주문 2 → order-events-2@0 → 활동 2행, incident-w6.md) |
| C-2 | 대기·실패·rollback·중복 결과 적용에서 거짓 이벤트나 새 결제가 생기지 않는다 | `OrderConfirmedPublishIntegrationTest$NoEvent`: PENDING·FAILED·확정 TX rollback → 기록 0 · record 0, reconcile 두 번 → 결제 단계(ALREADY_APPLIED)에서 막혀 기록 1 · record 1 · PG 생성 호출 그대로, **결제 PENDING인데 주문이 이미 CONFIRMED → 기록 0**(`if (changed)` 가드 — 가드를 지우면 실패함을 확인) | ✅ 통과 (R-5 반영) |
| C-3 | 저장된 구매 사실을 이후 랭킹 입력으로 쓸 수 있고 민감정보를 보내지 않는다 | `OrderConfirmedTest#carriesNoSensitiveOrExtraFields`, `OrderConfirmedPublishIntegrationTest#payloadHasTheSameShapeAsTheSharedFixture`, `PurchaseActivityServiceIntegrationTest#sumsQuantityPerProductAcrossOrders`(주문 42·43 → 3행, 상품 7 합 5) | ✅ 통과 |
| C-4 | partition 2개와 같은 group의 consumer 1→2→3 실제 배정을 확인했다 | E-1 `PartitionAssignmentExperimentTest` — [0,1] → [0]·[1] → [0]·[1]·[] | ✅ 통과 |
| C-5 | 같은 record가 DB commit 뒤 미커밋 offset에서 재전달됨을 관찰했다 | E-2 `RedeliveryAfterDbCommitTest` — 같은 위치 2회 전달, committed 0 → 0 → 1, 활동 2행 불변. 전체 흐름 실행에서도 재투입 record no-op · committed 1 → 2 | ✅ 통과 |
| C-6 | 중복 방지와 모든 품목 저장이 원자적이며 같은 사실·충돌을 구분한다 | `OrderConfirmedMessageTest`·`ConsumedOrderConfirmationTest`(TDD), `PurchaseActivityServiceIntegrationTest`(no-op · 충돌 2종 · **두 스레드가 모두 SELECT를 지난 뒤 INSERT하게 강제한** 동시 중복 — 1062 재판정을 지우면 실패 · 두 번째 품목 실패 전체 rollback), listener 충돌 → 1+3번 시도 → DLT, `OrderConfirmedMessageParserTest`(1.9 · 문자열 숫자 · 단일 객체 items 거절) | ✅ 통과 (R-4 · R-6 반영) |
| C-7 | 실제 listener의 DB 실패·error handler·ack·재시작 결과를 확인했다 | E-3 `OrderConfirmedListenerIntegrationTest#retriesThenDeadLettersAndReplaysOnce` — 1+3번 시도(도중 committed offset ≤ n — 처리 전 ack로 바꾸면 실패) → DLT · 원래 offset n+1 · 0행 → DLT 재생 1번 저장 → 재생 반복 no-op. 계약 위반·충돌도 1+3번 뒤 DLT | ✅ 통과 (R-2 · R-3 반영). 과제 예시(중단형)와 다른 DLT 정책이라 검증 순서를 바꿨다 (ADR-W6-06) |
| C-8 | 직접 after-commit 발행의 누락 구간을 재현하고 주문 상태를 되돌리지 않는다 | E-4 `S13PublishGapScenarioTest` — send 직전 hook: CONFIRMED · CONSUMED · published_at null · record 없음 · 누락 조회에 잡힘. 전송 실패 주입(비동기 실패 · **send() 동기 예외** 둘 다): last_error · PG 재호출 없음 | ✅ 통과 (R-1 반영) |
| C-9 | 미발행 1건·이미 소비된 1건의 재현 코드와 실제 사건 사실을 남기고 임시 DB는 정리했다 | E-4 + `docs/week6/incident-w6.md` 사건 표. 컨테이너는 Ryuk가 정리 | ✅ 미발행 1건(E-4) · 이미 소비된 1건(전체 흐름 실행: 주문 2를 다시 넣어 no-op, committed 1 → 2)의 사실을 incident-w6.md에 기록. 실험 DB `loopers_w6`는 E-6용으로 남기고 seed로 비운다 |
| C-10 | 실제 설정·broker 주소·broker 구성의 한계를 설명할 수 있다 (과제는 단일 broker, 이번 구성은 3대) | D-5 설정표, `KafkaClusterSettingsTest`(Kafka가 해석한 acks·idempotence·max.block.ms, topic RF·ISR·min.insync), E-5 `BrokerFailureReplicationTest`, incident-w6.md "증명하지 않은 것" | ✅ 설명 근거는 갖춤. E-6(`max.block.ms` 부하 측정)은 과제 밖 추가 실험 — 키트 `docs/week6/k6/` 준비, 학습자 실행 대기 |
| C-11 | 관련 회귀·lint·ArchUnit을 실행하고 테스트 자원을 정리했다 | 10-29 밤: `:apps:commerce-api:check` 476 · `:apps:commerce-streamer:check` 37 · 실패·오류·skip 0, `check -x test` 통과 (Checkstyle·ArchitectureTest 포함). 앞선 api 실행 1건 실패(`ProductApiE2ETest` Redis timeout)는 다른 세션의 부하(load 345) 중이었고, 재실행에서 통과. 테스트 컨테이너는 Ryuk가 정리 | ✅ 통과 |

## D-1. 왜 이벤트로 나누나 — 경계와 실패 지점 ✅

### 흐름과 경계

```
[commerce-api]                                   [Kafka]              [commerce-streamer]
PG SUCCESS 확인
  → TX: 주문 CONFIRMED + 예약 CONSUMED  ──commit──→ send ──→ order-events ──poll──→ TX: 완료 기록 + 구매 활동 ──commit──→ offset commit
        ①                                    ②            ③                         ⑤                         ④
```

경계는 세 개다. api DB commit, broker 기록, streamer DB commit은 서로 원자적으로 묶이지 않는다. offset commit은 streamer DB commit과 또 다른 쓰기다.

### 같은 TX(가) 대신 이벤트(나)를 고른 이유

| | (가) 같은 TX에서 구매 활동 INSERT | (나) 확정 commit 뒤 이벤트 |
|---|---|---|
| 구매 활동 저장이 실패하면 | 주문 확정까지 rollback → PG는 성공했는데 주문은 PAYMENT_PENDING | 주문 확정은 남는다. 구매 활동만 재시도 대상 |
| 후속 기능이 늘면 | 결제 결과 적용 TX에 코드·잠금 시간이 계속 붙는다 | 주문은 자기 확정 사실만 알린다. 소비자가 늘어도 주문 코드는 그대로 |
| 잃는 것 | — | **원자성**: 확정은 됐는데 이벤트가 없을 수 있고(②), 같은 효과가 두 번 올 수 있다(④) |

- (가)의 문제는 "외부 API가 느려서"가 아니다. 구매 활동 INSERT도 같은 MySQL이다. 문제는 **부가 기능의 실패가 핵심 기능을 되돌린다**는 것이다.
- "외부가 느리면 잠금을 오래 쥔다"는 Kafka `send`를 주문 TX 안에서 부를 때의 문제다 → D-3.

### 실패 지점마다 남는 상태

committed offset은 "group이 다음에 읽을 위치"다. 처리할 record의 offset을 n이라 한다. lag = log end offset − committed offset.

용어 주의 — 이름이 겹친다.
- **재고 예약 CONSUMED**: W5 재고 예약이 판매로 확정된 상태. commerce-api가 주문 확정 TX에서 바꾼다. Kafka consumer와 무관하다.
- **producer `acks`**: broker가 producer에게 "replica 몇 개에 기록했다"고 답하는 기준 (D-5). 복제와 관련.
- **consumer ack** (`Acknowledgment.acknowledge()`): consumer가 "n까지 처리 완료"라며 offset n+1을 commit하는 요청 (D-7). 복제와 무관.

| 지점 | 주문·예약 (api DB) | Kafka record | 구매 활동 (streamer DB) | committed offset | 결과 | 막는 것 |
|---|---|---|---|---|---|---|
| ① 주문 TX commit 전 실패 | rollback → PAYMENT_PENDING·RESERVED | 없음 | 0행 | — | 정상 (확정 사실이 없음) | 발행은 commit 뒤에만 (D-3) |
| ② 주문 commit 후 ~ send 전 | CONFIRMED·CONSUMED | 없음 | 0행 | — | **누락** | 이번 주 재현만, W7 Outbox |
| ③ broker 기록 후 ~ consumer 처리 전 | CONFIRMED·CONSUMED | offset n에 있음 (retention 동안 남음) | 0행 | n (lag 1) | **지연 → 정상**. 재시작하면 n부터 읽는다 | committed offset이 있는 group. 처음 뜨는 group이면 `auto.offset.reset`이 결정 (D-7) |
| ④ consumer DB commit 후 ~ offset commit 전 | CONFIRMED·CONSUMED | offset n | 있음 | n | **재전달** | 멱등 consumer (eventId 유일 제약, 같은 TX) (D-6) |
| ⑤ consumer DB TX 중간 실패 | CONFIRMED·CONSUMED | offset n | 0행 (완료 기록·첫 품목도 rollback) | n (ack 안 함) | **재전달(재시도)** | 같은 TX, 실패 record를 건너뛰지 않는 error handler (D-7) |

- ③·⑤가 "정상·재시도"인 데는 조건이 있다. ③은 group에 committed offset이 있어야 하고 retention 안에 돌아와야 한다. ⑤는 error handler가 실패 record를 recover 후 commit해 **건너뛰지 않아야** 한다. 조건이 깨지면 둘 다 누락이 된다.
- 지금 `modules/kafka` 설정은 `auto.offset.reset: latest`다. committed offset이 없는 새 group이 latest로 시작하면 그 전에 쌓인 record를 읽지 않는다 → D-7에서 정한다.

> D-7로 넘긴 질문 — ⑤에서 error handler가 "재시도 3번 뒤 로그만 남기고 진행"이면 구매 활동 0행, committed offset n+1, 결과 **누락**이다. 학습자 제안: 이 경우 DLQ(DLT)로 보낸다. DLT도 원래 topic의 offset은 n+1로 진행한다(건너뛰되 따로 보관). 일시 장애(DB 다운)와 영구 실패(파싱 불가·payload 충돌)를 나눠 정한다.
>
> 학습자 의견 (10-28) — 일시 장애도 DLT로 보내고 알림으로 사람이 개입한다(재발행 또는 처음부터 재처리). 기준은 topic의 중요도에 따라 다르다(예: 푸시 알림은 중복 발송이 치명적이지 않아 재발행해도 무방). DLT에 왔다는 것 자체가 재시도에도 실패했다는 뜻이므로 사람이 볼 신호다. AI 반론: DB 다운으로 DLT에 간 record는 DB가 복구되면 성공할 record라 영구 실패와 성격이 다르고, DLT로 넘기면 복구 후 재생 도구와 순서 문제가 남는다. → D-7에서 구매 활동 topic의 중요도를 기준으로 정한다.

## D-2. 이벤트 계약 ✅

### 필드 🔧 (과제 계약을 그대로 옮김)

| 항목 | 값 | 근거 |
|---|---|---|
| topic | `order-events` (테스트는 고유 suffix로 격리) | 과제 |
| eventType / schemaVersion | `OrderConfirmed` / `1` | 과제 |
| key | `String.valueOf(orderId)` — 같은 주문은 같은 partition | 과제 |
| eventId | 최초 확정 때 저장한 UUID — 아래 ADR-W6-01 | ✅ |
| orderId / confirmedAt | `orders.id` / `orders.confirmed_at` (최초 확정 시각, ISO-8601 + offset) | 과제. `confirmedAt`은 W5부터 저장 중 |
| items | `[{productId, quantity}]`, productId 오름차순 | 주문 생성 때 `OrderLines.of`가 같은 상품을 이미 합친다. 수량은 양수 |
| 넣지 않는 것 | 카드·transactionKey·userId·가격 | 랭킹 입력에 필요 없음, 민감정보 (C-3) |

```json
{ "eventId": "3f1c…(UUID)", "eventType": "OrderConfirmed", "schemaVersion": 1,
  "orderId": 42, "confirmedAt": "2026-10-28T14:03:12.123456+09:00",
  "items": [ {"productId": 7, "quantity": 2}, {"productId": 8, "quantity": 1} ] }
```

JSON 속성 순서·items 배열 순서가 달라도 같은 사실이다. 비교는 정렬한 items와 고정 필드로 한다 (D-6).

### ADR-W6-01. eventId는 최초 확정 TX에서 만든 UUID를 저장해 쓴다 ✅

| | (A) 유도 `order-confirmed:v1:{orderId}` | **(B) 저장 UUID — 채택** |
|---|---|---|
| ID의 출처 | 코드의 규칙 | DB에 저장된 값 |
| 규칙이 바뀌면 | 과거 사실의 ID도 바뀐다 → 다시 보낸 옛 이벤트가 새 사실처럼 보인다 | 영향 없음 |
| 전제 | 주문 하나에 확정 사실은 하나 | 없음 |
| 비용 | 없음 | 저장할 곳이 필요하다 (→ 발행 테이블, ADR-W6-03). 이전 POINT 확정 주문은 행이 없다 (소급 발행 없음) |
| consumer의 orderId 유일 검사 | eventId와 1:1이라 우리 producer로는 충돌이 생길 수 없다 | ID를 다시 만드는 버그가 나면 "같은 orderId, 다른 eventId"를 실제로 잡는다 |
| W7 Outbox | 행을 만들 때 같은 규칙으로 계산 | 저장한 값을 outbox 행의 event_id로 옮긴다 |

- 결정 이유 (학습자): 컬럼 하나의 비용으로 ID가 코드 규칙에서 독립한다. 멱등성의 강도는 같다 — 중복을 막는 것은 consumer의 eventId 유일 제약(D-6)이다.
- A도 과제가 허용하는 방식이라 대안으로 기록한다.
- 🔧 UUID는 응용 계층에서 만들어 발행 행을 만들 때 넘긴다 (도메인이 난수에 의존하지 않게, 테스트에서 고정 가능). 저장 위치는 발행 테이블 (ADR-W6-03 "어디에 쓰나").

## D-3. 발행 시점과 위치 ✅

### ADR-W6-02. 확정 TX 안에서 Spring 이벤트를 내고, AFTER_COMMIT listener가 Kafka로 보낸다

| | **(a) AFTER_COMMIT listener — 채택** | (b) commit 뒤 orchestration |
|---|---|---|
| 방법 | `applyToOrder`에서 `OrderConfirmedEvent`를 `publishEvent` → `@TransactionalEventListener(AFTER_COMMIT)`가 send | `newTransaction.execute(...)`가 "이번에 확정됨 + payload"를 돌려주고, 그 바깥(=commit 뒤)에서 send |
| 결과 적용 진입점 (callback·reconcile·생성 응답·만료 등) | 모두 `applyToOrder`를 지나므로 한 곳만 바꾼다 | 진입점마다 반환값을 바꾸고 send를 붙인다 |
| W7 Outbox 전환 | 이미 TX 안에서 이벤트가 나온다 → 같은 TX에서 outbox 행을 쓰도록 바꾸기만 하면 된다 | TX 밖으로 꺼낸 payload를 다시 TX 안으로 옮겨야 한다 |
| 위험 | 발행이 흐름에서 숨는다. TX 없는 곳에서 publish하면 listener가 조용히 실행되지 않는다(`fallbackExecution=false`) | 새 진입점에서 send를 빠뜨리기 쉽다 |

- 결정 이유 (학습자): 덜 뜯어고친다 — 지금(진입점 한 곳)과 W7(Outbox 전환) 모두.
- `fallbackExecution`은 기본값 false로 둔다. 확정은 항상 TX 안에서 일어나야 하므로, TX 밖 publish가 조용히 무시되는 것을 테스트로 드러낸다(목록 캐시 listener는 true를 썼지만 성격이 다르다).
- TX 안에서 `kafkaTemplate.send`를 직접 부르지 않는다 — rollback돼도 record가 남고(거짓 이벤트), broker가 느리면 결제·주문·재고 잠금을 쥔 채 기다린다.

### payload는 TX 안에서 값으로 만든다 🔧

- 이벤트 객체에 `OrderModel`(entity)을 넣지 않는다. `items`는 `@OneToMany`(기본 LAZY)이고 `open-in-view: false`라서, commit 뒤 listener에서 `order.getItems()`를 부르면 detached entity의 lazy 로딩이 실패한다.
- 그래서 TX 안에서 eventId·orderId·confirmedAt·items를 꺼내 **값**으로 담는다 → lazy 문제 해결.
- 그 값을 **불변 record**로 둔다 → commit된 사실과 보내는 payload가 그 사이에 달라질 수 없다.

### Kafka로 보내는 흐름은 이번 주 하나다

이번 과제에서 Kafka로 나가는 것은 `OrderConfirmed` 하나다. 다른 in-process 이벤트(`BrandChanged`·`ProductChanged`·`ProductLikesChanged` → 목록 캐시, `PaymentRequested` → 발송)는 그대로 둔다. 그래서 eventId 방식 A·B를 두 흐름에 나눠 써 볼 자리는 없다 (ADR-W6-01은 B 하나).

## D-4. send 결과 관찰과 발행 실패 ✅

### 비동기 send ✅

- AFTER_COMMIT listener는 commit을 부른 스레드(PG callback 요청·reconcile 스케줄러)에서 돈다. `send(...).get()`으로 기다리지 않고 `send(...).whenComplete(...)`로 결과를 받는다.
- 성공: `RecordMetadata`의 topic·partition·offset을 eventId·orderId와 함께 남긴다.
- 실패: 주문·결제·예약을 되돌리지 않고 PG를 다시 부르지 않는다. 실패를 eventId·orderId와 함께 드러낸다 (ADR-W6-03: outbox 행의 `last_error`).
- 실패가 콜백으로 오는 시점은 즉시가 아니다 — producer 내부 재시도 뒤 `delivery.timeout.ms`(기본 120s)가 지나야 온다.

### `max.block.ms` — 시작값 1s 🔧, 실험으로 조정

비동기여도 `send()` 호출 자체가 막히는 경우가 있다: topic metadata를 못 얻을 때(네트워크 단절·주소 설정 실수·broker 전체 장애)와 producer 버퍼(`buffer.memory` 32MB)가 찼을 때. 그동안 최대 `max.block.ms`(기본 60s) 막힌다. broker 1대 장애는 새 leader로 넘어가므로 해당하지 않는다.

시작값 근거 — W5 부하 가정(0-1)과 Tomcat `threads.max: 200`:

| `max.block.ms` | 결제 100 TPS에서 막혀 있는 callback 스레드 수 (= TPS × 막힘 시간) | Tomcat 200 대비 |
|---|---|---|
| 60s (기본) | 6,000 | 몇 초 안에 고갈 → 다른 API까지 멈춤 |
| 2s | 200 | 꽉 참 |
| **1s** | **100** | 절반 — 다른 API가 살아 있다 |

정상일 때 metadata는 처음 한 번 받아 캐시하고 수 ms면 끝나므로, 1s는 정상 경로를 막지 않는다. 실제 값은 D-8 실험(broker 전체 중단 + callback 부하에서 p99·busy 스레드 측정)으로 확인하고 고친다. 예측 → 측정 표로 남긴다.

### ADR-W6-03. 발행 결과를 DB에 기록한다 ✅

| | (1) ERROR 로그 | (2) 로그 + 실패 metric | **(3) 로그 + DB 기록 — 채택** |
|---|---|---|---|
| 빠진 주문 찾기 | 로그 검색 | 건수만 안다, 어느 주문인지는 로그 | **쿼리로 바로** |
| 재시작 뒤 | 다시 셀 수 없음 | 카운터 초기화 | 남아 있다 |
| 비용 | 없음 | 작음 | 테이블·컬럼, 콜백에서 DB 쓰기 |

- 결정 (학습자, 10-28): (3).
- 범위 경계 — 과제는 "이번 주 Outbox를 구현하지 않는다". 그래서 **기록까지만** 하고 자동 재발행(relay)은 만들지 않는다. 기록이 W7 Outbox의 출발점이 된다.
- 한계 — 프로세스가 commit 뒤 send 전에 죽으면 send 콜백이 오지 않는다. 그래서 실패가 아니라 성공을 기록한다 (아래).
- 누락 재현 실험(C-8)과의 관계 — 전송 실패 주입은 실패 기록이 남는 경우, send 직전 중단 hook은 콜백이 오지 않는 경우를 보여 준다. 두 결과를 함께 남긴다.
- 🔧 주의 — `whenComplete` 콜백은 producer I/O 스레드(`kafka-producer-network-thread`)에서 돈다. 여기서 DB를 쓰면 그 스레드가 막혀 **모든 send가 늦어진다**. DB 기록은 별도 executor로 넘긴다(`whenCompleteAsync(…, executor)` 등).

### 무엇을 기록하나 — 성공으로 탐지, 실패 원인은 진단 ✅

> 용어 — 여기서 "콜백"은 **producer send 콜백**(`whenComplete`, 우리 JVM 안에서 Kafka 클라이언트가 부르는 함수)이다. W5의 **PG callback**(PG가 우리 API로 보내는 HTTP 웹훅)과 다르다.

| | (i) 실패만 기록 | **(ii) 성공을 기록 — 채택** |
|---|---|---|
| 기록 | send 콜백이 실패를 알릴 때 실패 행 | send 콜백이 성공을 알릴 때 `published_at`(+ topic·partition·offset), 실패면 `last_publish_error` |
| 빠진 주문 찾기 | 실패 행이 있는 주문 | `CONFIRMED AND PG AND published_at IS NULL AND confirmed_at < now − 5분` |
| 추가 쓰기 | 실패할 때만 | 확정마다 1번 (피크 100 TPS, 별도 executor라 응답 경로 밖) |

같은 3건으로 본 차이:

| 주문 | 일어난 일 | send 콜백 | (i) 결과 | (ii) 결과 |
|---|---|---|---|---|
| 42 | 정상 발행 | 성공 | — | `published_at` 있음 |
| 43 | broker 장애로 send 실패 | 실패 | **잡음** (실패 행) | **잡음** (null + 오류) |
| 44 | commit 직후 프로세스 종료 | **오지 않음** | **놓침** — 흔적 없음 | **잡음** (null, 오류도 null) |

- 결정 (학습자, 10-28): (ii). (i)은 "무슨 일이 일어났을 때"만 남겨서, 아무 일도 일어나지 않은 44(=C-8 누락 재현 장면)를 볼 수 없다. (ii)는 "확정된 모든 주문 중 표시 없는 것"을 찾으므로 44도 잡는다.
- 판정은 `published_at` 하나로 한다. `last_publish_error`는 왜 빠졌는지(43: 원인 있음, 44: 콜백조차 없음)만 알려 준다.
- 정석 Outbox와 같은 모양이다 — "보내야 할 것(=CONFIRMED 주문)" + "보냄 표시". relay만 없다(W7).
- 보상 = 같은 eventId로 재발행 (주문은 되돌리지 않는다). 이번 주는 쿼리로 보이는 데까지.
- 성공 기록 직전에 죽으면 "보냈는데 안 보냄"으로 보여 W7에서 중복 발행될 수 있다 → consumer 멱등(D-6)이 흡수한다.
- 🔧 대기 5분 — `delivery.timeout.ms`(기본 120s)보다 길게 둬서 콜백을 기다리는 중인 주문을 빼낸다.

### 어디에 쓰나 — 별도 발행 테이블 ✅

확정 TX에서 발행 행을 `published_at = null`로 INSERT하고, send 콜백 성공 때 표시한다. 테이블 모양은 아래 "범용 outbox 테이블"에서 정한다.

| | orders 컬럼 | **별도 발행 테이블 — 채택** |
|---|---|---|
| 책임 | 주문이 Kafka 전달 상태(topic·partition·offset·오류)까지 안다 | 주문은 확정만, 전달 상태는 발행 테이블 |
| 이벤트가 늘면 (취소·환불…) | 종류마다 orders 컬럼·조회 쿼리가 는다 | 행의 종류만 다르다 |
| 갱신 충돌 | 콜백의 `published_at` UPDATE와 주문 업무 갱신이 같은 행을 다툰다. JPA는 기본적으로 모든 컬럼을 UPDATE하므로, 콜백 전에 주문을 읽은 TX가 나중에 save하면 `published_at`을 null로 되돌린다 | 다른 행이라 다투지 않는다 |
| W7 Outbox | relay가 orders에서 payload를 다시 만든다 | 행이 거의 그대로 outbox 행이 된다 |
| 비용 | 확정 TX 쓰기 증가 없음 | 확정 TX에 INSERT 1번 |

- 결정 (학습자, 10-28): 확장을 미리 고려한다 → 별도 테이블.
- Outbox가 반드시 별도 테이블이어야 하는 것은 아니다. 필수 조건은 "보내야 할 것을 업무 변경과 같은 TX에 남김"이고, 별도 테이블은 relay 범용화·payload 고정 때문에 표준이 됐다.
- 과제 범위 설명 (PR에 적는다) — relay(미발행 행을 읽어 다시 보내는 주체)가 없으므로 이번 주 구현은 Outbox가 아니다. 발송은 여전히 AFTER_COMMIT 직접 발행 하나뿐이고, 행은 결과 기록·누락 조회용이다.
- 🔧 ADR-W6-01의 eventId 저장 위치를 orders 컬럼에서 이 테이블로 옮긴다. eventId가 "이 발행"의 신원이므로 같은 행에 두는 게 맞고, orders에 Kafka 관련 컬럼이 하나도 남지 않는다. "주문당 확정 이벤트 하나"는 상태 가드 + consumer 충돌 검사로 지킨다 (아래).

### 범용 outbox 테이블 ✅ · 정확성은 상태 가드 + consumer 충돌 검사 ✅

- 결정 (학습자, 10-28): W7에 relay를 붙이므로 범용으로 간다 — relay 하나가 모든 이벤트 종류를 보낸다. 정확성(주문당 확정 하나)도 있어야 한다.
- 정확성 (학습자, 10-29): **(a) 주문 상태 가드만** 쓴다. `PAYMENT_PENDING → CONFIRMED`는 한 번만(W5 코드·테스트). 가드가 뚫려 outbox 행이 2개(UUID X·Y) 생기면 consumer가 `orderId`로 조회해 **충돌**로 드러낸다(D-6). 아래 `dedup_key`는 버그를 producer에서 더 일찍 잡는 대안으로 기록만 하고, 이벤트 종류가 늘면 다시 본다.
- 범용 키 `UNIQUE(aggregate_type, aggregate_id, event_type)`는 같은 대상에 여러 번 나는 이벤트(예: 좋아요 변경)에 걸 수 없다. 그래서 **종류별 규칙을 값으로 받는 `dedup_key` 컬럼**을 둔다.

| 컬럼 | 예 (주문 42 확정) | 제약 |
|---|---|---|
| `event_id` | UUID (ADR-W6-01) | PK |
| `aggregate_type` · `aggregate_id` | `ORDER` · `42` | 조회 인덱스 |
| `event_type` | `OrderConfirmed` | |
| `dedup_key` (대안, 채택 안 함) | `OrderConfirmed:ORDER:42` | **UNIQUE, NULL 허용** — 한 번만 나야 하는 이벤트만 값을 넣는다. 여러 번 나도 되는 이벤트는 NULL (MySQL UNIQUE는 NULL 여러 개를 허용) |
| `payload` | 원문 JSON | `TEXT` |
| `created_at` · `published_at` · topic·partition·offset · `last_error` | | 미발행 조회 인덱스 (`published_at`, `created_at`) |

- (대안 기록) `dedup_key`를 쓰면 가드를 우회한 버그가 두 번째 확정 행을 만들 때 확정 TX 전체가 rollback된다 — consumer까지 가기 전에 막는다.
- 🔧 컬럼 이름·인덱스는 구현 때 확정한다.

### 학습 메모 — 한 행을 쓰는 주체가 둘이면 서로 덮어쓴다

orders에 `published_at`을 두면 같은 행을 두 주체가 쓴다.

| 쓰는 주체 | 언제 | 스레드 | 잠금 |
|---|---|---|---|
| 주문 업무 TX (확정, 앞으로의 환불 등) | 업무 요청 | 요청·스케줄러 스레드 | `FOR UPDATE` |
| 우리 앱의 producer **send 콜백** (`published_at` 기록) | broker 응답 | 콜백 → executor 스레드 | 주문 잠금 규칙을 모른다 |

JPA는 save할 때 **읽어 둔 값으로 모든 컬럼을 UPDATE**한다(`@DynamicUpdate` 없을 때). 그래서:

```
t1  send 콜백: 주문 42를 읽음 (status = CONFIRMED)
t2  환불 TX:  주문 42를 REFUNDED로 바꾸고 commit
t3  send 콜백: published_at만 바꿔 save → 모든 컬럼 UPDATE → status = CONFIRMED  (Kafka 콜백이 환불을 되돌림)
```

반대 순서면 업무 TX가 콜백이 찍은 `published_at`을 null로 되돌린다. 문제는 "주문 상태가 들어 있어서"가 아니라 **시점·스레드·잠금 규칙이 다른 두 주체가 한 행을 쓰는 것**이다. 행을 나누면 각자 자기 행만 쓴다.

같은 이유로 "바뀌는 이유"도 둘이 된다 — 주문 규칙 변경(부분 취소)과 메시징 변경(재시도 횟수 기록, 새 이벤트 종류)이 모두 `OrderModel`·orders 마이그레이션을 부른다 (SRP). topic·partition·offset 같은 adapter의 단어가 도메인 필드가 되면 메시징 기술을 바꿀 때 도메인도 바뀐다 (헥사고날 분리 깨짐).

### 누가 전달 상태를 아나 🔧

- 주문(`OrderModel`·`orders`) — 확정 사실만 안다. 전달 상태를 모른다.
- 발행 테이블 — 전달 상태(published_at·topic·partition·offset·last_error)를 가진다.
- 이 테이블을 쓰는 것은 두 곳뿐이다: 확정 TX에서 행을 만드는 발행 기록(응용 계층, 출력 포트 뒤), send 콜백에서 결과를 표시하는 Kafka 발행 adapter. 주문 도메인은 둘 다 모른다.

### payload 원문을 저장한다 ✅

- 결정 (학습자, 10-28): 저장한다. 재발행(W7)이 처음과 같은 바이트를 보내야 하고, 외부와 주고받는 원문은 그대로 남기는 게 기본이다(웹훅 원문 저장과 같은 이유).
- 확정 TX 안에서 **한 번** 직렬화한 문자열을 행에 저장하고, AFTER_COMMIT listener도 그 문자열을 그대로 보낸다. 저장한 것과 보낸 것의 출처가 같다.
- 🔧 컬럼 타입은 `TEXT`(utf8mb4)다. MySQL `JSON` 타입은 내부 이진 형식으로 바꿔 저장하고 읽을 때 키 정렬·공백을 다시 만든다 → 원문 바이트가 보존되지 않는다.
- 🔧 함정 — `modules/kafka`의 producer `value-serializer`는 `JsonSerializer`다. 이미 JSON인 문자열을 넘기면 `"{\"eventId\"…}"`처럼 한 번 더 감싼다. 이 발행에는 `StringSerializer`(또는 bytes)를 쓴다 (D-7에서 consumer 역직렬화와 함께 확정).
- 흔한 outbox 행 모양 (Debezium outbox 규약도 비슷하다): `id(eventId)`, `aggregate_type`, `aggregate_id`, `event_type`, `payload`, `created_at`, `published_at`(또는 status).

## D-5. Kafka 클러스터 — broker 3대 ✅

### "2n+1"이 적용되는 곳과 아닌 곳

| | 무엇 | 규칙 | 3대일 때 |
|---|---|---|---|
| controller quorum (KRaft) | 클러스터 metadata (leader·topic) | Raft 과반수 — 2n+1대면 n대 장애까지 | 1대 장애까지 |
| partition 복제 (데이터) | record 사본 | 과반수가 아니다. ISR·`min.insync.replicas`·`acks`로 정한다 | 아래 ADR |

### ADR-W6-04. RF 3 · `min.insync.replicas` 2 · `acks=all` · idempotent producer ✅

| 설정 | 1대 장애 | 2대 장애 | "성공" 응답의 의미 |
|---|---|---|---|
| **(ㄱ) `acks=all` · min.insync 2 — 채택** | 쓰기 성공, 읽기 됨, 유실 없음 | 쓰기 **거절**(`NotEnoughReplicasException`), 읽기 됨, 유실 없음 (최악은 partition offline — `unclean.leader.election.enable=false`) | 최소 2대에 복제됨 |
| (ㄴ) `acks=1` · min.insync 1 | 쓰기 성공, 유실 가능 | 쓰기 성공, 유실 가능 | leader 1대에만 있음 (`min.insync.replicas`는 `acks=all`일 때만 의미) |

(ㄴ)의 유실 장면: leader 기록 → producer에 성공 응답 → follower 복사 전 leader 사망 → 새 leader에 record 없음.

(ㄴ) `acks=1` 유실 시간표 — 주문 42, broker B1(leader)·B2·B3

| 시각 | 일어난 일 | B1 | B2 | outbox 행 | 구매 활동 |
|---|---|---|---|---|---|
| t1 | 앱이 B1에 send | — | — | published_at null | 없음 |
| t2 | B1이 기록하고 **복사를 기다리지 않고** "성공" 응답 | 있음 | 없음 | null | 없음 |
| t3 | send 콜백이 성공을 받아 `published_at` 찍음 | 있음 | 없음 | **14:03** | 없음 |
| t4 | B1 사망 (B2·B3 복사 전) | ✖ | 없음 | 14:03 | 없음 |
| t5 | B2가 새 leader | ✖ | **없음** | 14:03 | 없음 |
| t6 | streamer가 B2에서 읽음 → 주문 42 없음 | ✖ | 없음 | 14:03 | **없음** |
| t7 | (W7) relay가 `published_at IS NULL`을 찾음 → 주문 42는 대상 아님 | ✖ | 없음 | 14:03 | **영영 없음** |

핵심: t2에서 "성공"이 복사보다 먼저 나간다. 실제(이벤트 없음)와 기록("보냈음")이 어긋나고 아무도 모른다.
(ㄱ) `acks=all`이면 t2에서 B2 복사가 끝나야 "성공"이 나간다. 복사 전에 B1이 죽으면 성공이 오지 않아 콜백이 실패 → `published_at` null → relay가 다시 보낸다. 복사 후에 죽으면 B2에 이벤트가 있다.

- 결정 이유 (학습자, 10-28) — ADR-W6-03은 "send 콜백 성공 = 안전하게 저장됨"을 전제로 `published_at`을 찍는다. (ㄴ)에서는 유실된 record도 성공 콜백을 받아 `published_at`이 찍힌다 → relay(`published_at IS NULL`)가 찾지 못하는 **아무도 모르는 유실**이 된다. (ㄱ)에서는 2대 장애 때 콜백이 실패로 오고 `published_at`이 null로 남아 W7 relay가 다시 보낸다 — 유실이 "미발행 기록"으로 바뀐다.

| 설정 | 값 |
|---|---|
| broker · controller | 3대, 모두 `broker,controller` 역할 (quorum 3) |
| `order-events` | partition 🔧(D-8), RF 3, `min.insync.replicas` 2 |
| 내부 topic (`__consumer_offsets`) | RF 3, min ISR 2 — committed offset도 같은 내구성 |
| producer | `acks=all`, `enable.idempotence=true` (Kafka 3.x 클라이언트 기본값이지만 명시) |
| `unclean.leader.election.enable` | false (기본값) — 뒤처진 replica를 leader로 올려 유실하느니 멈춘다 |

### 어디서 3대를 띄우나 ✅

| 용도 | 방식 | endpoint | 정리 책임 |
|---|---|---|---|
| 실험·로컬 실행 | `docker/infra-compose.yml`의 kafka를 **3대로 변경** (따로 두지 않는다) | `localhost:19092,19093,19094` (local profile `bootstrap-servers`도 함께) | 사람이 `docker compose down`. 처음 한 번은 기존 단일 broker 볼륨과 맞지 않아 `down -v` 필요 |
| 자동 테스트 | Testcontainers **3대** (한 네트워크, KRaft quorum 3) | 컨테이너가 매핑한 포트를 테스트가 `bootstrap-servers`로 주입 | Testcontainers(Ryuk)가 JVM 종료 때 제거 |

- 결정 (학습자, 10-28): 둘 다 3대. 3대는 이번 주의 기준 구성이지 실험 토글이 아니다 — 앱 설정(RF 3·min.insync 2)이 로컬·테스트에서 그대로 동작해야 하고, 복제 보장(1대 장애 쓰기 성공, 2대 장애 거절)을 자동 테스트로 증명한다.
- 비용 — 테스트 기동이 수십 초 늘어난다. 🔧 컨테이너 3대를 테스트 JVM 하나에서 한 번만 띄워 모든 테스트 클래스가 공유하고, 테스트마다 topic suffix·group id로 격리한다(과제 "고유 suffix").
- `max.block.ms`처럼 실험할 때만 바꾸는 값은 기존 규칙대로 `docs/week6/` 아래 별도 설정 파일(additional-location)에 둔다.
- 학습자 숙제 (10-29) — ADR-W6-04의 (ㄴ) 유실 장면 시간표를 다시 읽고 막히는 시각(t)을 짚는다.

## D-6. 소비 저장 모델 — 멱등 consumer ✅

### 테이블 🔧 (과제 요구를 옮김, 이름은 구현 때 확정)

```
consumed_order_confirmation (완료 기록 — Inbox 역할)
  event_id     PK          같은 이벤트 두 번 처리 금지
  order_id     UNIQUE      같은 주문에 다른 이벤트 금지
  fingerprint              같은 사실 비교 (SHA-256)
  payload      TEXT        받은 원문 — 충돌 조사용
  consumed_at

product_purchase_activity (구매 활동)
  order_id, product_id  UNIQUE   주문·상품별 하나
  quantity, confirmed_at         이벤트 값 그대로 (상품 테이블 조회·갱신 안 함 — 삭제·비노출 상품을 건드리지 않는다)
  event_id
```

- 완료 기록 + 품목 N개는 **한 TX**. 중간 품목 실패면 완료 기록·앞 품목까지 rollback (D-1 ⑤).
- fingerprint 입력 = 정규화한 사실: `orderId`, `confirmedAt`(UTC·마이크로초 고정 형식), productId 오름차순 `productId:quantity` 목록. JSON 속성·배열 순서와 무관하다.

### 판정

| 들어온 이벤트 | 기존 완료 기록 | 판정 |
|---|---|---|
| eventId 같음, fingerprint 같음 | 있음 | **중복 → no-op**(DB 변경 없이 성공 처리, ack) |
| eventId 같음, fingerprint 다름 | 있음 | **충돌** — 저장 없이 오류 |
| eventId 다름, orderId 같음 | 있음 | **충돌** — 저장 없이 오류 |
| 처음 | 없음 | 저장 |

- 중복은 at-least-once에서 정상적으로 생긴다. 충돌은 정상 흐름에서 생기면 안 된다(producer 버그·변조·잘못된 주입). 충돌 검사가 없으면 멱등 처리가 버그를 숨긴다 — 같은 eventId의 다른 수량을 조용히 버리거나(fingerprint가 잡음), 새 UUID로 온 같은 주문을 또 저장해 구매가 두 배가 된다(`order_id` UNIQUE가 잡음).
- 충돌 오류를 멈출지·DLT로 보낼지는 D-7.

### ADR-W6-05. 중복 판단은 SELECT 먼저 + INSERT를 유일 제약으로 보호 ✅

| | 방법 | 동시 처리 | 같은 사실·충돌 구분 |
|---|---|---|---|
| (1) SELECT만 | 있나 조회 → 없으면 INSERT | 둘 다 "없음"을 보고 둘 다 INSERT | 가능 |
| (2) INSERT 먼저 | 유일 제약 위반 → rollback → 새 TX에서 기존 행 비교 | DB가 하나만 통과 | 가능 |
| (3) `INSERT IGNORE` | 위반을 경고로 넘김 | 하나만 들어감 | 불가. 다른 오류까지 삼킨다 |

- **채택: (1)+(2)** (학습자, 10-28). 평소 재전달은 SELECT로 예외 없이 끝내고, 드문 동시 처리(rebalance 중 예전·새 consumer가 같은 record를 처리)는 유일 제약이 막는다.
- 유일 제약 위반만 "중복 가능성"으로 다룬다. 다른 `DataAccessException`은 그대로 실패시킨다(과제: 다른 오류를 모두 중복으로 바꾸지 않는다).

### 비교 방법 — 원문 + fingerprint ✅

| | (1) fingerprint만 | **(2) 원문 + fingerprint — 채택** | (3) 구매 활동 행과 비교 |
|---|---|---|---|
| 비교 | 해시 하나 | 해시 하나 | 품목 N행을 읽어 비교 |
| 충돌 조사 | 무엇이 달랐는지 모름 | 원문 두 개를 나란히 남김 | 가능하지만 복잡 |

- 결정 (학습자, 10-28). producer outbox의 원문 저장(ADR-W6-03)과 대칭이다.

### 처리 흐름 (handler)

```
handle(record):
  event = parse(record.value)                       // 파싱 실패 → 영구 실패 (D-7)
  fp = fingerprint(event)

  // ① 빠른 길 — 평소 재전달
  existing = 완료기록.findByEventIdOrOrderId(event.eventId, event.orderId)
  if existing: return judge(existing, event, fp)    // 같음 → no-op / 다름 → 충돌 예외

  // ② 저장 — 한 TX
  try:
    TX {
      완료기록.insert(eventId, orderId, fp, payload)   // PK·UNIQUE가 동시 처리를 막는다
      for item in event.items: 구매활동.insert(orderId, item.productId, item.quantity, confirmedAt, eventId)
    }
  catch 유일 제약 위반:                              // TX는 이미 rollback됨
    existing = 새 TX { 완료기록.findByEventIdOrOrderId(...) }
    return judge(existing, event, fp)

  // ③ listener — handle이 정상 반환(저장 또는 no-op)했을 때만 ack. 예외면 ack 안 함 (D-7)
```

### 같은 것이 두 번 발행되면

| 경우 | 예 | consumer ① 조회 | 판정 |
|---|---|---|---|
| 같은 이벤트를 두 번 보냄 | W7 relay·수동 재발행 (eventId X 두 번) | `event_id = X` 있음, fp 같음 | 중복 → no-op |
| 같은 확정에 이벤트 두 개 | 상태 가드 버그로 outbox 행 X·Y | `event_id = Y` 없음, **`order_id = 42` 있음** | 충돌 (같은 주문, 다른 ID) |

조회를 eventId **또는** orderId로 하는 이유가 두 번째 줄이다. 동시에 오면 `order_id` UNIQUE 위반 → 새 TX 재조회 → 충돌.

## D-7. listener 설정 ✅

### 🔧 과제가 정한 것

| 설정 | 값 | 이유 |
|---|---|---|
| listener factory | **record listener factory 새로 추가** (기존 batch factory 유지) | record 1건 = DB TX 1개 = ack 1번. 과제: 기존 factory 전역 변경 금지 |
| ack mode | `MANUAL_IMMEDIATE` | handler 정상 반환 직후 consumer 스레드에서 offset commit |
| `enable.auto.commit` | false — **실제 `ConsumerConfig` 값을 테스트로 확인** | 지금 yml은 `properties.enable-auto-commit`(Kafka가 읽는 키가 아님)으로 적혀 있다 |
| key / value 역직렬화 | `StringDeserializer` / `StringDeserializer` → handler에서 파싱 | producer `StringSerializer`와 짝 (ADR-W6-03). yml의 consumer `value-serializer` 오기도 고친다 |
| `auto.offset.reset` | **`earliest`** (이 group) | D-1 ③: 새 group이 `latest`면 그 전에 쌓인 확정 이벤트를 건너뛴다 |
| fetch (테스트 profile) | `fetch.max.wait.ms` 짧게 | 기존 1MiB·5s면 작은 테스트에서 늦게 보인다. 긴 sleep으로 덮지 않는다 |
| streamer `spring.application.name` | `commerce-streamer`로 고친다 | 지금 `commerce-api`로 복사돼 있어 client-id가 api와 같다 |

### ADR-W6-06. 재시도 3회 뒤 DLT, 사람이 재생 ✅

| 실패 | 예 | 처리 |
|---|---|---|
| 모든 실패 | DB 다운, lock timeout, 파싱 불가·계약 위반(수량 0·1.9), **충돌**, 예상 못 한 예외(NPE 등) | 같은 자리에서 **3회 재시도**(1s·2s·4s) → 그래도 실패면 `order-events.DLT` |

- 변경 (학습자, 10-29 저녁): 처음에는 "재시도해도 같은 실패(파싱 불가·충돌)는 `addNotRetryableExceptions`로 바로 DLT"였다. **모든 실패를 재시도 뒤 DLT**로 바꿨다 — 계약 위반이든 예상 못 한 오류든 같은 경로로 처리한다.
- AI 반론 (기록): 계약 위반·충돌은 다시 읽어도 결과가 같아 재시도가 나아지게 하지 못한다. 그 7초 동안 그 partition의 뒤 주문이 멈춘다. Spring Kafka 기본값도 역직렬화·변환 실패는 재시도하지 않는다. 계약 위반이 몰려 들어오면 partition당 record 하나에 7초씩 밀린다.

- 결정 (학습자, 10-29): 정책 횟수만큼 재시도해도 안 되면 DLT. DB 장애는 심각한 사건이라 사람이 확인하고 다시 넣는다.
- AI 반론 (기록): DB 다운 중 들어온 record는 복구되면 성공할 record라 "멈추고 lag로 알림 → 자동 재개"도 가능하다. DLT를 택하면 재생 도구가 필요하고, 재생 전까지 구매 활동이 비어 있다.
- 구현: `DefaultErrorHandler(DeadLetterPublishingRecoverer, ExponentialBackOff 3회)`.
  - recover(DLT 발행)가 성공하면 원래 record의 offset이 **n+1로 commit**된다 (`ackAfterHandle` 기본 true). DB는 이미 rollback이라 구매 활동 0행.
  - DLT 발행 자체가 실패하면(Kafka 장애) recover 실패 → offset을 commit하지 않고 다시 시도한다 → 유실 없음.
  - DLT도 RF 3·min.insync 2. 기본 recoverer는 **원래와 같은 partition 번호**로 보내므로 DLT partition 수 ≥ 원래 topic.
  - DLT record 헤더에 원래 topic·partition·offset·예외가 남는다 → 재생·조사의 근거.
- **과제 검증과의 차이 (C-7)**: 과제 6절 예시는 중단형 handler라 "실패 record 이후 offset이 진행하지 않음 → 오류 제거 후 재시작 → 재전달"을 본다. DLT를 골랐으므로 과제 문구대로 "재시도 소진·recover·ackAfterHandle"을 설명하고, 검증은 **DB 실패 주입 → 3회 재시도 → DLT 기록·원래 offset n+1·구매 활동 0행 → 오류 제거 → DLT 재생 → 저장 1번·재생 재실행 시 no-op**으로 바꾼다.

### DLT 재생 수단 ✅

| | 방법 | 이번 주 |
|---|---|---|
| 검증 (C-7) | **테스트 코드 안에서** DLT record를 읽어 key·value 원문 그대로 `order-events`에 다시 보낸다 → 1번 저장, 다시 넣으면 no-op | 테스트 코드만 |
| 운영 재생 | kafka-ui·console 도구로 DLT → 원래 topic. 절차를 런북으로 | 문서만 |
| 재생 기능 코드 | streamer 재생 컴포넌트·관리 명령 | 만들지 않는다 |

- 결정 (학습자, 10-29): 임의 발행 API(매직)는 과제 의도가 아니다. 과제는 합성 주입을 로컬 테스트 도구에서만 허용한다. 공개 endpoint는 만들지 않는다.

### consumer group 🔧

| 항목 | 값 | 이유 |
|---|---|---|
| group id | `commerce-streamer.product-purchase-activity` (`<앱>.<용도>`) | group은 "이 용도로 어디까지 처리했나"(committed offset)를 갖는다. 기존 기본값 `loopers-default-consumer`를 같이 쓰면 다른 용도의 listener와 partition을 **나눠 가져** 서로 일부만 받는다 |
| 같은 topic을 다른 용도로 읽을 때 (예: 9주차 랭킹 집계) | **새 group id** | group마다 committed offset이 따로라 각자 모든 record를 받는다 |
| concurrency | 3 | `order-events` partition 3 = 한 프로세스 안에서 consumer 3개가 하나씩 맡는다 |
| 테스트 | group id·topic에 고유 suffix | 테스트끼리 committed offset이 섞이지 않게 |

## D-8. 실험 설계 ✅

`order-events` partition 수 = **3** (학습자, 10-29). broker 3대에 leader가 하나씩 퍼지고, 주문당 이벤트가 하나라 partition을 늘려 key 매핑이 바뀌어도 순서 문제가 없다. 실험 E-1은 별도 실험 topic(partition 2)으로 한다.

| # | 실험 | 체크리스트 | 어디서 |
|---|---|---|---|
| E-1 | partition 2 실험 topic, 같은 group consumer 1→2→3 배정 (3번째는 배정 없음). 같은 key → 같은 partition (send metadata 비교, 다른 key가 다른 partition이라고 assert하지 않음) | C-4 | 자동 테스트 |
| E-2 | DB commit 뒤 offset 미커밋 재전달 — low-level `KafkaConsumer`, committed offset n → n → n+1, 같은 topic·partition·offset, 활동 행 수 불변 | C-5 | 자동 테스트 |
| E-3 | 실제 listener에 DB 실패 주입 → 3회 재시도 → DLT·원래 offset n+1·활동 0행 → 오류 제거 → 테스트 코드로 DLT 재생 → 1번 저장 → 다시 재생 no-op | C-7 | 자동 테스트 |
| E-4 | 실제 주문 API·로컬 PG fixture로 두 주문 확정. 1건은 commit 뒤 send 직전 hook으로 중단(미발행), 1건은 broker·consumer까지. 사건 사실(orderId·providerOrderId·transactionKey·eventId·confirmedAt·items·각 상태) 기록 | C-8, C-9 | 자동 테스트 + 결과 문서 |
| E-5 | broker 1대 정지 → 쓰기 성공·유실 없음. 2대 정지 → 거절, send 콜백 실패, outbox `published_at` null | ADR-W6-04 | 자동 테스트 (Testcontainers 3대) |
| E-6 | broker 전체 정지 + callback 부하, `max.block.ms` 60s·2s·1s의 p99·busy 스레드 | ADR-W6-03·04 | 수동 (compose + k6), 예측 → 측정 표 |

## D-9. 체크리스트와 테스트, PR ✅

### 앱 경계를 넘는 검증 — 양쪽 테스트를 broker record로 잇는다

| | (1) 분리 + record로 잇기 — 채택 | (2) e2e 전용 모듈 |
|---|---|---|
| 방법 | api 테스트는 broker의 record까지, streamer 테스트는 같은 형식의 record부터. 공유 fixture JSON이 둘 사이의 계약 | 새 모듈에서 api·streamer 컨텍스트를 함께 띄움 |
| 비용 | "끝까지 한 번에"를 자동 테스트 하나로 못 보여 준다 | 모듈 추가, 두 컨텍스트 분리(둘 다 `com.loopers` 스캔) |

- 결정 (학습자, 10-29): (1).
- 한계 (PR에 적는다): C-1·E-4의 "확정 → broker → streamer → 구매 활동" 전체 체인은 compose로 두 앱을 띄워 한 번 실행하고 결과(주문·outbox·record offset·활동 행)를 기록한다. 자동 테스트는 경계(record)에서 나뉜다. 계약이 어긋나는 것은 공유 fixture가 막는다 — api 테스트는 실제 payload가 fixture와 같은 필드·형식인지, streamer 테스트는 fixture를 소비할 수 있는지 확인한다.

### TDD 순서

도메인 규칙(fingerprint 정규화, 판정 judge, payload 생성)은 Red(`test:`) → Green(`feat:`) → Refactor(`refactor:`) 커밋을 나눈다. Kafka·DB 연결은 통합 테스트로 확인한다.

### PR 구성 (#82 형식)

- **Summary** — 항목마다 근거 문서와 증명 테스트 링크.
  1. 이벤트 계약과 발행 경계 — D-1·D-2·D-3, `OrderConfirmedPublishIntegrationTest`
  2. 발행 결과 기록과 누락 구간 — ADR-W6-03, E-4
  3. 클러스터 3대와 복제 보장 — ADR-W6-04, E-5
  4. 멱등 consumer — D-6·ADR-W6-05, handler 테스트·E-2
  5. listener 실패와 DLT — ADR-W6-06, E-3
  6. partition·group 배정 — E-1 배정표
  7. 대표 주문의 활동 결과, offset 전후, 남은 DB→broker 누락 구간, 실험의 한계
- **Review Points** — 아래 후보에서 2개.
- **Checklist** — 과제 묶음(📬 업무 연결 · 🔁 전달과 효과 · 🧪 경계와 검증)대로.

## 개발 진행 (10-29 새벽, `volume-6-claude`)

학습자가 잠든 동안 S1 ~ S6를 진행했다. 커밋은 이 브랜치에만 있고 push는 막혀 있다.

| 조각 | 커밋 | 내용 |
|---|---|---|
| S1 | `89168164` `3eefc231` | broker 3대(compose·Testcontainers), producer·consumer 설정, order-events topic, E-5 |
| S2 | `72ef0f30`(Red) → `79274aea`(Green) | `OrderConfirmed` — 신원·items 정렬·confirmedAt 형식·확정 아닌 주문 거절 |
| S3 | `e776016f` | 범용 발행 기록, AFTER_COMMIT 비동기 send, 결과 표시, C-2, E-4(S-13) |
| S4 | `c5526d7b`(Red) → `8af3e047`(Green) | `OrderConfirmedMessage` fingerprint·계약 검증, `ConsumedOrderConfirmation` 판정 |
| S5 | `d75f3639` | 멱등 consumer, record listener, 재시도 3회 → DLT, E-3 |
| S6 | `c6d0f912` | E-1 배정, E-2 재전달 |

검사: `:apps:commerce-api:check` 473 · `:apps:commerce-streamer:check` 32, 실패·오류·skip 0 (Checkstyle·ArchitectureTest 포함). 10-29 밤 R-1 ~ R-6 반영 뒤: api 476 · streamer 37 · 실패·오류·skip 0, lint 통과.
Red 단계는 둘 다 컴파일 오류가 아니라 assertion 실패였다 (S2 8개 중 7개 — 필드 목록 검사 1개는 뼈대 record 정의로 이미 통과, S4 16개 중 16개).

### 🤔 학습자 검토가 필요한 것 (아침)

1. **TDD 기대값** — 원래는 학습자가 먼저 확정해야 하는데, 이번에는 설계 계약으로 AI가 정하고 진행했다. 아래 두 테스트의 `@DisplayName` 목록을 읽고 빠진 것·틀린 것을 짚는다.
   - `apps/commerce-api/src/test/java/com/loopers/order/domain/OrderConfirmedTest.java` (8개)
   - `apps/commerce-streamer/src/test/java/com/loopers/purchase/domain/OrderConfirmedMessageTest.java` (12개) · `ConsumedOrderConfirmationTest.java` (4개)
2. **confirmedAt 표기** — 이벤트는 확정 TX 안의 시각이라 `+09:00`(시스템 zone)으로, 같은 주문을 DB에서 다시 읽으면 `Z`(UTC)로 표기된다. 순간(Instant)은 같고 consumer fingerprint는 UTC로 정규화하므로 판정에는 문제가 없다. 다만 W7 relay가 다시 만들거나 사람이 비교할 때 헷갈릴 수 있다. **payload를 UTC(`...Z`)로 고정할지** — 계약 변경이라 정하지 않았다. 지금 테스트는 순간이 같은지로 확인한다.
   - ✅ **10-29 학습자 결정: KST(`+09:00`)로 고정.** `OrderConfirmed.from`이 확정 시각을 `Asia/Seoul`로 바꿔 쓴다. consumer는 계속 어떤 offset이든 받고 fingerprint만 UTC로 정규화한다.
3. **E-6** — `max.block.ms` 부하 측정은 학습자가 직접 돌린다 (incident-w6.md 절차·예측 표).
4. **전체 흐름 실행** — PG 시뮬레이터(8082)가 필요해 돌리지 않았다. 런북은 incident-w6.md. C-1·C-9의 "이미 소비된 1건"을 이 실행으로 채운다.

### 구현 중 바뀌거나 구체화된 판단 (변경 이력)

| 항목 | 설계 때 | 구현 후 | 이유 |
|---|---|---|---|
| DLT 이름 | 기본 recoverer 규칙(원래 topic + `.DLT`) | resolver로 `order-events.DLT` 명시 | Spring Kafka 3.3의 기본은 `-dlt`. 어긋나 DLT 발행이 86번 실패하는 동안 partition이 멈췄다 (유실은 없음) |
| recover 뒤 commit | "ackAfterHandle 기본 true라 n+1 commit" | `commitRecovered=true` 명시 | MANUAL_IMMEDIATE에서는 이 값이 true일 때만 recover한 offset을 바로 commit한다. false면 다음 record ack 때 함께 넘어간다 |
| 재시도 범위 (ADR-W6-06) | 계약 위반·충돌은 재시도 없이 DLT | 모든 실패를 3회 재시도 뒤 DLT | 학습자 결정(10-29 저녁). 반론은 ADR에 기록 |
| 동기 send 실패 기록 | catch에서 바로 `markFailed` | 결과 executor의 새 TX로 기록 | AFTER_COMMIT 단계에서는 commit된 TX에 합류해 `last_error`가 사라졌다 — 테스트로 확인(R-1) |
| streamer 파싱 | 공용 ObjectMapper 그대로 | parser만 복사본을 엄격하게(실수·문자열 숫자·단일 객체 배열 변환 끔) | 수량 1.9가 1로 저장됐다(R-6). 공용 설정은 그대로 둔다 |
| 확정 시각 정밀도 | — | 결과 적용에서 `now().truncatedTo(MICROS)` | DB `datetime(6)`과 이벤트 confirmedAt이 같은 값이어야 한다 |
| confirmedAt 표기 | ISO-8601 + offset (zone은 정하지 않음) | KST `+09:00` 고정 | 같은 순간이 확정 TX에서는 `+09:00`, DB에서 다시 읽으면 `Z`로 나왔다. 학습자 결정(10-29) |
| eventId 생성 위치 | 응용 계층에서 만들어 `confirmPaid`에 넘김 | `OrderConfirmedRecorder`(order.application)가 만들어 발행 기록에 넣음 | 발행 테이블로 옮긴 결정(ADR-W6-03)을 따름. 도메인은 난수를 모른다 |
| 발행 기록 결과 표시 | 엔티티 갱신 | `WHERE published_at IS NULL` 조건부 UPDATE | 콜백 스레드가 행 전체를 다시 쓰지 않게, 늦은 실패가 성공을 덮지 않게 |
| 발행 기록은 TX 안에서만 | `fallbackExecution=false`로 드러냄 | `OutboxAppendService`를 `Propagation.MANDATORY`로 — TX 밖이면 예외 | 조용히 무시되기 전에 먼저 막는다 |
| streamer 포트 | — | local·test 프로필 8085 · 관리 8086 | api(8080·8081)와 함께 띄우기 위해 |
| fetch 설정 | 테스트 profile에서 짧게 | 이 listener factory는 Kafka 기본값(min 1 byte · 대기 500ms) | batch factory의 1MiB·5s를 쓰지 않으므로 따로 줄일 필요가 없다 |

## PR Review Point 후보

1. **eventId를 유도할지 저장할지** — 같은 사실에 같은 ID라는 조건은 둘 다 지킨다. 저장(B)을 골랐는데, 실무에서 둘 사이에 장단점 이상의 트레이드오프(스키마 진화·재발행·Outbox 전환)가 있는지 멘토님께 묻는다.
2. **일시 장애(DB 다운)도 DLT로 보낼지** — 재시도 3회 뒤 DLT·사람 재생을 골랐다. 복구되면 성공할 record를 DLT로 넘기는 비용(재생 도구·재생 전 공백)과 멈추고 lag로 알리는 방식 중 실무 기준을 묻는다.
3. **DLT 발행 자체가 실패할 때** — recover가 실패하면 offset을 commit하지 않고 같은 record를 다시 시도한다. 유실은 없지만 그동안 그 partition 전체가 멈췄다(E-3에서 86번). 실무에서는 DLT 발행 실패를 어떻게 감시·차단하는지 묻는다.
