# 포인트 결제의 PG 교체와 외부 결과 반영 설계 (W5) - AI용 상세

> 사람이 읽는 요약은 [pg-payment-design.md](pg-payment-design.md)다. 이 문서는 구현에 필요한 상세(상태 전이, 오류 우선순위, 시나리오)를 담는다.

> **외부 시스템을 기다리는 시간과, 외부에서 실제로 생긴 결과를 구분한다. 외부 결과는 확인한 뒤 주문·재고에 한 번만 반영한다.**

week4까지의 상품·주문·관리자 API와 동시성 제어를 유지하고, **주문 확정의 결제 경계를 포인트에서 로컬 테스트 PG(HTTP)로 교체**하는 설계 문서다.
이 문서는 week2·week3 문서를 고치지 않고 **바뀌는 부분만** 담는다. 확정된 내용만 단정적으로 적고, 구현하며 더 나은 판단이 나오면 이 문서를 고친다. 아직 실측하지 않은 내용은 "가설" 또는 "열린 항목"으로 구분한다.

## 0. week5 범위와 이전 문서와의 관계

| 구분 | 내용 |
|---|---|
| 필수 | ① 주문 확정에서 PG 결제 시작 ② 시도·외부 거래·결과 확인 경계 ③ PG 호출 전 재고 확보와 결과 반영 ④ callback·reconcile·조회 회복(timeout, 제한된 재조회, circuit breaker) |
| 하지 않음 | 실패 후 새 결제 시도, 환불, 정산, 사용자 취소, 실제 카드 저장, 상시 스케줄러(선택 과제), 별도 PG 프로세스, 운영 DB 이관 |
| 전제 | 실제 카드·금전·외부 PG 계정을 쓰지 않는다. PG 계약은 이 실습이 정의한 로컬 HTTP 계약이다. 포인트 결제 코드는 남기지만 더 이상 쓰지 않으며, 과거 POINT 주문은 이미 `CONFIRMED`라고 가정한다 |

### 이전 결정과의 관계

| 이전 | 상태 | week5의 처리 |
|---|---|---|
| D-10·D-21 확정은 단일 트랜잭션에서 재고·포인트 차감 | **대체** | 확정이 세 단계(TX A / HTTP / TX B)로 나뉜다. 포인트는 차감하지 않는다 (D-45) |
| D-26 포인트는 비관적 잠금으로 보호 | **confirm에서 제외** | 충전·조회 API의 잠금은 그대로다. 주문 확정 경로에서 사용자 잠금이 빠진다 |
| D-27 잠금 순서 주문 → 상품(id 오름차순) → 사용자 | **유지** | TX A·TX B 모두 주문 → 상품(id 오름차순) 순서를 지킨다. 사용자는 잠그지 않는다 |
| D-44 `PaymentResult`는 결제 수단의 상태를 담지 않는다 | **유지** | 구조를 바꾸지 않는다. 결제액만 담고, PG 결제 사실은 `PaymentAttempt`가 가진다 |
| D-5 soft delete | **유지** | 이미 재고를 확보한 결제 대기 주문의 실패 반환은 삭제된 상품 행에도 수량을 돌려주되 `deletedAt`은 건드리지 않는다 (D-46) |

---

## 1. 교체 범위와 식별자

### 1-1. 무엇이 바뀌고 무엇이 남는가

| 대상 | 변경 | 유지 |
|---|---|---|
| 주문 확정(`POST /orders/{id}/confirm`) | PG 결제 *시작*으로 바뀐다. 주문은 `PAYMENT_PENDING`이 된다 | 소유권, 품목, 양수 수량, 저장 금액, 삭제·재고 검사 |
| 포인트 | 주문 결제에서 분리. 차감 0회 | 충전·조회 API, `User.payPoint` 등 도메인 코드 |
| 주문 상태 | `PAYMENT_PENDING`, `PAYMENT_FAILED` 추가 | `DRAFT`, `CONFIRMED` |
| 재고 | 결제 시작 시 가용 재고에서 차감, 확정 실패 시에만 반환 | `products.stock`(의미는 가용 재고), 관리자 재고 설정 API |

`POST /confirm`은 이름이 "확정"이지만 이제 **결제를 시작하는 요청**이다. 주문이 `CONFIRMED`가 되는 때는 PG 성공을 확인한 뒤(TX B)다. 엔드포인트 이름은 기존 계약을 지키려고 그대로 둔다.

### 1-2. 식별자 4종

| 식별자 | 만드는 곳 | 저장 | 용도 |
|---|---|---|---|
| `orderId` | commerce | `orders.id` | 주문 소유권·품목·상태 |
| `paymentAttemptKey` | 고객 요청 | `payment_attempts.attempt_key` (UNIQUE) | 같은 시도의 중복 요청 구분, 요청 일치 검사 |
| `providerOrderId` | commerce adapter | `payment_attempts.provider_order_id` (UNIQUE) | PG 요청·주문 단위 조회. `LP-ORD-%010d` (주문 42 → `LP-ORD-0000000042`) |
| `transactionKey` | 테스트 PG | `payment_attempts.transaction_key` (UNIQUE, nullable) | PG 거래 한 건 조회. 응답을 받기 전에는 null |

`transactionKey`는 PG가 발급하므로 commerce가 만들 수 없다. 응답을 잃은 시도는 키 없이 `providerOrderId`로 조회해 찾는다. 테스트 PG는 호출자의 멱등 키를 받지 않으므로 키 문자열만으로는 멱등성이 생기지 않는다. **저장 위치(DB 유일 제약)와 상태 전이 조건**이 멱등성을 만든다.

---

## 2. 상태 모델

### 2-1. 주문

```
DRAFT ──start──▶ PAYMENT_PENDING ──complete──▶ CONFIRMED
                        │
                        └─────fail────────▶ PAYMENT_FAILED
```

- 전이는 모델 메서드(`markPaymentPending`, `completePayment(PaymentResult)`, `failPayment`)로만 일어난다. 각각 정해진 상태에서만 허용하고, 아니면 예외를 던진다.
- `CONFIRMED`·`PAYMENT_FAILED`는 종결이다. 나가는 전이가 없다.
- `PaymentResult`는 week4와 같이 `paidAmount`만 담는다. 결제 수단을 구분하는 필드는 두지 않는다. PG가 포인트 결제를 대체했고 과거 주문은 이미 `CONFIRMED`로 가정하므로 구분할 필요가 없다. PG 결제의 거래 사실은 `PaymentAttempt`에 있다.

### 2-2. 결제 시도 (`PaymentAttempt`)

