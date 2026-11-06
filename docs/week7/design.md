# 주문 확정 Outbox와 단일 relay 설계 — Week 7

> 상태: **구현 중 (2026-11-05)**. 1~9단계(바꿀 트랜잭션 표시, Outbox 모델, 확정 TX의 Outbox 기록, 로컬 원자성 검증, 단일 relay, 재시도·재개·관찰, 두 중단 구간, 주문부터 구매 활동까지의 E2E, W6 누락 재현·복구)를 진행했다. 회귀·제출은 다음 단계다.
> W6의 이벤트 계약(`docs/week6/design.md` 2절)과 구매 활동 consumer는 바꾸지 않는다. 바꾸는 것은 "확정 뒤 직접 발행"을 "같은 TX의 Outbox 기록 + 단일 relay"로 교체하는 경계다.

## 0. 범위

| 이번 주에 하는 것 | 하지 않는 것 |
|---|---|
| 주문 확정 TX에서 보낼 사실(OrderConfirmed)을 `order_outbox`에 함께 기록 | 새 결제수단·새 주문 도메인 |
| commit 뒤 직접 발행(`PaymentFacade.publishAfterCommit`) 제거 | 범용 saga, CDC 플랫폼 |
| 단일 polling relay가 PENDING을 broker로 보내고 확인 뒤 PUBLISHED로 기록 (5단계) | 다중 relay·분산 잠금 |
| 발행 중단·재시작, W6 누락 상태의 재현과 복구 검증 (6~9단계) | 미발행·격리 행의 자동 삭제 |

## 1. 바꿀 트랜잭션

바꾸는 곳은 새 주문을 insert하는 코드가 아니라, 기존 미완료 주문(PAYMENT_PENDING)을 처음 확정하는 결과 적용 경계 `PaymentTransactions.settle()`(TX B)이다.

```
TX 밖  : PaymentFacade가 기존 PG 거래를 조회·검증한다 (create 응답 / reconcile / callback)
DB TX B: settle() — 주문 → 결제 시도 → 재고 예약 잠금, 관찰 적용
         → 이번 적용이 시도를 SUCCESS로 종결한 경우에만:
            주문 CONFIRMED + confirmedAt + 예약 소비 + 같은 eventId의 OrderConfirmed Outbox PENDING
         → 함께 commit (또는 함께 rollback)
TX 밖  : relay가 나중에 PENDING을 찾아 Kafka로 발행한다 (5단계)
```

| | W6 | W7 |
|---|---|---|
| 보낼 사실 | TX 안에서 만들어 메모리로 넘긴다 | TX 안에서 만들어 `order_outbox`에 저장한다 |
| 발행 | commit 뒤 요청 스레드가 직접 send하고 결과를 최대 5초 기다린다 | 확정 경로는 send하지 않는다. relay의 일이다 |
| commit 뒤 send 전 중단 | 이벤트가 사라진다 | PENDING 행이 남아 relay가 다시 찾는다 |
| broker 불통 | 확정 응답이 send-timeout만큼 늦어진다 | 확정 응답은 broker를 기다리지 않는다 |

- `PaymentFacade`는 `OrderEventPublisher`에 의존하지 않는다. 직접 send와 relay가 함께 돌지 않는다.
- confirm(create 응답)·reconcile·callback은 계속 `applyCreate`/`applyLookup` → `settle()` 한 경계를 지난다.

## 2. Outbox 저장 모델 (`order_outbox`)

| 열 | 규칙 |
|---|---|
| `event_id` | W6 계약의 `order-confirmed:v1:{orderId}`. 유일 제약 `uk_order_outbox_event`. 생성 뒤 변경 금지 |
| `order_id`, `event_type`, `schema_version` | 원본 주문과 계약(`OrderConfirmed` / `1`) 연결. 변경 금지 |
| `message_key` | W6와 같은 Kafka key(주문 id 문자열). 변경 금지 |
| `payload` | 확정 TX 안에서 저장된 주문으로 만든 이벤트의 JSON. 변경 금지 |
| `status` | 처음 `PENDING`. broker 확인을 저장하면 `PUBLISHED` (5단계) |
| `attempt_count`, `next_attempt_at`, `last_error` | 재시도·지연·실패 관찰. `next_attempt_at`은 확정 시각으로 시작해 바로 relay 대상이다 |
| `created_at`, `published_at` | 기록 시각과 발행 확인 저장 시각을 구분한다 |

