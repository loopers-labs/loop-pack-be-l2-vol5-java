# 5주차 구현 기록

구현을 어떤 순서로 했고, 무엇을 테스트로 확인했는지 적는다. 현재 결정과 이유는 [설계](design.md)와 ADR에 둔다.

## 현재 상태

| 단계 | 결과 | 기록 |
| --- | --- | --- |
| PG HTTP 경계 | 주문 없이 adapter·가짜 PG 계약 확인 | [1](#http-boundary) |
| 결제 시도·주문 모델 | 앞으로만 가는 상태, 예약 상태 칸 | [2](#attempt-model) |
| 주문 결제 연결 | TX A → PG → TX B, reconcile API | [3](#order-payment) |
| 접수 전 거절(422) | 거래 없음이 증명된 거절을 FAILED로 종결 | [4](#rejected) |
| 검증 과정에서 발견한 문제 보완 | 삭제 상품 반환, 422 금액 범위, 생성 응답 불일치 보존 | [5](#review-fixes) |
| 조회 retry·회로·fallback | 이유 라벨, 조회만 감쌈, `checked` | [6](#lookup-resilience) |
| 주문 조회의 결제 상태 | 고객 한 칸, 관리자 PG 식별자 | [7](#order-query) |
| callback API | 본문 대신 PG 조회, 실패 시 503 | [8](#callback) |
| 응답 배정·`checked` 보완 | 가짜 PG 응답 엇갈림, 같은 키 재요청 표시 | [9](#review-followup) |
| 누락 검증 보완 | 조회 불일치, 관리자 재고 변경, 여러 품목 롤백 | [10](#missing-tests) |
| TX B·C 분리 | 결과 저장과 주문 반영을 나눔, 재생성 복구 | [11](#split-b-c) |
| 조회 시간 측정 | 응답 순서별 GET 수·시간 | [12](#timing) |
| 미완료 시도 배치 | 10분 지난 시도를 reconcile과 같은 규칙으로 마무리 | [13](#recovery-batch) |
| 카오스 테스트 | PG 죽음·느려짐·DB 멈춤, 장애를 섞은 동시 실행 | [14](#chaos) |
| 과제 마무리 점검 | 요구사항·현재 코드·문서 대조, 전체 검사 재실행, 기술 글 정리 | [15](#assignment-wrap-up) |
| 같은 키의 요청 일치 | 카드 종류·번호 변경은 409, 원문 대신 비교용 해시 저장 | [16](#request-matching) |
| 조회 정책의 가정 실험 | retry·회로 설정을 설정 파일로, 순서·retry 횟수·창 크기·실패율 기준·창 방식·연결 풀 비교, 실험 테스트를 평소 검사에서 분리 | [17](#assumption-experiments) |
| 확실한 미발송과 장기 미확인 | 연결 단계의 확실한 미발송은 즉시 실패. 자동 만료를 철회하고 시간 경과·조회 0건에도 예약 유지 | [18](#close-unsent-and-expired) |

**자동 만료 철회 전 전체 검사 이력**: `./gradlew :apps:commerce-api:check --offline` (Java 21, `DOCKER_HOST=tcp://localhost:2375`) → 69 suites·503건, 실패·오류·skip 0. Checkstyle main·test 오류 0과 ArchUnit 포함. 종료 결과 `BUILD SUCCESSFUL`, 4분 10초. 실험 테스트(`@Tag("experiment")`)는 이 검사에서 빠지며, `./gradlew :apps:commerce-api:experimentTest`로 따로 돌려 9 suites·41건 통과([17절](#assumption-experiments)). 자동 만료 철회 뒤의 검사는 [18절](#close-unsent-and-expired)에 별도로 기록한다.

**최신 전체 검사**: 자동 만료를 철회하고 남은 만료 흔적(닫힌 결제 재조회 분기, 만료 이력 사유, 겹치는 테스트)을 정리한 뒤 `./gradlew :apps:commerce-api:check --offline` → 69 suites·503건, 실패·오류·skip 0. Checkstyle main·test 오류 0과 ArchUnit 포함. `BUILD SUCCESSFUL`, 4분 58초. 실험 테스트는 `./gradlew :apps:commerce-api:experimentTest`로 따로 돌려 9 suites·44건 통과(감싸는 순서 실험에 "거래 하나만 실패" 장면 2건, 창 크기 실험에 창 20·최소 표본 4 1건 추가).

**실행 환경**: Java 21(기본 JDK가 다르면 `JAVA_HOME`을 Java 21로 지정한다. 17로는 `List.getFirst()` 컴파일 오류가 난다), `source ./scripts/fix-docker-testcontainers.sh`로 Docker 연결을 맞춘 뒤 Testcontainers MySQL과 테스트가 띄우는 가짜 PG HTTP 서버를 쓴다.

<a id="http-boundary"></a>

## 1. PG HTTP 경계

주문과 연결하기 전에 adapter와 가짜 PG만으로 HTTP 계약을 확인했다([ADR 0028](../adr/0028-test-pg-http-boundary-and-no-create-retry.md)). 테스트는 [PaymentProviderContractTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentProviderContractTest.java), 외부 상태는 [FakePaymentServer](../../apps/commerce-api/src/test/java/com/loopers/support/payment/FakePaymentServer.java)로 통제한다.

| 장면 | 확인한 것 |
| --- | --- |
| 정상 생성·조회 | POST 1회·거래 1건, 생성 응답 PENDING. 상태를 SUCCESS로 바꾼 뒤 GET으로 같은 거래 확인 |
| 거래 생성 뒤 응답 유실 | create는 UNKNOWN, PG 거래 1건. providerOrderId 조회로 SUCCESS 확인, POST는 1회 그대로 |
| 응답 timeout | 거래 저장 뒤 응답 보류. 2초 안에 UNKNOWN, 거래는 남고 조회 가능 |
| 같은 주문을 두 번 생성 | 거래 2건. PG가 주문 번호로 중복을 막지 않음 |
| 조회 404·빈 목록 | OBSERVED·빈 목록. 결제 실패로 확정하지 않음 |
| query 인코딩 | 공백·`+`·`/`·`?`·`=`·`&`·한글을 orderId 한 값으로 전송 |
| 503·본문 없는 422 | UNKNOWN. 숨은 재시도 없이 실제 호출 수 확인 |
| 잘못된 로컬 입력 | 빈 카드·0원은 발송 전 거절. toString에 카드 번호 없음 |
| Spring 설정 | DB 없는 작은 context에서 Duration 바인딩과 PG 빈 생성. 빈 초기화만으로 외부 요청 없음 |

**해석**: 응답을 받지 못했다고 외부 효과가 없었다고 볼 수 없다. 반대로 POST를 두 번 보내면 거래가 두 건 생긴다. 그래서 adapter의 재시도 금지와, DB로 발송 권한을 한 번만 주는 장치가 모두 필요하다.

테스트를 먼저 작성해 컴파일 실패를 확인한 뒤 최소 구현했다. 계약 테스트 15건·ArchUnit·Checkstyle 통과.

<a id="attempt-model"></a>

## 2. 결제 시도·주문 모델

| 변경 | 테스트 |
| --- | --- |
| `PaymentAttempt`·`PaymentAttemptStatus`: 앞으로만 가는 전이, `APPLIED`·`IGNORED`·`CONFLICT` 결과 | `PaymentAttemptTest` |
| `payment_attempt` 저장, `order_id`·`attempt_key` 유일 제약 | `PaymentAttemptPersistenceTest` (MySQL): 같은 주문의 두 번째 시도, 다른 주문의 같은 키 거부 |
| Order의 `paymentMethod`·`reservationStatus`와 PG 전이 메서드 3개 | `OrderModelTest` |
| providerOrderId를 adapter가 아니라 `PaymentAttempt.start()`에서 정해 저장. adapter는 받은 번호를 그대로 보냄 | 계약 테스트가 HTTP 본문의 orderId `LP-ORD-0000000042` 확인 |

정한 것: PG 주문은 포인트 2% 적립 없음, DRAFT·과거 POINT 주문의 null을 `paymentMethod`로 구분, 금액은 서버가 주문에서 정하므로 confirm 판단에서 "같은 키 + 다른 금액"을 뺌.

**환경 메모**: 4주차 benchmark 프로필은 `ddl-auto: validate`라, 새 칼럼·테이블이 없는 `loopers_benchmark` DB로는 앱이 시작하지 않는다. 5주차 검증은 Testcontainers로 충분해 benchmark 스키마는 갱신하지 않았다.

<a id="order-payment"></a>

## 3. 주문 결제 연결 (TX A·PG·TX B)

주문 확정을 포인트 결제에서 PG 결제로 바꾸고 reconcile API를 추가했다([ADR 0029](../adr/0029-order-payment-switches-to-pg-with-reservation-on-order.md), [ADR 0030](../adr/0030-pg-payment-split-transactions-and-apply-once.md)).

| 위치 | 내용 |
| --- | --- |
| `OrderPaymentTransactions` | TX A `start`, TX B `recordCreateResponse`·`applyLookup`, 소유 확인 `getMine` |
| `OrderFacade` | `confirm`(트랜잭션 없음: 카드 검증 → A → PG 생성 → B), `reconcile`(저장된 키 또는 providerOrderId로 조회) |
| `PaymentEvent` | 관찰 이력: 경로·관찰 상태·거래 키·금액·결과·사유 |
| `Stock.increase`, `ProductModel.increaseStock` | 확정 실패 시 재고 반환 |
| `interfaces/api/order` | `POST /orders/{id}/confirm`(미종결 202·종결 200), `POST /orders/{id}/payment/reconcile` |
| 삭제 | `OrderConfirmationService`, `PointBalanceService.use`·`reward` |

**새 테스트** `OrderPaymentIntegrationTest`: 실제 MySQL과 가짜 PG로 주문 42(재고 5, 수량 3)를 따라간다. 생성 PENDING·재고 2·포인트 변화 0, reconcile SUCCESS, 응답 유실 → UNKNOWN → providerOrderId 조회로 확정(POST 1회), FAILED면 재고 5로 한 번 반환·재적용 IGNORED, 끝난 주문의 reconcile은 조회 없음, 조회 0건 대기, 복수 거래·반대 결과 CONFLICT, 재고 부족 시 PG 0회, 같은 키 재요청·다른 키 409, 과거 POINT 주문 거절.

**동시성 테스트가 잡은 결함 두 가지**

1. 같은 주문을 8개 요청이 서로 다른 키로 동시에 확정하자, 일부가 "이미 결제가 시작된 주문"이 아니라 "확정할 수 없는 상태"로 실패했다. TX A가 주문을 잠그기 전에 사용자를 읽어 REPEATABLE READ 스냅샷이 먼저 만들어졌고, 그 뒤 읽은 결제 시도가 앞 요청의 커밋을 보지 못했다.
2. 결제 시도를 잠금 조회로 바꾸자, 서로 다른 주문의 동시 확정이 교착됐다. 아직 없는 행을 잠금 조회하면 유일 인덱스의 빈 구간이 잠기고, 두 트랜잭션이 같은 구간에 INSERT하며 서로를 기다렸다.

최종적으로 TX A가 **주문 행을 가장 먼저 잠그고**, 결제 시도는 일반 조회로 읽는다(3주차 ADR 0010과 같은 원리). 동시성 관련 테스트를 두 번 더 실행해 통과했다.

**기존 테스트 이전**: 확정 계약이 "포인트로 결제하고 CONFIRMED"에서 "재고를 예약하고 PG 결제 시작"으로 바뀌었다. 기대값을 완화하지 않고 지키던 보장에 따라 나눴다.

| 구분 | 테스트 | 처리 |
| --- | --- | --- |
| 재고·잠금 보호 | `StockConcurrencyTest`, `OrderConfirmationConcurrencyTest`, `LockFailureIntegrationTest`, `BrandRcBulkValidationTest`의 경쟁 2건, `BrandRemovalTransactionTest`, `TransactionFailureIntegrationTest`의 롤백, `OrderPersistenceIntegrationTest` | CONFIRMED·포인트 차감 기대값을 PAYMENT_PENDING·RESERVED·포인트 변화 0으로. 재고 수치·잠금·롤백 기대값은 그대로. 실패 주입은 TX A의 결제 시도 저장에, 잠금 timeout은 TX A가 실제로 잠그는 상품 행에 걸었다 |
| 포인트 결제 전용 | `OrderConfirmationServiceTest`, `PointConcurrencyTest`의 주문 결제 6건, 포인트 결제·적립 롤백 등 | 삭제. 주문이 포인트를 쓰는 경로 자체가 없어짐 |
| 응답 모양 | `OrderV1ControllerTest`, `AdminOrderV1ControllerTest` | 결제수단·예약 상태 칸, confirm 202·reconcile 200 |

이 테스트들은 가짜 PG를 띄우지 않아 PG 연결이 거부되고 UNKNOWN이 기록된다. 재고·잠금만 보는 장면이라 의도한 동작이다.

전체 `check` 60 suites·377건 통과.

<a id="rejected"></a>

## 4. 접수 전 거절(422)

- `PaymentObservation.REJECTED`를 추가했다. adapter는 422의 code·`transactionCreated:false`(boolean)·주문 번호·금액이 모두 맞을 때만 REJECTED, 하나라도 빠지면 UNKNOWN이다.
- `PaymentAttempt.reject()`: REQUESTED면 키 없이 FAILED, 키 없는 FAILED면 IGNORED, 그 밖은 CONFLICT.
- 이력 사유 `REJECTED_BEFORE_CREATE`. 재고 반환·주문 실패는 조회 FAILED와 같은 경로를 쓴다.
- 테스트: 계약 테스트에 정확한 거절 1건과 증거가 빠진 422 7가지, `PaymentAttemptTest`에 대답 규칙 3건, 통합 테스트에 거래 0건·FAILED·재고 5·POST 1회·이후 reconcile 조회 없음.

전체 `check` 60 suites·389건 통과.

<a id="review-fixes"></a>

## 5. 검증 과정에서 발견한 문제 보완: 삭제 상품·422 금액 범위·생성 응답 불일치

| 장면 | 고치기 전 | 처리 |
| --- | --- | --- |
| 예약 뒤 상품 삭제 → PG FAILED | 재고 반환이 활성 상품만 잠가 NOT_FOUND → 반영이 매번 롤백, 주문이 PAYMENT_PENDING에 머묾 | `getForUpdateIncludingDeleted`로 삭제된 상품도 잠가 반환. 삭제 표시는 유지 |
| 422 금액이 long 범위를 넘음 | `longValue()`로 잘려 주문 금액과 같아지면 거절로 판정 | `canConvertToLong()` 확인, 아니면 UNKNOWN |
| 생성 202의 주문 번호·금액 불일치 | adapter가 UNKNOWN으로 바꿔 받은 거래 정보를 버림. 응답 유실과 이상 응답을 구분 못 함 | adapter는 PENDING 여부만 보고 넘김. TX B가 대조해 `ORDER_MISMATCH`·`AMOUNT_MISMATCH` CONFLICT와 관찰값을 기록. 시도는 REQUESTED에 머묾 |

- 삭제 상품 실패와 범위 초과 금액은 고치기 전에 테스트가 실패하는 것을 먼저 확인했다.
- 생성 응답 불일치는 계약 테스트의 "UNKNOWN" 기대에서 "OBSERVED로 전달"로 옮겼다. 기대값 완화가 아니라 대조 책임을 adapter에서 TX B로 옮긴 계약 변경이며, 같은 불일치를 통합 테스트에서 CONFLICT·예약 유지·POST 1회로 확인한다.
- 당시 TX B의 롤백 복구와 같은 FAILED의 동시 반영도 `PaymentResultApplicationTest`로 확인했다. 이 검증은 이후 TX B·C 분리에 맞춰 바뀌었다([11](#split-b-c)).

전체 `check` 61 suites·395건 통과.

<a id="lookup-resilience"></a>

## 6. 조회 retry·회로·fallback

[실험](resilience-experiment-plan.md)에서 정한 정책을 네 단계로 붙였다([ADR 0031](../adr/0031-pg-lookup-retry-circuit-breaker-and-fallback.md), [설계](design.md#lookup-resilience)). 단계마다 가짜 PG가 실제로 받은 GET 수로 확인했다.

| 단계 | 변경 | 확인한 것 |
| --- | --- | --- |
| 1. 이유 라벨 | `UnknownCause`. adapter가 `ResourceAccessException`·`HttpServerErrorException`은 일시적 장애, 나머지는 예상 밖 응답으로 분류 | timeout·503·연결 불가는 TEMPORARY_FAILURE, 401·JSON 깨짐·증거 부족 422는 UNEXPECTED_RESPONSE |
| 2. 조회 retry | `ResilientPaymentProvider`(`@Primary`)가 조회만 감쌈. 최초 포함 최대 3회, 100 → 200ms | 정상 GET 1, 503 → 정상 GET 2, 503 두 번 → 정상 GET 3, 계속 503 GET 3 후 UNKNOWN, timeout → 정상 GET 2. FAILED·404·0건·JSON 깨짐·401은 GET 1. 생성 503은 다시 보내지 않음. 통합: 한 번의 reconcile로 확정 |
| 3. 시간 예산 | 조회 전용 RestClient(응답 500ms). 예산 2s, 남은 예산 < 200ms + 1.1s이면 멈춤 | 매번 1초 늦는 조회는 GET 2에서 멈춤. 같은 600ms 지연에 생성은 받고 조회는 timeout |
| 4. 회로 | retry 안쪽, GET마다 집계, TEMPORARY_FAILURE만 실패, 차단은 retry 제외하고 `CIRCUIT_OPEN` | 계속 503이면 첫 조회 GET 3(CLOSED), 둘째 조회의 첫 GET에서 OPEN. OPEN이면 GET 0·즉시 반환. 정상 FAILED·JSON 깨짐 4번은 CLOSED. 테스트 시계로 9초 뒤 GET 0, 11초 뒤 다음 두 조회가 시험 → CLOSED. 한 조회의 retry가 시험 2번을 다 쓰면 다시 OPEN |
| fallback 표시 | `OrderPaymentInfo.checked`, 응답 `PaymentResponse.checked` | 끝내 조회 실패·회로 OPEN·생성 응답 유실은 false, 복구 조회는 true. reconcile fallback은 202 |

**실험 때 예상과 달라진 숫자**
- 3회차 계산은 조회 한 번을 500ms로 보아 3회가 예산 안이었다. 구현은 풀 대기·연결까지 더한 1.1s로 판단해, 응답이 계속 늦으면 2회에서 멈춘다. 빠르게 실패하는 503은 3회까지 간다.
- 7회차 예시는 최대 2회라 GET 4에 OPEN이었다. 구현은 최대 3회라 첫 조회 GET 3, 둘째 조회의 첫 GET에서 OPEN이다.

**테스트 지원 변경**
- `FakePaymentServer`: 지정 응답과 응답 지연을 대기열로 바꿨다. 한 번의 reconcile에서 GET이 여러 번 나가므로 "503 → 503 → 정상"을 순서대로 넣어야 한다.
- `unreadableLookupReturnsStoredStateWithoutRecording`: 503 한 번은 이제 retry로 회복되므로, 끝내 조회하지 못하는 장면을 유지하려고 503을 3번으로 늘리고 GET 3·`checked` false 확인을 더했다.
- 같은 Spring 컨텍스트에서 회로가 공유되므로 통합 테스트는 테스트마다 회로를 초기화한다. 이를 위해 `lookupCircuitBreaker()`를 열었다.
- 1단계는 컴파일 실패로, 2~4단계는 구현과 함께 실행해 확인했다. 구현 전 실패를 따로 확인하지 않은 단계가 있다.

전체 `check` 63 suites·416건 통과. 첫 실행에서 결제와 무관한 `PopularPageCacheIntegrationTest.actualAndIdempotentLikesKeepRankingAndDoNotExtendExpiry`가 Redis 남은 만료 시간 17,026ms(기대 15,000ms 이하)로 한 번 실패했고, 단독·전체 재실행에서는 통과했다. 원인은 조사하지 않았고 테스트도 고치지 않았다.

<a id="order-query"></a>

## 7. 주문 조회의 결제 상태와 같은 키·다른 카드

- `OrderInfo.PaymentSummary`를 추가했다. 고객 응답에는 `paymentStatus`만, 관리자 응답에는 `providerOrderId`·`transactionKey`까지. 목록 조회는 `findByOrderIds`로 결제 시도를 한 번에 읽는다.
- 당시에는 같은 키 + 다른 카드의 동작을 바꾸지 않고 해석을 [ADR 0030](../adr/0030-pg-payment-split-transactions-and-apply-once.md)과 설계에 남겼다. 이후 [16절](#request-matching)에서 다른 카드 입력은 409로 바꿨다.
- 테스트: PENDING·UNKNOWN·DRAFT 주문의 고객 단건·목록과 관리자 조회(PG 조회 0회), 카드만 다른 같은 키 재요청(POST 1회·거래 1건·재고 2), 컨트롤러의 노출 범위.

전체 `check` 63 suites·419건 통과.

<a id="callback"></a>

## 8. callback API

| 위치 | 내용 |
| --- | --- |
| `PaymentCallbackV1Controller` | `POST /api/v1/payments/callback`. 본문 `providerOrderId, transactionKey, status`. UNAVAILABLE이면 503, 나머지 200 |
| `OrderFacade.handleCallback` | 빈 번호 400 → 우리 시도가 아니면 끝 → 끝난 시도면 끝 → 저장된 키로 조회 → UNKNOWN이면 B 없이 UNAVAILABLE. reconcile과 조회 키 선택을 공유 |
| `PaymentEvent.Source.CALLBACK`, `findByProviderOrderId` | callback 이력 구분, PG 주문 번호로 시도 찾기. `uk_payment_attempt_provider_order` 추가 |

`PaymentCallbackE2ETest`(MySQL·가짜 PG·MockMvc): SUCCESS 알림 → 거래 키 조회 → 확정·이력 CALLBACK, 위조 본문 SUCCESS + PG PENDING → 미확정, 우리 시도가 아닌 번호 → 200·조회 0, 끝난 뒤 알림 → 200·조회 0, FAILED 알림 두 번 + reconcile → 재고 반환 1회·조회 1회, 키 없는 시도 → providerOrderId 조회, 조회 503 세 번 → 503·이력 없음 → 다음 알림으로 확정, 회로 OPEN → 503·조회 0, 빈 번호 → 400. 가짜 PG는 callback을 보내지 않아 테스트가 직접 호출한다.

전체 `check` 64 suites·428건 통과.

<a id="review-followup"></a>

## 9. 응답 배정·`checked` 보완

검증 과정에서 발견한 두 문제를 회귀 테스트로 먼저 재현한 뒤 고쳤다.

- **가짜 PG 응답 엇갈림**: 지연을 꺼내 기다린 **뒤에** 응답을 꺼내서, 기다리는 사이 다음 요청이 앞 요청의 503을 가져갈 수 있었다. 수신 기록·호출 수·지연과 응답 꺼내기를 같은 짧은 동기화 구간에서 처리하고, 기다림은 잠금 밖에서 한다. `FakePaymentServerTest`가 두 요청이 실제로 겹친 상태에서 각 응답을 확인한다.
- **같은 키 confirm의 `checked`**: PG를 부르지 않고 미완료 상태(PENDING·UNKNOWN)만 돌려주는데 `checked: true`였다. 이제 false, 이미 종결된 결과는 true다.
- **예산 이름**: `attemptMax`를 `attemptEstimate`로 바꿔 재시도 판단용 추정치임을 드러냈다. 숫자는 바꾸지 않았다.

관련 10 suites·85건, ArchUnit·Checkstyle 통과.

<a id="missing-tests"></a>

## 10. 누락 검증 보완

설계에 적었지만 테스트가 없던 규칙을 확인했다. 모두 기존 구현에서 통과했고, 제품 로직·설정·기존 기대값은 바꾸지 않았다.

- `OrderPaymentIntegrationTest`: 조회 SUCCESS의 주문 번호·금액·거래 키 불일치 각각에서 PENDING·예약·재고 유지와 충돌 기록(3건). 예약 뒤 가용 재고를 10으로 바꾸면 성공 후 10·실패 후 13, 반복 reconcile에도 추가 효과 없음(2건). 여러 품목 중 뒤 상품이 부족하면 앞 상품 차감도 롤백, DRAFT·시도 없음·POST 0(1건).
- `PaymentLookupCircuitBreakerTest`: 형식 오류가 통계에서 빠지는 것이 아니라 성공 표본으로 기록되고, HALF_OPEN에서 JSON 오류 두 번으로 CLOSED가 되어도 결과는 UNKNOWN(1건).

관련 13 suites·122건, ArchUnit·Checkstyle 통과.

<a id="split-b-c"></a>

## 11. TX B 결과 저장과 TX C 주문 반영 분리

확인한 결제 결과가 주문 처리 오류로 함께 롤백되지 않도록 기존 TX B를 나눴다([ADR 0032](../adr/0032-persist-payment-result-before-order-application.md)).

| 위치 | 변경 |
| --- | --- |
| `recordCreateResponse`·`applyLookup` (B) | 관찰 대조, 결제 시도·이력 저장. 주문·재고는 바꾸지 않음 |
| `applyRecordedResult` (C) | DB의 종결 결과를 읽어 주문·예약·실패 재고 반환. 이미 완료면 효과 없음 |
| `OrderFacade` | B가 커밋된 뒤 C를 호출. reconcile·callback·같은 키 confirm은 결제만 종결된 주문을 PG 조회 없이 C부터 복구 |
| `OrderPaymentInfo` | 결제 종결 `hasFinalPaymentResult()`와 주문·예약까지 완료 `isFinished()`를 구분 |

**장애를 넣어 확인한 것**

| 장면 | 결과 |
| --- | --- |
| B에 SUCCESS·FAILED만 저장 | 결제 결과·이력은 남고 주문 PAYMENT_PENDING·RESERVED·재고 2 |
| B 저장을 flush한 뒤 예외 | B 롤백, C 미실행. 다음 reconcile은 PG 재조회 → B → C |
| C의 확정·반환을 flush한 뒤 예외 | C만 롤백, B의 결과·이력 유지. 회로 OPEN에서도 다음 reconcile이 GET 0으로 C 복구 |
| B 커밋 뒤 C 전 중단 | 같은 MySQL·PG를 유지하고 서비스·HTTP 클라이언트를 새로 만들어 C부터 복구. 추가 POST·GET 0 |
| A 뒤 POST 전 / POST 뒤 B 전 중단 | 새 서비스가 providerOrderId로 조회. 전자는 0건·REQUESTED·예약 유지, 후자는 SUCCESS 반영. 재발송 0 |
| 같은 결과를 4개 실행이 B·C 처리 | SUCCESS·FAILED 각각 B APPLIED 1·IGNORED 3, 확정·반환 한 번. latch로 C 진입을 맞춰 sleep 의존을 없앰 |
| 같은 키 confirm·callback의 미반영 복구 | 새 POST·조회 없이 C. callback은 회로 OPEN·위조 본문에도 저장된 결과로 반영하고 200 |
| 결제 종결·주문 미반영 응답 | 202. 결제 결과만으로 완료 200을 주지 않음 |

B 전용 경계 테스트 2건이 기존 구현에서 실패하는 것을 먼저 확인했다. 기존 "TX B 실패 → 전부 롤백" 테스트는 새 경계에 맞춰 B 저장 오류와 C 반영 오류로 나눴고, 검증을 완화하지 않았다. 관련 테스트: `PaymentResultApplicationTest` 10건, `PaymentRecoveryRestartTest` 4건, `PaymentCallbackE2ETest` 11건, `OrderV1ControllerTest` 고객 8건.

전체 `check` 66 suites·454건 통과.

<a id="timing"></a>

## 12. 조회 시간 측정

응답 순서만 바꿔 `ResilientPaymentProvider` 조회 한 번의 GET 수와 시간을 쟀다. 결과와 해석은 [실험 문서의 측정](resilience-experiment-plan.md#time-comparison)에 둔다. 측정 원본 CSV는 저장소에 올리지 않는다.

| 응답 순서 | GET | 시간 (3회) |
| --- | --- | --- |
| 정상 PENDING | 1 | 1.6~2.3ms |
| 503 → PENDING | 2 | 104.8~108.9ms |
| 503 → 503 → PENDING | 3 | 316.6~339.1ms |
| 503 × 3 | 3 | 309.6~313.2ms |

- 장면마다 새 가짜 PG·adapter·회로로 시작했고, 측정 중 추가 POST는 0이었다.
- 측정용 코드는 레포 밖 임시 Gradle task로 실행했다. JUnit 검사가 아니며, 코드·설정은 바꾸지 않았다.
- 고정한 응답 순서의 동작과 비용을 본 것이며, 최대 3회·500ms·2초를 고른 근거는 아니다.

<a id="recovery-batch"></a>

## 13. 미완료 시도 배치

고객 reconcile도 callback도 없으면 미완료 시도와 예약이 계속 남는 문제를 배치로 메웠다([ADR 0033](../adr/0033-payment-recovery-batch-for-unfinished-attempts.md)). 포인트 만료 배치(`PointExpirationBatch`·`PointExpirationScheduler`)와 같은 구조다.

| 위치 | 변경 |
| --- | --- |
| `PaymentRecoveryBatch` | 만든 지 10분이 지났고 주문이 PAYMENT_PENDING인 시도를 id 순 50건씩 끝까지 처리. 결과 `Result(finished, unfinished, failed)` |
| `PaymentRecoveryScheduler` | `fixedDelay` 1분. `payment.recovery.scheduling-enabled=true`일 때만 등록 |
| `OrderFacade` | reconcile·callback의 결과 확인을 `resolve` 하나로 모으고, 배치용 `recover(orderId)`가 같은 규칙을 씀. callback의 503 판단은 `resolve` 결과의 `checked`로 정함 |
| `OrderPaymentTransactions.get` | 사용자 확인 없이 주문의 저장 상태 읽기 |
| `PaymentAttemptRepository.findUnfinishedCreatedBefore` | 주문 상태·생성 시각·id cursor로 대상 조회 |
| `PaymentEvent.Source.BATCH` | 배치가 남긴 이력 구분 |

`PaymentRecoveryBatchTest` 9건(실제 MySQL·가짜 PG). 결제 시도의 생성 시각을 SQL로 과거로 옮겨 10분 조건을 만든다.

| 장면 | 결과 |
| --- | --- |
| 9분 된 PENDING | 대상 아님, GET 0 |
| 11분 된 PENDING, PG SUCCESS | CONFIRMED·CONSUMED, 이력 `BATCH`, POST 추가 0 |
| 11분 된 REQUESTED, PG 거래 0건 | providerOrderId로 조회, REQUESTED·RESERVED 유지, POST 0 |
| 저장된 FAILED, 주문 미반영 | GET 0으로 C, 재고 반환 |
| 첫 건은 503 세 번, 둘째 건은 SUCCESS | 첫 건 그대로, 둘째 건 확정 |
| 회로 OPEN | GET 0, 상태 그대로 |
| 이미 끝난 주문 | 대상 아님 |
| 첫 건 TX B에서 예외 | failed 1, 둘째 건 확정. 다음 실행에서 첫 건도 확정 |
| 51건 | 페이지 경계를 넘어 모두 처리, 두 번째 실행은 대상 0·재고 반환 한 번 |

테스트를 먼저 작성한 뒤 구현했다. 구현 전 실패를 실행해 확인하지는 않았다. `resolve`로 합친 뒤 기존 reconcile·callback 테스트(`OrderPaymentIntegrationTest` 29건, `PaymentCallbackE2ETest` 11건, `PaymentResultApplicationTest` 10건, `PaymentRecoveryRestartTest` 4건)도 그대로 통과했다.

전체 `check` 67 suites·463건 통과. 스케줄러 자체의 주기 실행은 테스트하지 않았다(포인트 배치와 같이 배치를 직접 호출).

<a id="chaos"></a>

## 14. 카오스 테스트와 남은 동시성·보존 검증

PG가 죽거나 느려지고 DB가 멈추는 장애를 테스트 안에서 실제로 만들어, 회로·fallback·배치가 함께 동작하는지 확인했다. 제품 코드는 바꾸지 않았다.

**가짜 PG에 더한 것**: `down()`·`up()`(같은 포트로 닫았다 열기, 거래 기록 유지), `delayEveryResponse`(모든 응답 지연), `beforeNextLookupReply`(조회 응답 직전에 한 번 실행), `clearQueuedFaults`.

`PaymentChaosTest` 4건(실제 MySQL·가짜 PG. MockMvc는 장애 중 상품 조회와 DB 멈춤 중 callback에만 쓴다). 아래 표는 테스트가 assert한 값만 적었다. 시간은 상한을 검사했고 실제 측정값은 남기지 않았다.

**PG가 죽음** (`PG.down()`으로 포트를 닫아 연결 거부)

| 확인한 것 | assert한 값 |
| --- | --- |
| 장애 중 새 confirm | UNKNOWN, `checked: false`, PG가 받은 POST 수 1 그대로(장애 전 주문의 1건). 포트가 닫혀 거부된 연결은 가짜 PG가 셀 수 없으므로 재발송 금지의 근거는 아니다(근거는 계약 테스트) |
| 첫 reconcile | `checked: false`, 회로 CLOSED, 회로가 센 실패 3 |
| 둘째 reconcile | 회로 OPEN |
| 셋째 reconcile | `checked: false`, 200ms 미만 |
| 장애 중 상품 조회 API(`GET /api/v1/products/{id}`) | 200, 1초 미만 |
| 장애 중 두 주문 | PAYMENT_PENDING·RESERVED, 재고 100 → 94 |
| PG를 다시 열고 OPEN 후 10.5초 뒤 배치 | `Result(1, 1, 0)`, 회로 CLOSED. 장애 전 주문은 CONFIRMED·CONSUMED |
| 연결이 거부된 주문 | UNKNOWN, PAYMENT_PENDING·RESERVED 그대로. PG 거래 수 1, POST 수 1, 재고 94 |

**PG가 느려짐** (모든 응답 1.5초 지연)

| 확인한 것 | assert한 값 |
| --- | --- |
| confirm | UNKNOWN, `checked: false`, 2.5초 미만 |
| PG의 늦은 처리 | 5초 안에 PG 거래 1건 생성 |
| 첫 reconcile | `checked: false`, 2.5초 미만, 회로가 센 실패 2 |
| 둘째 reconcile 뒤 | 회로 OPEN |
| 셋째 reconcile | 200ms 미만, 주문 PAYMENT_PENDING·RESERVED |
| 지연을 걷고 거래를 SUCCESS로 바꾼 뒤 10.5초 대기 + 배치 | `Result(1, 0, 0)`, CONFIRMED·CONSUMED, 재고 97, POST 1 |

**DB가 멈춤** (PG가 조회에 SUCCESS로 답하기 직전 MySQL 컨테이너 일시정지)

| 확인한 것 | assert한 값 |
| --- | --- |
| reconcile | 예외로 끝남, 15초 미만 |
| PG 회로 | CLOSED, 회로가 센 실패 0 |
| DB가 멈춘 동안의 callback(실제 controller) | 5xx. 시도를 찾는 DB 읽기에서 먼저 실패하므로 PG 조회까지 가지 않는다 |
| DB를 다시 켠 뒤 | 주문 PAYMENT_PENDING·RESERVED 그대로 |
| 배치 | `Result(1, 0, 0)`, CONFIRMED·CONSUMED, 재고 97, POST 1 |

**시드 고정 장애 + 동시 실행** (시드 `20251021`)

- 주문 20개. 생성 장애는 정상 10·응답 유실 2·생성 503 8개, 거래가 있는 12개의 PG 결과는 SUCCESS 3·FAILED 4·PENDING 5로 정해졌다(테스트가 세 장애와 세 결과가 모두 있는지 assert하고 구성을 출력한다).
- 조회 503 응답 4개를 예약한 뒤 callback·reconcile·배치·같은 키 confirm 40개를 스레드 8개에서 latch로 함께 시작했다. API가 아니라 `OrderFacade`를 직접 부른다. 예약한 503이 몇 개 쓰였는지, 실제로 몇 개가 겹쳐 실행됐는지는 기록하지 않았다.
- assert한 값: 40개 호출의 예외 0. 동시 실행 직후와 복구 뒤 모두 아래 불변식 유지. 장애를 걷고 회로가 닫혀 있지 않으면 10.5초 대기한 뒤 배치 2번 → SUCCESS 주문은 CONFIRMED·CONSUMED, FAILED는 PAYMENT_FAILED·RELEASED, PENDING과 거래 없는 주문은 PAYMENT_PENDING·RESERVED.

불변식: PG가 받은 POST 수 20(주문당 1), PG 거래 수 12(503으로 막히지 않은 주문 수), CONFIRMED ⇒ 결제 SUCCESS·CONSUMED, PAYMENT_FAILED ⇒ 결제 FAILED·RELEASED, 결제 SUCCESS·FAILED는 PG에 정한 결과와 같음, 가용 재고 = 100 − RELEASED가 아닌 주문의 수량 합.

DB 장면을 위해 이 테스트에서만 JDBC `socketTimeout=3000&connectTimeout=2000`을 붙였다. 테스트가 끝날 때마다 컨테이너가 멈춰 있으면 다시 켠다.

**미뤄 둔 검증 보완** (`OrderPaymentIntegrationTest` 3건 추가)

- 과거 POINT 주문: 본인 단건 조회에서 CONFIRMED·POINT·총액 2,000·결제액 2,000·예약 상태 null·결제 시도 없음·품목(상품, 1,000원, 2개). 본인 목록과 관리자 조회도 POINT. 다른 사용자의 단건 조회는 예외, 목록은 비어 있음. reconcile은 예외. PG POST·GET 0, 포인트 10,000 그대로.
- 같은 키 confirm 8개를 latch로 함께 시작: 8개 모두 응답, `checked: true`는 1개, POST 1, PG 거래 1, 결제 시도 1, PAYMENT_PENDING·RESERVED, 재고 2.
- 관리자 재고 변경(가용 10)과 FAILED reconcile을 함께 시작, 5회 반복: 매번 최종 재고가 10 또는 13, 주문 PAYMENT_FAILED·RELEASED. 각 회차에 어느 쪽이 먼저였는지는 기록하지 않았다.

전체 `check` 68 suites·470건 통과.

<a id="assignment-wrap-up"></a>

## 15. 과제 마무리 점검

- 과제의 PG HTTP 계약, 생성 응답 유실, 예약·반환, 동시 confirm, callback 권위 조회, B·C 오류 복구, 서비스·클라이언트 재생성, 과거 POINT 주문 보존을 현재 코드와 관련 테스트에 대조했다. 전체 검사는 위 「최신 전체 검사」의 결과다.
- 테스트는 전용 Testcontainers MySQL·Redis와 로컬 가짜 PG를 사용했다. 이 점검 시점에는 새로운 부하·연결 풀 비교 실험을 실행하지 않았다(이후 [17절](#assumption-experiments)에서 실행).
- **당시 과제 문구와의 해석 차이**: 같은 키·같은 주문에서 카드만 바뀐 재요청도 저장 결과를 반환했다. 과제의 다른 요청 충돌 조건을 카드 입력에 적용하지 않던 차이는 이후 [16절](#request-matching)에서 보완했다.
- 설계의 복구 설명을 바로잡았다. 결과 미확정인 같은 키 confirm은 PG를 재조회하지 않는다. 저장 결과를 반환하고, 결제만 종결된 경우에는 C를 실행한다.
- 로컬 기술 글 `velog.md`는 retry·fallback·회로의 판단 과정과 이미 있는 장애 검증까지 정리했다. 시간 측정·테스트의 상한 검사·미실험 가설을 구분했다.
- 구현·검증·문서 준비와 PR 생성·블로그 게시를 구분한다. 이번 점검에서 커밋·PR 생성·블로그 게시는 하지 않았다. 설정값 선정·연결 풀 경쟁·생성과 조회의 자원 분리는 후속 학습 범위다. 이 중 설정값별 비교와 연결 풀 경쟁은 17절에서 실험했고, 설정값 선정과 자원 분리 구현은 하지 않았다.

<a id="request-matching"></a>

## 16. 같은 키의 요청 일치 보완

- 같은 키·같은 주문이면 카드가 달라도 저장 결과를 반환하던 동작을, 같은 키의 다른 요청은 409라는 과제 계약에 맞췄다. 정상 재전송은 계속 기존 결과를 반환하며 PG 생성·조회·재고 처리를 반복하지 않는다.
- `PaymentAttempt.requestFingerprint`에 시도 키·카드 종류·번호의 SHA-256 비교값을 저장한다. 앞 두 필드에는 길이를 붙여 경계를 구별한다. `matchesRequest()`가 저장 금액과 해시를 대조한다. 카드 원문은 저장하지 않고, 해시도 응답·로그에 내보내지 않는다. 입력은 합성 카드만 사용한다.
- TX A에서 주문을 잠근 뒤 요청을 대조한다. REQUESTED·PENDING·UNKNOWN·SUCCESS·FAILED 모두 같은 규칙이다. 불일치 요청은 PG 호출뿐 아니라 저장된 종결 결과의 TX C 복구도 실행하지 않는다. 기존 결제·주문·예약·재고·관찰 이력은 유지한다.
- 새 기대값으로 바꾼 통합 테스트 10건이 기존 구현에서 실패하는 것을 먼저 확인했다. 최소 구현 뒤 관련 8 suites·91건(실패·오류·skip 0)이 통과했다. 같은 내용의 재전송, 카드 종류·번호 변경, 금액·필드 경계의 비교, DB 재조회 후 비교값 유지, B만 저장된 상태에서 잘못된 요청의 C 실행 차단, HTTP 409 응답, 재생성 복구·배치를 포함한다.
- 전체 `check`도 재실행해 69 suites·488건, 실패·오류·skip 0을 확인했다. Checkstyle main·test 오류 0과 ArchUnit을 포함하며 `BUILD SUCCESSFUL`(4분 54초)로 끝났다. 기존 검사 규칙과 기대값은 완화하지 않았다.
- **후속 재결제 참고**: 잔액 부족 등으로 앞 카드의 결제가 확정 실패한 뒤 다른 카드로 다시 결제하려는 상황은 있을 수 있다. 이번 과제는 실패 후 재결제를 구현하지 않는다. 추가할 때는 확정 실패 확인, 새 시도·새 키, 주문당 한 시도 제약과 재고 예약 정책을 함께 검토한다. 카드 변경은 기존 결제의 실패 증거가 아니며, PENDING·UNKNOWN에 새 POST를 보내는 근거가 아니다.
- 설계와 ADR 0030·흐름 문서에 현재 정책을 맞췄다. 새 필수 칼럼은 Testcontainers의 새 스키마로 검증하며, 기존 PG 데이터의 비교값 소급 생성·운영 스키마 전환은 수행하지 않았다.

<a id="assumption-experiments"></a>

## 17. 조회 정책의 가정 실험

정한 정책이 동작하는지가 아니라, 정책을 세울 때의 가정이 깨지는 장면을 실험했다. 과정과 해석은 [실험 문서의 7회차 이어서](resilience-experiment-plan.md#wrap-order-experiments)에 둔다.

| 변경 | 내용 |
| --- | --- |
| `LookupResilienceSettings` | 조회 retry·회로 숫자를 코드 상수에서 `application.yml`의 `lookup-retry.*`·`lookup-circuit.*`로 옮김. 값은 그대로 |
| `PaymentProviderContractTest` | 설정 바인딩 테스트에 새 키 8개를 추가. 기대값은 그대로 |
| `PaymentLookupWrapOrderExperimentTest` | 고객마다 첫 GET만 503인 장애에서 감싸는 순서만 비교. retry가 바깥이면 확인 2·막힘 2·GET 4·OPEN, 회로가 바깥이면 확인 4·막힘 0·GET 8·CLOSED. 회로가 바깥인 쪽은 테스트 안에서만 감쌈. 처음에는 회로가 바깥인 쪽에 남은 예산 확인이 빠져 조건이 달랐고, 맞춘 뒤 다시 돌려 같은 결과를 확인. 첫 거래만 retry 끝까지 503이고 뒤 정상 3건인 장애도 두 순서로 실행: retry가 바깥이면 실패 3·성공 1, 정상 1건 확인·2건 막힘·GET 4·OPEN, 회로가 바깥이면 실패 1·성공 3, 정상 3건 확인·막힘 0·GET 6·CLOSED. 처음에는 회로가 바깥인 쪽을 창 계산으로만 적었다가 실행해 같은 결과를 확인 |
| `PaymentLookupWindowSizeExperimentTest` | 창 4·20(최소 표본 같은 값). 거래 하나만 실패 + 정상 30건: 창 4는 막힘 29·OPEN, 창 20은 막힘 0·CLOSED. 조회 전체 503: 창 4는 2번째 고객·GET 4에서 OPEN, 창 20은 7번째 고객·GET 20에서 OPEN. 두 장애에 창 20·최소 표본 4도 실행: 거래 하나만 실패하면 첫 정상 조회에서 실패율 75%로 OPEN·막힘 29, 전체 503이면 2번째 고객·GET 4에서 실패율 100%로 OPEN. 둘 다 창 4·최소 4와 같았음 |
| `PaymentLookupCircuitSettingsExperimentTest` | 실패율 50·80%, 창 최근 4개·최근 10초를 네 장면에서 비교. 실패율 80%는 거래 하나 실패·새벽 장면에서 막힘 0이지만 절반씩 실패하는 PG를 못 알아챔. 시간 창은 새벽 장면만 막힘 0. 셋 다 테스트 안의 같은 조회 장치 |
| `PaymentLookupTimeoutExperimentTest` | 조회 응답 대기 300·500·800ms × PG 400ms·2초, 연결 빌리기 대기 300·500ms. 300ms는 조금 느린 정상 PG를 막고, 800ms는 고객당 retry가 줄지만 4번째 고객에서 OPEN. 연결 대기 500ms는 확인 5 → 10, 그래도 OPEN |
| `PaymentLookupRetryCountExperimentTest` | retry 최대 1·3회. 가끔 끊김: 확인 3 대 4. 계속 장애: GET 4로 같고 OPEN은 4번째 대 2번째 고객. 거래 하나만 실패: 막힘 0 대 2 |
| `PaymentLookupConnectionPoolExperimentTest` | PG 정상(400ms), 연결 5개. 동시 조회 5·10·20에서 확인 5·5·5, 회로 CLOSED·OPEN·OPEN. 회로가 센 실패는 모두 PG에 닿지 못한 시도 |
| `PaymentLookupPoolFailureCountingExperimentTest` | 테스트 안의 조회 장치로 연결 빌리기 실패를 세는 정책과 세지 않는 정책 비교. PG 정상: 확인 5·OPEN 대 확인 10·CLOSED. PG 느림(2초): 둘 다 OPEN, 시점 약 0.31초 대 0.50초 |
| `PaymentCreateConnectionPoolExperimentTest` | MySQL·가짜 PG. 조회 20개가 연결을 잡은 순간 confirm. 11번 중 8번은 POST가 PG에 닿지 못해 UNKNOWN·예약 유지, reconcile 0건으로 끝나지 않음. 3번은 연결이 풀린 뒤 POST가 나가 PENDING. 결과를 고정하지 않고 각 결과의 사실만 확인 |
| `PaymentLookupBulkheadExperimentTest` | 조회 동시 3개 제한(테스트 안의 세마포어). 조회 확인 3, 17개 즉시 확인 불가, 회로 CLOSED, 생성 POST는 남은 연결로 나가 PENDING |
| `PaymentLookupPoolSeparationExperimentTest` | 같은 총 연결 한도 5개로 공유 풀·조회 실행 3개 제한·조회 3개/생성 2개 풀 분리를 비교. 조회 확인 5·3·3, 조회 회로 OPEN·CLOSED·OPEN, PG의 생성 POST 수신 0·1·1. 세 조건을 세 번씩 실행해 같은 결과 확인 |

순서·창 크기·retry 횟수·실패율 기준·창 방식 비교는 테스트를 돌리기 전에 예측을 적었고, 결과가 예측과 같았다. OPEN 대기가 지나지 않도록 테스트 시계를 멈춘 채 실행했다. 순서·창 크기·retry 실험은 요청을 하나씩 차례로 보낸 장면이며, 제품 코드는 설정 이동 외에 바꾸지 않았다. 연결 풀 실험들은 조회를 동시에 보냈고, 시간에 민감해 세 번 이상 돌려 같은 결과인지 확인했다.

**실험 결과를 읽을 때의 한계**: 창 크기 실험은 처음에 최소 표본을 함께 바꿨고, 이후 두 장애 모두 창 20·최소 표본 4를 추가해 구분했다. 시간 창은 짧은 시간에 이어진 요청이 10초 창에 모두 들어가 창에 들어가는 표본 수도 달라졌다. 손잡이별 결과는 서로 다른 크기의 장면(정상 거래 3건, 30건)에서 쟀으므로 숫자끼리 비교하지 않는다. 시간 창에 불리한 장면(요청이 드묾, 실패가 띄엄띄엄)은 보지 않았다. timeout과 retry 예산의 상호작용은 `PaymentLookupTimeoutExperimentTest`에서 확인했다. 연결 부족을 실패로 세지 않는 후보는 `recordResult`가 false인 결과를 성공 표본에 넣는 방식이며, 표본에서 완전히 제외한 비교는 아니다.

**창 20·최소 표본 4의 계속 장애 추가 검증**: 창과 최소 표본을 각각 받도록 기존 전체 503 실험을 확장했다. 실행 전 예상은 두 번째 고객의 첫 GET에서 누적 4건 모두 실패해 OPEN이 되는 것이었다. 실제로 고객 1은 GET 3번 실패 뒤 CLOSED, 고객 2는 GET 1번 실패 뒤 OPEN이 되었고, 누적 실패 표본 4·실패율 100%·약 423ms를 기록했다. OPEN 뒤 다음 조회는 PG GET 없이 차단됐다. 이번 로컬 실행의 시간이며 운영 환경의 차단 시간을 뜻하지 않는다.

검증 명령: `./gradlew :apps:commerce-api:experimentTest --tests '*PaymentLookupWindowSizeExperimentTest' :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*LayeredArchitectureTest' --offline` (Java 21). 창 비교 6건과 ArchUnit, Checkstyle test가 통과했고 `BUILD SUCCESSFUL`을 확인했다. 전체 `check`와 나머지 실험은 재실행하지 않았다. 제품 정책·설정과 설계·ADR의 결정은 바꾸지 않았다.

**풀 분리 비교 추가 검증**: 연결 수를 바꿔도 실제 adapter의 응답 처리·재시도 금지 규칙을 그대로 검증하도록, `TestPgPaymentAdapter`에 패키지 내부에서 `HttpClientBuilder`를 받는 생성자를 추가했다. 기본 생성자는 기존과 같은 builder를 넘긴다. 앱의 빈 구성·공유 풀 정책·연결 한도는 바꾸지 않았고, 실험에서만 조회용 adapter(3개)와 생성용 adapter(2개)를 별도 풀로 만들었다. 비교 범위는 HTTP 경계이며 주문·재고 처리는 이번 실험에 포함하지 않는다.

- 첫 실행에서는 모든 조회가 끝난 뒤 현재 창의 실패 수를 검사해 공유 풀·풀 분리 2건이 실패했다. 이미 진행 중이던 정상 조회가 뒤늦게 끝나 창의 실패 표본을 밀어냈기 때문이다. 회로가 열리는 순간의 상태 전환 이벤트에서 실패 수를 기록하도록 측정 시점을 고쳤고, OPEN·실패 표본 4개 이상이라는 검증은 유지했다.
- 수정 뒤 세 조건 3건을 세 번 실행해 총 9건 통과. 공유 풀은 조회 5건 확인·15건 확인 불가·POST 미발송, 실행 제한은 조회 3건 확인·17건 즉시 거절·POST PENDING, 풀 분리는 조회 3건 확인·17건 대기 후 확인 불가·POST PENDING이었다. OPEN 뒤 다음 조회를 허용하는 정책으로 바꾸지 않았다.
- 관련 회귀 검사: `PaymentProviderContractTest` 31건, `PaymentLookupRetryTest` 9건, `PaymentLookupCircuitBreakerTest` 6건, `LayeredArchitectureTest` 1건, 합계 47건 통과. Checkstyle main·test도 통과했다. 전체 `check`와 나머지 실험은 재실행하지 않았다.
- 실행: `./gradlew :apps:commerce-api:experimentTest --tests '*PaymentLookupPoolSeparationExperimentTest' :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*PaymentProviderContractTest' --tests '*PaymentLookupRetryTest' --tests '*PaymentLookupCircuitBreakerTest' --tests '*LayeredArchitectureTest' --offline` (Java 21). 두 차례 반복은 `experimentTest --tests '*PaymentLookupPoolSeparationExperimentTest' --rerun-tasks --offline`로 실행했다. 각 명령의 종료 결과는 `BUILD SUCCESSFUL`이었다.
- 커밋 전 창 비교와 풀 분리 비교를 함께 지정해 실험 9건을 다시 실행했고 모두 통과했다. 같은 명령에 포함한 관련 회귀 테스트 47건과 Checkstyle main·test는 입력 변경이 없어 `UP-TO-DATE`로 확인됐다. 전체 `check`와 나머지 실험은 재실행하지 않았다.

**실험 테스트를 평소 검사에서 분리**: 위 실험 테스트에 `@Tag("experiment")`를 달고, `apps/commerce-api`의 `test`(따라서 `check`)에서 제외했다. 실험은 `./gradlew :apps:commerce-api:experimentTest`로 따로 돌린다.
- 이유: 기능 검증이 아니라 설정·장애 조건을 바꿔 결과를 비교하는 실험이다. 연결 풀 실험은 연결 대기 300ms와 PG 응답 400ms처럼 100ms 차이에 결과가 걸려, 느린 환경에서 기능과 무관하게 실패할 수 있다.
- 영향: 평소 `check`는 기능 검증만 돌린다. 기존 기능 테스트의 기대값·Checkstyle·ArchUnit은 바꾸지 않았다. 실험 결과를 문서와 다시 대조하려면 `experimentTest`를 직접 실행해야 한다.
- 함께 고친 것: MySQL을 쓰는 실험 두 개(`PaymentCreateConnectionPoolExperimentTest`, `PaymentLookupBulkheadExperimentTest`)는 가짜 PG 주소가 달라 Spring 컨텍스트를 새로 띄운다. 닫지 않고 쌓이자 DB 연결이 모자라 다른 테스트(`PaymentCallbackE2ETest`)가 컨텍스트를 띄우지 못하고 실패했다. 두 테스트에 `@DirtiesContext(AFTER_CLASS)`를 달아 끝나면 닫게 했고, 이후 전체 검사가 통과했다.

## 남은 검증

- JVM 종료·재기동 자체. 지금은 같은 DB·PG를 유지한 서비스·클라이언트 재생성까지 확인했다.
- 오래 모르는 시도의 조회 횟수 제한·알림, 여러 서버에서 배치를 켰을 때의 동시 처리.
- 확실한 미발송 처리는 18절에 반영했다. 시간 경과·조회 0건으로 닫는 만료 정책은 철회했다. 장기 미확인 예약의 점유 비용과 안전한 취소·수동 처리 계약은 남은 과제다.
- 트랜잭션 commit 자체 오류. 지금은 flush 뒤 테스트 예외를 주입했다.
- 조회 설정값 후보와 연결 풀 대기 장면은 17절에서 비교했다. 실제 트래픽·장애 기록에 따른 값 선정, 연결 수립 timeout의 실제 재현, 연결 부족을 회로의 표본에서 완전히 제외하는 비교는 남아 있다.

<a id="close-unsent-and-expired"></a>

## 18. 확실한 미발송은 닫고, 오래 결과를 모르는 결제는 유지하기

실험에서 PG에 닿지 않은 결제가 UNKNOWN으로 남아 재고를 묶는 것을 확인해, 확실한 미발송을 따로 분류했다([ADR 0034](../adr/0034-close-only-proven-unsent-payments-and-keep-unresolved-pending.md), [설계](design.md)). 처음에는 "30분 경과 + PG 조회 0건"도 실패 근거로 삼았지만, 조회 0건은 이후 거래가 생기지 않는다는 보장이 아니므로 이 자동 만료 정책은 철회했다.

| 위치 | 변경 |
| --- | --- |
| `PaymentObservation.notSent()` | 생성 요청이 연결 단계에서 끝나 PG에 닿지 않은 결과 |
| `TestPgPaymentAdapter.create` | 원인이 `ConnectionRequestTimeoutException`·`ConnectException`·`ConnectTimeoutException`이면 `notSent`. 응답 timeout·5xx·재사용 연결로 쓰다 끊김은 그대로 `UNKNOWN`. 조회는 바꾸지 않음 |
| `PaymentAttempt.markNotSent` | REQUESTED만 거래 키 없이 FAILED. 이미 진행이 알려졌다면 기존 상태·키를 유지하고 CONFLICT |
| `OrderPaymentTransactions` | TX B에서 `NOT_SENT` 기록. 기존 `expireWithoutTransaction` 전이는 제거 |
| `PaymentRecoveryBatch`·`OrderFacade.recover` | 10분 지난 모든 미완료 시도에 같은 복구 규칙. 조회 0건·PENDING·조회 실패에는 예약 유지. 기존 `OrderFacade.expire`와 30분 분기를 제거 |
| `PaymentEvent.Reason.EXPIRED_WITHOUT_TRANSACTION`, `OrderFacade.handleCallback`의 닫힌 결제 재조회 분기 | 만료와 함께 제거. 거래 없이 닫히는 것은 접수 전 거절·확실한 미발송뿐이라 다시 조회할 이유가 없음. 이 분기를 지나는 테스트도 남아 있지 않았음 |

**최초 구현 이력**: 테스트를 먼저 작성했지만 새 메서드가 없어 컴파일되지 않는 상태였고, 실행해 실패를 확인하지는 않았다.
- `PaymentAttemptTest`: 확실한 미발송의 대상·반복·충돌. 처음 추가했던 만료 전이 검증은 정책 철회로 제거하고, UNKNOWN·PENDING·SUCCESS·거래가 있는 FAILED에 늦게 NOT_SENT가 와도 상태·키를 덮어쓰지 않는 검증으로 보완했다.
- `PaymentProviderContractTest` 3건: 새 연결 거부·연결을 빌리지 못함은 `notSent`, 응답 timeout은 `UNKNOWN`.
- `OrderPaymentIntegrationTest` 1건: 조회 5개가 연결을 모두 잡은 동안의 confirm은 바로 FAILED·RELEASED·재고 반환, PG 거래 생성 0, 사유 `NOT_SENT`.
- `PaymentRecoveryBatchTest`: 최초에는 31분 된 UNKNOWN·REQUESTED의 조회 0건을 실패 처리하고, 닫은 뒤 SUCCESS는 충돌만 기록하는 기대값이었다. 정책 철회 뒤에는 조회 0건의 예약 유지와 이후 SUCCESS의 정상 확정으로 바꿨다. 기존 20분·PENDING·조회 실패·거래 발견 검증도 유지했다.

**구현 중 발견**: 처음에는 PG를 내린 장면(`PG.down()`)으로 "새 연결 거부 → 바로 실패"를 확인하려 했는데 UNKNOWN이 나왔다. PG가 꺼진 직후 첫 요청은 앞 요청이 열어 둔 **재사용 연결**로 나가 "쓰다 끊김"이 됐고, 그다음 요청부터 새 연결이 거부돼 `notSent`가 됐다. 재사용 연결로 요청을 쓴 경우는 PG에 닿지 않았다고 증명할 수 없어 UNKNOWN이 맞다. 그래서 정책은 그대로 두고 테스트를 두 경우로 나눴다. 카오스 테스트에서 "연결이 거부된 결제가 끝나지 않는다"고 적었던 장면도 실제로는 이 재사용 연결 때문이었다.

**정책 때문에 바뀐 기존 테스트**
- `PaymentChaosTest`의 PG가 죽은 장면: 재사용 연결로 나간 결제는 UNKNOWN, 다음 결제는 새 연결이 거부돼 바로 FAILED. PG 복구 뒤에는 확인한 성공만 확정하고, 31분 된 UNKNOWN의 조회 0건은 예약을 유지하는 기대값으로 바꿨다.
- `PaymentCreateConnectionPoolExperimentTest`(실험): 연결을 빌리지 못한 결제의 기대값을 "UNKNOWN·예약 유지"에서 "FAILED·재고 반환"으로 바꿨다.
- 가짜 PG 없이 연결 거부에 기대던 통합 테스트(재고 동시성·잠금·롤백 등 7개)는 이제 confirm이 바로 실패로 닫혀 재고 예약을 볼 수 없게 됐다. 기대값을 바꾸지 않고, 주소를 지정하지 않은 테스트도 응답하는 기본 가짜 PG를 쓰도록 `DefaultPgTestServerConfig`를 추가했다. MySQL 컨테이너 설정과 같이 테스트 설정 클래스의 `static` 블록에서 주소를 지정하고, 테스트가 `@DynamicPropertySource`로 지정한 주소가 우선한다.

**테스트 MySQL 연결 수**: 전체 검사에서 `PaymentCallbackE2ETest`가 Spring 컨텍스트를 띄우다 MySQL `Too many connections`로 실패했다. 가짜 PG 주소나 Mock 설정이 달라 컨텍스트를 따로 띄우는 DB 테스트가 15개쯤이고, Spring은 재사용하려고 컨텍스트를 닫지 않으므로 컨텍스트마다 연결 풀(test 프로필 최대 10개)이 남는다. MySQL 기본 최대 연결 151개에 거의 닿아 테스트 순서에 따라 넘쳤다. 테스트용 MySQL 컨테이너에 `--max-connections=500`을 주었다(`MySqlTestContainersConfig`). 테스트 기대값과 앱 설정은 바꾸지 않았고, 이후 전체 검사 69 suites·503건이 통과했다.

**자동 만료 철회 검증**: 수정 전에 조회 0건인 31분 된 UNKNOWN·REQUESTED의 예약 유지와 이후 늦은 SUCCESS 확정을 기대하는 테스트 3개를 실행해 모두 기존 실패 처리 때문에 실패함을 확인했다. 이후 자동 만료 경로를 제거하고, 과제의 미완료 유지 계약에 맞춰 정책과 기대값을 함께 수정했다.

- UNKNOWN은 배치를 반복해도 UNKNOWN·PAYMENT_PENDING·RESERVED·재고 97 유지, `NO_TRANSACTION_YET`·IGNORED 이력, POST 추가 0.
- REQUESTED도 31분 뒤 조회 0건이면 상태·예약 유지. providerOrderId로 조회하고 POST는 보내지 않음.
- 조회 0건 뒤 SUCCESS 거래가 나타나면 callback으로 CONFIRMED·CONSUMED. 이후 callback·reconcile·배치를 반복해도 추가 차감·조회·POST 없음. 늦은 거래는 테스트에서 PG 생성 호출로 주입한 것이며 앱이 재전송한 것이 아니다.
- 30분은 향후 장기 미확인 검토·알림의 기준으로만 고려한다. 이번 변경에는 새 상태·알림·운영 화면·취소 API를 추가하지 않았다. 기존 만료 이력과 이미 실패한 주문도 자동으로 되돌리지 않는다.

**수정 뒤 실행 결과**: 아래 명령을 Java 21·`DOCKER_HOST=tcp://localhost:2375`에서 실행해 `BUILD SUCCESSFUL`(2분 3초)을 확인했다. 7 suites·132건 모두 통과했고 실패·오류·skip은 0, Checkstyle main·test 오류도 0이다. 전체 `check`와 `experimentTest`는 이번에 재실행하지 않았다. 앞의 503건·실험 41건은 이전 구현의 실행 이력이다.

```bash
./gradlew :apps:commerce-api:test \
  --tests '*PaymentRecoveryBatchTest' \
  --tests '*PaymentAttemptTest' \
  --tests '*OrderPaymentIntegrationTest' \
  --tests '*PaymentResultApplicationTest' \
  --tests '*PaymentChaosTest' \
  --tests '*PaymentProviderContractTest' \
  --tests '*LayeredArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --offline
```