| 상태 | 의미 | 재고 |
|---|---|---|
| `REQUESTED` | 발송을 시작한다고 DB에 기록함. 실제 HTTP 발송 여부는 불확실 | 확보 유지 |
| `PENDING` | PG가 거래를 만들었고 처리 중임을 확인함 (`transactionKey` 있음) | 확보 유지 |
| `UNKNOWN` | 요청은 보냈으나 거래 생성 여부를 확인하지 못함 | 확보 유지 |
| `SUCCEEDED` | 검증한 SUCCESS | 소비(추가 차감 없음) |
| `FAILED` | 검증한 FAILED 또는 효과 0건 확정 거절 | 반환 1회 |

허용 전이:

| 현재 | 다음 |
|---|---|
| `REQUESTED` | `PENDING`, `UNKNOWN`, `SUCCEEDED`, `FAILED` |
| `PENDING` | `SUCCEEDED`, `FAILED` |
| `UNKNOWN` | `PENDING`, `SUCCEEDED`, `FAILED` |
| `SUCCEEDED`, `FAILED` | 없음 (종결) |

- `PENDING`에서 `UNKNOWN`으로 되돌아가지 않는다. callback이 먼저 종결한 뒤 늦게 돌아온 생성 응답·timeout 처리도 이미 나아간 상태를 되돌리지 못한다.
- 종결 뒤 상충하는 조회 결과는 상태를 덮어쓰지 않고 `anomaly`만 기록한다: `MISMATCH`(식별·금액 불일치), `MULTIPLE_TRANSACTIONS`, `TRANSACTION_NOT_FOUND`, `CONFLICTING_RESULT`.
- 카드 번호는 저장하지 않는다. 시도에는 금액과 식별 관계만 남긴다.

### 2-3. PG 응답 → 상태 매핑

**결제 요청 응답** (adapter가 매핑 단계 한곳에서 판정한다)

| PG 응답 | 시도 | 주문 | 재고 |
|---|---|---|---|
| 202 + 본문(`PENDING`, orderId·금액 일치, transactionKey 있음) | `PENDING` + 키 기록 | `PAYMENT_PENDING` | 유지 |
| 422 + `code=TEST_PG_REJECTED_BEFORE_CREATE` + `transactionCreated=false` + orderId·amount 일치 | `FAILED` | `PAYMENT_FAILED` | 반환 1회 |
| 위 두 가지가 아닌 모든 결과: read timeout, 연결 끊김, 5xx, 본문 없는 422, 다른 code의 4xx, 깨진 JSON, 식별·금액 불일치 | `UNKNOWN` | `PAYMENT_PENDING` | 유지 |

효과 0건 확정 거절은 4가지 조건이 **모두** 맞을 때만 성립한다. 하나라도 어긋나면 효과가 없다는 증거가 아니므로 `UNKNOWN`으로 합류한다.

**조회 결과** (reconcile·callback)

| 조회 결과 | 처리 |
|---|---|
| 1건, 식별·금액 일치, `SUCCESS` | 시도 `SUCCEEDED`, 주문 `CONFIRMED`, 재고 소비 |
| 1건, 식별·금액 일치, `FAILED` | 시도 `FAILED`, 주문 `PAYMENT_FAILED`, 재고 반환 1회 |
| 1건, 일치, `PENDING` | `PENDING` 유지(키 기록). 종결하지 않는다 |
| 1건, 식별·금액 불일치 | `MISMATCH` 기록, 상태 유지, 재발송 금지 |
| 0건 | 상태 유지. 전파 지연일 수 있으므로 거래 없음의 증거가 아니다 |
| 2건 이상 | 첫 거래를 고르지 않는다. `MULTIPLE_TRANSACTIONS` 기록, 상태 유지 |
| 거래 키로 조회했는데 404 | `TRANSACTION_NOT_FOUND` 기록, 상태 유지 |
| 조회 불가(timeout, 5xx, 회로 OPEN) | 상태 유지. 응답에 "확인 불가"를 표시한다 |

---

## 3. 트랜잭션 경계와 흐름

### 3-1. 세 단계

```
[confirm 요청 스레드]

TX A  OrderFacade.prepareConfirm                 ← 로컬 TX (TransactionTemplate)
  주문 FOR UPDATE (소유권 포함)
  → 요청 일치·상태 검사
  → 상품 FOR UPDATE (id 오름차순) → 가용 재고 차감
  → PaymentAttempt(REQUESTED) insert, 주문 PAYMENT_PENDING
  commit                                         ← 발송 권한 = 이 insert를 한 실행

HTTP  PaymentGateway.requestPayment              ← TX 밖 (잠금·커넥션 보유 없음)
  POST /api/v1/payments   (자동 재시도 없음)

TX B  PaymentStatusUpdater.updateByPaymentRequestResult ← 로컬 TX (호출하는 Facade가 TransactionTemplate으로 감쌈)
  주문 FOR UPDATE → 시도 조회
  → 식별·금액 확인 → 멱등 가드
  → 조회 결과에 따라 시도·주문·재고 전이
  commit
```

- 재고 부족·삭제·잘못된 입력이면 TX A가 전체 rollback되고 **PG는 호출하지 않는다**.
- HTTP 호출 동안 DB 트랜잭션과 재고 잠금을 잡지 않는다. 다른 주문은 같은 상품을 기다리지 않는다.
- 시도에 대한 쓰기는 항상 **주문 잠금을 먼저 잡은 뒤에** 한다. 그래서 시도 행에 별도의 잠금 순서가 필요 없다.

### 3-2. 발송 권한

- TX A에서 `PaymentAttempt`를 insert한 실행만 발송 권한을 얻는다. 같은 주문에 동시에 confirm이 들어와도 주문 행 잠금 때문에 한쪽이 먼저 TX A를 끝내고, 나중 실행은 저장된 시도를 보고 **발송 없이** 저장된 상태를 반환한다.
- `order_id`, `attempt_key`, `provider_order_id` 유일 제약은 주문 잠금이 뚫렸을 때의 마지막 방어선이다.
- `REQUESTED`를 기록한 뒤 프로세스가 멈추면 실제 발송 여부를 로컬 기록만으로 알 수 없다. 재시작 뒤 이 시도는 **조회 대상으로만** 남고, 거래가 0건이어도 결제 요청을 다시 보내지 않는다 (D-48).

### 3-3. confirm 요청 규칙

오류 우선순위:

| 순서 | 조건 | 결과 |
|---|---|---|
| 1 | 없는·타인의 주문 | 404 `NOT_FOUND` |
| 2 | 같은 `paymentAttemptKey`가 다른 주문에 쓰임 | 409 `PAYMENT_ATTEMPT_CONFLICT` |
| 3 | 주문에 이미 시도가 있음 | 같은 키·같은 요청이면 **저장된 결과를 반환**(미종결 202, 종결 200, 발송 없음). 다른 키면 409 `PAYMENT_ATTEMPT_CONFLICT` |
| 4 | DRAFT | 상품 삭제 `PRODUCT_UNAVAILABLE` → 재고 부족 `OUT_OF_STOCK` → 시도 생성 |