- **payload는 고정된 snapshot이다.** `OrderConfirmedEvent.from(order)`를 W6 producer가 쓰던 것과 같은 mapper(spring-kafka `JacksonUtils.enhancedObjectMapper()`)로 직렬화한다. 그래서 W6 broker record 값과 같은 모양이다. relay는 현재 상품·주문을 다시 읽어 payload를 만들지 않는다.
- 변경 금지 열은 `updatable = false`로 둔다.
- 인덱스 `idx_order_outbox_relay(status, next_attempt_at, id)`는 relay의 "재시도 시각이 된 PENDING을 id 순으로" 조회용이다. 실행계획은 5단계에서 확인한다.
- `attempt_count`는 시도 수이지 broker 수신 수가 아니다.
- Outbox는 event sourcing이 아니다. 주문의 원본은 여전히 주문·결제 테이블이고, Outbox는 전달할 사건만 보존한다.

## 3. 원자성 규칙

| 상황 | Outbox |
|---|---|
| 검증된 PG SUCCESS로 처음 CONFIRMED (create 응답·reconcile·callback 어느 경로든) | 같은 TX에 1행 PENDING |
| PAYMENT_PENDING·UNKNOWN·확정 실패(PAYMENT_FAILED) | 없음 |
| 이미 종결된 시도의 반복 적용 (중복 callback·reconcile, 동시 도착 포함) | 추가 없음 |
| Outbox 직렬화·insert 실패 | TX B 전체 rollback |
| 이전 POINT 확정 주문 | 없음 (소급 없음) |

- **rollback은 주문 삭제가 아니다.** 주문은 TX 직전의 PAYMENT_PENDING, 시도는 미종결, 예약은 RESERVED로 남는다. 주문 행이 사라지지 않는다.
- PG에는 SUCCESS가 남아 있을 수 있다. 로컬 rollback은 외부 결제를 취소하지 않는다. W5의 같은 거래 조회(reconcile·callback)로 다시 적용하고 PG create는 다시 부르지 않는다.
- `OrderOutboxService.append`는 `Propagation.MANDATORY`다. 호출한 확정 TX에 참여만 하고 새 TX를 열지 않으며, TX 밖에서 부르면 예외다. `REQUIRES_NEW`나 after-commit insert로 나뉘는 실수를 구조로 막는다.
- 중복 판단은 `settle()`의 "이번 적용이 시도를 종결했는가"(`PaymentEffect.SUCCEEDED`)가 먼저 한다. `event_id` 유일 제약은 마지막 방어선이다.
- Outbox는 새 행 insert뿐이라 기존 잠금 순서(주문 → 시도 → 예약 → 상품 → revision)에 행 잠금을 더하지 않는다.

## 4. 단일 relay

```
회차 (scheduler가 fixed-delay마다, 또는 수동으로 runOnce)
  tryLock 실패 → 기다리지 않고 건너뜀 (scheduler·수동 실행이 겹치지 않는다)
  [짧은 읽기 TX] status = PENDING AND next_attempt_at <= now
                 ORDER BY next_attempt_at, id LIMIT batch-size
  행마다 차례로:
    [짧은 TX] 아직 PENDING이면 attempt_count + 1, 저장된 key·payload를 꺼낸다
    [TX 밖]   relay 전용 producer로 send → future.get(send-timeout)
    [짧은 TX] broker 확인 → PUBLISHED + published_at
              실패·timeout → PENDING 유지, next_attempt_at = now + backoff(attempt), last_error
```

| 관찰 | relay의 기록 |
|---|---|
| broker 확인(producer future 성공) + 상태 TX 성공 | PUBLISHED, `published_at` |
| 명시적 전송 실패 | PENDING, 다음 시도 시각, 오류 |
| timeout·응답 유실 | PENDING, 다음 시도 시각, "수신 여부를 모름". 수신 0회라고 단정하지 않는다 |
| broker 확인 뒤 상태 TX 실패·중단 | PENDING이 남아 같은 사건을 다시 보낸다(중복은 consumer가 막는다) |

- **성공의 기준은 `send()`가 예외 없이 끝난 것이 아니라 producer future의 성공**이다. `acks=all`·멱등 producer 설정은 공통 `kafka.yml`을 그대로 쓴다. 단일 broker라 복제 보장은 확인하지 못한다(W6 한계 그대로).
- **상태를 바꾸는 곳은 relay의 회차 흐름 하나다.** future에 callback을 달아 상태를 바꾸지 않는다. 로컬 대기가 timeout된 뒤 하위 producer가 늦게 성공해도 행은 바뀌지 않고, 다음 시도가 같은 사건을 다시 보낸다.
  - 실패 기록은 "이 행의 마지막 시도"일 때만 반영한다(`attempt == attempt_count`). 이전 시도의 늦은 실패가 새 시도의 상태를 덮어쓰지 않는다.
  - PUBLISHED는 PENDING에서만 바뀌고, 이미 PUBLISHED면 처음 기록한 시각을 지킨다.
