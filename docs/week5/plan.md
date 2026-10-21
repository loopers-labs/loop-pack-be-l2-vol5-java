# 결제 교체 설계 — 포인트 → PG (W5)

> **이 문서의 자리** — W5의 Plan·Design 산출물이다. W2 `docs/week2/plan.md`(주문 ORD-*, 구조 5-2), W3 `docs/week3/plan.md`(잠금·트랜잭션 경계), W4 `docs/week4/plan.md`(목록 캐시)에서 이어진다.
>
> **목표** — 주문 확정의 결제를 포인트에서 PG로 바꾼다. 외부 결과는 DB rollback으로 되돌릴 수 없으므로, **요청 수락·처리 중·성공·실패·결과 미확인**을 구분하고 외부 결과를 확인한 뒤 주문·재고에 **한 번만** 반영한다.
>
> **표기** — ✅ 합의 · 🔧 AI 기본값 · 🤔 결정 필요.
>
> **관찰 근거** — `spike/pg/README.md` (Toss 승인은 동기 DONE, PortOne 테스트 결제는 `include_sandbox` 없이 단건 조회 404).

## 0. 체크리스트 — 모두 통과해야 한다

| # | 체크리스트 (과제 원문 요약) | 증거 (예정) | 상태 |
|---|---|---|---|
| C-1 | 미확정 주문의 결제는 PG를 사용하고 포인트를 이중 차감하지 않는다 | S01·PurchaseFlowE2ETest: 포인트 잔액·이력 변화 0 | ✅ |
| C-2 | 이전 POINT 주문의 결제·품목 사실과 기존 소유권을 보존한다 | S11·OrderApiE2ETest#keepsPointConfirmedOrder·OrderAdminApiE2ETest: 이전 POINT 주문 조회, 재확정 409 | ✅ |
| C-3 | 생성 수락·처리 중·최종 성공·실패·결과 미확인을 구분한다 | PaymentTest P-1~P-15, S-2·S-4 | ✅ |
| C-4 | 시도·발송·예약이 DB에서 중복을 막고 PG HTTP 동안 잠금을 계속 잡지 않는다 | S-5·OrderConfirmConcurrencyTest·LockingSqlIntegrationTest(orders → products 잠금, PG는 TX 밖) | ✅ |
| C-5 | 성공은 추가 차감 없이 확정하고 확정 실패만 예약을 한 번 반환한다 | S-1·S-3·S-6, StockReservationServiceIntegrationTest(5 → 3 → 5, 7 아님) | ✅ |
| C-6 | timeout·재시작·조회 0건에서 새 결제를 만들거나 예약을 임의 반환하지 않는다 | S-4·S-8·S-9: 생성 추가 0, 0건은 기록만 | ✅ |
| C-7 | 실제 adapter HTTP 계약과 callback의 권위 조회·식별·금액 검증을 확인했다 | LocalPgPaymentGatewayAdapterTest G-1~G-11, S-6·S-9(권위 조회·금액·식별 검증) | ✅ |
| C-8 | 중복·순서 변경·내부 저장 실패와 timeout·회로·조회 복구를 검증했다 | S-6·S-7·S-10, PaymentResultApplierIntegrationTest R-1~R-5 | ✅ |
| C-9 | 관련 회귀·lint·ArchUnit을 실행하고 실험 자원을 정리했다 | `./gradlew :apps:commerce-api:check` 451/0/0/0, `check -x test` 통과 (10-22). 실험 자원: spike 서버(8090)·ngrok 터널 종료(10-22), 에이전트 작업 공간·임시 스키마 정리 | ✅ |

## 0-1. 부하 가정 ✅

중형 패션 커머스의 타임세일 오픈 직후를 가정한다. 테스트·설정 값의 근거로 쓴다.

| 항목 | 가정 | 계산 · 설계에 주는 의미 |
|---|---|---|
| 평소 피크 | 일 주문 3만 · 피크 1시간에 15% | 4,500건/시간 ≈ 결제 시도 1~2 TPS |
| 타임세일 오픈 1분 | 평소 피크의 50~100배 | 결제 시도 **100 TPS** |
| PG 정상 응답 | 0.1~0.5s | 동시 대기 = 100 × 0.5 = 50 |
| PG 지연 | 3s (생성 읽기 timeout 직전) | 동시 대기 = 100 × 3 = 300 > Tomcat 기본 200 → timeout·회로로 빨리 끊어야 다른 API가 산다 |
| DB 커넥션 | Hikari 기본 10 | TX를 연 채 PG를 부르면 50 중 10만 진행 → PG 호출은 TX 밖 (4장) |

## 1. 바꾸는 것과 바꾸지 않는 것 ✅ (과제 1절)

| 대상 | 이번 변경 | 유지 |
|---|---|---|
| DRAFT 주문 | 확정 요청이 PG 결제를 시작한다 | 소유권·품목·저장 금액·삭제·재고 검사 (ORD-02~04) |
| 신규 PG 결제 | 결제 시도·외부 거래·결과 적용 경계 | 포인트 차감 0회 |
| 이전 POINT 주문 | 그대로 조회 | `payment_method = POINT`, 금액, 당시 품목 |
| 포인트 충전·조회 | 그대로 | 주문 결제에서만 분리 |