- 요청 본문: `paymentAttemptKey`(1~64자), `cardType`(공백 불가), `cardNo`(`####-####-####-####`). 금액은 **저장된 주문 합계**로 정하고 요청에서 받지 않는다.
- 카드 값은 합성 값만 쓴다. DTO의 `toString`은 카드 번호를 가리고, DB·로그에 남기지 않는다.
- 주문이 `DRAFT`가 아니고 시도도 없으면(예: 이미 `CONFIRMED`) 기존 `ORDER_ALREADY_CONFIRMED`를 유지한다.

### 3-4. 세 입구, 하나의 결과 적용

```
confirm   ───▶ OrderFacade.confirm (TX 없음, 오케스트레이션)
reconcile ──┐
callback  ──┴─▶ PaymentResultCheckFacade (TX 없음, 오케스트레이션)
                      │
          (둘 다)     ├─ 조회/발송 (TX 밖)
                      └─ PaymentStatusUpdater.updateByStatusCheckResult(...)   ← 유일한 상태 쓰기 지점
```

- **confirm**: TX A → 결제 요청 → `updateByPaymentRequestResult`(결제 요청 결과 반영). `UNKNOWN`이어도 같은 요청 안에서 조회하지 않고 202를 돌려준다 (D-48).
- **reconcile** (`POST /orders/{id}/payment/reconcile`): 본인 주문의 미종결 시도를 조회해 `updateByStatusCheckResult`로 반영한다. 콜백과 무관하게 고객이 부르는 **기본 복구 경로**다.
- **callback** (`POST /api/v1/payments/callback`): 본문의 `providerOrderId`로 시도를 찾고, 본문의 `transactionKey`로 **권위 조회**를 한 뒤 `updateByStatusCheckResult`로 반영한다. 본문의 `status`는 쓰지 않는다. 시도를 못 찾으면 404이고 아무 효과도 없다.
- `GET /orders/{id}`는 **PG를 호출하지 않는** 순수 조회다. 저장된 주문·결제 상태만 보여 준다.
- 콜백은 confirm을 처리하던 스레드의 연속이 아니라 완전히 새로운 HTTP 요청이다. 두 흐름을 잇는 것은 DB의 시도 레코드뿐이다.

### 3-5. 결과 적용 (`updateByStatusCheckResult`)

```
updateByStatusCheckResult(orderId, checkResult):
  TX 시작
  주문 FOR UPDATE → 시도 조회
  시도와 조회 결과의 식별(providerOrderId, transactionKey)·금액 확인 → 불일치면 anomaly 기록 후 종료
  이미 같은 종결 결과이면 아무 효과도 추가하지 않고 종료
  다른 종결 결과와 충돌하면 덮어쓰지 않고 CONFLICTING_RESULT 기록 후 종료
  SUCCESS → 시도 SUCCEEDED, 주문 CONFIRMED(결제액=주문 합계)
  FAILED  → 시도 FAILED, 주문 PAYMENT_FAILED,
            상품 FOR UPDATE (삭제 행 포함, id 오름차순) → 품목별 stock 증가
  PENDING/불확실 → 미완료 유지(PENDING이면 transactionKey 기록)
  commit
```

- 재고 반환의 멱등 가드는 **주문의 `PAYMENT_PENDING → PAYMENT_FAILED` 전이**다. 중복 callback이나 동시 reconcile이 와도 전이는 한 번만 성공하고, 반환도 같은 TX에서 한 번만 일어난다. 단순한 `stock += quantity`를 조건 없이 실행하지 않는다.
- 성공·반환 상태와 재고 변경이 같은 TX에 있으므로, TX B가 DB 오류로 rollback되면 아무것도 바뀌지 않고 **같은 외부 결과를 다시 조회해 적용**하면 된다. 새 결제는 만들지 않는다.

---

## 4. PG 계약과 adapter

### 4-1. 로컬 PG 계약 (실습이 정의한 약속)

| 요청 | 계약 |
|---|---|
| `POST /api/v1/payments` | `orderId, cardType, cardNo, amount, callbackUrl` → HTTP **202** `{transactionKey, orderId, amount, status}` |
| `GET /api/v1/payments/{transactionKey}` | 200 거래 한 건. 없으면 404 |
| `GET /api/v1/payments?orderId=` | 200 같은 주문의 거래 배열(빈 배열 가능) |
| 공통 헤더 | `X-USER-ID: 135135` (합성 제공자 계정. 내부 고객 인증을 대신하지 않는다) |
| 외부 상태 | `PENDING`, `SUCCESS`, `FAILED` |
| 생성 전 확정 거절 | 422 + `{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":...,"amount":...,"transactionCreated":false}` |

- `orderId`는 6자 이상 문자열, `amount`는 원화 정수다. 테스트 PG는 **호출자 멱등 키를 받지 않고 같은 orderId의 새 POST를 중복 제거하지 않는다**고 가정한다.
- base URL·callback URL은 설정으로 `127.0.0.1`에 고정한다. 고객이 임의의 외부 주소를 주입할 수 없다.

### 4-2. 포트와 구현

| 구분 | 위치 | 내용 |
|---|---|---|
| 포트 | `application.payment.PaymentGateway` | 입출력은 application 타입만 쓴다 |
| HTTP 호출 | `infrastructure.payment.PgHttpClient` | URL·헤더·JSON·timeout을 숨기고 응답을 해석한다. 조회는 하지 못하면 `PgStatusCheckFailedException`을 던진다. 조회 메서드에 Retry·CB를 건다 |
| 포트 구현 | `infrastructure.payment.PaymentGatewayImpl` | `PgHttpClient`를 호출하고, 조회 실패 예외(`PgStatusCheckFailedException`, 회로가 막은 `CallNotPermittedException`)를 `COULD_NOT_CHECK`로 바꾼다 |

```
requestPayment(...)                         → outcome: ACCEPTED | REJECTED_WITHOUT_TRANSACTION | UNKNOWN
checkPaymentStatusByTransactionKey(key)     → outcome: ANSWERED(거래 1건) | KEY_NOT_FOUND | COULD_NOT_CHECK
checkPaymentStatusByOrder(providerOrderId)  → outcome: ANSWERED(거래 0건 이상) | COULD_NOT_CHECK
```