- **broker를 기다리는 동안 DB 트랜잭션·잠금을 잡지 않는다.** 조회·시도 기록·결과 기록은 각각 짧은 트랜잭션이고 주문 확정 트랜잭션과 무관하다.
- **실행자는 하나다.** 한 프로세스 안에서는 scheduler와 수동 실행이 같은 `ReentrantLock`을 `tryLock`하고, 기본 스케줄러는 스레드 하나라 tick이 겹치지 않는다. 여러 인스턴스에서는 `order-outbox.relay.scheduling-enabled`를 한 곳에서만 켠다. 이것은 분산 잠금이 아니다. 두 인스턴스가 함께 켜면 같은 행을 두 번 보낼 수 있다(중복은 consumer가 막지만 claim·소유권은 다루지 않는다).
- **조회 순서와 인덱스.** (다음 시도 시각, id) 순으로 고른다. 실패한 행은 다음 시도 시각이 뒤로 밀리므로 정상 행을 막지 않는다. `idx_order_outbox_relay(status, next_attempt_at, id)`가 이 조건과 정렬을 그대로 따른다.
- **값 직렬화.** 저장된 payload는 이미 JSON 문자열이라 relay 전용 producer(`StringSerializer`)로 보낸다(결정 A). 이 producer는 빈으로 등록하지 않아 공통 `kafkaTemplate`과 섞이지 않는다.
- **PUBLISHED는 소비 완료가 아니다.** consumer의 진행·실패·구매 활동은 consumer 쪽 기록(완료 기록·구매 활동 행·lag)으로 따로 본다.

| 설정 (`order-outbox.relay.*`) | 값 | 이유 |
|---|---|---|
| `scheduling-enabled` | `true` (test 프로파일 `false`) | 테스트는 relay를 직접 한 회차씩 불러, 백그라운드 회차가 테스트의 행을 바꾸지 않게 한다 |
| `fixed-delay` | 1s | 회차가 끝난 뒤 쉬는 시간. 회차가 겹치지 않는다 |
| `batch-size` | 50 | 한 회차에 고르는 작은 묶음 |
| `send-timeout` | 5s | broker 확인 대기 상한. 넘으면 수신 여부를 모르는 실패로 기록 |
| `initial-backoff` / `backoff-multiplier` / `max-backoff` | 1s / 2 / 5m | 실패 뒤 즉시 무한 반복하지 않는다. 영구 오류도 5분보다 자주 보내지 않는다 |
| `alert-after-attempts` | 5 | 한 행이 이만큼 실패하면 반복 실패로 보고 ERROR로 드러낸다(첫 실패부터 약 15초 뒤) |

### 4-1. 재시도·재개·관찰·보존 (6단계)

**재시도.** 실패한 행마다 다음 시도 시각을 `backoff(attempt)`만큼 뒤로 민다. 회차는 (다음 시도 시각, id) 순으로 고르므로 실패한 행이 정상 행을 막지 않는다. broker 장애가 풀리면 쌓인 PENDING을 같은 행·같은 eventId로 다시 보낸다.

**반복 실패.** 시도 수가 `alert-after-attempts` 이상이면 `eventId`·`orderId`·시도 수·다음 시도 시각·원인을 ERROR로 남기고, 회차 요약에 backlog(PENDING 수·가장 오래된 기록 시각·최대 시도 수)를 붙인다. 오류 문장에는 payload를 넣지 않고 500자까지만 저장한다.

**BLOCKED 상태를 두지 않은 이유.** 이 이벤트의 payload는 우리 직렬화기가 확정 TX에서 만든 고정 값이다. 그래서 "데이터가 잘못돼 영원히 보낼 수 없는" 경우보다 broker·topic·권한 같은 환경 원인이 대부분이고, 원인을 고치면 같은 행이 그대로 나가야 한다. 행을 격리하지 않고 상한(5분) 간격으로 계속 시도하며, 반복 실패를 ERROR로 드러내고, 고친 뒤에는 재개로 바로 보낸다. 반복 실패 행은 완료가 아니라 미해결 작업이고 backlog에 계속 남는다. 계약을 어긴 record의 소비 쪽 처리는 W6의 중단형 error handler가 맡는다.