범위 밖: 실패 후 새 결제 시도, 환불·정산, 사용자 취소, 카드 저장. **주문당 결제 시도 1건.**

## 2. 식별자 🔧

| 식별자 | 만드는 곳 | 예 | 용도 · 보호 |
|---|---|---|---|
| `orderId` | commerce | `42` | 주문 소유권·품목. `payment.order_id` **unique** → 주문당 시도 1건 |
| `paymentAttemptKey` | 클라이언트 (`Idempotency-Key` 헤더) ✅ | UUID | 우리 API의 멱등 키. `payment.attempt_key` **unique**. 같은 키 재요청 = 같은 시도 상태 반환, 같은 키 + 다른 주문 = 409, 같은 주문 + 다른 키 = 409. Toss 승인에는 같은 값을 `Idempotency-Key`로 전달 |
| `providerOrderId` | commerce adapter | `LP-ORD-0000000042` | PG 요청·주문 단위 조회. 생성·조회에 같은 문자열 |
| `transactionKey` | PG | `20261020:TR:abc` | 외부 거래 한 건 조회 |

## 3. 상태 🔧

### 3-1. 주문 `OrderStatus`

`DRAFT → PAYMENT_PENDING → CONFIRMED | PAYMENT_FAILED`. 기존 POINT 주문은 `DRAFT → CONFIRMED` 그대로.
`PaymentMethod`에 `PG` 추가. PG 확정 주문은 `paid_amount`·`confirmed_at`을 결과 적용 시 채운다.

### 3-2. 결제 시도 `Payment` (새 애그리거트, `com.loopers.payment`)

| 상태 | 뜻 | 어떻게 들어오나 | 다음 |
|---|---|---|---|
| `REQUESTED` | 저장됨, **아직 발송 권한을 아무도 안 가짐** | TX A | `SENDING` |
| `SENDING` | 발송 권한을 얻음. 실제 발송 여부는 모를 수 있음 | 조건부 UPDATE (`WHERE status='REQUESTED'`) | `PENDING`·`UNKNOWN`·`FAILED`·`SUCCESS` |
| `PENDING` | PG가 거래를 만들고 처리 중이라고 답함 (`transactionKey` 있음) | 생성 응답·조회 | 종결 |
| `UNKNOWN` | 발송했을 수 있으나 결과를 모름 (timeout·연결 유실·해석 불가) | 생성 예외 | 조회로만 종결 |
| `SUCCESS` | 검증한 외부 성공 | TX B | — (종결) |
| `FAILED` | 검증한 외부 실패 또는 **효과 0건 확정 거절** | TX B | — (종결) |

- 종결(`SUCCESS`·`FAILED`)은 덮어쓰지 않는다. 늦게 온 생성 응답·timeout handler가 `PENDING`·`UNKNOWN`으로 되돌리지 못한다.
- 조회 0건·2건 이상·금액/식별 불일치는 상태를 바꾸지 않고 **이상 기록**(`last_check_result`)만 남긴다. 재발송하지 않는다.
- **보내지 않은 게 확실한** 경우(풀 거절, claim 전 정체, 회로 OPEN으로 호출 전 차단)는 바로 `FAILED`(효과 0건)로 닫고 재고를 반환한다. 다시 보내지 않는다 (ADR-W5-03). 보낸 뒤 결과를 모르면 `UNKNOWN` → 조회로만 종결한다.

### 3-2-1. 상태 전이 기대값 ✅ (`PaymentTest`)

| # | 주어진 상태 | 일어난 일 | 기대 |
|---|---|---|---|
| P-1 | — | 결제 생성 (주문 42, 5,000원) | `REQUESTED`, `LP-ORD-0000000042`. 금액 0 이하·빈 시도 키는 거절 |
| P-2 | `REQUESTED` | 발송 권한 획득 | `SENDING` |
| P-3 | `SENDING`·종결 | 발송 권한 다시 | 거절 (한 번만 보낸다) |
| P-4 | `REQUESTED` | 보내지 못함 (풀 거절·정리) | `FAILED`(NOT_SENT) |
| P-5 | `SENDING` | 회로 OPEN으로 호출 전 차단 | `FAILED`(NOT_SENT). `PENDING`·`UNKNOWN`이면 `CONFLICT`(보냈을 수 있다) |
| P-6 | `SENDING` | 생성 수락 + transactionKey | `PENDING` |
| P-7 | `SENDING` | 생성 timeout·응답 유실 | `UNKNOWN` (실패로 닫지 않는다). `PENDING`을 `UNKNOWN`으로 내리지 않는다 |
| P-8 | `SENDING` | 효과 0건 업무 거절 | `FAILED` |
| P-9 | `PENDING`·`UNKNOWN` | 조회 SUCCESS·FAILED·PENDING | `SUCCESS`·`FAILED` → `APPLIED`, PENDING → `NOT_FINAL` |
| P-10 | 아무 상태 | 금액·식별자·거래 키 불일치 | 상태 그대로 + `MISMATCH` 기록 |
| P-11 | `SUCCESS` | FAILED 관찰 | `CONFLICT`, SUCCESS 그대로 |
| P-12 | `SUCCESS` | 같은 SUCCESS 다시 (중복 callback) | `ALREADY_APPLIED` |
| P-13 | `SUCCESS` | 늦게 온 생성 응답·timeout | `ALREADY_APPLIED` (종결을 되돌리지 못한다) |
| P-14 | `PENDING`·`UNKNOWN` | 보낸 지 30분 미종결 → 만료 | `FAILED`(EXPIRED). `REQUESTED`·`SENDING`은 `CONFLICT`(정리 스케줄러 몫) |
| P-15 | `FAILED`(EXPIRED) | SUCCESS 관찰 | `CONFLICT`, FAILED 그대로 (환불 필요, 범위 밖) |