- `COULD_NOT_CHECK`는 timeout·5xx·회로 OPEN을 포함한다. 결제 결과가 아니라 "확인하지 못함"이다.
- HTTP client는 `RestClient` + `JdkClientHttpRequestFactory`다. Feign·WebClient는 새로 들이지 않는다.
- timeout은 request factory에 건다: connect 1초, 결제 요청 read 3초, 조회 read 1초. 가정이 아니라 설정 객체에서 확인하는 테스트를 둔다.
- JDK `HttpClient`는 POST를 자동 재시도하지 않는다. 그래도 **fixture의 결제 요청 횟수로** 숨은 재시도가 없음을 확인한다.
- 조회 query는 URI builder로 만들어 인코딩한다.
- `RestClient`는 4xx에 예외를 던지는 것이 기본이다. 조회의 404는 업무 결과(`KEY_NOT_FOUND`)이므로 annotation이 붙은 메서드 **안에서** 결과로 바꿔 회로 실패로 세지 않는다.

### 4-3. 테스트 PG fixture

별도 PG 프로세스를 만들지 않는다. 테스트 소스에 JDK `HttpServer`로 작은 fixture를 둔다.

- `127.0.0.1` 임의 포트에 뜬다. 거래 저장소, 결제 요청·조회 호출 횟수, 받은 요청(메서드·경로·헤더·본문)을 관찰할 수 있다.
- 시나리오를 한 줄로 고정하는 모드를 가진다: 정상 `PENDING` 후 `SUCCESS`/`FAILED` 전이, 거래 생성 뒤 응답 유실, 생성 전 확정 거절, 지연, 5xx, 거래 키 404, 조회 0건·복수 거래·금액 불일치.
- 무작위 지연이나 성공 확률은 넣지 않는다. 테스트가 끝나면 서버를 반드시 정리한다.

---

## 5. 회복 전략

### 5-1. 적용 범위

| 호출 | timeout | Retry | CircuitBreaker |
|---|---|---|---|
| 결제 요청 (비멱등) | 있음 | **없음** | **없음** |
| 거래 조회 2종 | 있음 | 있음 | 있음 |

결제 요청은 다시 실행하면 새 거래가 생길 수 있다. HALF_OPEN 탐색 호출이 실제 거래를 또 만들 위험도 있어 회로도 걸지 않는다. 조회는 같은 거래의 현재 상태를 읽을 뿐이므로 횟수·간격·예산을 제한해 재조회할 수 있다.

### 5-2. 구성 (`resilience4j-spring-boot3` + `spring-boot-starter-aop`, annotation 방식)

버전은 `gradle.properties`에 `resilience4jVersion=2.2.0`으로 명시한다. 스타터가 이미 있다고 가정하지 않는다.

**Retry `pgLookup`**

| 항목 | 값 | 이유 |
|---|---|---|
| `maxAttempts` | 3 | 최초 1회 + 재시도 2회 (라이브러리 계약을 테스트로 확인한다) |
| 대기 | 100ms → 200ms 지수 | 조회 1회 최대 1초 × 3 + 300ms ≈ 3.3초가 전체 예산 |
| 재시도 대상 | `PgStatusCheckFailedException` (연결·timeout, 5xx, 해석할 수 없는 응답) | 일시적 실패. 404 외의 4xx(예: 401)도 같은 예외라 재시도되는 단순화를 받아들인다. 설정 실수일 때 호출이 3회로 늘 뿐이다 |
| 재시도 제외 | `CallNotPermittedException` | 회로가 막은 호출을 다시 두드리지 않는다 |

**CircuitBreaker `pgLookup`**

| 항목 | 값 |
|---|---|
| 윈도우 | COUNT_BASED 10 |
| `minimumNumberOfCalls` | 5 |
| `failureRateThreshold` | 50% |
| `waitDurationInOpenState` | 10초 (`PaymentResilienceTest`는 400ms로 덮어쓴다) |
| `permittedNumberOfCallsInHalfOpenState` | 2 |
| 실패로 세는 것 | `PgStatusCheckFailedException` (연결·timeout, 5xx, 해석할 수 없는 응답) |
| 세지 않는 것 | 404, 빈 목록 등 정상 응답으로 돌아온 업무 결과 |

회로의 표본 크기는 동시 요청 제한이 아니다. 동시 호출을 제한하는 bulkhead와 역할이 다르고, 이번 범위에는 넣지 않는다. 회로도 개별 호출의 timeout을 대신하지 않는다.

### 5-3. 적용 순서가 중요한 이유

기본 순서는 `Retry( CircuitBreaker( 호출 ) )`다. Retry가 바깥이다. PG가 계속 500을 줄 때:

| | Retry 바깥 (기본) | CB 바깥 |
|---|---|---|
| 실제 PG 호출 | 3회 | 3회 |
| 회로가 센 실패 | 3건 | 1건 |
| 회로 열림 | 실제 호출 기준이라 빠르다 | 표본이 느리게 쌓인다 |
| HALF_OPEN 탐색 | 실제 호출 1회가 곧 표본 1건 | 재시도 때문에 최대 3회로 불어난다 |

외부 시스템 보호가 회로의 목적이므로 실제 호출 기준으로 세는 기본 순서를 택한다 (D-50).

**테스트로 고정한다** (`PaymentResilienceTest`, 기준은 fixture가 받은 실제 호출 수와 회로가 센 표본)

- 항상 500을 주는 fixture에 조회 1회 → 실제 호출 3회, 회로가 센 실패 3건 (표본 최소 5건에 못 미쳐 CLOSED). CB가 바깥이면 실패가 1건이 되어 깨진다.
- 같은 조회를 한 번 더 → 5번째 실제 호출의 실패에서 회로가 OPEN 되고, 여섯 번째 시도는 회로가 막아 **실제 호출 5회에서 멈춘다**.
- OPEN 동안 조회 → 실제 호출 증가 0, 조회 불가를 돌려준다. 막힌 호출은 재시도하지 않는다(막힌 시도 1건).
- 대기 후 HALF_OPEN → 회복했으면 탐색 호출 2건이 성공해 CLOSED, 여전히 실패하면 **실제 호출 2건만** 나가고 다시 OPEN. 재시도가 탐색 호출을 늘리지 못한다.
- 404와 빈 목록은 몇 번을 받아도 회로의 실패로 세지 않는다.
- 결제 생성은 실패를 반복해도 요청 수만큼만 PG에 나가고(재시도 0), 조회 회로의 표본에 들지 않으며, 조회 회로가 OPEN이어도 막히지 않는다.
- 조회를 하는 `PgHttpClient`가 AOP 프록시이고, 프록시를 거치면 회로가 호출을 센다. 프록시를 거치지 않으면 센 호출이 0건이라 위 검사가 모두 깨진다. self-invocation 실수도 여기서 잡힌다.