**재개.** `OrderOutboxRelay.resume(eventId)`는 지정한 PENDING 한 건을 다음 시도 시각을 기다리지 않고 지금 보낸다. 회차와 같은 잠금을 쓰고, 없거나 이미 PUBLISHED면 보내지 않는다. 공개 관리자 API로 노출하지 않고 로컬의 운영 도구·테스트에서 직접 부른다.

**관찰.** relay 로그와 SQL로 원본부터 결과까지 따라간다. 로그에는 늘 `eventId`·`orderId`가 있다.

| 로그 | 수준 | 내용 |
|---|---|---|
| `Outbox 발행 확인` | INFO | eventId, orderId, attempt, broker의 topic·partition·offset |
| `Outbox 발행 미확인` | WARN | eventId, orderId, attempt, nextAttemptAt, 원인 |
| `Outbox 발행이 반복해서 실패한다` | ERROR | 위와 같음. `alert-after-attempts` 이상 |
| `Outbox relay 회차` | INFO / WARN | 고른 수·발행·실패. 실패가 있으면 backlog를 함께 |

```sql
-- 상태별 수, 가장 오래 기다린 기록 시각, 최대 시도 수
SELECT status, COUNT(*) AS cnt, MIN(created_at) AS oldest, MAX(attempt_count) AS max_attempt
FROM order_outbox GROUP BY status;

-- 오래 기다린 PENDING과 마지막 원인
SELECT event_id, order_id, attempt_count, next_attempt_at, last_error, created_at
FROM order_outbox WHERE status = 'PENDING' ORDER BY created_at LIMIT 20;

-- 한 사건 추적: Outbox → (broker 위치는 relay 로그) → 소비 완료·구매 활동 (streamer DB)
SELECT * FROM order_outbox WHERE event_id = 'order-confirmed:v1:42';
SELECT * FROM processed_order_confirmation WHERE event_id = 'order-confirmed:v1:42';
SELECT * FROM product_purchase_activity WHERE order_id = 42;
```

| 관찰값 | 알려 주는 것 | 혼자서 증명하지 못하는 것 |
|---|---|---|
| PENDING 수·가장 오래된 기록 시각 | 발행 backlog, 오래 미발행된 사건 | 소비 완료 |
| 시도 수·마지막 원인 | relay 실패 경향 | broker에 실제로 쓰인 record 수 |
| PUBLISHED·`published_at` | broker 확인을 저장했음 | consumer의 처리·구매 활동 |
| 완료 기록·구매 활동 행 (streamer) | 실제 후속 결과 | 모든 주문에 누락이 없음 |

**보존·정리.** 이번에는 어떤 Outbox 행도 자동으로 지우지 않는다. PENDING을 지우면 보낼 사실을 잃고, PUBLISHED를 바로 지우면 전달 이력과 재처리 근거를 잃는다. 나중에 정리 정책을 정할 때는 같은 사건이 다시 와도 소비 효과가 늘지 않도록 아래 관계를 지킨다.

- Outbox PUBLISHED 행의 보존 기간 ≤ consumer 완료 기록(`processed_order_confirmation`)의 보존 기간
- Kafka `order-events`의 retention ≤ consumer 완료 기록의 보존 기간
- 완료 기록을 먼저 지우면, 남아 있는 Outbox 행이나 Kafka record의 재처리가 구매 활동을 한 번 더 만든다. 지금 W6 consumer는 완료 기록을 지우지 않는다.

### 4-2. 두 중단 구간과 재시작 (7단계)

relay 한 건의 흐름은 단계로 나뉜다. 어느 단계 사이에서 멈춰도 다음에 할 일은 DB의 행이 알려 준다.

```
[확정 TX commit] ─①─ [relay: 대상 조회] → [시도 기록] → [send → broker 확인] ─②─ [PUBLISHED 저장]
```

| 중단 지점 | 멈춘 직후 | 재시작 뒤 |
|---|---|---|
| ① commit 뒤 relay 실행 전 | 주문 CONFIRMED·예약 CONSUMED, Outbox PENDING(시도 0), broker record 0(구매 활동을 만들 것이 없다) | 새 relay가 저장된 행을 골라 저장된 key·payload 그대로 보낸다. 같은 행이 PUBLISHED(시도 1) |
| ② broker 확인 뒤 PUBLISHED 저장 전 | broker에 record 1개(실제 send 성공 metadata의 offset), 같은 행이 PENDING(시도 1, `published_at`·`last_error` 없음, 다음 시도 시각 그대로) | 새 relay가 같은 행을 다시 보낸다. broker에 같은 eventId·같은 payload의 record가 **다른 offset**으로 2개, 같은 행이 PUBLISHED(시도 2) |