### 3-3. PG 관찰 → 내부 반영 (과제 4절 표)

| PG 관찰 | 결제 | 주문 | 재고 예약 |
|---|---|---|---|
| 생성 수락 PENDING | `PENDING` | `PAYMENT_PENDING` | 유지 |
| timeout·응답 유실·조회 불가 | `UNKNOWN` | `PAYMENT_PENDING` | 유지 |
| 검증한 SUCCESS | `SUCCESS` | `CONFIRMED` | 소비 (추가 차감 없음) |
| 검증한 FAILED · 효과 0건 확정 거절 | `FAILED` | `PAYMENT_FAILED` | 한 번 반환 |
| 0건·2건 이상·불일치 | 그대로 + 이상 기록 | 그대로 | 그대로 |

## 4. 트랜잭션 경계 ✅

```
confirm 요청
 ├─ TX A ──────────────────────────────────────────────
 │   주문 FOR UPDATE · 소유 · DRAFT · attemptKey 일치
 │   상품 FOR UPDATE · 판매 가능 · 단가 · 재고 (OrderConfirmPolicy 재사용)
 │   재고 예약 (가용 재고 차감 + 예약 행 RESERVED)
 │   주문 PAYMENT_PENDING · Payment REQUESTED · outbox 행 PENDING (ADR-W5-01)
 │   부족·삭제·잘못된 입력 → 전체 rollback, PG 호출 0
 ├─ commit
 ├─ commit 성공 시에만 PaymentRequested 이벤트 → 비동기 스레드풀(50)에 넘기고 즉시 202
 │   풀이 가득 차면 넘기지 않음 → 그 자리에서 FAILED로 닫고 재고 반환 (ADR-W5-03)
 ├─ (풀 스레드) 발송 권한 획득 (outbox CLAIMED + payment SENDING, 같은 TX)
 │   UPDATE payment SET status='SENDING' WHERE id=? AND status='REQUESTED'
 │   영향 0행 → 다른 실행이 가짐 → 저장된 상태를 202로 반환
 ├─ TX 밖: PG POST (timeout O, 재시도 X)
 └─ TX B ──────────────────────────────────────────────
     Payment FOR UPDATE → 종결이면 아무것도 안 함
     응답 반영: PENDING/UNKNOWN 기록, 또는 SUCCESS·FAILED면 결과 적용
```

**결과 적용 경계 (callback·reconcile·생성 응답이 모두 여기로)** — `PaymentResultApplier` ✅

0. **TX 밖에서** PG 조회 (callback 본문은 계기일 뿐). 잠금을 잡은 채 외부를 부르지 않는다.
1. TX 시작. Payment `SELECT … FOR UPDATE` (비관적 잠금).
2. 조회한 거래를 검증: 제공자 계정·`providerOrderId`·`transactionKey`·저장 금액.
3. `payment.applyResult(결과)` → `APPLIED` · `ALREADY_APPLIED` · `CONFLICT`.
4. `APPLIED`일 때만 — SUCCESS: 주문 CONFIRMED, 예약 CONSUMED. FAILED: 주문 PAYMENT_FAILED, 예약 RELEASED + 가용 재고 복구.
5. 같은 TX. 실패하면 전체 rollback → 같은 외부 결과를 다시 조회해 적용 가능.

### 4-1. 결과 반영의 멱등성 ✅ (ADR-W5-04)

| 겹 | 장치 | 막는 상황 |
|---|---|---|
| ① | payment 행 비관적 잠금 (`FOR UPDATE`) | callback·reconcile이 **동시에** 반영 |
| ② | 종결 상태 가드 → `ALREADY_APPLIED` | **차례로** 온 중복 callback, 늦게 온 생성 응답 |
| ③ | 주문 `PAYMENT_PENDING`·예약 `RESERVED`일 때만 전이 | ②의 버그·잘못된 재호출 (마지막 방어선) |

- 종결과 반대 결과(SUCCESS 뒤 FAILED)는 `CONFLICT`: 상태 유지 + 이상 기록 + 경고 로그.
- 잠금 순서 고정: payment → order → 예약 → product(id 오름차순). TX A(order → product)와 순환이 없다.
- 기대값: R-1 같은 SUCCESS 2번 → 확정·소비 각 1 / R-2 같은 FAILED 2번 → 재고 5→3→5 (7이면 실패) / R-3 동시 10스레드 → `APPLIED` 1·`ALREADY_APPLIED` 9 / R-4 SUCCESS 뒤 FAILED → `CONFLICT`, 재고 변화 0 / R-5 반영 중 저장 실패 → 전체 rollback, 재반영 시 정상 확정.