**순서 변이 검증**: `circuitBreakerAspectOrder=1`, `retryAspectOrder=2`로 순서를 일부러 뒤집으면 이 클래스 14건 중 10건이 실패한다. 순서를 지정하는 효과가 실제로 테스트에 잡힌다. (JDK `HttpClient`는 GET을 연결 만료 때 한 번 자동 재시도할 수 있지만, 이 테스트의 500 응답은 정상 응답이라 해당하지 않는다.)

### 5-4. 실패의 변환과 self-invocation

- **`fallbackMethod`를 쓰지 않는다.** annotation을 건 `PgHttpClient`는 조회하지 못하면 예외를 그대로 던지고(그래야 Retry가 재시도하고 CB가 센다), 그 예외를 `COULD_NOT_CHECK` 결과로 바꾸는 일은 `PgHttpClient`를 호출하는 **다른 빈** `PaymentGatewayImpl`이 한다. Retry·CB 바깥에서 변환하므로 fallback이 재시도를 가로채는 일이 없다.
- 변환은 `COULD_NOT_CHECK`를 돌려줄 뿐이다. **결제 결과를 정하지 않는다.** 실패 확정, 성공 응답, 금액 0, 포인트 대체 결제, 예약 임의 반환을 하지 않는다. 회로가 OPEN이어도 기존 `UNKNOWN`·`PENDING`을 미발송으로 되돌리지 않는다.
- annotation은 프록시를 거칠 때만 동작한다. 호출하는 쪽(`PaymentGatewayImpl`)이 다른 빈이므로 자기 호출이 구조적으로 생기지 않는다. 어느 조회를 쓸지의 분기(거래 키 유무)도 adapter 안이 아니라 application(`PaymentResultCheckFacade`)에 둔다. `PgHttpClient` 안에서 `this.getTransaction...`을 부르면 Retry·CB가 조용히 빠진다.

### 5-5. 조회를 시작시키는 트리거

| 트리거 | 이번 범위 |
|---|---|
| callback | 구현 (권위 조회의 계기) |
| 고객의 reconcile | 구현 (기본 복구 경로) |
| 주문 조회(`GET`) 시 자동 조회 | **하지 않음.** `GET`은 PG를 호출하지 않는다 |
| 배치 스케줄러가 장기 미확인 스캔 | 하지 않음 (선택 과제, 열린 항목) |

---

## 6. 반례 점검

| 장면 | 상태와 효과 |
|---|---|
| **A. 생성 후 응답 유실** | PG에 거래 1건 생성, 우리는 read timeout. 시도 `UNKNOWN`, 주문 `PAYMENT_PENDING`, 재고 확보 유지, **결제 요청 추가 0회**. 이후 reconcile이 `providerOrderId`로 조회해 거래 1건을 찾고(키 기록) 종결한다 |
| **B. 생성 전 확정 거절** | 거래 0건. 시도 `FAILED`, 주문 `PAYMENT_FAILED`, 재고 반환 1회. 같은 거절을 다시 적용해도 반환은 늘지 않는다 |
| **C. 성공 뒤 내부 저장 실패** | PG는 `SUCCESS`, TX B가 DB 오류로 rollback. 시도·주문·재고는 변경 전 그대로다. 같은 거래를 다시 조회해 한 번 확정한다. 결제 요청 추가 0회 |
| **D. 중복 callback** | 첫 callback이 종결한다. 이후는 "이미 같은 종결 결과"라 효과 없음. 재고 증가/감소 0 |
| **E. callback-first vs lookup-first** | 둘 다 같은 `updateByStatusCheckResult`를 거친다. 먼저 적용한 쪽이 이기고 나중은 변화 없음. 최종 상태·transactionKey 동일 |
| **F. 동시 callback + reconcile** | 둘 다 주문 `FOR UPDATE`에서 직렬화된다. 한쪽만 전이하고 반환·확정은 한 번 |
| **G. 늦게 도착한 결제 요청 응답/timeout 처리** | callback이 먼저 `SUCCEEDED`로 종결한 뒤 PENDING·UNKNOWN 기록이 오면 종결 상태를 되돌리지 않는다 |
| **H. 위조 callback** | 본문이 `SUCCESS`라도 권위 조회가 `PENDING`이거나 거래가 없으면 확정하지 않는다. 다른 주문의 `transactionKey`면 `MISMATCH` 기록 |
| **I. 발송 기록 뒤 재시작** | `REQUESTED`가 DB에 남는다. 새 서비스 인스턴스가 올라와도 결제 요청을 다시 보내지 않고 조회로만 복구한다. 실제로 한 번도 안 보낸 시도는 영구 미완료일 수 있다 (한계) |
| **J. 동시 같은 시도 confirm** | 시도·재고 확보 각 1회, 실제 결제 요청 1회. 나머지는 저장 상태 반환 |
| **K. 다른 키·다른 요청** | 같은 키의 다른 주문, 같은 주문의 새 키는 409. 두 번째 PG 시도·재고 차감은 만들지 않는다 |
| **L. 조회 0건 / 복수 거래 / 금액 불일치** | 임의 성공·실패·재결제 없음. `anomaly` 기록, 상태·재고 유지 |
| **M. 관리자 재고 설정 중 결제 대기** | 설정값은 **예약을 제외한 가용 수량**이다. 2개 대기 중 10으로 설정 후 실패하면 12가 된다 (대기분은 처음부터 10에 없었다). PG 성공 때 다시 차감하지 않는다 |
| **N. 결제 대기 중 상품 삭제** | 이미 확보를 마친 주문은 저장된 품목·금액·예약으로 PG 결과를 마무리한다. 실패 반환은 삭제된 행에도 수량을 돌려주되 `deletedAt`은 유지해 상품이 다시 노출되지 않는다. 삭제 뒤 새로 시작하는 DRAFT 확정은 `PRODUCT_UNAVAILABLE` |
| **O. 재고 5, 수량 2** | 확보 후 가용 3. 성공 후 3. 확정 실패 후 5. 7(중복 반환)이나 `UNKNOWN`에서 5(조급한 반환)는 버그다 |

---

## 7. API 계약 변경