- **중단 hook** (`RelayStopHook`, 테스트 전용). relay가 부르는 Outbox 단계를 실제 서비스 빈(트랜잭션 프록시)에 그대로 넘기다가, 멈출 단계에 이르면 그 단계를 수행하지 않고 예외로 회차 흐름을 끝낸다. 앞 단계는 실제로 끝났다. ②에서는 send가 돌려준 broker 위치(`RecordingOutboxSender`가 남긴 topic·partition·offset)와 broker에 실제로 쓰인 record를 함께 확인한 뒤 PUBLISHED 저장만 막는다. relay는 이 예외를 잡지 않으므로 실패 기록도 남지 않는다.
- **OS 강제 종료가 아니다.** 예외로 relay의 실행 흐름을 끝낸 실험이다. 프로세스가 죽었을 때와 같은 점은 "다음 단계의 DB 기록이 일어나지 않았다"는 것뿐이고, JVM·커넥션·producer 버퍼의 상태까지 재현하지는 않는다.
- **재시작**은 relay 객체와 producer를 새로 만들고 같은 MySQL·topic·eventId를 쓴다. 테스트는 Outbox 상태를 직접 PENDING으로 되돌리지 않는다. 재시작한 relay가 고르는 것은 중단 전에 남은 행 그대로다.
- ②의 두 record는 W6의 "같은 offset 재전달"과 다르다. 같은 사건이 broker에 두 번 **기록**된 것이고, 소비 효과의 중복은 W6 consumer(eventId·주문 유일 제약)가 막는다. 소비까지 이어서 확인하는 것은 8단계 E2E다.

### 4-3. 주문부터 구매 활동까지 (8단계, `OutboxDeliveryE2ETest`)

한 테스트 실행이 자원 준비 → consumer 시작 → API 호출 → relay 중단 → 재시작 → 결과 조회를 모두 소유한다.

1. 합성 상품 둘(상품 7·8 역할)·사용자 포인트를 만들고, 실제 구매 활동 listener를 시작한다.
2. 기존 주문 API로 주문(7×2, 8×1)을 만들고, 결제 시작 → PG fixture SUCCESS → callback으로 확정한다.
3. 확정 주문·예약 소비·같은 eventId의 Outbox 한 행(PENDING)을 확인한다.
4. relay가 첫 broker 확인 뒤 PUBLISHED 저장 전에 멈춘다(4-2절 ②). consumer는 이 record를 처리해 구매 활동을 만든다. Outbox는 아직 PENDING이다.
5. 같은 Outbox에서 새 relay로 재시작해 다시 발행한다. consumer는 같은 사건을 다시 받고 이미 반영된 사실로 no-op 처리한다.

| 관찰 | 기대 | 확인 방법 |
|---|---|---|
| 주문·PG 거래 | 같은 확정 주문, 외부 거래 1 | 주문 상태, PG fixture의 주문별 거래·create 수 |
| Outbox | 같은 eventId 1행, 최종 PUBLISHED(시도 2) | `order_outbox` |
| Kafka | 같은 eventId의 별도 record 2개(다른 offset) | broker를 처음부터 읽은 실제 record. send 호출 수를 세지 않는다 |
| consumer | 같은 사건 수신 2회(RECORDED → ALREADY_PROCESSED) | listener 로그의 offset별 결과, group의 committed offset = 2 |
| 완료 기록 | 같은 orderId·eventId로 1 | `processed_order_confirmation` |
| 구매 활동 | 상품별 2행, 수량 2·1 유지 | `product_purchase_activity` |
| 재고·포인트 | 예약 추가 소비·반환 없음, 포인트 차감 없음 | 재고 10→8·9 유지, 예약 CONSUMED, 잔액 그대로 |

- **context 구성 (결정 B).** commerce-api 앱 context 하나에 streamer 클래스는 component scan에서 모두 빼고(`ExcludeStreamerFromScan`, `@TypeExcludeFilters`), 실제로 쓰는 listener factory(`OrderActivityKafkaConfig`)·parser·listener·handler·recorder·repository 구현만 `@Import`로 명시한다. streamer의 엔티티·JPA repository는 공통 `JpaConfig`의 패키지 scan으로 같은 MySQL fixture에 함께 올라온다.
- **실제 commit.** 테스트 트랜잭션으로 감싸지 않는다. relay·consumer는 각자의 트랜잭션에서 commit된 행을 본다.
- **격리와 정리.** topic·group·상품·사용자·주문은 이 테스트만 쓰는 값이다. DB를 일괄 초기화하지 않는다. 끝에는 자기가 시작한 listener와 만든 producer만 멈추고, PG fixture는 테스트 전후로 되돌린다. MySQL·Kafka 컨테이너는 `e2eTest` JVM의 fixture이고 Testcontainers가 JVM 종료 때 정리한다.
- 상품 id는 DB가 정한다. 과제의 "상품 7×2, 8×1"은 새로 만든 두 상품의 수량 2·1로 확인한다.