## 5. 재고 예약 ✅ (ADR-W5-02)

콘서트 좌석 임시 선점처럼, 결제 요청 시점에 재고를 잡아 두고 결제가 실패로 **확인될 때만** 푼다.

- `stock_reservation(order_id, product_id, quantity, status)` — `RESERVED → CONSUMED | RELEASED`. 예약할 때 `product.stock`(가용 재고)을 바로 줄인다.
- 반환은 `RESERVED → RELEASED` 조건부 전이로 한 번만. 중복 실패 알림은 0행 갱신으로 무효과.
- PG에 보낸 뒤에는 성공했을 수 있으므로 바로 풀지 않는다. 단 보낸 지 30분이 지나도 미종결이면 마지막 조회 후 실패로 닫고 반환한다 (ADR-W5-05). 보내기 전 선점 만료(TTL)는 Toss·PortOne처럼 결제창 구간이 있을 때 다시 본다.

## 5-1. 발송과 확인 — outbox · 이벤트 · 정리 · reconcile ✅ (ADR-W5-01·03)

```
TX A: 주문·재고 선점·payment REQUESTED·outbox PENDING 저장 + PaymentRequested 이벤트 발행
  └ AFTER_COMMIT → 비동기 스레드풀(50) → claim → PG 생성 1회 → TX B          ← 유일한 발송 경로
      └ 풀이 가득 참 → 그 자리에서 FAILED + 재고 반환
PaymentDispatchCloser      1분마다: 30초 넘게 claim 안 된 행 → FAILED + 재고 반환     ← 놓친 건 정리 (보내지 않음)
                                    30초 넘게 claim 후 결과 없는 행 → UNKNOWN          ← 보냈을 수 있음
PaymentReconcileScheduler  PENDING·UNKNOWN 결제를 PG 조회로 확인 → 결과 적용         ← 확인만, 생성 X
```

| 스케줄러 | 대상 | 하는 일 | 하지 않는 일 |
|---|---|---|---|
| 정리 (`PaymentDispatchCloser`) | `created_at`이 30초 넘게 지난 PENDING outbox 행 / claim 후 30초 넘게 결과 없는 행 | 앞은 `FAILED`(효과 0건) + 재고 반환, 뒤는 `UNKNOWN`으로 넘김 | 발송 |
| reconcile (확인·대사) | `PENDING`·`UNKNOWN` 결제, `next_check_at <= now` | PG **조회** → SUCCESS·FAILED면 결과 적용 경계 | 결제 생성, 0건을 실패로 확정 |

| 만료 (reconcile 스케줄러, 60초마다) ✅ | 보낸 지 30분 넘게 `PENDING`·`UNKNOWN`인 결제 | 마지막으로 한 번 더 조회 → 그래도 미종결이면 `FAILED`(EXPIRED) + 재고 반환 (ADR-W5-05) | 결제 생성 |

- 두 스케줄러는 섞지 않는다: 정리는 "보내지 못한 일"을, reconcile은 "결과를 모르는 일"을 다룬다.
- 발송 claim과 정리는 같은 조건부 UPDATE(`WHERE status='PENDING'` / `'REQUESTED'`)로 경쟁한다. 이긴 쪽 하나만 효과를 낸다 → 서버가 여러 대여도 발송 1회 또는 실패 1회.
- 결제 생성은 **어떤 경우에도 두 번 보내지 않는다.** 보내지 못했으면 실패로 닫고, 보낸 뒤 모르면 조회만 한다.
- 정리 주기: 대기 30초 + 주기 1분 → 놓친 건은 최악 약 1분 30초 안에 실패로 닫혀 재고가 풀린다. `fixedDelay`라 실행이 겹치지 않는다.

## 6. 회복 전략 (Resilience4j) ✅

설정은 `application.yml`의 `resilience4j.*`, 적용은 `LocalPgPaymentGatewayAdapter`가 코드로 한다 (재시도 → 회로 → HTTP 순으로 감싼다. 포트 예외로 바꾸는 곳이 한 곳이다).

| 항목 | 생성 (`pgCreate`) | 조회 (`pgLookup`) |
|---|---|---|
| timeout | 연결 1s · 읽기 3s | 연결 1s · 읽기 1s |
| retry | **없음** (인스턴스 자체가 없다. JDK HttpClient라 숨은 POST 재시도도 없다) | 최대 3회(1+2), 100ms·200ms 지수 + jitter ±50%. timeout·5xx·IO·해석 불가만 재시도 |
| circuit breaker | 최근 10건(COUNT) · 최소 5건 · 실패율 50% · 느린 호출 2s(100%) · OPEN 10s · HALF_OPEN 2건 | 같음 |
| 실패로 세지 않음 | `PgRejectedException`(업무 거절 4xx) | 404 외 4xx |
| 회로 OPEN일 때 | 호출 전 차단 → `FAILED`(효과 0건) + 재고 반환 (ADR-W5-03) | `PgLookupUnavailableException` → 상태 유지, 확인 기록만 (실패 확정 X) |
| TimeLimiter | 없음 | 없음 |