| 기능 | method · path | 성공 | 대표 오류 |
|---|---|---|---|
| 결제 시작 | `POST /api/v1/orders/{orderId}/confirm` (본문: `paymentAttemptKey`, `cardType`, `cardNo`) | 미종결(`PENDING`/`UNKNOWN`) **202**, 같은 시도의 종결 **200**. 본문: `orderId`, `orderStatus`, `payment{status, amount}`, `links.order`(주문 조회 경로). 카드 값은 응답에 담지 않는다 | 404, 400, 409 `PAYMENT_ATTEMPT_CONFLICT`, 409 `PRODUCT_UNAVAILABLE`·`OUT_OF_STOCK`·`ORDER_ALREADY_CONFIRMED` |
| 결제 확인 | `POST /api/v1/orders/{orderId}/payment/reconcile` | 결제 시작과 같은 응답 모양. 미종결 202, 종결 200. PG 를 조회하지 못했으면 저장된 상태와 `lookupUnavailable: true`(조회했다면 생략). 이미 종결된 시도는 PG 를 부르지 않는다 | 404(없는·타인 주문), 409 `CONFLICT`(결제를 시작하지 않은 주문), 400(형식·헤더 누락) |
| PG 알림 | `POST /api/v1/payments/callback` (본문: `providerOrderId`, `transactionKey`, `status` 모두 필수, 식별 헤더 없음) | 200 수신 확인 `{orderId, orderStatus, paymentStatus, lookupUnavailable?}`. 본문의 `status` 는 쓰지 않고 `transactionKey` 로 PG 를 조회해 확인한 사실만 반영한다 | 404(모르는 `providerOrderId`, 효과 없음), 400(본문 오류) |
| 주문 상세 | `GET /api/v1/orders/{orderId}` | 기존 응답 + `payment{status, paidAmount?}`. PG 호출 없음 | 기존과 동일 |
| 관리자 주문 조회 | `GET /api-admin/v1/orders[/{id}]` | 결제 대기·미확인·최종 결과 표시 | 기존과 동일 |

- 새 `ErrorType`: `PAYMENT_ATTEMPT_CONFLICT`(409). `ErrorTypeHttpStatusMapper`에 매핑을 추가한다.
- `/api/v1/orders/**`는 이미 `IDENTIFIED_PATHS`에 있으므로 confirm·reconcile은 요청자를 식별한다. **`/api/v1/payments/callback`은 식별 경로에 넣지 않는다** (PG가 보내는 요청이라 `X-USER-ID`가 없다). `/api/v1/payments/**` 같은 넓은 패턴을 등록하면 callback이 식별 필터에 걸리므로, `SecurityConfigTest`로 고정한다.
- 식별이 필요한 confirm·reconcile에는 "헤더 누락 400" E2E를 둔다.
- callback의 성공 응답은 PG에 대한 수신 확인일 뿐이다. 업무 결과는 DB의 최종 상태다.

---

## 8. 검증 설계

### 8-1. 원칙

- **상태값이 아니라 효과 횟수**로 검증한다: 외부 거래 수, 결제 요청 횟수, 조회 횟수, 가용 재고, 포인트 잔액 변화.
- 형식 확인(실제 HTTP 계약)과 업무 확인(순서·효과)을 분리한다. 같은 조건을 여러 계층에 반복하지 않는다.
- 실제 DB는 기존 MySQL Testcontainers fixture를 쓴다. callback도 실제 controller를 거친다.
- 대기는 유한하게 제한한다. 테스트 0건·skip·연결 실패를 성공으로 세지 않는다. fixture 서버와 worker는 정리한다.

### 8-2. 과제 장면 → 테스트

| 장면 | 핵심 기대 | 테스트 |
|---|---|---|
| 정상 PG 주문 | 거래 1, 주문 `CONFIRMED`, 재고 소비, 포인트 변화 0 | `PaymentIntegrationTest` |
| 생성 수락 PENDING | 주문 미확정, 재고 유지, 조회 SUCCESS 뒤에만 확정 | `PaymentIntegrationTest` |
| 효과 전 확정 거절 | 거래 0, 주문 실패, 반환 1 | `PaymentIntegrationTest`, `PaymentProviderContractTest` |
| 생성 뒤 응답 유실 | 거래 1·`UNKNOWN`, 결제 요청 추가 0, 조회로 종결 | `PaymentIntegrationTest` |
| 같은 시도의 동시 요청 | 시도·확보 각 1, 결제 요청 1, 다른 키·불일치는 효과 없이 거절 | `PaymentConcurrencyTest` (`ConcurrentRequests`) |
| callback-first·lookup-first·동시 적용 | 같은 `transactionKey`·최종 상태, 재고 5→3→5(7 금지) | `PaymentConcurrencyTest` |
| 성공 뒤 내부 저장 실패 | rollback, 같은 거래 재조회로 한 번 확정, 결제 요청 추가 0 | `PaymentIntegrationTest` (`FailingStatementInspector`) |
| 발송 기록 뒤 재시작 | 시도 유지, 자동 재발송 0 (서비스·client 재생성, DB·fixture 유지) | `PaymentRestartTest` |
| 조회 0건·복수 거래·불일치 | 임의 성공·실패·재결제 없음, anomaly 보존 | `PaymentIntegrationTest` |
| timeout·회로 OPEN·복구 | 호출 수·대기 제한, 상태 유지, 회복 뒤 조회 재개 | `PaymentResilienceTest` |
| 기존 기능 보호 | 재고 부족 시 PG 0회, 삭제·예약 결과 구분, 소유권 | `PaymentIntegrationTest`, `OrderPaymentApiE2ETest` |
| 위조 callback | 조작한 본문만으로 성공하지 않음 | `PaymentCallbackApiE2ETest` |
| HTTP 형식 | 메서드·경로·헤더·JSON 타입·금액·인코딩·응답 매핑 | `PaymentProviderContractTest` |

### 8-3. 검사 명령

```bash
./gradlew :apps:commerce-api:test --tests '*PaymentProviderContractTest'
./gradlew :apps:commerce-api:test --tests '*PaymentResilienceTest'
./gradlew :apps:commerce-api:test --tests '*PaymentIntegrationTest'
./gradlew :apps:commerce-api:check   # Checkstyle + ArchUnit + 전체 회귀
```

---

## 9. 설계 결정 기록

번호는 week4의 D-44에서 이어진다.

### D-45. 주문 결제를 PG로 교체하고 포인트를 결제에서 분리한다

- **상황**: week3까지 확정은 같은 DB에서 재고·포인트를 차감했다. PG는 외부 시스템이라 효과를 rollback할 수 없다.
- **결정**: confirm은 PG 결제를 *시작*하고, 주문은 `PAYMENT_PENDING`을 거쳐 `CONFIRMED` 또는 `PAYMENT_FAILED`로 끝난다. 새 결제는 포인트를 차감하지 않는다. 포인트 충전·조회와 `payPoint` 코드는 남기지만 주문 결제에서는 쓰지 않는다. `PaymentResult`는 바꾸지 않는다 (D-44 유지).
- **근거**: "결제수단을 바꾼다"는 포인트 차감과 PG 호출을 겹친다는 뜻이 아니다. 외부 연결은 adapter 뒤로 분리해 Controller·Entity가 카드 JSON과 네트워크 오류를 알지 않게 한다.
- **비용**: 확정이 한 번의 호출이 아니라 상태 머신이 된다. 고객은 202를 받은 뒤 조회·reconcile로 결과를 알아야 한다.
- **재검토 조건**: 포인트와 PG를 혼합 결제하는 요구가 생기면.
- **기각한 대안**: 포인트 차감 후 PG 호출(이중 부담, 롤백 불가), 포인트 코드 삭제(과거 기록 모델과 충돌 소지, 범위 밖 개편).