### 4-4. W6 누락 상태의 재현과 복구 (9단계, `W6OmissionRecoveryE2ETest`)

역사적 원본 행의 복원이 아니라 **새 격리 실행에서 W6 누락 상태를 재현하고 같은 실행 안에서 복구**한다. W6의 임시 DB 없이 시작하고, 사건은 이번 실행의 것이다(W6 기록과 별개).

**복구 경로 (제품 코드).** `OrderOutboxBackfill.backfill(orderIds)` → 주문마다 `OrderOutboxService.backfill(orderId, recordedAt)` (별도 트랜잭션).

| 대상 상태 | 결과 | 쓰는 것 |
|---|---|---|
| 확정 주문, Outbox 없음 | `ADDED` | 저장된 주문에서 확정 경로와 같은 방식으로 만든 이벤트(eventId·confirmedAt·품목)의 PENDING 한 행. `next_attempt_at` = 복구 시각 |
| 같은 사실의 행이 이미 있음 | `ALREADY_PRESENT` | 없음. 이미 PUBLISHED여도 다시 PENDING으로 돌리지 않는다 |
| 주문 없음 / 미확정·확정 시각 없음 (원본 사실 부족) | `REJECTED` + 이유 | 없음 |
| 같은 eventId에 다른 사실의 행 (충돌) | `REJECTED` + 이유 | 없음. 기존 행을 고치거나 덮어쓰지 않는다 |

- 대상은 **명시한 주문뿐**이다. 이전 POINT 주문 전체를 자동으로 소급하지 않는다. 한 대상이 거부돼도 다른 대상은 처리하고, 거부는 ERROR 로그와 결과로 드러낸다.
- 주문·결제·예약은 읽기만 한다. PG를 부르지 않고, 주문을 다시 확정하지 않는다. 보내는 것은 단일 relay다.
- 공개 API로 노출하지 않는다. 로컬의 운영 도구·테스트가 부른다(재개 `resume(eventId)`와 같은 경계).

**재현 → 복구 순서 (한 테스트 실행, 사이에 DB·broker·PG 초기화나 `@AfterEach` 없음).**

| 단계 | 하는 일 | 확인 |
|---|---|---|
| 준비 (W6 경로) | 보존한 W6 테스트 구성(`W6DirectPublishConfig`)으로 주문 API → PG SUCCESS → callback 확정 → commit 뒤 직접 발행. W6 고정 입력(확정 시각 2026-09-07T01:00:00Z, 단가 7,000원·재고 5, 주문마다 수량 2) | A: send 직전 중단(주입) → broker record 없음. B: W6 실제 발행기로 발행 → 실제 consumer 소비. 두 주문 CONFIRMED·예약 CONSUMED, Outbox 0, **A만 구매 활동 0** |
| 기록 | A·B의 orderId·eventId·confirmedAt·items·PG 거래 key, PG 누적 호출 수 | **C = 2, T = 2** (조회·요청 수도 함께) |
| 전환 | W6 직접 발행을 멈추고(테스트 구성의 전환, 제품 스위치 아님) 같은 DB에 W7 복구 경로·relay를 잇는다. 시각을 복구 시점(+3일)으로 옮긴다 | — |
| 1차 복구 | `backfill([A, B])` → relay 한 회차 | 두 행 `ADDED`. payload의 confirmedAt은 복구 시각이 아니라 W6 확정 시각, B의 payload는 W6가 broker에 보낸 값과 같은 바이트. relay 2건 발행. **A 구매 활동 생성**, B는 재발행을 `ALREADY_PROCESSED`로 받아 **활동 행·수량 불변** |
| 2차 복구 | 같은 대상으로 다시 `backfill` → relay | 두 행 `ALREADY_PRESENT`, relay 0건, Outbox 2행·broker record 3개·구매 활동 그대로 |
| 마무리 확인 | PG·주문·예약·포인트 | PG create = C, 외부 거래 = T, 조회·요청 증가 0, 확정 시각 그대로(재확정 0), 예약 CONSUMED·재고 그대로(추가 소비 0), 복구 중 직접 발행 0 |

