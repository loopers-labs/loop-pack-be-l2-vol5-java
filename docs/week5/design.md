# 5주차 설계 — 테스트 PG 연동

이 문서는 **현재 코드의 결정과 이유**를 적는다. 결정 하나씩의 대안·비용은 ADR, 검증 과정과 실행 결과는 [구현 기록](implementation-log.md), 주문 하나의 흐름과 유즈케이스는 [PG 주문 흐름](payment-flow.md), 조회 장애 실험은 [PG 조회 장애 실험](resilience-experiment-plan.md)에 둔다.

## 개요

주문 확정의 결제를 포인트에서 테스트 PG로 바꿨다. 확정은 네 구간으로 나뉜다.

```text
TX A   재고 예약 + 결제 시도 기록(REQUESTED)
 ↓
PG     결제 생성 POST (트랜잭션 밖, 한 번만)
 ↓
TX B   검증한 결제 결과와 관찰 이력 저장 (주문·재고는 그대로)
 ↓
TX C   결과가 SUCCESS·FAILED이면 주문·예약·재고에 반영
```

결과를 아직 모르면 고객의 reconcile API, PG의 callback API, 10분이 지난 시도를 찾는 배치가 기존 거래를 **조회**해 같은 TX B·C로 반영한다. 조회에만 retry·서킷 브레이커·fallback을 적용한다.

| ADR | 결정 |
| --- | --- |
| [0028](../adr/0028-test-pg-http-boundary-and-no-create-retry.md) | 테스트 PG HTTP 경계, 결제 생성은 다시 보내지 않음 |
| [0029](../adr/0029-order-payment-switches-to-pg-with-reservation-on-order.md) | 주문 결제를 PG로 바꾸고 예약 상태를 주문에 둠 |
| [0030](../adr/0030-pg-payment-split-transactions-and-apply-once.md) | 외부 호출을 트랜잭션 밖에 두고, 결과를 한 번만 반영 |
| [0031](../adr/0031-pg-lookup-retry-circuit-breaker-and-fallback.md) | 조회에만 retry·회로·fallback |
| [0032](../adr/0032-persist-payment-result-before-order-application.md) | 결제 결과 저장(TX B)과 주문 반영(TX C) 분리 |
| [0033](../adr/0033-payment-recovery-batch-for-unfinished-attempts.md) | 10분이 지난 미완료 시도를 배치가 같은 규칙으로 마무리 |
| [0034](../adr/0034-close-only-proven-unsent-payments-and-keep-unresolved-pending.md) | 확실한 미발송만 즉시 실패. 시간 경과·조회 0건에는 미완료와 예약 유지 |