### D-46. 재고는 가용 수량만 저장하고, 예약은 주문 상태로 표현한다

- **상황**: PG 성공 뒤 재고 부족을 발견하지 않으려면 호출 전에 재고를 확보해야 한다. 과제는 가용 재고 차감과 "주문별 확보 수량·상태" 저장을 요구한다.
- **결정**: `products.stock`은 계속 **가용 수량**이다. TX A에서 차감하고, 성공하면 상품을 건드리지 않으며, 확정 실패 때만 주문의 `PAYMENT_PENDING → PAYMENT_FAILED` 전이와 **같은 TX에서** 품목별로 한 번 `Stock.increase`한다. 주문별 확보 수량은 불변인 `order_items`, 확보 상태는 주문 상태가 맡는다. `reserved` 컬럼이나 예약 엔티티는 두지 않는다. 반환은 삭제된 상품 행도 잠가서(id 오름차순) 수량만 돌려주고 `deletedAt`은 유지한다. 관리자 재고 설정은 "예약을 제외한 가용 수량의 최종 설정"으로 정의한다.
- **근거**: 필요한 사실(가용, 주문별 수량, 확보 상태)이 모두 이미 저장되어 있다. reserved 컬럼은 이를 미리 계산해 둘 뿐이며, 성공 때마다 상품 행을 잠그고 갱신하는 비용을 만든다. 중복 반환은 주문 상태 전이가 막는다. 과제의 수치(5→3→성공 3/실패 5)가 그대로 맞는다.
- **비용**: `reserved >= 0` 같은 불변식 안전망이 없어 반환 중복은 상태 전이 가드와 동시성 테스트에만 의존한다. 상품별 예약 합계는 미완료 주문 품목의 조회로 계산해야 한다. 삭제 행을 포함하는 잠금 쿼리가 새로 필요하다.
- **재검토 조건**: 이번 범위는 주문당 한 시도라서 주문 상태 전이가 곧 시도의 전이다. 실패 후 재시도를 지원하게 되거나 예약 만료·상품별 예약량 조회가 필요해지면 예약 엔티티 분리를 검토한다.
- **기각한 대안**: `Stock` VO에 `reserved` 추가(안전망은 얻지만 성공 경로의 잠금 경합 증가), 주문별 예약 엔티티(주문 상태와 정보가 중복).

### D-47. 결제 시도는 별도 엔티티로 두고 유일 제약과 상태 전이로 발송 권한을 정한다

- **상황**: 주문당 한 시도, 시도 키의 요청 일치, 같은 시도의 동시 요청에서 PG POST 1회가 필요하다. 메모리 `Set`이나 락은 재시작·다중 인스턴스에 무력하다.
- **결정**: `payment_attempts`에 `order_id`, `attempt_key`, `provider_order_id`, `transaction_key`(nullable)를 각각 UNIQUE로 둔다. TX A에서 주문을 잠근 뒤 시도를 insert한 실행만 발송 권한을 얻는다. 카드 번호는 저장하지 않는다.
- **근거**: 식별자 역할(1-2)이 달라 하나로 합칠 수 없다. 권한 획득을 DB 행 생성에 묶으면 재시작 뒤에도 "이미 발송을 시작했다"는 사실이 남는다.
- **비용**: 테이블 하나와 `REQUESTED` 상태(발송 여부 불확실)가 생긴다.
- **기각한 대안**: 주문에 컬럼으로 흡수(식별자 4종과 anomaly를 주문이 떠안음), 메모리 잠금(재시작·다중 인스턴스에서 무효).

### D-48. 결제 요청은 자동 재시도하지 않고, 확정 거절이 아니면 `UNKNOWN`으로 두며, confirm 안에서 조회하지 않는다

- **상황**: 테스트 PG는 멱등 키를 받지 않고 같은 orderId의 새 POST를 중복 제거하지 않는다. timeout은 "내 대기가 끝났다"만 알려 주고 거래가 생겼는지는 알려 주지 않는다.
- **결정**: 결제 요청에는 timeout만 걸고 Retry·CircuitBreaker·숨은 재시도를 두지 않는다. 효과 0건 확정 거절(422 + 정확한 code + `transactionCreated:false` + orderId·amount 일치)만 `FAILED`로 종결하고, 나머지 모든 비정상 응답은 `UNKNOWN`으로 합류시킨다. `UNKNOWN`·`REQUESTED` 시도는 재시작 뒤에도 조회 대상으로만 남기며 결제 요청을 다시 보내지 않는다. confirm은 `UNKNOWN`이어도 같은 요청 안에서 조회하지 않고 202를 즉시 반환한다. 종결은 callback·reconcile이 한다.
- **근거**: 이중 결제는 되돌리기 어렵고 미완료는 나중에 조회로 바로잡을 수 있다. 직후 조회는 대부분 `PENDING`만 얻고 confirm 응답 시간을 늘린다.
- **비용**: 응답을 잃고 한 번도 보내지 않은 시도는 자동으로 종결되지 않을 수 있다. 재고 확보가 길게 점유된다 (10장).
- **기각한 대안**: timeout 즉시 실패 처리·결제 요청 재시도(이중 결제), `UNKNOWN` 직후 즉시 1회 조회(응답 지연, 경로 증가).

### D-49. 결과 적용은 한 경계로 모으고 callback은 확인 요청의 신호로만 쓴다

- **상황**: callback은 유실·중복·지연·순서 변경이 가능하고, 본문만 믿으면 위조된 요청이 결제를 완료시킬 수 있다.
- **결정**: confirm·reconcile·callback의 결과는 모두 `PaymentStatusUpdater` 한 클래스에서 상태에 반영한다(결제 요청 결과는 `updateByPaymentRequestResult`, 조회 결과는 `updateByStatusCheckResult`). callback은 본문의 `status`를 쓰지 않고 `transactionKey`로 adapter 권위 조회를 한 뒤, 계정·`providerOrderId`·`transactionKey`·저장 금액을 확인하고 적용한다. 주문 행 잠금 아래에서 단방향 전이만 허용하며, 종결 뒤 상충은 덮어쓰지 않고 `anomaly`로 남긴다.
- **근거**: 여러 입구가 각자 상태를 쓰면 순서에 따라 결과가 갈린다. 단일 경계와 전이 조건이 callback-first·lookup-first의 수렴을 보장한다.
- **비용**: callback마다 PG 조회가 한 번 더 나간다. 서명 검증은 하지 않는다 (10장).
- **기각한 대안**: callback 본문을 신뢰(위조에 취약), 입구마다 상태 직접 변경(중복 확정·반환 위험).

