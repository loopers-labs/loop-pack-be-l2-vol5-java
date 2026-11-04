# 주문 확정 Outbox와 단일 relay 설계 — Week 7

> 상태: **구현 중 (2026-11-05)**. 1~4단계(바꿀 트랜잭션 표시, Outbox 모델, 확정 TX의 Outbox 기록, 로컬 원자성 검증)를 진행한다. relay·재시도·중단 실험·E2E·W6 누락 복구는 다음 단계다.
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

## 4. relay (5단계에서 채운다)

단일 polling relay가 짧은 TX로 재시도 시각이 된 PENDING을 찾고, TX 밖에서 저장된 key·payload를 send하고, broker 확인(producer future 성공) 뒤 별도 TX에서 PUBLISHED로 바꾼다. 실패·timeout은 같은 행을 PENDING으로 두고 다음 시각·오류를 기록한다.

## 5. 결정 사항

| 결정 | 선택 | 이유 | 상태 |
|---|---|---|---|
| A. relay의 값 직렬화 | relay 전용 `KafkaTemplate`(value `StringSerializer`)로 저장된 payload를 그대로 보낸다 | 공통 template의 `JsonSerializer`에 문자열을 넘기면 JSON 문자열 리터럴로 한 번 더 감싸 consumer parser가 읽지 못한다 | 5단계에서 적용 |
| B. E2E에서 consumer 로딩 | 미정. apps 모듈은 plain jar가 꺼져 있어(`build.gradle.kts`) `testImplementation(project(":apps:commerce-streamer"))`가 그대로 동작하지 않는다 | 빌드 설정 변경이 필요하다 | 8단계 전에 확인 |
| C. W6 누락 상태 재현 | 보존한 W6 테스트 구성: 테스트 전용 `W6DirectPublishConfig`가 그 context에서만 Outbox 기록 자리를 "commit 뒤 직접 발행"으로 바꾼다 | W7 제품 코드에는 직접 발행이 없고, 운영용 우회 스위치는 만들지 않는다. W6 기준선 `OrderConfirmedOmissionReproTest`를 기대값 그대로 계속 실행한다 | 3단계에서 적용, 9단계에서 재사용 |
| 기존 테스트 기대값 | `OrderConfirmedPublicationTest` → `OrderConfirmedOutboxTest`. "한 번 발행"을 "Outbox 1행 + 직접 발행 0회"로 바꾸고, W6의 "commit 뒤 발행 실패" 장면은 "broker 불통에도 확정 경로는 broker를 부르지 않는다"로 바꾼다 | 발행 위치가 바뀐 결과다. 같은 장면을 새 경계에서 확인하고 검사를 약하게 만들지 않는다 | 1~4단계 진행 요청에 따라 적용 (2026-11-05) |

## 6. 테스트 (1~4단계)

| 테스트 | 확인 |
|---|---|
| `OrderOutboxModelTest` | PENDING 시작값, 이벤트·payload에서 옮긴 식별 정보, 필수값 거부 |
| `JacksonOrderEventSerializerTest` | 계약 예시(주문 42)의 정확한 JSON 문자열, 여섯 필드뿐 |
| `OrderOutboxRepositoryTest` | 저장·조회, `event_id` 유일 제약 |
| `OrderConfirmedOutboxTest` | 처음 확정에서 1행·저장 사실과 같은 payload, 중복 적용 무추가·payload 불변, 대기·실패·rollback 무기록, broker 불통에도 직접 send 0회 |
| `OutboxAtomicityTest` | Outbox insert 전·후 실패와 직렬화 실패에서 TX B 전체 rollback(주문·시도·예약·재고 유지, Outbox 0, PG create 추가 0)과 같은 거래 재조회로 한 번 확정, UNKNOWN 무기록, 동시 중복 callback에서 1행, TX 밖 append 거부 |
| `OrderConfirmedOmissionReproTest` | W6 기준선. `W6DirectPublishConfig`로 W6 경로를 재현해 기대값 그대로 실행 |

relay 없이 실제 MySQL에서 로컬 원자성을 먼저 확인한다. broker·consumer 연결은 5단계 이후다.

## 7. 알려진 한계 (현재 단계)

- relay가 아직 없다. 이 단계의 코드에서는 확정 주문의 Outbox가 PENDING으로 쌓이기만 하고 broker로 나가지 않는다(5단계).
- `created_at`은 `BaseEntity`가 시스템 시계로 정한다. 확정 시각과 `next_attempt_at`은 주입한 `Clock`을 따른다.
- `OrderConfirmedOmissionReproTest`가 실행하는 W6 경로는 테스트 구성으로 재현한 것이다. 원래 W6 코드의 실행은 커밋 `389b6d4`·`666e515`에 남아 있다.