| 구현됨 | 아직 없음 |
| --- | --- |
| confirm·reconcile·callback API, TX A·B·C | JVM 종료·재기동 자체의 검증 |
| 결제 시도 상태 전이와 `payment_event` 이력 | 오래 모르는 시도의 조회 횟수 제한·알림 |
| 생성 응답 유실 뒤 조회 복구, 접수 전 거절(422), 보내지 못한 생성의 즉시 실패, 오래 미확인인 시도의 예약 유지 | 장기 미확인 결제의 취소·수동 처리 절차 |
| 반대 결과·복수 거래·주문 번호·금액 불일치의 충돌 기록 | 결제 생성 POST의 회로 |
| 실패 시 재고 1회 반환, 예약 뒤 삭제된 상품의 마무리 | |
| B 오류 → PG 재조회, C 오류 → 저장된 결과로 C부터 복구 | 조회 설정값의 선정. 후보별로 얻고 잃는 것은 실험으로 비교했고([실험 7회차 이어서](resilience-experiment-plan.md#wrap-order-experiments)), 값은 고르지 않음 |
| 같은 DB·PG를 유지한 서비스·클라이언트 재생성 복구 | |
| 10분이 지난 미완료 시도의 배치 복구 | |
| PG 죽음·느려짐·DB 멈춤 카오스 테스트, callback·reconcile·배치의 `OrderFacade` 수준 동시 실행 | |
| 조회 retry·회로·fallback과 응답의 `checked` | |
| 주문 조회의 결제 상태(고객 한 칸, 관리자 PG 식별자) | |

## 책임과 의존 방향

```text
domain/payment/PaymentProvider (포트)
                 ↑ 구현
infrastructure/payment/ResilientPaymentProvider   ← 조회만 fallback → retry → 회로로 감쌈
                 ↓ 위임
infrastructure/payment/TestPgPaymentAdapter
                 ↓ RestClient + HttpComponentsClientHttpRequestFactory (생성용·조회용)
          테스트 PG HTTP 서버
```

| 모델·구성 | 책임 |
| --- | --- |
| `PaymentProvider` | 결제 생성·transactionKey 조회·providerOrderId 조회 포트 |
| `PaymentRequest` | 저장된 providerOrderId·금액·일시적인 합성 카드 입력. toString에서 카드 값 제외 |
| `PaymentTransaction` | PG가 보고한 transactionKey·providerOrderId·금액·PENDING/SUCCESS/FAILED |
| `PaymentObservation` | 응답을 읽은 OBSERVED, 확인할 수 없는 UNKNOWN, 거래 생성 전 거절이 증명된 REJECTED, 생성 요청이 연결 단계에서 끝나 PG에 닿지 않은 NOT_SENT. UNKNOWN은 이유(`TEMPORARY_FAILURE`·`UNEXPECTED_RESPONSE`·`CIRCUIT_OPEN`)를 담는다 |
| `TestPgPaymentAdapter` | 받은 providerOrderId로 HTTP 요청·응답 형식을 처리하고 UNKNOWN의 이유를 고른다. 번호를 만들지 않는다 |
| `ResilientPaymentProvider` | 주문 흐름이 쓰는 PG 포트(`@Primary`). 조회만 retry·회로로 감싸고 생성은 그대로 넘긴다 |
| `PaymentProviderConfig` | 설정의 URL·timeout으로 adapter와 `ResilientPaymentProvider` 생성. 종료 시 HTTP client 닫기 |
| `OrderFacade` | 트랜잭션 없이 흐름만 조정. A → PG → B → C 순서로 호출. reconcile·callback·배치(`recover`)가 같은 결과 확인 규칙을 공유 |
| `PaymentRecoveryBatch` | 만든 지 10분이 지났고 주문이 PAYMENT_PENDING인 시도를 50건씩 찾아 `recover` 호출. 스케줄러가 1분 간격으로 실행 |
| `OrderPaymentTransactions` | DB 트랜잭션 구간을 모은 별도 빈. `start`(A), `recordCreateResponse`·`applyLookup`(B), `applyRecordedResult`(C) |
| `FakePaymentServer` (테스트) | 랜덤 포트의 실제 HTTP 서버. 거래·호출 수 보관, 요청마다 응답 코드·지연을 예약 |

domain은 HTTP·Jackson·infrastructure에 의존하지 않는다. adapter에는 트랜잭션과 DB 접근이 없다.

## PG HTTP 계약과 관찰 결과

로컬 테스트 PG는 실제 금전을 처리하지 않는 HTTP 서버다. 호출자의 멱등 키를 받지 않으며, 같은 orderId로 POST를 다시 보내면 별도 거래를 만든다. 외부 상태는 PENDING·SUCCESS·FAILED다.

| 요청 | 계약 |
| --- | --- |
| `POST /api/v1/payments` | `orderId, cardType, cardNo, amount, callbackUrl`. 202로 `transactionKey, orderId, amount, status: PENDING` |
| `GET /api/v1/payments/{transactionKey}` | 있으면 200으로 거래 하나, 없으면 404 |
| `GET /api/v1/payments?orderId={providerOrderId}` | 200으로 같은 주문의 거래 배열. 빈 목록·복수 거래 가능 |

- 세 요청 모두 제공자 계정 헤더 `X-USER-ID: 135135`를 보낸다. 내부 고객 인증과 별개다.
- PG의 orderId는 6자 이상 문자열, amount는 양수 원화 정수(JSON 정수)다. callback URL은 서버 설정에서만 가져온다.
- 주문 조회는 query 변수로 인코딩하고 transactionKey는 path 변수로 넣는다.
- 카드 번호는 저장하지 않고 요청·HTTP 본문 객체의 toString에서도 제외한다. 테스트는 합성 카드만 쓴다.

| PG 응답 | adapter 반환 |
| --- | --- |
| 생성 202 + PENDING | OBSERVED, 거래 1건. 주문 번호·금액 대조는 TX B가 한다 |
| 조회 200 | OBSERVED, 응답의 거래 목록 그대로 |
| transactionKey 조회 404, 주문 조회 빈 배열 | OBSERVED, 빈 목록. 거래 부재나 결제 실패의 증거는 아님 |
| 생성 422 + 접수 전 거절의 증거 네 가지가 모두 맞음 | REJECTED |
| 생성: 연결을 빌리지 못함·새 연결 거부·연결 수립 시간 초과 | NOT_SENT. 요청이 우리 서버 밖으로 나가지 않았다 |
| timeout·연결 실패·5xx (조회 전부, 생성은 위를 뺀 나머지) | UNKNOWN(`TEMPORARY_FAILURE`). 재사용 연결로 요청을 쓰다 끊긴 경우도 여기다 |
| 잘못된 JSON·필드 타입·외부 상태, 생성 응답의 PENDING 이외 상태, 증거가 하나라도 빠진 422, 그 밖의 4xx | UNKNOWN(`UNEXPECTED_RESPONSE`) |

OBSERVED는 응답을 읽었다는 뜻이지 주문 확정 허가가 아니다. 복수 거래도 adapter가 하나를 고르지 않고 목록으로 넘긴다.

**접수 전 거절의 증거**: HTTP 422 + code `TEST_PG_REJECTED_BEFORE_CREATE` + boolean `transactionCreated:false` + 요청 orderId·amount(long 범위) 일치. 넷이 모두 맞을 때만 "거래를 만들지 않았다"의 증거로 본다. 하나라도 빠지면 효과 0건을 증명하지 못하므로 UNKNOWN이다.

## 설정과 호출 제한

하위 HTTP client는 Boot 3.4.4 의존성 관리의 Apache HttpClient 5.4.2다. 자동 재시도와 redirect를 끈다.

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `commerce.payment.pg.base-url` | `http://127.0.0.1:18080` | 로컬 PG 주소. 테스트는 랜덤 포트 |
| `commerce.payment.pg.callback-url` | `http://127.0.0.1:8080/api/v1/payments/callback` | 생성 요청에 싣는 수신 경로. 가짜 PG는 callback을 보내지 않아 테스트가 직접 호출 |
| `commerce.payment.pg.connect-timeout` | 300ms | 연결 수립 제한. 연결 풀 대기에도 같은 값 |
| `commerce.payment.pg.read-timeout` | 1s | 생성 응답 대기 제한 |
| `commerce.payment.pg.lookup-read-timeout` | 500ms | 조회 응답 대기 제한. 다시 물을 수 있으므로 생성보다 짧다 |
| `commerce.payment.pg.lookup-budget` | 2s | 다음 조회 재시도를 시작할지 판단하는 예산 |
| `commerce.payment.pg.lookup-retry.*` | 최대 3회, 첫 쉬는 시간 100ms, 배수 2.0 | 조회 retry. 예산 판단에 쓰는 마지막 쉬는 시간(200ms)은 이 값에서 계산 |
| `commerce.payment.pg.lookup-circuit.*` | 창 4, 최소 4, 실패율 50, OPEN 10s, HALF_OPEN 2 | 조회 회로 |

- 예산은 **다음 재시도를 시작할지만** 판단한다. 최초 GET을 막거나, 진행 중인 GET을 남은 시간에 맞춰 끊지 않는다. 그래서 2초 안의 종료를 보장하지 않는다.
- 풀 대기 + 연결 + 응답 = 1.1초는 재시도 판단용 추정치다. 응답 timeout은 "데이터가 오지 않는 시간"의 한도라서 GET 전체의 최대 시간이 아니다.
- 조회 예산은 PG 조회 구간만 대상으로 하며 reconcile 전체(DB 포함) 시간과 다르다.
- 숫자는 모두 로컬 학습값이다. 운영이라면 생성·조회 각각의 응답 시간 분포, 자원 점유, UNKNOWN 비율을 재서 정한다.

## 주문·예약·결제 시도

예시는 **주문 42, 상품 재고 5개, 수량 3개, 3,000원**이며 다른 재고 변경은 없다고 가정한다.

| 모델·정보 | 맡는 것 |
| --- | --- |
| 주문 `status` | 주문 단계: DRAFT, PAYMENT_PENDING, CONFIRMED, PAYMENT_FAILED |
| 주문 `reservationStatus` | 이 주문 몫 재고를 잡았나(RESERVED)·팔았나(CONSUMED)·돌려줬나(RELEASED) |
| 주문 `paymentMethod` | POINT 또는 PG. 확정 시점에 정해짐 |
| `OrderItem` | 주문 시점의 상품·수량·가격. 예약·반환 수량도 여기서 읽음 |
| `Product.stock` | 다른 주문에 팔 수 있는 가용 재고 |
| 결제 시도 `PaymentAttempt` | 주문 id, PG 진행 상태, PG 식별자, 요청 금액. 재고 상태는 갖지 않음 |

| 주문 | status | paymentMethod | reservationStatus |
| --- | --- | --- | --- |
| 확정 전 | DRAFT | null | null |
| 과거 POINT 주문 | CONFIRMED | POINT | null |
| PG 결제 중 | PAYMENT_PENDING | PG | RESERVED |
| PG 성공 | CONFIRMED | PG | CONSUMED |
| PG 확정 실패 | PAYMENT_FAILED | PG | RELEASED |

- 두 null은 `paymentMethod`로 구분한다. DRAFT의 null은 "아직 정해지지 않음", 과거 POINT 주문의 null은 "예약을 거치지 않음"이다.
- 새 결제는 PG만 사용하고 포인트를 차감·적립하지 않는다. 과거 POINT 주문은 그대로 조회하며, 다시 확정하면 기존 409다.
- 주문의 상태 전이 메서드 `startPaymentWithReservedStock()`, `completePayment()`, `failPaymentWithReleasedStock()`만 `status`와 `reservationStatus`를 함께 바꾼다. PAYMENT_PENDING이 아닌 주문에 결과를 반영하면 CONFLICT 예외다.

### 결제 시도 모델

결제 시도는 주문과 별도 테이블(`payment_attempt`)에 두고 주문 id를 가진다. PG 요청의 식별 관계와 진행 상황을 주문 단계·재고 처리와 구분해 기록하기 위해서다.

| 필드 | 제약 | 이유 |
| --- | --- | --- |
| 주문 id | 유일 | 주문당 한 시도. 동시 confirm의 마지막 안전망 |
| attemptKey | 유일 | 고객의 같은 요청(연타)인지 구분 |
| providerOrderId | 유일 | transactionKey 없이도 PG 조회. POST 전 TX A에서 `LP-ORD-%010d`로 정해 저장 |
| transactionKey | nullable | PG 거래 한 건 조회. 생성 응답을 못 받으면 비어 있음 |
| 상태 | | REQUESTED → PENDING·UNKNOWN → SUCCESS·FAILED. 앞으로만 간다 |
| 금액 | | PG에 보낸 금액. 조회 결과 대조 기준 |
| requestFingerprint | 필수·64자·변경 불가 | 같은 시도 키로 카드 입력을 바꾼 요청인지 대조. 카드 원문 대신 SHA-256 비교값 보존 |

- transactionKey가 비어 있다는 것은 "우리가 못 들었다"이지 "PG가 만들지 않았다"가 아니다.
- providerOrderId를 POST 전에 저장하므로, 응답을 잃거나 재시작해도 같은 번호로 조회할 수 있다.
- 같은 주문의 두 번째 시도와 다른 주문의 같은 키는 DB 유일 제약이 거부하고, TX A가 **409**로 바꾼다.

관찰을 반영하는 `markPending(키)`, `markUnknown()`, `succeed(키)`, `fail(키)`, `reject()`는 결과를 돌려준다.

| 결과 | 뜻 |
| --- | --- |
| APPLIED | 결제 시도 상태를 바꿨다. 주문 반영 완료를 뜻하지는 않는다 |
| IGNORED | 늦거나 중복된 관찰이라 무시 |
| CONFLICT | 반대 종결 결과나 다른 거래 키라서 덮지 않음 |

늦은 응답·중복은 정상적으로 생기는 일이라 예외 대신 IGNORED로 답한다. 예외로 던지면 롤백 → callback 500 → PG 재전송이 반복된다.

| 현재 상태 | 늦게 온 관찰 | 처리 |
| --- | --- | --- |
| SUCCESS | PENDING·UNKNOWN | 무시 |
| SUCCESS | FAILED | 덮지 않고 충돌 기록 |
| PENDING | UNKNOWN | 무시 (UNKNOWN도 뒤로 가는 것) |
| 종결 상태 | 같은 결과 | 아무것도 하지 않음 |

**접수 전 거절**: 거래 키가 없고 callback도 오지 않는다. 빈 키로 `fail()`을 부르면 "거절돼서 키가 없음"과 "못 들어서 키를 모름"이 섞이므로 `reject()`로 FAILED를 기록하고 이력 사유를 `REJECTED_BEFORE_CREATE`로 남긴다. 주문 반영은 일반 FAILED와 같은 TX C를 거친다.

| 현재 시도 | `reject()` |
| --- | --- |
| REQUESTED | APPLIED → FAILED |
| 키 없는 FAILED | IGNORED |
| PENDING·UNKNOWN·SUCCESS·키 있는 FAILED | CONFLICT, 상태 유지 |

### confirm 요청 판단

attemptKey는 고객 화면이 결제하기 한 번에 붙이는 이름표다. 주문 행 잠금으로 동시 요청을 줄 세우고, `order_id` 유일 제약을 마지막 안전망으로 둔다.

| 요청 | 처리 |
| --- | --- |
| 같은 키 + 같은 주문·금액·카드 입력 | 연타. PG 호출 없이 저장된 상태 반환. 결제가 종결됐는데 주문이 미반영이면 TX C만 실행 |
| 같은 키 + 다른 카드 종류·번호 또는 저장 금액 불일치 | 409. PG 호출·결과 적용·재고 변경 없음 |
| 같은 키 + 다른 주문 | 409 |
| 다른 키 + 이미 시도가 있는 주문 | 409. 앞 결제가 UNKNOWN일 수 있어 받으면 이중 결제 위험 |

**요청 일치**: TX A에서 주문을 잠근 뒤 시도 키·저장 금액·카드 입력을 대조한다. `PaymentAttempt.matchesRequest()`는 금액과 `requestFingerprint`를 비교한다. 시도 키와 카드 종류·번호를 SHA-256으로 해시하고, 시도 키와 카드 종류에는 길이를 붙여 필드 경계가 다른 입력을 구별한다. 카드 종류·번호는 문자열 그대로 비교하며 대소문자·하이픈 등을 임의로 정규화하지 않는다. 원문은 DB에 저장하지 않고, 해시는 API 응답·로그에 노출하지 않는다. 해시도 민감한 비교 정보이며 익명화를 보장하지 않는다. 테스트는 계속 합성 카드만 사용한다.

같은 키라도 입력이 다르면 REQUESTED·PENDING·UNKNOWN·SUCCESS·FAILED 어느 상태에서도 409다. 저장된 종결 결과의 TX C를 복구하기 전에도 요청 일치를 확인하므로, 불일치 요청은 기존 결과·주문·예약·재고를 바꾸지 않는다. 기존 요청과 일치하면 FAILED를 포함해 저장 결과를 반환한다.

**후속 재결제 참고**: 앞 카드가 잔액 부족 등으로 확정 실패해 다른 카드로 다시 결제하려는 상황은 있을 수 있다. 이번 과제는 주문당 한 시도이며 실패 후 재결제는 범위 밖이다. 카드 변경 자체는 앞 결제의 실패 증거가 아니므로 PENDING·UNKNOWN에서 새 결제를 보내지 않는다. 재결제를 추가할 때는 확정 실패 확인, 새 시도·새 키, 주문당 한 시도 제약, 재고 예약의 재확보·유지 정책을 함께 검토한다.

**스키마**: `payment_attempt.request_fingerprint`를 필수 칼럼으로 추가했다. 기존 PG 시도에 원문 카드가 없어 비교값을 소급 생성할 수는 없다. 현재 검증은 `ddl-auto: create`인 격리된 Testcontainers DB를 쓰며, 기존 데이터를 유지하는 환경의 스키마 변경·기존 시도 처리는 별도 전환 정책이 필요하다.

<a id="reservation-model"></a>

### 재고 예약 — 주문의 `reservationStatus`

주문당 예약 상태 하나를 주문에 저장한다. 별도 예약 테이블과 상품별 예약 행은 만들지 않는다.

- **단위**: 모든 품목은 TX A에서 함께 예약되거나 함께 롤백되고, TX C에서 함께 소비되거나 함께 반환된다. 한 상품이라도 부족하면 TX A 전체를 롤백하고 PG를 부르지 않는다. 반환 수량은 주문 시점 스냅샷인 OrderItem에 있다([ADR 0007](../adr/0007-order-item-keeps-order-time-snapshot.md)).
- **칸을 둔 이유와 대가**: 주문 상태로 예약 상태를 추론할 수도 있지만, 주문 진행과 재고 예약의 의미를 코드·DB에 드러내려고 칸을 두었다. 대신 두 상태의 올바른 조합을 상태 전이 메서드가 함께 지켜야 한다.
- **다시 볼 조건**: 재결제 대기·부분 취소처럼 주문 단계와 예약이 따로 움직이는 기능을 넣을 때.

| 관찰·적용 | 예약 상태 | 가용 재고 |
| --- | --- | --- |
| 재고 예약 | RESERVED | 2 |
| PENDING·UNKNOWN | RESERVED 유지 | 2 |
| 성공 반영 | CONSUMED | 2, 추가 차감 없음 |
| 첫 실패 반영 | RELEASED | 5 |
| 같은 실패 재반영 | RELEASED 유지 | 5, 추가 반환 없음 |

- **결과를 모르면 예약 유지**: 재고를 5로 돌린 뒤 결제 성공이 확인될 수 있다. 그사이 다른 주문이 가져가면 결제한 고객에게 줄 상품이 없다.
- **중복 반환 방지**: 재고 숫자에는 다른 주문·관리자 변경도 섞여서, 숫자만으로 이미 돌려줬는지 알 수 없다. RESERVED에서 처음 반환할 때 RELEASED로 바꾸고, 이미 RELEASED이면 더하지 않는다.
- **반환은 더하기**: 현재 가용 재고에 예약 수량을 더한다. 과거 수량으로 덮어쓰지 않는다.
- **삭제된 상품**: soft delete라 행이 남는다. 실패 반환은 `ProductService.getForUpdateIncludingDeleted`로 삭제된 상품도 잠가 재고를 더한다. 삭제 표시는 그대로라 다시 노출되지 않는다.
- **예약 중 관리자 재고 변경**: 재고 설정 API는 예약분을 뺀 가용 수량을 설정한다. 가용 10으로 바꾸면 성공 후 10, 실패 후 13이다.

## 트랜잭션 경계 — A·B·C

### 외부 호출을 트랜잭션 밖에 두는 이유

PG 호출을 DB 트랜잭션 안에 두면, PG를 기다리는 동안 연결과 행 잠금을 계속 쥐고 있게 된다. 또 DB 트랜잭션은 PG에 참여하지 않는다. 한 트랜잭션 안에서 PG가 성공한 뒤 DB가 롤백되면 재고와 기록은 사라지지만 PG의 결제는 남는다. 같은 메서드에서 실행했다는 이유로 외부 효과까지 취소되지는 않는다.

그래서 PG를 부르기 **전에** 예약과 결제 시도를 커밋(TX A)하고, PG의 대답은 **별도 트랜잭션**에 기록한다. 이렇게 하면 PG 성공 뒤 우리 저장이 실패해도, 남아 있는 시도의 providerOrderId로 같은 거래를 다시 조회해 복구할 수 있다.

### 구간별 책임

| 구간 | 메서드 | 함께 커밋·롤백되는 것 |
| --- | --- | --- |
| TX A | `start` | 주문 PAYMENT_PENDING·예약 RESERVED·가용 재고 차감·REQUESTED 시도 |
| 트랜잭션 밖 | `paymentProvider.create`·조회 | PG의 효과. DB 롤백에 참여하지 않음 |
| TX B | `recordCreateResponse`·`applyLookup` | 결제 시도 상태·`payment_event` 이력. 주문·재고는 바꾸지 않음 |
| TX C | `applyRecordedResult` | 주문 상태·paidAmount·예약 상태·실패 시 재고 반환 |

- **TX B**는 관찰을 저장된 주문 번호·금액·거래 키·거래 수·종결 여부와 대조한다. 0건은 IGNORED(`NO_TRANSACTION_YET`), 복수·불일치·반대 종결은 CONFLICT로 이력에 남긴다.
- **TX C**는 HTTP 관찰을 다시 쓰지 않고 DB의 결제 시도를 읽는다. SUCCESS면 CONFIRMED·CONSUMED, FAILED면 재고를 더하고 PAYMENT_FAILED·RELEASED. PENDING·UNKNOWN·REQUESTED면 아무것도 하지 않는다.
- B와 C를 나눈 이유: C에서 오류가 나도 **이미 확인한 결제 결과가 남는다.** 다음 실행은 PG에 다시 묻지 않고 C만 하면 된다([ADR 0032](../adr/0032-persist-payment-result-before-order-application.md)).

### 결제 종결과 주문 반영 완료는 다르다

B가 SUCCESS를 커밋했지만 C가 아직이면, 결제는 끝났는데 주문은 PAYMENT_PENDING·RESERVED인 **중간 상태**가 생긴다.

| 판단 | 뜻 |
| --- | --- |
| `hasFinalPaymentResult()` | 결제 시도가 SUCCESS 또는 FAILED |
| `isFinished()` | 결제와 주문·예약이 모두 끝남. SUCCESS+CONFIRMED+CONSUMED 또는 FAILED+PAYMENT_FAILED+RELEASED |

- 별도 "반영 상태" 칸은 두지 않는다. 결제 종결 + 주문 PAYMENT_PENDING 조합이 곧 "C가 남았다"는 표시다.
- C 재실행은 주문 잠금 안에서 완료 여부를 확인하므로 효과가 한 번뿐이다.
- `payment_event`는 B의 관찰 이력이다. C 완료는 주문·예약 상태에 남는다.

### 복구

| 어디서 실패했나 | 남는 것 | 다음 실행 |
| --- | --- | --- |
| TX A 뒤, POST 전 | REQUESTED·예약 | 조회 0건 → 대기. 실패 처리·재발송 없음 |
| POST 뒤, TX B 전 | REQUESTED·예약 + PG 거래 | providerOrderId로 조회 → B → C |
| TX B 오류 | B 롤백, REQUESTED/PENDING/UNKNOWN 그대로 | PG 조회부터 |
| TX B 뒤, TX C 전·TX C 오류 | 결제 SUCCESS/FAILED + 주문 대기 | **PG 조회 없이** C만 |

reconcile·callback·배치는 저장 상태를 먼저 본다. 이미 완료면 아무것도 안 하고, 결제만 종결이면 C만, 결과를 모르면 조회부터 한다. 같은 키 confirm은 결과가 미확정이면 PG 조회·재발송 없이 저장 상태만 반환하며, 결제만 종결된 경우에는 C를 실행한다. 조회 회로가 OPEN이어도 저장된 결과의 C는 실행한다. 아무도 부르지 않은 시도는 생성 후 10분이 지나면 배치 조회 대상이 된다(아래 「미완료 시도 배치」).

### 잠금 순서

A·B·C 모두 주문을 먼저 잠근다. B·C는 이어 결제 시도를 잠근다. 상품은 TX A의 재고 예약과 TX C의 실패 반환에서만 ID 오름차순으로 잠근다. TX A는 주문 잠금 뒤 기존 시도를 **일반 조회**한다. REPEATABLE READ의 앞선 커밋을 읽고, 없는 행을 잠금 조회할 때의 빈 구간 잠금 교착을 피하기 위해서다([ADR 0030](../adr/0030-pg-payment-split-transactions-and-apply-once.md)).

### 작성 방식

`OrderFacade`에는 `@Transactional`을 붙이지 않는다. 바깥 트랜잭션이 있으면 기본 REQUIRED 전파로 안쪽 구간이 모두 합쳐진다. 또 같은 객체 안의 호출은 프록시를 거치지 않아 annotation이 적용되지 않으므로, 트랜잭션 구간은 별도 빈 `OrderPaymentTransactions`의 public 메서드에 둔다. [Spring 전파 규칙](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html), [Spring @Transactional 적용 방식](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html).

## API 입구와 결과 확인

| API | 부르는 쪽 | 하는 일 | PG 호출 |
| --- | --- | --- | --- |
| `POST /api/v1/orders/{id}/confirm` | 고객 | 결제 시작: A → POST → B → 종결이면 C | POST |
| `POST /api/v1/orders/{id}/payment/reconcile` | 고객 | 결과 확인 | GET만 |
| `POST /api/v1/payments/callback` | PG | 결과 확인 | GET만 |
| `GET /api/v1/orders/{id}` | 고객 | 저장 상태 + `paymentStatus` 한 칸 | 없음 |
| `GET /api-admin/v1/orders/{id}` | 관리자 | 저장 상태 + `providerOrderId`·`transactionKey` | 없음 |

- 주문 조회는 PG를 부르지 않는다. 고객은 할 수 있는 행동(기다리기, 결제 확인 누르기)만 알면 되므로 상태 한 칸만 받고, PG 식별자는 문의·원인 확인용으로 관리자에게만 준다. DRAFT와 과거 POINT 주문의 `paymentStatus`는 null이다.
- confirm·reconcile은 필요한 C까지 마친 뒤 응답한다. 완료(결제·주문·예약이 모두 끝남)는 200, 그 밖은 202다. C에서 예외가 나면 기존 예외 응답으로 전파하고 결제 FAILED로 바꾸지 않는다.

**결과 확인**은 reconcile과 callback이 공통으로 하는 "필요하면 PG 조회 → TX B → 종결이면 TX C"다.

- **조회 키**: 저장된 transactionKey가 있으면 `GET /payments/{key}`, 없으면 저장된 providerOrderId로 `GET /payments?orderId=`. callback 본문의 값보다 우리가 저장한 값을 쓴다.
- **0건**: "지금은 안 보인다"일 뿐이라 대기한다. 재발송·반환하지 않는다.
- **서버가 켜질 때** 자동으로 조회하지 않는다. 남은 시도는 배치가 찾는다.

**callback 처리 순서**: 본문은 `providerOrderId, transactionKey, status`다.

1. providerOrderId가 비어 있으면 400
2. 우리 시도가 아니면 조회 없이 200
3. 결제·주문 모두 완료면 조회 없이 200. 결제만 종결이면 조회 없이 C 후 200
4. 본문의 키·status는 버리고, 저장된 키로 PG 조회(retry·회로 포함) → B(이력 경로 `CALLBACK`) → 종결이면 C → 200
5. 조회로 끝내 확인하지 못하면(retry 소진·회로 OPEN) B 없이 DB 그대로 503

200은 "알림을 받아 처리했다"이지 결과를 바꿨다는 뜻이 아니다. 공개 주소라 본문은 위조될 수 있고, 유실·중복·순서 변경도 가능하다. 그래서 본문은 "확인해 보라"는 알림으로만 쓰고, reconcile을 두 번째 입구로 둔다.

**조회 실패 때 503을 고른 이유**: 200을 주면 PG가 다시 보낼 기회가 사라진다. 다시 보낼지는 PG의 규칙이고, 다시 오지 않아도 reconcile로 회복할 수 있다. 받은 알림을 저장하고 200을 주는 방식은 저장한 알림을 다시 꺼내 처리할 장치가 필요해 택하지 않았다. 같은 조회 실패가 reconcile 입구에선 고객에게 202·`checked: false`, callback 입구에선 PG에게 503이 된다.

## 미완료 시도 배치

고객이 결제 확인을 누르지 않고 callback도 오지 않으면 미완료 시도가 남고, 그동안 예약이 재고를 잡고 있다. 그래서 오래된 미완료 시도를 배치가 찾아 마무리한다([ADR 0033](../adr/0033-payment-recovery-batch-for-unfinished-attempts.md)).

| 항목 | 정책 |
| --- | --- |
| 대상 | 주문이 PAYMENT_PENDING이고 결제 시도를 만든 지 **10분**이 지난 것. 결과를 모르는 시도와 B 뒤 C가 남은 시도가 함께 잡힌다 |
| 처리 | 건마다 `OrderFacade.recover` → reconcile과 같은 규칙. 이력 경로는 `BATCH` |
| 장기 미확인 | 30분이 지나도 같은 `recover` 규칙. 조회 0건·PENDING·조회 실패에는 상태와 예약을 유지한다. 이후 확인한 SUCCESS·FAILED만 B·C로 반영한다([ADR 0034](../adr/0034-close-only-proven-unsent-payments-and-keep-unresolved-pending.md)) |
| 순서·단위 | 결제 시도 id 오름차순, 50건씩 끝까지. 기준 시각은 실행 시작 때 고정 |
| 실패 | 한 건의 예외는 로그로 남기고 다음 건 계속. 다음 실행에서 다시 대상 |
| 실행 | `PaymentRecoveryScheduler`, 앞 실행이 끝난 뒤 1분(`fixedDelay`). `payment.recovery.scheduling-enabled=true`로 켬, 기본 꺼짐 |

- 결제 생성 POST를 다시 보내지 않는다. 시간 경과와 조회 0건을 합쳐도 최종 실패의 증거가 아니므로 자동으로 닫거나 재고를 반환하지 않는다.
- 조회는 고객 reconcile과 같은 retry·회로를 거친다. 회로가 OPEN이면 GET 없이 넘어간다.
- 오래 REQUESTED·UNKNOWN·PENDING인 시도는 매 실행마다 다시 조회된다. 조회 횟수 제한·장기 미확인 알림은 없다.
- 여러 서버에서 켜면 같은 시도를 동시에 처리할 수 있다. TX B·C는 주문 잠금 안에서 한 번만 반영한다.

<a id="lookup-resilience"></a>

## 조회 회복 정책과 fallback

결정과 대안은 [ADR 0031](../adr/0031-pg-lookup-retry-circuit-breaker-and-fallback.md), 실험 과정은 [PG 조회 장애 실험](resilience-experiment-plan.md)에 둔다.

| 항목 | 정책 |
| --- | --- |
| 재시도 대상 | UNKNOWN 중 `TEMPORARY_FAILURE`(timeout·연결 실패·5xx)만. SUCCESS·FAILED·PENDING·0건은 대답을 들은 것, `UNEXPECTED_RESPONSE`는 다시 물어도 같을 가능성이 크다 |
| 횟수·시간 | 최초 포함 최대 3회, 쉬는 시간 100 → 200ms, 조회 응답 500ms, 예산 2s. 남은 예산 < 200ms + 1.1s이면 횟수가 남아도 멈춘다 |
| 회로 | 조회 두 경로에 하나. GET마다 집계, `TEMPORARY_FAILURE`만 실패. 최근 4·최소 4·실패율 50% 이상이면 OPEN, OPEN 10초, HALF_OPEN 시험 2회, 자동 전환 꺼짐 |
| 순서 | 바깥부터 fallback → retry → 회로 → GET. 회로가 막은 호출은 재시도하지 않고 `CIRCUIT_OPEN` |
| 범위 | 생성 POST는 감싸지 않는다. 조회 회로가 OPEN이어도 confirm의 POST는 나간다. TX C의 DB 오류도 대상이 아니다 |

숫자는 학습용 임시값이다. 응답 순서를 고정한 테스트와 소요 시간 측정은 동작을 확인할 뿐, 최대 3회·500ms·2초가 적절하다는 근거는 아니다.

**fallback**: retry 소진·예산 부족·회로 OPEN이면 DB를 바꾸지 않고 저장 상태와 `checked: false`를 202로 돌려준다. 원래 PENDING이던 시도를 UNKNOWN으로 바꾸지 않는다. 회로 OPEN은 "PG에 못 물어봤다"이지 결제 실패가 아니다. fallback이 읽는 DB까지 실패하면 결과를 지어내지 않고 오류를 돌려준다.

**`checked`**: 결제 성공 여부가 아니라 **이번 요청에서 결제 결과를 확인했는지**다. DB에 저장하지 않고 응답을 만들 때 정한다.

| 상황 | checked |
| --- | --- |
| PG가 PENDING이라고 대답 | true (정상 결과이지 fallback이 아님) |
| 조회 실패·회로 OPEN, 생성 응답 UNKNOWN | false |
| 같은 키 confirm이 PG를 부르지 않고 미완료 상태만 반환 | false |
| 이미 끝난 결과, 저장된 종결 결과로 C를 실행 | true |

**회로가 닫혔다고 응답을 쓸 수 있는 것은 아니다**: `UNEXPECTED_RESPONSE`는 회로에서 성공으로 센다. 그래서 HALF_OPEN에서 깨진 JSON을 두 번 받아도 CLOSED로 돌아갈 수 있다. 그 응답은 여전히 UNKNOWN이라 주문은 확정하지 않는다. 지금 회로는 일시적 통신 장애만 보는 정책이다.

## 관찰 정보와 결과 적용의 책임

| 경계 | 책임 |
| --- | --- |
| adapter | HTTP 상태·JSON·필드 타입·외부 응답 계약 확인. 읽을 수 있는 관찰과 통신·계약 이상을 구분해 전달 |
| TX B | 저장된 주문 번호·금액·시도·거래 키와 대조. 복수 거래·불일치·반대 결과를 충돌로 기록. 종결 상태 보호 |

- 생성 202의 PENDING 거래는 주문 번호·금액과 상관없이 OBSERVED로 넘기고, TX B가 조회와 같은 규칙으로 대조한다. 불일치면 `ORDER_MISMATCH`·`AMOUNT_MISMATCH` CONFLICT와 관찰한 거래 키·금액을 이력에 남긴다. 시도는 REQUESTED에 머물고 거래 키도 저장하지 않으므로, 이후 조회는 providerOrderId로 한다.
- 생성 202에 PENDING 이외 상태가 오면 생성 계약 위반이라 UNKNOWN으로 두고 조회로 확인한다. 이때의 관찰값은 이력에 남지 않는다.
- 카드 번호·요청 본문 전체는 기록이나 로그에 남기지 않는다.

<a id="pending-decisions"></a>

## 남은 판단과 검증

### 결제 생성 POST의 회로 — 결정 아님

조회 회로가 OPEN이어도 confirm의 POST는 나간다. 생성에도 회로를 둔다면, 회로가 OPEN일 때 재고를 잡기 전에 "지금 결제할 수 없음"으로 거절할지 정해야 한다. 주문은 DRAFT로 남아 나중에 같은 주문으로 진행할 수 있다. 이번에는 생성 회로를 두지 않았다.

### 보내지 못한 시도와 끝나지 않는 시도 — ADR 0034

PG가 꺼졌거나, PG는 정상인데 조회가 몰려 연결 풀(같은 주소당 5개)을 모두 잡은 동안의 결제 생성은 PG에 닿지 않아 조회해도 계속 0건이었다. 예전에는 이 시도가 UNKNOWN으로 남아 끝나지 않고 재고를 묶었다([PG 조회 장애 실험 11회차](resilience-experiment-plan.md#learning-e)). 이제 두 가지로 정리한다([ADR 0034](../adr/0034-close-only-proven-unsent-payments-and-keep-unresolved-pending.md)).

| 상황 | 처리 |
| --- | --- |
| 생성이 연결 단계에서 끝남 (연결을 빌리지 못함·새 연결 거부·연결 수립 시간 초과) | 보내지 못한 것이 확실하다. confirm에서 바로 FAILED(사유 `NOT_SENT`)로 닫고 재고를 돌려준다 |
| 보냈는지 모름 (응답 timeout·5xx·재사용 연결로 쓰다 끊김) | 다시 보내지 않고 조회로만 확정한다 |
| 오래 기다려도 PG 조회가 0건임 | 미완료와 예약을 유지한다. 배치가 계속 기존 거래를 조회하고, 이후 확인한 종결 결과를 반영한다 |

- 닫힌 주문은 PAYMENT_FAILED로 끝나고, 같은 주문으로 다시 결제하지 않는다. 고객은 주문을 새로 만든다.
- PG가 꺼진 직후 첫 요청은 앞 요청이 열어 둔 재사용 연결로 나가 "쓰다 끊김"이 된다. 보내지 못했다고 증명할 수 없어 UNKNOWN이며, PG 복구 뒤 31분 된 시도를 조회해 0건이어도 예약을 유지한다. 그다음 요청부터는 새 연결이 거부돼 바로 닫힌다(카오스 테스트).
- 처음 구현했던 "30분 경과 + 조회 0건이면 실패"는 철회했다. 조회 0건은 이후 거래가 생기지 않는다는 증명이 아니며, 늦은 SUCCESS를 충돌로 기록해도 실패 주문·반환 재고는 복구되지 않는다. 배치·도메인의 만료 경로, 만료 이력 사유, 닫힌 결제에 callback이 오면 다시 조회하던 분기를 함께 제거했다.
- 재고가 오래 묶이는 비용은 남는다. 30분 등을 장기 미확인 검토 기준으로 삼을 수 있지만 자동 실패 기준으로 쓰지는 않는다. 알림·운영 화면은 아직 없으며, 이전 정책으로 닫힌 데이터도 자동으로 되돌리지 않는다.

### 아직 검증하지 않은 것

- JVM 종료·재기동 자체. 같은 DB·PG를 유지한 채 서비스·HTTP 클라이언트를 다시 만드는 복구는 `PaymentRecoveryRestartTest`로 확인했다([흐름 UC9](payment-flow.md)).
- callback 처리 시간(조회 예산 2s + B + C)이 PG의 응답 대기 한도 안인지. 로컬 PG에는 재전송 계약이 없다.
- 두 트랜잭션의 commit 자체 오류. 지금은 flush 뒤 예외 주입과 DB 컨테이너 일시정지로 확인했다.
- 여러 서버에서 배치를 함께 켠 경우.

### 운영이라면 더 볼 것

| 항목 | 현재 판단 |
| --- | --- |
| 연결 풀 | HttpClient 5.4.2 기본값은 경로당 5·전체 25. 생성과 조회가 같은 풀을 씀. 조회가 몰리면 연결 빌리기 실패가 `TEMPORARY_FAILURE`로 회로에 세져 PG가 정상인데 회로가 열리고, 생성 POST도 막힐 수 있음을 실험으로 확인([실험 E](resilience-experiment-plan.md#learning-e)). 연결 빌리기 실패의 성공 집계·조회 실행 3개 제한·조회 풀 3개/생성 풀 2개 분리는 테스트 안에서만 비교했고 조회 정책은 바꾸지 않음. 풀 분리는 생성 POST를 보호했지만 조회 대기 실패로 조회 회로는 OPEN이 됨. 표본에서 완전히 제외하는 후보는 아직 비교하지 않음 |
| timeout | 로컬 학습값. 생성·조회의 응답 시간 분포·자원 점유·UNKNOWN 비율을 재서 정함. 다른 PG의 안내값(예: 토스 결제 API 60초)을 비동기 접수형 로컬 PG에 그대로 쓰지 않음 |
| 확실한 미발송·장기 미확인 | 연결 단계의 확실한 미발송만 즉시 실패로 닫음(ADR 0034). 시간 경과·조회 0건에는 예약 유지. 응답 시간 분포만으로 자동 반환을 정당화할 수 없으며 PG의 최종 실패 보장이나 확인된 취소 같은 추가 계약과 장기 미확인 운영 절차가 필요함 |
| 관찰 지표 | 호출 종류·주문 번호·소요 시간·실패 이유를 카드 정보 없이 로그·지표로 남기는 일은 아직 없음. 회로 상태 변화도 로그·지표·알림으로 내보내지 않음. 운영이라면 OPEN 전환은 운영자가 알아야 하는 사건이라 지표(Micrometer)와 알림으로 낸다. 회로는 메모리라 서버마다 따로이고 재시작하면 CLOSED |
| 회로 숫자 | 창 4·최소 4는 상태 변화를 관찰하기 쉬운 학습값이라 우연한 실패 2번에도 열릴 수 있음. 운영이라면 평소 실패율·응답 시간 p99·호출량을 보고, 호출량에 맞는 큰 창이나 시간 기반 창(예: 최근 60초)을 쓴다 |
| 느린 응답 | 지금은 실패율만 본다. PG는 죽기 전에 느려지는 경우가 많아 운영이라면 느린 호출 비율(`slowCallRateThreshold`)도 함께 본다. 기준을 정할 정상 응답 시간 분포가 없어 넣지 않았다 |
| callback 조회 증폭 | 우리 시도가 아니거나 거래가 있는 완료 시도는 조회 없이 끝냄. 거래 없이 FAILED로 닫힌 시도는 늦은 거래 확인을 위해 완료 뒤에도 조회함. 미완료·거래 없이 닫힌 시도의 중복 알림은 매번 조회하며 호출 제한은 없음 |
| providerOrderId 단위 | 주문당 한 시도라 주문 기반 번호. 실패 후 재결제를 허용하면 과거·새 거래가 같은 주문 조회에 섞이므로 시도 단위 번호를 다시 검토 |
| 망취소 | 결과 미확인 거래에 취소를 요청하는 대안. 테스트 PG에 취소 계약이 없어 범위 밖. 취소 응답까지 잃으면 다시 미확인 |
| 결과 저장·주문 반영 분리 | 결제 뒤 작업(포인트·쿠폰·알림 등)이 늘면 C를 더 나누거나 비동기로 옮길지 검토. 그때는 B 커밋과 메시지 발송 사이의 유실도 다뤄야 함 |

연결 수립 timeout은 설정만 확인했고 장애 재현은 하지 않았다. `10.255.255.1` 같은 주소는 환경에 따라 즉시 거부되거나 다른 경로로 연결될 수 있어 결정적인 테스트를 보장하지 않는다.

참고: [HttpClient 5.4.2 연결 풀 기본값](https://github.com/apache/httpcomponents-client/blob/rel/v5.4.2/httpclient5/src/main/java/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManager.java), [토스 Read Timeout 안내](https://docs.tosspayments.com/resources/glossary/timeout), [Spring 6.2.5 request factory](https://docs.spring.io/spring-framework/docs/6.2.5/javadoc-api/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.html).