### D-50. 회복 도구는 Resilience4j annotation을 조회 경로에만, 기본 순서(Retry 바깥)로 적용한다

- **상황**: 조회는 재시도해도 되지만 결제 요청은 안 된다. Retry와 CB를 겹치면 어느 호출을 표본으로 세는지 달라진다.
- **결정**: `resilience4j-spring-boot3` + `starter-aop`, `@Retry`/`@CircuitBreaker`를 조회 메서드에만 둔다. 기본 순서를 유지해 실제 호출마다 회로 표본으로 센다. `CallNotPermittedException`은 재시도하지 않는다. `fallbackMethod`는 쓰지 않고, 호출하는 `PaymentGatewayImpl`이 실패 예외를 `COULD_NOT_CHECK`로 바꾼다. 순서·프록시 적용은 실제 호출 수와 회로 지표 테스트로 고정한다. 수치는 5-2.
- **근거**: 보편적인 Spring 사용 방식이고, 회로의 목적(외부 보호)에 맞게 실제 호출 기준으로 센다.
- **비용**: 순서가 코드에 드러나지 않고, self-invocation 실수가 조용히 일어날 수 있다. 테스트와 분기 위치 규칙(5-4)으로 막는다. 호출하는 쪽과 변환하는 쪽을 나누느라 클래스가 둘이 된다.
- **기각한 대안**: 함수형 decorator(순서는 보이지만 Spring에서 덜 보편적), CB 바깥 순서(회로가 늦게 열리고 HALF_OPEN 탐색이 불어남).

### D-51. 트랜잭션 경계는 Facade 메소드가 아니라 `TransactionTemplate`으로 구간마다 둔다

- **상황**: CLAUDE.md는 트랜잭션 경계를 Facade의 `@Transactional`로 두라고 한다. 그런데 결제는 TX A, HTTP(TX 밖), TX B가 한 유스케이스 안에 순서대로 이어진다. 한 Facade 메소드에 `@Transactional`을 달면 HTTP가 트랜잭션 안으로 들어오고, 같은 클래스 안에서 호출하면 프록시를 거치지 않는다.
- **결정**: 결제를 시작하는 `OrderFacade.confirm`과 결과를 확인하는 `PaymentResultCheckFacade.checkMyPaymentResult`·`checkPaymentResultOnPgCallback`은 메소드에 트랜잭션을 열지 않고, 필요한 구간(TX A, TX B, PG 조회 전 읽기)만 `TransactionTemplate`으로 감싼다. `PaymentStatusUpdater`는 `@Transactional` 없이 반영 로직만 가지며 호출하는 Facade가 트랜잭션을 연다. 도메인 `Service`에는 여전히 `@Transactional`을 붙이지 않는다. `ProductFacade`가 이미 같은 방식으로 읽기 구간만 감싼다.
- **근거**: PG 호출 동안 DB 트랜잭션·재고 잠금을 잡지 않는다는 요구는 경계를 나누는 것으로만 지킬 수 있다. 구간이 Facade 코드에 그대로 보여 흐름(TX A → PG → TX B)을 한 곳에서 읽을 수 있고, 트랜잭션 전용 빈을 따로 두지 않아도 된다. `/confirm`은 주문의 확정 요청이므로 `OrderFacade`에 둔다.
- **비용**: 구조가 경계를 강제하지 않는다. `TransactionTemplate` 구간 밖에서 쓰기를 하거나 구간 안에서 PG를 호출하는 실수를 막으려면 `PaymentTransactionTest`가 필요하다. "트랜잭션은 Facade의 `@Transactional`"이라는 단순 규칙에 예외가 생긴다.
- **기각한 대안**: Facade 한 메소드에 `@Transactional`(HTTP가 잠금·커넥션을 쥠), 트랜잭션 전용 빈(준비·반영 컴포넌트에 `@Transactional`: 경계는 구조가 강제하지만 클래스가 늘고 흐름이 여러 파일로 흩어짐).

---

## 10. 열린 항목과 한계

| 항목 | 상태 |
|---|---|
| 장기 `UNKNOWN`·`REQUESTED`의 종결 | 시간 경과만으로 실패 처리하지 않는다. 자동 만료·상시 스케줄러는 넣지 않는다. 확인되지 않는 동안 재고가 점유되는 비용이 남는다. 운영 개입과 별도 판단을 전제로 한다 |
| 발송 기록 뒤 실제 미발송 시도 | 결제 요청을 다시 보내지 않으므로 영구 미완료일 수 있다. 중복 방지와 모든 시도의 자동 완료는 같은 보장이 아니다 |
| 같은 키의 "다른 요청" 비교 | 같은 키 재요청의 일치는 **주문과 키**로만 확인한다. 금액은 요청이 아니라 저장된 주문 합계로 정하므로 달라질 수 없고, 카드 값은 저장하지 않으므로 비교할 수 없다. 같은 주문·같은 키로 카드 값만 다르게 보내도 저장된 결과를 돌려주고 새로 발송하지 않는다 |
| callback 서명 검증 | 하지 않는다. 대신 권위 조회로 검증한다 (로컬 실습 한정). 위조 알림은 확정이나 실패를 만들지 못하지만, 다른 주문의 거래 키를 담은 알림은 해당 시도에 `MISMATCH` 기록을 남길 수 있다(상태·재고는 그대로) |
| 종결 뒤의 재확인 | 이미 종결된 시도는 reconcile·callback 이 PG 를 다시 부르지 않고 저장된 결과를 돌려준다. 그래서 종결 뒤 PG 쪽 결과가 달라져도 상충 기록(`CONFLICTING_RESULT`)은 동시에 도착한 요청 사이에서만 남는다 |
| reconcile 호출 빈도 제한 | 하지 않는다. 남용 방지는 범위 밖 |
| 프론트 폴링 주기 | 과제 범위 밖. `GET /orders/{id}`는 PG를 호출하지 않으므로 폴링 비용은 DB 조회 수준이다 |
| 회로 수치(윈도우 10, 최소 5, 50%, 10초, 탐색 2) | 가설. 로컬 fixture에서의 동작은 테스트로 확인하지만 실제 부하에서의 적정값은 측정하지 않았다 |
| `UNKNOWN`·`PENDING` 체류 시간, 조회 불가·충돌 건수 지표 | 운영 관점에서는 필요하나 이번 범위에서 만들지 않는다 |