- 준비 상태를 만들려고 SUCCESS를 직접 넣거나 W7 Outbox 행을 지우지 않는다. W6 경로가 처음부터 Outbox를 쓰지 않아 누락이 생긴다.
- 실제 W6 원본 DB를 보존한 것이 아니므로 이것은 "원본 사건의 복구"가 아니다. 원본 DB가 있었다면 준비를 다시 하지 않고, 보존한 사건 사실을 확인한 뒤 같은 `backfill`을 적용한다.
- 복구 경로의 경계 사례(원본 사실 부족·충돌·이미 보낸 행·재복구)는 `OrderOutboxBackfillTest`가 따로 확인한다.

## 5. 결정 사항

| 결정 | 선택 | 이유 | 상태 |
|---|---|---|---|
| A. relay의 값 직렬화 | relay 전용 producer(value `StringSerializer`)로 저장된 payload를 그대로 보낸다 | 공통 template의 `JsonSerializer`에 문자열을 넘기면 JSON 문자열 리터럴로 한 번 더 감싸 consumer parser가 읽지 못한다 | 5단계에서 적용 (`KafkaOrderOutboxSender`) |
| B. E2E에서 consumer 로딩 | commerce-api에 E2E 전용 source set `e2eTest`를 두고 그 classpath에만 commerce-streamer를 둔다. streamer는 plain jar(classifier `plain`, `application.yml` 제외)를 켠다. E2E context는 streamer 클래스를 component scan에서 빼고 consumer 부품만 `@Import`한다 | 두 앱의 `@SpringBootApplication`이 같은 패키지에 있어 일반 test classpath에 넣으면 기존 테스트의 설정 탐색·scan이 깨진다. 제품 runtime에는 commerce-api → commerce-streamer 의존이 생기지 않는다. 별도 E2E 모듈은 settings 변경과 테스트 지원 코드 이동이 더 크다 | 8단계에서 적용 (2026-11-05 확인). 실행: `./gradlew :apps:commerce-api:e2eTest` (`check`에 포함) |
| C. W6 누락 상태 재현 | 보존한 W6 테스트 구성: 테스트 전용 `W6DirectPublishConfig`가 그 context에서만 Outbox 기록 자리를 "commit 뒤 직접 발행"으로 바꾼다. 복구 E2E는 같은 context에서 `stopDirectPublishing()`으로 W7 기록으로 되돌린다 | W7 제품 코드에는 직접 발행이 없고, 운영용 우회 스위치는 만들지 않는다. W6 기준선 `OrderConfirmedOmissionReproTest`를 기대값 그대로 계속 실행한다 | 3단계에서 적용, 9단계에서 재사용 |
| 기존 테스트 기대값 | `OrderConfirmedPublicationTest` → `OrderConfirmedOutboxTest`. "한 번 발행"을 "Outbox 1행 + 직접 발행 0회"로 바꾸고, W6의 "commit 뒤 발행 실패" 장면은 "broker 불통에도 확정 경로는 broker를 부르지 않는다"로 바꾼다 | 발행 위치가 바뀐 결과다. 같은 장면을 새 경계에서 확인하고 검사를 약하게 만들지 않는다 | 1~4단계 진행 요청에 따라 적용 (2026-11-05) |

## 6. 테스트