- 생성도 2초 넘는 호출을 "느림"으로 센다 ✅: 느려지는 PG를 timeout(3s)까지 기다리지 않고 일찍 차단한다 (부하 가정 0-1: 3s × 100 TPS면 Tomcat 200을 넘는다).
- OPEN 10초 ✅ — 단, **부하 테스트로 검증한다**: 타임세일 중 OPEN 동안의 결제는 모두 실패로 닫힌다(ADR-W5-03). 짧으면 회복 중인 PG를 다시 두드린다. 측정 결과로 다시 정한다.
- 조회 1회 최대 예산 ≈ 1s×3 + 0.3s = 3.3s.
- TimeLimiter를 쓰지 않는 이유: 동기 HTTP client의 읽기 timeout이 실제 소켓을 끊는다. TimeLimiter는 Future 대기만 끊고 HTTP는 계속 돌 수 있다 (발제).
- 검증: `LocalPgPaymentGatewayAdapterTest` G-1~G-11. 생성 재시도를 일부러 넣으면 G-3·G-5·G-6이, 업무 거절 제외를 지우면 G-7이 실패함을 확인했다.

## 7. PG 어댑터 ✅ — 로컬 테스트 PG가 체크리스트 경로, Toss·PortOne은 같은 포트 뒤 추가 어댑터

출력 포트 `PaymentGatewayPort` (`payment.application.port.out`): `request(PaymentRequest)`, `findByTransactionKey(key)`, `findByProviderOrderId(id)`.

- 로컬 테스트 PG는 과제 계약대로 **직접 만든 테스트 fixture**(HTTP 서버)다. 응답 유실·지연·500·중복 callback을 마음대로 일으킬 수 있어 체크리스트 증거가 된다. 계약대로 멱등 키를 받지 않는다.
- Toss는 `Idempotency-Key`를 받으므로 승인 timeout 뒤 재시도가 허용된다 — "키가 있는 PG는 재시도, 없는 PG는 조회만"을 두 어댑터로 대비한다.

## 8. API ✅ (과제 3절) — 결제 API는 `payment.adapter.in.web.PaymentController`

확정 API를 `payment` 쪽 컨트롤러에 둔다: 결제가 주문·재고를 부르므로, 주문 컨트롤러가 결제를 부르면 기능 사이 순환이 생긴다 (ArchitectureTest). 경로는 그대로다.

| API | 동작 | 응답 |
|---|---|---|
| `POST /api/v1/orders/{orderId}/confirm` | 헤더 `Idempotency-Key`(없거나 비면 400), body `{cardType, cardNo}`(비면 400). 금액은 저장된 주문에서. 같은 키 재요청은 현재 상태 반환, 다른 주문에 쓴 키·이미 다른 키로 시작한 주문·DRAFT 아님은 409, 남의 주문 404 | 미종결(REQUESTED·SENDING·PENDING·UNKNOWN) **202** / 종결 **200** (풀 거절로 즉시 NOT_SENT 실패 포함) |
| `GET /api/v1/orders/{orderId}/payment` | 본인 결제 상태. PG 호출 없음 | 200 / 404 |
| `POST /api/v1/orders/{orderId}/payment/reconcile` | 본인 미완료 시도의 거래를 조회 → 결과 적용 | 종결 200 / 미종결 202 |
| `POST /api/v1/payments/callback` | `{transactionKey, orderId, status}` (`orderId` = providerOrderId). 본문은 계기일 뿐 → 권위 조회 → 결과 적용. `X-USER-ID` 없이 호출된다 | 200 / 모르는 주문 404 |
| `GET /api/v1/orders/{orderId}` | 주문 상태(`PAYMENT_PENDING`·`CONFIRMED`·`PAYMENT_FAILED`)·결제수단(`PG`·이전 `POINT`) | 200 |

응답 본문: `{orderId, orderStatus, paymentStatus, failureReason, transactionKey}`. 카드 번호는 저장·로그 금지 (요청 처리 중 메모리 이벤트로만 PG에 전달, `toString`에서 가린다).

## 9. 저장 컬럼 ✅ — PG 응답을 어디까지 남기나

`payments`: `order_id`(uk) · `user_id` · `attempt_key`(uk) · `provider` · `provider_order_id`(uk) · `amount` · `status` · `transaction_key` · `pg_status`(PG가 마지막으로 알려 준 상태) · `failure_reason` · `requested_at` · `sent_at` · `completed_at` · `last_checked_at` · `check_count` · `last_check_result`(`OBSERVED:*`·`CONFLICT:*`·`MISMATCH:*`·`NOT_FOUND`·`MULTIPLE:n`·`UNAVAILABLE`) · **`next_check_at`** ✅(reconcile 다음 확인 시각. 5s → 10s → 30s → 1m → 5m, 종결이면 null).
`payment_outbox`: `payment_id`(uk) · `event_type` · `status`(PENDING·CLAIMED·DONE·CLOSED) · `claimed_at` · `completed_at`.
`stock_reservations`: `order_id`·`product_id`(uk) · `quantity` · `status`(RESERVED·CONSUMED·RELEASED).

