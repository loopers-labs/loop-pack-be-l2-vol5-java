# ADR 0030: PG 결제를 TX A·PG 호출·TX B로 나누고 결과를 결제 시도 전이와 이력으로 한 번만 반영한다

Status: Accepted — 결과 저장·주문 반영 경계는 [ADR 0032](0032-persist-payment-result-before-order-application.md)로 변경
Related: [ADR 0010](0010-point-balance-locks-before-ledger-read.md), [ADR 0011](0011-order-confirmation-locks-order-row.md), [ADR 0012](0012-stock-decrement-locks-product-row.md), [ADR 0028](0028-test-pg-http-boundary-and-no-create-retry.md), [ADR 0029](0029-order-payment-switches-to-pg-with-reservation-on-order.md)

Follow-up: 최초 PG 확정 시각과 커밋 후 이벤트 전달은 [ADR 0035](0035-publish-order-confirmation-after-db-commit.md)에서 확장한다. 아래 본문은 당시 결정·메서드 형태·검증 기록을 보존한다.

## Context

PG 호출은 DB 트랜잭션에 참여하지 않는다. 한 트랜잭션 안에서 PG를 부르면 PG를 기다리는 동안 주문·상품 행 잠금과 DB 연결을 잡고 있고, PG 성공 뒤 DB가 롤백돼도 외부 결제는 남는다. 결과는 생성 응답, 고객 reconcile, callback, 배치 등 여러 경로로 늦거나 중복해 들어올 수 있으므로 한 번만 반영해야 한다. 테스트 PG는 호출자 멱등 키를 받지 않는다.

## Decision

**트랜잭션 구간**

- `OrderFacade.confirm`은 트랜잭션 없이 흐름만 조정한다. callback(`handleCallback`)도 reconcile과 같이 저장된 결과가 미확정일 때 TX 밖에서 PG를 조회하고 `applyLookup`(TX B)에 저장한 뒤, 종결 결과는 `applyRecordedResult`(TX C)로 주문에 반영한다. 별도 빈 `OrderPaymentTransactions`의 `start`(TX A) → TX 밖 `PaymentProvider.create` → `recordCreateResponse`(TX B) → 종결이면 `applyRecordedResult`(TX C) 순서다. `reconcile`은 저장된 시도를 읽은 뒤 TX 밖에서 조회하고, 응답을 받은 다음에 `applyLookup`(TX B)을 연다. 조회 결과를 확인할 수 없으면 TX B를 열지 않고 저장된 상태를 돌려준다.
- TX A: 주문 행 잠금 → 사용자·소유권 확인 → 기존 시도와 키·요청 내용 판단 → DRAFT 확인 → 상품 ID 오름차순 잠금·재고 차감 → 주문 PAYMENT_PENDING·PG·RESERVED → `PaymentAttempt.start()`로 시도(REQUESTED, providerOrderId, 금액, requestFingerprint) 저장 → 커밋. 재고 부족·삭제·잘못된 입력이면 전체 롤백되고 PG를 호출하지 않는다. 카드 입력은 TX A 전에 검증한다.
- 발송 권한: TX A에서 새 시도를 만든 실행만 PG에 POST한다. 같은 키·같은 주문·같은 금액·같은 카드 입력의 재요청은 PG를 부르지 않는다. 저장된 종결 결과가 주문에 미반영이면 TX C를 수행한 뒤 반환한다. 같은 키라도 카드 종류·번호 또는 저장 금액이 다르면 409로 거절하며 기존 결제·주문·예약·재고는 바꾸지 않는다. 요청 일치는 시도 상태와 무관하게 TX C 복구보다 먼저 확인한다. 다른 키로 같은 주문, 같은 키로 다른 주문도 409다. `order_id`·`attempt_key` 유일 제약 위반도 409로 바꾼다.
- 요청 비교: `PaymentAttempt`에 `request_fingerprint`(필수·64자·변경 불가)를 저장한다. 시도 키·카드 종류·번호를 SHA-256으로 해시하고 앞 두 필드에 길이를 붙여 경계를 구별한다. `matchesRequest()`에서 금액과 해시를 대조한다. 카드 입력은 문자열 그대로 비교하며 원문은 저장하지 않는다. 해시도 민감한 비교 정보로 취급하고 API 응답·로그에 노출하지 않는다. 합성 카드 입력을 사용하는 로컬 과제의 구현이며 실제 카드 보관·익명화를 보장하는 설계는 아니다.
- 생성 POST는 자동으로 다시 보내지 않는다(ADR 0028). 응답을 잃으면 UNKNOWN으로 남기고 저장된 providerOrderId로 조회한다.
- 같은 키 confirm이 저장된 미완료 결과만 반환하면 PG 관찰 없이 확인한 것으로 표시하지 않도록 `checked: false`다. 종결된 저장 결과는 추가 조회 없이 true로 반환한다(응답 규칙은 ADR 0031). 발송 권한은 그대로이며, 저장된 종결 결과의 미반영 주문은 TX C로 복구한다.