| 테스트 | 확인 |
|---|---|
| `OrderOutboxModelTest` | PENDING 시작값, 이벤트·payload에서 옮긴 식별 정보, 필수값 거부, 시도 수·PUBLISHED 전이, 실패 기록과 늦은 실패 무시, 오류 길이 제한 |
| `JacksonOrderEventSerializerTest` | 계약 예시(주문 42)의 정확한 JSON 문자열, 여섯 필드뿐, W6 producer와 같은 바이트 |
| `OrderOutboxRepositoryTest` | 저장·조회, `event_id` 유일 제약, relay 대상 조회의 조건·순서·limit, relay 인덱스 열 순서 |
| `OrderOutboxRelayPropertiesTest` | 실패 뒤 대기 시간의 배수 증가와 상한, 즉시 반복이 되는 설정 거부 |
| `KafkaOrderOutboxSenderTest` | 실제 broker에 저장 값을 감싸지 않고 그대로 보내고 broker 위치를 돌려줌, 닿지 않는 broker는 예외 없이 실패 |
| `OrderOutboxRelayTest` | 실제 주문으로 만든 PENDING을 broker로 보내고 확인 뒤 PUBLISHED, 다음 회차 재발행 없음, broker 불통이면 PENDING·다음 시각·오류를 남기고 그 시각 전에는 고르지 않음, send하는 동안은 PENDING, 겹친 회차 건너뜀, batch-size·순서, 발행기 예외도 실패로 기록 |
| `OrderOutboxRelaySchedulerTest` | 켜면 fixed-delay마다 회차 실행, 끄거나 없으면 scheduler 없음 |
| `OrderOutboxRetryAndResumeTest` | broker 장애 동안 쌓인 PENDING 2건이 회복 뒤 같은 행으로 발행되고 backlog가 비워짐, 연속 실패의 대기 1s → 2s → 4s(상한) → 4s와 알림 기준부터 ERROR(eventId·원인), eventId를 지정한 재개가 다음 시도 시각 전에 보내고 PUBLISHED·없는 eventId는 보내지 않음 |
| `OrderOutboxInterruptionTest` | ① commit 뒤 relay 실행 전 중단: CONFIRMED·CONSUMED·PENDING(시도 0)·broker 0 → 새 relay가 저장 payload 그대로 1회 발행·PUBLISHED. ② broker 확인 뒤 PUBLISHED 저장 전 중단: send 성공 offset과 broker record 일치, 같은 행 PENDING(시도 1, 실패 기록 없음) → 새 relay가 같은 eventId를 다른 offset에 재발행, 같은 행 PUBLISHED(시도 2) |
| `OutboxDeliveryE2ETest` (`e2eTest`) | 주문 API → PG 성공 callback → Outbox → relay(PUBLISHED 저장 전 중단·재시작) → Kafka 2 record → 실제 구매 활동 consumer. 4-3절 표의 기대값 전부 |
| `OrderOutboxBackfillTest` | W6 경로로 확정된 주문에 저장된 사실 그대로 PENDING 1행(W6가 보내려던 값과 같은 바이트, 확정 시각 보존, PG·재고 불변), 재복구 무추가·PUBLISHED 유지, 없는·미확정 주문 거부와 다른 대상 처리, 충돌 행 거부·미변경 |
| `W6OmissionRecoveryE2ETest` (`e2eTest`) | 4-4절 표 전부: W6 경로 준비(A 미발행·B 소비), C=2·T=2 기록, 복구로 A 구매 활동 생성·B 불변, 2차 복구 무변화, 복구 중 PG·주문·예약·직접 발행 증가 0 |
| `OrderConfirmedOutboxTest` | 처음 확정에서 1행·저장 사실과 같은 payload, 중복 적용 무추가·payload 불변, 대기·실패·rollback 무기록, broker 불통에도 직접 send 0회 |
| `OutboxAtomicityTest` | Outbox insert 전·후 실패와 직렬화 실패에서 TX B 전체 rollback(주문·시도·예약·재고 유지, Outbox 0, PG create 추가 0)과 같은 거래 재조회로 한 번 확정, UNKNOWN 무기록, 동시 중복 callback에서 1행, TX 밖 append 거부 |
| `OrderConfirmedOmissionReproTest` | W6 기준선. `W6DirectPublishConfig`로 W6 경로를 재현해 기대값 그대로 실행 |

relay 없이 실제 MySQL에서 로컬 원자성을 먼저 확인한다. broker·consumer 연결은 5단계 이후다.

## 7. 알려진 한계 (현재 단계)

- relay는 한 인스턴스에서만 켠다는 실행 설정에 기대고, 분산 잠금·claim은 없다.
- 반복 실패 행을 자동으로 격리하지 않는다. 원인이 남아 있으면 상한 간격으로 계속 시도하고 ERROR를 남긴다. broker가 계속 꺼져 있으면 수렴하지 않는다.
- timeout 뒤 같은 사건을 다시 보내므로 broker에는 같은 eventId의 record가 둘 이상 생길 수 있다. 소비 효과의 중복은 W6 consumer(eventId·주문 유일 제약)가 막는다.
- W6의 `OrderEventPublisher`·`KafkaOrderEventPublisher`는 제품 경로에서 더 이상 부르지 않는다. W6 기준선 재현(`W6DirectPublishConfig`)만 쓴다.
- `created_at`은 `BaseEntity`가 시스템 시계로 정한다. 확정 시각과 `next_attempt_at`은 주입한 `Clock`을 따른다.
- `OrderConfirmedOmissionReproTest`·`W6OmissionRecoveryE2ETest`가 실행하는 W6 경로는 테스트 구성으로 재현한 것이다. 원래 W6 코드의 실행은 커밋 `389b6d4`·`666e515`에 남아 있다.
- 누락 복구는 대상 주문을 사람이 정한다. 어떤 주문이 누락됐는지 자동으로 찾지 않는다(확정 주문 중 Outbox·완료 기록이 없는 것을 조회해 후보를 고르는 것은 운영자 몫이다).