- 원문 JSON은 남기지 않는다: Toss 응답에 `secret`·마스킹 카드 번호가 들어 있다 (spike T1). 카드 번호 컬럼은 없다.
- `product.stock`은 이제 **가용 재고**다. 관리자 재고 설정(`changeStock`)도 가용 재고를 설정하며, 결제 대기 주문이 선점한 수량은 포함하지 않는다.

## 10. 장면 테스트 (과제 6절)

| # | 장면 | 핵심 기대 결과 | 테스트 |
|---|---|---|---|
| S-1 | 정상 PG 주문 | 외부 거래 1, 주문 CONFIRMED, 예약 소비 1, 포인트 변화 0 | ✅ `S01*ScenarioTest` |
| S-2 | 생성 수락 PENDING | 주문 미확정, 예약 유지, 조회 SUCCESS 뒤에만 확정 | ✅ `S02*ScenarioTest` |
| S-3 | 효과 전 확정 거절 | 외부 거래 0, 주문 실패, 예약 반환 1 | ✅ `S03*ScenarioTest` |
| S-4 | 거래 생성 뒤 응답만 유실 | 거래 1·UNKNOWN, create 추가 0, 조회로 종결 | ✅ `S04*ScenarioTest` |
| S-5 | 같은 주문·시도 동시 요청 | 시도·예약 각 1, create 1, 다른 키·불일치 거절 | ✅ `S05*ScenarioTest` |
| S-6 | callback-first·lookup-first·동시 적용 | 같은 최종 상태, 추가 차감·중복 반환 0 | ✅ `S06*ScenarioTest` |
| S-7 | 외부 성공 뒤 내부 저장 실패 | rollback, 재조회로 한 번 확정, create 추가 0 | ✅ `S07*ScenarioTest` |
| S-8 | 발송 기록 뒤 재시작 | 시도·예약 유지, 자동 재발송 0 | ✅ `S08*ScenarioTest` |
| S-9 | 조회 0건·복수·불일치 | 임의 종결·재결제 없음, 이상 기록 | ✅ `S09*ScenarioTest` |
| S-10 | timeout·회로 OPEN·복구 | 호출 수 제한, 상태 유지, 회복 뒤 조회 재개 | ✅ `S10*ScenarioTest` |
| S-11 | 기존 기능 보호 | POINT 기록·소유권, 재고 부족 시 PG 0회, 삭제 상품 | ✅ `S11*ScenarioTest` |
| S-12 | 보낸 지 30분 미종결 (ADR-W5-05) | 31분 → 마지막 조회 후 FAILED(EXPIRED)·재고 반환 1, 29분 → 그대로, 마지막 조회 SUCCESS → 확정, 만료 뒤 SUCCESS → CONFLICT·재고 변화 0, 만료·callback 동시 → 반환 1 |  ✅ `S12ExpireUnresolvedScenarioTest` |

재고 예: 재고 5 · 수량 2 확보 → 가용 3. 성공 후 3, 확정 실패 후 5. 중복 실패 알림으로 7, UNKNOWN에서 5가 되면 버그.

## 10-1. 기존 테스트 이전 ✅

포인트로 확정하던 테스트(주문 확정 통합·동시성, 교착·잠금 대기·경쟁 조건 E2E, 구매 흐름 등 12개 파일)는 지우지 않고 **증명하던 의도를 PG 흐름으로 옮긴다**: 초과 판매 방지 → TX A 재고 선점, 잠금 순서·교착 없음 → TX A의 order → product 순서, 포인트 차감 기대 → "포인트 변화 0"(C-1).

## 11. ADR

### ADR-W5-01 · outbox 테이블 + AFTER_COMMIT 비동기 발송 + 정리 스케줄러 (Kafka 없음, 스케줄러는 commerce-api 안)

- **상황**: PG 생성 요청을 "보내야 한다"는 사실이 commit 직후 서버 재시작으로 사라지면 안 된다. 과제 PG는 멱등 키가 없어 재전송하면 거래가 둘 생길 수 있다. 부하 가정(0-1)에서 PG가 3초로 느려지면 요청 스레드가 기다리는 구조는 Tomcat 스레드 200개를 넘긴다.
- **선택**
  1. TX A에서 outbox 행을 함께 저장한다 (장부).
  2. commit 성공 시에만 `PaymentRequested` 이벤트를 비동기 스레드풀(50)에 넘기고 요청은 즉시 202를 받는다. 과제 PG는 생성 응답이 어차피 `PENDING`이라, 동기로 기다려도 고객이 받는 결과는 같다. 풀이 가득 차면 넘기지 않는다 (격벽).
  3. 이벤트는 메모리에만 있으므로 놓친 행은 `PaymentDispatchCloser`가 1분마다 찾아 실패로 닫는다 (ADR-W5-03, 평소 0건).
  4. 스케줄러는 `commerce-api` 안 `payment.adapter.in.scheduler`에 **방아쇠만** 둔다. 판단·상태 변경은 유스케이스(application)에 있다.