**잠금**

- TX A·B·C 모두 주문 행을 먼저 잠근다. TX A는 기존 결제 시도를 일반 조회한 뒤, 재고 예약 때 상품을 ID 오름차순으로 잠근다. TX B·C는 주문 다음 결제 시도를 잠그고, TX C에서 실패 재고를 반환할 때만 이어 상품을 ID 오름차순으로 잠근다. 행은 FOR UPDATE로 잠그고 자바 코드로 판단한다.
- TX A는 주문 행을 **가장 먼저** 잠그고, 그 뒤에 일반 조회로 기존 시도를 읽는다. REPEATABLE READ의 스냅샷은 첫 일반 조회 때 만들어지므로, 주문 잠금 전에 다른 행을 먼저 읽으면 앞 요청이 커밋한 시도를 보지 못한다(ADR 0010과 같은 원리). 반대로 아직 없는 시도를 잠금 조회하면 유일 인덱스의 빈 구간이 잠겨, 서로 다른 주문의 동시 확정이 INSERT에서 교착된다. 둘 다 구현 중 동시성 테스트에서 실제로 재현했다.

**한 번만 반영**

- 관찰 반영은 `PaymentAttempt`의 `markPending`·`markUnknown`·`succeed`·`fail`이 `APPLIED`·`IGNORED`·`CONFLICT`로 대답한다. 상태는 앞으로만 가고 SUCCESS·FAILED는 되돌리지 않는다. 저장된 키와 다른 거래 키, 반대 종결 결과는 CONFLICT이며 상태를 바꾸지 않는다.
- TX B는 결제 시도와 관찰 이력만 저장한다. TX C는 저장된 종결 결과와 주문·예약 상태를 대조해 성공은 `completePayment()`(재고 그대로), 실패는 OrderItem 수량만큼 재고를 더한 뒤 `failPaymentWithReleasedStock()`을 수행한다. C의 주문·예약·재고는 함께 롤백되지만 B의 결제 사실은 유지된다. 중복 C는 완료된 주문·예약 조합으로 무시하며, `APPLIED` 여부를 C 실행 조건으로 쓰지 않는다([ADR 0032](0032-persist-payment-result-before-order-application.md)). 재고 반환은 예약 뒤 삭제(soft delete)된 상품도 잠가 더한다. 이미 판매를 시작한 주문의 결과를 끝내는 일이라 상품 활성 여부로 막지 않는다.
- 늦은 응답·중복은 예외 대신 IGNORED로 대답한다. 예외로 던지면 TX B 롤백과 (이후 callback의) 재전송이 반복되기 때문이다.
- 접수 전 거절(422의 증거 네 가지가 모두 맞는 경우)은 거래 키 없이 `reject()`로 TX B에서 FAILED를 기록하고 TX C에서 재고를 한 번 반환한다(이력 사유 `REJECTED_BEFORE_CREATE`). 증거가 하나라도 빠진 422는 UNKNOWN이다.
- 조회 결과가 0건이면 IGNORED(`NO_TRANSACTION_YET`)로 기다리고, 2건 이상·주문 번호 불일치·금액 불일치는 CONFLICT로 기록하고 상태를 바꾸지 않는다.
- 생성 응답도 같은 `apply()`로 주문 번호·금액을 대조한다. adapter는 생성 202의 PENDING 거래를 대조 없이 넘기므로, 불일치한 생성 응답은 UNKNOWN 대신 CONFLICT와 관찰값으로 남고 시도는 REQUESTED에 머문다. 이후 복구는 providerOrderId 조회로 같다.

**이력**

- `payment_event`에 TX B가 반영한 모든 관찰을 한 줄씩 추가한다: 결제 시도, 들어온 경로(CREATE_RESPONSE, RECONCILE, CALLBACK, BATCH), 관찰한 상태·거래 키·금액, 결과, 사유. 정상 관찰도 남겨 순서를 다시 볼 수 있게 한다. CONFLICT는 WARN 로그도 남긴다. 카드 번호는 남기지 않는다. "사람이 봐야 하는 시도"는 CONFLICT 기록이 있는 시도로 찾는다.

## Alternatives and costs