- **버린 대안과 비용**

  | 대안 | 버린 이유 |
  |---|---|
  | outbox → Kafka → 컨슈머 → PG | outbox 행을 받는 쪽이 PG 호출 하나뿐이다. Kafka의 최소 1회 전달이 "두 번 보내면 안 되는" 생성과 맞지 않아 중복 방지 장치가 더 든다 |
  | CDC(Debezium이 binlog를 읽어 전달) | 폴링 없이 "생기면 바로 전달"을 안전하게 하는 표준이지만 Kafka·Kafka Connect 운영이 필요하다 |
  | 1초 폴링 relay만 | 평소 빈 조회가 대부분이고, 주기를 줄이면 DB를 두드리고 늘리면 늦다 |
  | 요청 스레드 동기 발송 | PG 지연 시 Tomcat 스레드가 모자라 결제와 무관한 API까지 멈춘다 |
  | 스케줄러를 `commerce-batch`로 분리 (B1 공유 모듈 추출 · B2 batch가 api 내부 엔드포인트 호출) | 지금 batch는 Job 하나를 돌리고 종료하는 앱이라 외부 cron이 필요하다. B1은 결제 도메인·PG 어댑터를 공유 모듈로 옮기는 큰 구조 변경이고, B2는 내부 엔드포인트 보호가 더 든다. 체크리스트에 주는 이득이 없다 |
- **치르는 비용**: 비동기라 테스트는 "몇 초 안에 상태가 바뀐다"를 기다리며 확인한다(Awaitility). 서버 내 스레드풀이라 재시작 시 대기 중 작업은 사라진다 → 정리 스케줄러가 실패로 닫는다.
- **재검토 조건**: API 서버가 발송 부하를 같이 지는 게 측정되거나 PG 동시 호출을 서버 대수와 무관하게 한 곳에서 조절해야 하면 방아쇠를 `commerce-batch`(또는 별도 워커)로 옮긴다. 결제 완료를 다른 곳(알림·통계)에 퍼뜨릴 일이 생기면 outbox → Kafka relay나 CDC를 검토한다.

### ADR-W5-02 · 재고는 결제 요청 시점에 선점한다

- **상황**: PG 결과는 나중에 온다. 그 사이 재고를 잡지 않으면 몰릴 때 재고 1개에 결제 성공이 여럿 생긴다.
- **선택**: TX A에서 가용 재고를 줄이고 예약 행을 만든다. 성공이면 소비, 확인된 실패면 한 번 반환.
- **버린 대안**: 성공 시점 차감 — 초과 판매가 나면 자동 환불이 필요한데, 몰릴 때는 환불 PG 호출도 함께 실패한다 (기현님 실무 경험). 이번 범위에 환불이 없다.
- **비용**: 결제창에서 떠난 사람이 재고를 쥔다 → 보내기 전 구간에만 TTL을 둔다(Toss·PortOne 때).
- **재검토 조건**: 재고가 넉넉한 일반 상품만 다루게 되면 성공 시점 차감도 다시 본다.

### ADR-W5-03 · 보내지 못한 결제는 늦게 보내지 않고 바로 실패로 닫는다

- **상황**: commit 뒤 발송을 놓치는 경우가 있다 — 피크에 스레드풀 가득 참(격벽), 배포·재시작 때 대기 중 작업 소실, 비정상 종료, 회로 OPEN. 방치하면 주문은 영원히 `PAYMENT_PENDING`, 재고는 영원히 선점 상태다.
- **선택**: claim 전(=PG에 보내지 않은 게 확실한) 결제는 `FAILED`(효과 0건)로 닫고 재고를 한 번 반환한다. 풀 거절과 회로 OPEN은 그 자리에서, 소실은 정리 스케줄러가 30초 뒤에 닫는다. claim 이후(보냈을 수 있음)는 실패로 닫지 않고 `UNKNOWN` → 조회.
- **버린 대안**: ① 늦게라도 보낸다 — 고객이 떠난 뒤 카드가 승인되는 "왜 이제 결제됐지" 민원이 생기고, 발송 경로가 둘(이벤트·relay)이 되어 두 번 보내지 않음을 지킬 곳이 늘어난다. ② 10분까지 늦게 보내고 그 뒤 실패 — 규칙이 둘이고 경계 시각 테스트가 더 든다.
- **비용**: 피크에 풀이 가득 차면 그 결제는 실패한다. 이번 범위는 "실패 후 새 결제 시도"가 없으므로 고객은 새 주문으로 다시 결제해야 한다. 풀 거절은 요청 스레드에서 바로 알 수 있으므로 202 대신 실패 결과를 즉시 돌려준다.
- **재검토 조건**: 피크 실패율이 측정상 문제가 되면 풀 크기를 키우거나, 같은 주문의 재시도 허용(범위 밖)을 먼저 검토한다.

### ADR-W5-04 · 결과 반영은 payment 행 비관적 잠금 + 상태 가드로 멱등하게

- **상황**: 같은 결과가 callback 중복·callback과 reconcile 겹침·늦은 생성 응답으로 여러 번 온다. callback과 reconcile은 설계상 자주 겹친다.
- **선택**: payment 행 `FOR UPDATE` + 종결 상태 가드 + 주문·예약 조건부 전이. 결과는 반환값(`APPLIED`·`ALREADY_APPLIED`·`CONFLICT`)으로 알린다 — 중복은 정상 흐름이라 예외로 다루지 않는다. 외부 조회는 잠금 밖에서 한다.
- **버린 대안**: ① 낙관적 잠금(`@Version`) — 겹침이 드물지 않아 충돌 예외 → 재시도 코드가 늘고, 재시도해도 결국 `ALREADY_APPLIED`다. ② 분산 락(Redis) — 지킬 자원이 DB 한 행이고 DB 잠금이 이미 서버 여러 대에 걸쳐 동작한다. Redis 장애·락 만료 같은 실패 모드만 는다. ③ inbox(처리한 메시지 ID 테이블) — 과제 callback에 메시지 ID가 없고, 결제 상태가 이미 처리 기록 역할을 한다.
- **비용**: 잠금 동안 같은 결제의 다른 반영은 기다린다. 잠금 구간은 DB 갱신만이라 짧다.
- **재검토 조건**: 결과 반영 경로에 외부 호출이 들어오거나 같은 행 대기가 측정상 문제가 되면 다시 본다.

### ADR-W5-05 · 30분 넘게 결과를 모르는 결제는 실패로 닫고 재고를 돌려준다

- **상황**: 보낸 직후 서버가 죽었는데 PG에는 거래가 없으면, 조회는 영원히 0건이다. 0건은 영구적인 증거가 아니라 실패로 확정하지 않았으므로(3-2), 그 결제는 영원히 `UNKNOWN`이고 선점한 재고도 영원히 묶인다.
- **선택**: 보낸 지 30분이 지나도 `PENDING`·`UNKNOWN`이면 마지막으로 한 번 더 조회하고, 그래도 미종결이면 `FAILED`(사유 `EXPIRED`)로 닫고 재고를 한 번 반환한다. 재고는 돌려줘야 한다 (학습자 결정).
- **버린 대안**: 일정 횟수 넘게 확인이 안 되면 `NEEDS_REVIEW`로 두고 사람이 처리 — 실제 결제된 건을 실패로 닫을 위험은 없지만, 사람이 볼 때까지 재고가 묶인다.
- **비용**: 실제로는 PG가 승인했는데 만료 뒤 SUCCESS가 오면, 결제는 FAILED 그대로 두고 `CONFLICT`로 기록·ERROR 로그를 남긴다 (재고는 다시 건드리지 않는다). 돈은 빠졌는데 주문은 실패라 **환불이 필요**하고, 환불은 이번 범위 밖이다.
- **재검토 조건**: 만료 뒤 SUCCESS(`CONFLICT`)가 실제로 관찰되면 30분을 늘리거나 환불(취소 API) 연동을 먼저 한다.

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v0.1 | 2026-10-20 | 초안: 식별자·상태·TX 경계·회복 전략·API·장면. Q1 outbox, Q2 재고 예약, Q3 PG 범위 결정 대기 |
| v0.2 | 2026-10-21 | 합의: TX 경계, 부하 가정(0-1), 멱등 키는 `Idempotency-Key` 헤더, 재고 선점(ADR-W5-02), outbox 테이블 + 요청 스레드 발송 + 복구 스케줄러(ADR-W5-01, Kafka 없음), 로컬 테스트 PG fixture가 체크리스트 경로 |
| v0.3 | 2026-10-21 | 발송을 AFTER_COMMIT 비동기(풀 50) + relay 안전망(1분, 30초 대기)으로, relay와 reconcile 스케줄러 분리, relay는 commerce-api 안 방아쇠로(ADR-W5-01 재작성) |
| v0.4 | 2026-10-21 | 놓친 발송은 바로 실패로 닫는다(ADR-W5-03): relay 스케줄러를 정리 스케줄러(`PaymentDispatchCloser`)로 바꾸고, 회로 OPEN 차단·풀 거절도 `FAILED`(효과 0건)로. 결제 생성 재발송 경로 없음 |
| v0.5 | 2026-10-21 | 결과 반영 멱등성(4-1, ADR-W5-04): payment 비관적 잠금 + 종결 상태 가드 + 조건부 전이, 반환값 `APPLIED`·`ALREADY_APPLIED`·`CONFLICT`. PG 조회를 잠금 밖으로 옮김. 기대값 P-1~13·R-1~5 합의 |
| v0.6 | 2026-10-21 | 구현 중 결정: §6 설정값 확정(생성도 느린 호출 2s로 차단, OPEN 10s는 부하 테스트로 검증), §9 `next_check_at` 추가·가용 재고 뜻, P-1~P-13 표(3-2-1), callback 필드는 `orderId`, 기존 테스트는 의도를 PG 흐름으로 이전(10-1) |
| v0.7 | 2026-10-21 | §8 API를 구현에 맞춤: 결제 API는 payment 컨트롤러(순환 방지), 결제 상태 조회 `GET …/payment` 추가, 202/200 규칙, 멱등 키 400·409 |
| v0.8 | 2026-10-21 | 구현 완료 반영: 체크리스트 C-1~C-8 증거, 장면 S-1~S-11 테스트 위치. C-9는 실험 자원 정리(PR 전) 남음 |
| v0.9 | 2026-10-22 | ADR-W5-05: 보낸 지 30분 넘게 미종결이면 마지막 조회 후 FAILED(EXPIRED) + 재고 반환. 포인트 결제 배분 코드 제거. 실험 자원 정리. P-14·P-15, S-12 추가 |