- **같은 키·주문이면 카드 변경을 무시하고 기존 결과 반환**: 처음에는 카드 원문을 저장하지 않으면서 중복 POST만 막는 방식으로 채택했다. 그러나 바뀐 입력을 동일 요청으로 받아 과제의 요청 일치 계약과 달랐다. 카드 원문 대신 비교용 해시를 저장해 409로 구분하도록 변경했다. 칼럼과 해시 계산·비교 책임이 추가된다. 기존 PG 시도에는 비교값을 소급 생성할 수 없어 데이터를 유지하는 환경에는 별도 전환 정책이 필요하다. 현재 Testcontainers 검증은 새 스키마를 생성한다.
- **확정 실패 후 다른 카드로 재결제**: 잔액 부족 등으로 앞 결제가 확정 실패한 뒤 다른 카드로 결제하려는 상황은 유효한 후속 요구다. 이번 범위는 주문당 한 시도라 구현하지 않는다. 추후 확정 실패를 확인한 뒤 새 시도·새 키를 만드는 흐름, 주문당 한 시도 제약, 재고 예약의 재확보·유지 정책을 함께 정한다. 카드 변경만으로 기존 시도의 실패를 추정하거나 PENDING·UNKNOWN을 재결제로 전환하지 않는다.
- **TransactionTemplate으로 한 메서드 안에서 구간 나누기**: 경계가 한 화면에 보이지만 기존 코드의 annotation 방식과 달라진다. 별도 빈을 택했고, 자기 호출 함정을 피하려고 흐름(OrderFacade)과 TX 구간(OrderPaymentTransactions)을 다른 빈에 둔다.
- **조건부 UPDATE**: 잠금 코드는 줄지만 0행일 때 "이미 같은 결과"인지 "충돌"인지 알려면 다시 읽어야 한다. 이력에 결과와 사유를 남겨야 해서 FOR UPDATE를 택했다.
- **충돌 기록을 결제 시도의 사유 칸이나 로그로만**: 더 단순하지만 여러 번의 관찰과 순서가 남지 않는다. 실무에서 흔한 append-only 이력 테이블을 택했고, 대가로 엔티티와 리포지토리가 하나씩 늘었다.
- **한계**: 결과를 끝내 확인하지 못한 시도는 예약을 계속 잡는다. 서비스·HTTP 클라이언트 재생성 복구는 검증하며, JVM 재기동 검증은 남아 있다. 미완료 시도를 찾는 배치는 [ADR 0033](0033-payment-recovery-batch-for-unfinished-attempts.md)에서 추가했다. 조회 retry·회로·fallback은 [ADR 0031](0031-pg-lookup-retry-circuit-breaker-and-fallback.md)에서 적용했으며 외부 조회 정책을 담당한다. B·C 반영 경계는 ADR 0032를 따른다.

## 검증

[week5 구현 기록](../week5/implementation-log.md)에 둔다. `OrderPaymentIntegrationTest`(실제 MySQL·가짜 PG HTTP, 같은 키의 미완료·종결 응답 표시), `PaymentResultApplicationTest`(B 저장 오류와 C 반영 오류의 별도 복구, 같은 결과 동시 반영), `PaymentRecoveryRestartTest`(서비스·클라이언트 재생성 복구), `PaymentAttemptTest`, `PaymentAttemptPersistenceTest`, 옮긴 `OrderConfirmationConcurrencyTest`·`StockConcurrencyTest`·`LockFailureIntegrationTest`·`TransactionFailureIntegrationTest`·`BrandRcBulkValidationTest`. 최신 실행 범위와 건수는 구현 기록의 해당 검증 항목을 따른다.

요청 일치 보완은 `PaymentAttemptTest`(동일 입력·금액/카드 변경·필드 경계 구별), `PaymentAttemptPersistenceTest`(DB 재조회 뒤 비교), `OrderPaymentIntegrationTest`(다섯 시도 상태의 같은 내용 재전송·카드 변경 거절, B만 저장된 상태에서 불일치 요청의 C 실행 차단), `OrderV1ControllerTest`(HTTP 409)로 확인한다. 변경 전 거절 기대값 10건의 실패와 변경 후 관련 검사 결과는 [구현 기록 16절](../week5/implementation-log.md#request-matching)에 둔다.

후속 보완에서 B의 결과 저장과 C의 SUCCESS 확정·FAILED 반환을 각각 flush한 뒤 예외로 롤백하는 장면, 조회의 주문·금액·거래 키 불일치 시 상태 보존·충돌 이력, 여러 품목 중 뒤 상품 부족 시 전체 TX A 롤백, 예약 뒤 가용 재고 변경의 성공·실패 결과를 추가로 검증했다. SQL 자체 오류·commit 실패, JVM 종료·재기동, callback/reconcile API를 거친 실제 동시 실행은 이 검증에 포함하지 않는다. callback·reconcile·배치·같은 키 confirm을 `OrderFacade` 수준에서 함께 실행한 검증은 이후 `PaymentChaosTest`에서 했다([week5 구현 기록](../week5/implementation-log.md#chaos)). 같은 DB·PG를 유지한 서비스·HTTP 클라이언트 재생성은 별도 테스트로 확인한다.
