# 결제는 승인됐는데 주문이 대기 중일 때

고객에게 결제를 다시 요청하기 전에 쇼핑몰이 보관한 주문과 PG의 기존 거래를 대조한다. 아래 절차와 수치는 로컬 모의 PG와 합성 주문으로 검증했다.

## 1. 미확인 주문과 장애 당시 기록을 보존한다

1. 새 실험 주문을 멈춘다. 관리 포트의 `GET /actuator/health`와 `GET /actuator/prometheus`에서 시각, health, `commerce_payment_unknown` 값을 보존한다. 이 gauge는 수집 요청이 들어올 때 DB에서 결제 상태가 `UNKNOWN`인 건을 센다. 화면에는 다음 수집 때 반영된다.
2. 영향받은 주문번호를 고정한다. 주문 소유자 권한으로 `GET /api/v1/orders/{orderId}`를 읽고 결제시도키, PG용 주문번호, 금액, 주문·결제 상태를 기록한다. 각 품목의 `productId`와 주문번호로 아래 예약 SQL을 실행해 예약 수량과 상태를 확인한다. 카드번호와 인증 헤더는 기록하지 않는다.
3. 같은 가맹 계정에서 기존 PG 거래를 읽기 전용으로 조회한다. 이 실습의 PG 조회는 `GET /api/v1/payments?orderId={providerOrderId}`다. 일치 건수·금액·상태·거래 키와 누적 생성 요청 수를 보존한다. 쇼핑몰의 대사 API는 상태를 변경하므로 이 기록을 남긴 뒤 호출한다.

로컬 실습 DB에서 대상을 찾는 읽기 전용 질의다. 출력한 시도키와 PG용 주문번호를 사고 로그에 연결한다.

```sql
SELECT order_id, payment_attempt_key, provider_order_id, amount, status, updated_at
FROM payments
WHERE status = 'UNKNOWN'
ORDER BY updated_at, order_id;
```

`updated_at`은 결제 행의 마지막 갱신 시각이다. 이번 증거의 `observedAt`은 각 단계에서 주문 집합을 관찰한 시각이다.

예약 조회는 읽기 전용이다. 첫 번째 물음표에 쇼핑몰의 `orderId`, 두 번째에 주문 상세의 `items[].productId`를 바인딩한다. 결과가 한 행인지, `quantity`가 해당 품목 수량과 같은지 확인한다. 미확인 결제에서는 `HELD`, 같은 주문 대사에 성공한 뒤에는 `CONSUMED`가 기대값이다. 행이 없거나 수량·상태가 다르면 대사를 멈추고 해당 주문을 조사한다.

```sql
SELECT quantity, status
FROM stock_reservations
WHERE order_id = ? AND product_id = ?;
```

| 가설 | 예상하는 증거 | 이번 사고에서 확인한 값 |
| --- | --- | --- |
| PG가 아직 처리 중이다 | 같은 거래의 PG 상태가 `PENDING` | 세 주문 모두 7,000원 `SUCCESS` 거래가 하나씩 있었다. |
| PG 승인 뒤 생성 응답을 잃었다 | PG에는 성공 거래가 있고, 쇼핑몰의 생성 요청은 응답을 읽다가 끝난다. | 세 주문 모두 `step=pg_create outcome=unconfirmed errorType=ConnectionClosedException`이었다. 쇼핑몰 결제는 `UNKNOWN`, 예약은 `HELD`였다. |
| 성공 결과를 받은 뒤 DB 반영에 실패했다 | 같은 처리에서 PG 성공 조회와 `step=payment_apply outcome=failed`를 확인할 수 있다. | 별도 저장 실패 테스트에서 `pgStatus=SUCCESS outcome=failed errorType=DataAccessResourceFailureException`을 관찰했다. 사고 세 건에서 직접 확인한 실패 단계는 생성 응답 수신이었다. |

PG 승인과 내부 `UNKNOWN`만 확인한 주문은 원인을 미확정으로 둔다. 주문별 경계 기록이 있어야 응답 유실과 저장 실패를 구분할 수 있다. [장애 당시 주문·예약](../week10/evidence/raw/fault.json) · [읽기 전용 PG 조회](../week10/evidence/raw/fault-pg-lookups.json) · [생성 응답 로그](../week10/evidence/raw/fault-create.log)

## 2. 확인된 주문 한 건씩 대사한다

- **진입 조건**. 실험의 응답 유실 주입을 끄고 같은 DB와 PG 거래를 유지한다. 조회한 가맹 계정·주문·금액이 일치하고 최종 성공 거래가 하나인 주문만 선택한다. 이미 저장된 거래 키가 있으면 그 값도 대조한다.
- **조치**. 주문 소유자 권한으로 쇼핑몰의 `POST /api/v1/orders/{orderId}/payment/reconcile`을 한 건 호출한다. 쇼핑몰 내부 주문번호를 경로에 넣으면 서버가 저장해 둔 PG용 주문번호로 기존 거래를 조회한다. 응답 뒤 주문 상세를 다시 읽어 `CONFIRMED`, 결제 `SUCCESS`, 같은 거래 키를 확인한다.
- **중단 조건**. PG 조회 실패·0건·복수 건·금액 또는 키 불일치·계속 처리 중이면 해당 주문을 보류한다. 저장 오류가 발생하거나 생성 요청 수가 증가해도 다음 주문 처리를 멈춘다. 당시 응답·시각·남은 주문을 보존하고 재결제와 DB 강제 변경을 피한다.
- **재진입**. 마지막으로 완료한 주문과 보류 목록을 인계한다. PG 조회나 DB 저장이 다시 가능한지 확인한 뒤 남은 주문의 현재 상태와 기존 거래를 한 번 더 읽는다. 이미 일치하는 주문은 건너뛰고 진입 조건을 충족한 주문만 대사한다. 조회 결과가 계속 불명확하면 담당자에게 인계한다.

테스트는 각 실행 폴더의 `reconcile-progress.jsonl`에 한 줄씩 추가한다. `plan`은 대상 주문과 조치 전 재고·포인트·쿠폰을 담는다. 요청을 보내기 전에 `started`, 응답과 불변 조건 확인 직후에 `verified`를 남긴다. `stopped`가 있으면 해당 `orderId`와 `unattemptedOrderIds`가 재진입 시 확인할 목록이다. 마지막 기록이 `started`면 요청 결과를 아직 확인할 수 없으므로 해당 주문부터 현재 상태와 기존 거래를 읽는다. 재진입 기록도 같은 파일 뒤에 추가한다.

쇼핑몰 대사 API가 HTTP 202와 `lookupAvailable=false`를 반환하면 테스트는 `reason=pg_lookup_unavailable`로 중단한다. 같은 주문의 `step=pg_lookup` 로그에서 세부 원인을 확인한다. 예를 들어 `reason=http_error pgHttpStatus=400`은 PG가 조회를 거절했음을 뜻한다. `timeout`은 시간 초과, `circuit_blocked`는 쇼핑몰의 회로 차단기가 조회를 막은 경우다. PG 응답 상태를 관측한 경우에만 `pgHttpStatus`를 기록한다.

### 같은 주문의 재요청과 PG 조회 재시도를 구분한다

`dev`, `qa`, `prd`의 JSON 콘솔에서 먼저 `orderId`로 주문을 찾는다. `step=payment_request`의 `decision`은 요청에 들어온 `paymentAttemptKey`를 기준으로 읽는다.

| decision | 의미 |
| --- | --- |
| `send_to_pg` | 첫 요청의 결제 준비가 커밋되어 PG 생성을 호출하기 직전이다. 실제 승인 여부는 PG 거래로 확인한다. |
| `reuse_existing` | 같은 주문과 같은 키로 다시 요청해 기존 결제를 반환했다. PG 생성 호출을 추가하지 않았다. |
| `attempt_key_conflict` | 같은 주문에 이미 저장한 키와 이번 요청의 키가 달라 HTTP 409로 거절했다. 로그에는 이번에 받은 키를 남긴다. |

대사와 콜백 처리에서는 DB에 저장된 시도키로 `step=pg_lookup_attempt`를 찾는다. 동일한 `traceId` 안에서 각 HTTP 요청은 서버 `spanId`로 구분한다. 조회 회차 `pgLookupAttempt`는 조회 작업마다 1부터 시작한다. 실패한 회차는 `outcome=failed`와 `reason`을, 회로 차단 등으로 전송 단계에 들어가지 못한 회차는 `outcome=not_sent`를 남긴다. `success`는 조회가 정상적으로 끝났다는 뜻이며 결제 승인 여부는 조회 결과로 판단한다.

저장소 루트에서 1번 주문으로 [실습 JSON 원문](../week10/evidence/raw/payment-requests.jsonl)을 검색하면 다른 시도키로 거절된 요청까지 여섯 줄이 나온다.

```sh
jq -c 'select(.orderId == "1") | {paymentAttemptKey, spanId, decision, pgLookupAttempt, outcome, pgHttpStatus} | with_entries(select(.value != null))' docs/week10/evidence/raw/payment-requests.jsonl
```

첫 행과 같은 `traceId`와 `paymentAttemptKey`로 좁히면 다섯 줄이 남는다. 다른 키를 쓴 세 번째 요청만 빠진다. 고객의 재요청에 새 trace가 부여됐다면 시도키로 두 요청을 찾는다.

## 3. 고객 주문과 후속 집계를 확인한다

1. 장애 때 고정한 주문 목록을 다시 읽는다. 확정 7건·미확인 3건이 같은 10건의 확정으로 바뀌고, 각 PG 거래 키와 누적 생성 요청 수가 그대로인지 대조한다.
2. 대사한 주문의 예약이 `HELD → CONSUMED`로 바뀌었는지 확인한다. 매 주문 직후 구매자의 보유 쿠폰 ID와 포인트 잔액, 상품의 가용 재고가 대사 직전 값과 같은지 비교한다.
3. 각 주문의 Outbox와 소비 기록을 같은 이벤트 ID로 찾는다. 구매 활동과 새 주간 스냅샷 입력에서 주문당 수량을 대조하고, 랭킹 API 점수가 복구 수량만큼 늘었는지 확인한다. 같은 이벤트를 다시 전송한 뒤에도 구매 기록과 점수가 유지돼야 한다.

주문이 확정됐는데 구매 기록이 보이지 않으면 API와 스트리머 로그에서 같은 `orderId`와 `eventId`를 찾는다.

| 로그 | 확인한 상태 | 이어서 볼 값 |
| --- | --- | --- |
| `step=order_outbox`, `outcome=published` | Kafka 발행이 확인됐고 Outbox에 `PUBLISHED`를 저장했다. | 같은 이벤트를 처리한 스트리머 로그와 구매 활동 행 |
| `step=order_outbox`, `outcome=mark_published_failed`, `brokerAck=true` | Kafka가 수신을 확인한 뒤 Outbox 상태를 저장하는 호출에서 예외가 발생했다. | 해당 이벤트의 DB 상태와 재발행 여부, 스트리머의 중복 처리 결과 |
| `step=purchase_activity`, `outcome=recorded` | 소비자가 구매 활동을 기록한 DB 트랜잭션을 커밋했다. | 해당 주문·상품의 수량과 스냅샷 입력 |
| `step=purchase_activity`, `outcome=duplicate` | 이벤트가 기존 주문의 구매 사실과 일치했다. | 구매 활동이 한 번인지, 같은 상품의 랭킹 점수가 유지됐는지 |
| `step=purchase_activity`, `outcome=failed` | 구매 활동 처리에서 예외가 발생했다. | 예외 종류와 Kafka 파티션·오프셋, 해당 위치의 소비 재개 여부 |

두 로그는 별도 스레드에서 콘솔에 쓴다. 큐가 가득 차면 처리 스레드가 빈자리를 기다린다. 서비스 간 출력 순서는 달라질 수 있다. 소비 로그는 Kafka 오프셋 승인 전에 남기므로, 재처리 여부는 소비 그룹의 커밋 위치와 DB 기록을 함께 확인한다.

이번 실행의 주문번호, 전후 값, 단계별 원문은 한 페이지에서 확인할 수 있다. [10주차 실행 증거](../week10/evidence/README.md)

## 4. 재현과 종료

```sh
./gradlew :apps:commerce-api:test --tests '*CheckoutIncidentTest' --rerun
./gradlew :apps:commerce-streamer:test --tests '*WeeklyRankingFlowTest' --rerun
```

각 실행은 모듈의 `build/reports/checkout-incident/` 아래에 새 증거를 만든다. 제출본은 단계별 health 응답과 Prometheus 업무 지표 발췌를 [관측 기록](../week10/evidence/raw/actuator.txt)에 모았다. 장애 JSON 원본은 `docs/week10/evidence/raw/`에서 `shasum -a 256 -c fault.sha256`로 확인한다. 테스트 실패 시 같은 실행의 fault 파일과 실패한 테스트 출력을 보존한 뒤 새 실행을 시작한다.

API의 대사 테스트는 실행 폴더에 `fault.sha256`을 남기고 복구 후 장애 원문의 바이트를 다시 비교한다. 스트리머의 `weekly-ranking-*/`에는 장애 시점의 `fault.json`, 대사 후 `recovery.json`, 재전송 후 `replay.json`과 `order-events.log`가 남는다. 각 JSON에서 장애 집합 열 건의 주문번호, 이벤트 ID, 구매 수량과 집계 입력 수량을 대조할 수 있다.

테스트가 만든 PG·consumer·토픽·테스트 컨테이너만 정리한다. 운영 PG의 지연 상한, 실제 고객 거래, 운영 트래픽에서의 복구 시간은 이 실험의 검증 범위 밖이다.

### 리뷰 반영 후 실습 체크리스트

11월 27일 로컬 모의 PG, MySQL, Redis, Kafka로 아래 **10개 항목을 모두 확인했다.** 코드 커밋 `eaadbbb`의 전체 회귀에서 결제 대사 기록은 `run-17279296254371966494`, 중단·재개 기록은 `run-2447769850849981300`에 남았다. 스트리머는 `weekly-ranking-10782123628350411809`에서 별도로 재현한 주문을 랭킹 API까지 추적했다. [저장소에 보존한 실습 원문과 각 실행의 출처](../week10/evidence/README.md)

| 체크리스트 항목 | 확인 결과 |
| --- | --- |
| Health·업무 지표·주문별 기록 | 세 단계 모두 health `UP`. 미확인 게이지는 0 → 3 → 0, 장애 주문마다 PG 생성 응답의 연결 종료를 확인했다. |
| 원인 가설 | 주문별 PG 성공 거래와 생성 응답 오류를 대조했다. 성공 조회 뒤 DB 저장 실패는 별도 롤백 테스트로 구분했다. |
| 민감정보·지표 레이블 | 합성 주문의 [구조화 로그 6줄](../week10/evidence/raw/payment-requests.jsonl)에서 trace와 시도키를 검색했다. 카드번호·인증 헤더는 기록되지 않았다. 세 단계의 Prometheus 원문에서 주문·시도·trace 식별자 레이블은 0개, 주문별 HTTP URI는 클라이언트·서버 각각 세 템플릿이었다. |
| 같은 DB·PG에서 정상·장애·복구 | 정상 주문 10건과 별도 장애 주문 10건을 생성했다. 장애와 복구에서 주문 목록과 PG 거래가 같았다. |
| 장애 집합의 복구 결과 | 내부 성공·미확인·PG 성공이 7·3·10에서 10·0·10으로 바뀌었다. |
| 추가 결제·예약·재고·포인트·쿠폰 | 추가 생성 0회. 장애와 복구 시점에 예약 수량 1과 `HELD → CONSUMED`를 확인했다. 세 주문을 한 건씩 대사할 때마다 가용 재고 3개, 50,000포인트와 보유 쿠폰 1장을 대조했다. |
| Outbox·Kafka·구매 활동·랭킹 | 복구 뒤 주문당 수량 1. 복구 이벤트 세 건을 다시 보낸 뒤에도 주간 점수는 20점이었다. |
| 장애 원문·중단·재진입 | 장애 원문의 SHA-256이 일치했다. 65번의 PG 조회가 HTTP 400으로 거절돼 대사를 중단했다. 62번의 확인 결과를 보존하고 65·68번만 재개했다. [중단·재개 원문](../week10/evidence/raw/interrupted-reconcile.jsonl) · [실패 로그](../week10/evidence/raw/boundary-failures.log) |
| 누적 회귀·정적 검사 | `eaadbbb`에서 `./gradlew --offline --max-workers=2 check --rerun-tasks`로 API 621건, 스트리머 46건, 배치 165건, Kafka 7건, 로깅 2건을 실행했다. 총 841건이 통과했고 실패·오류·건너뜀은 0건이었다. API·스트리머·JPA의 Checkstyle 위반은 0건이었고, 전체 테스트에 포함된 ArchUnit 3건도 통과했다. |
| 자원 정리·검증 범위 | 전체 회귀 전후에 실행 중인 Docker 컨테이너는 같은 8개였고 ID가 모두 일치했다. 처리량·운영 PG 장애·실제 고객 거래는 검증 범위 밖이다. |

### 기능별 회귀 범위

전체 회귀에서 실행한 대표 테스트를 기능별로 연결했다. 사고 행에서는 주문별 결과를 대조할 수 있도록 보존한 실습 원문을 연결했다.

| 기능 | 확인한 동작과 근거 | 미검증 범위 |
| --- | --- | --- |
| 상품·브랜드·좋아요·관리자 | [브랜드와 연결 상품 삭제 후 과거 주문 보존](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalIntegrationTest.java) · [삭제 상품의 관리자 조회](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/ProductApiE2ETest.java) · [좋아요 취소 시 타인 관계 보존](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/LikeApiE2ETest.java) · [Redis 중단 시 DB 조회](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/product/ProductCacheRedisFailureTest.java) | 캐시 장애 중 운영 트래픽에서 DB가 감당할 조회량 |
| 포인트·주문·PG | [기존 포인트 주문의 결제 기록 보존](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/OrderApiE2ETest.java) · [승인 응답 유실 후 예약 유지 및 기존 PG 거래 조회](../../apps/commerce-api/src/test/java/com/loopers/application/payment/OrderPaymentIntegrationTest.java) | 실제 PG에서 승인 거래가 조회되기까지 걸리는 시간 |
| 이벤트·Outbox | [Kafka 수신 후 Outbox 상태 저장 실패 시 재발행](../../apps/commerce-api/src/test/java/com/loopers/application/payment/OrderConfirmedOutboxDeliveryTest.java) · [중복 이벤트 소비 후 구매 수량 유지](../../apps/commerce-streamer/src/test/java/com/loopers/purchaseactivity/PurchaseActivityIntegrationTest.java) | 운영에서 소비가 장시간 밀린 뒤 따라잡는 시간 |
| 쿠폰 | [총 발급 수량 제한](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponQueueEndToEndIntegrationTest.java) · [동시 신청 수용 한도와 사용자 중복 방지](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/coupon/queue/RedisCouponQueueTest.java) · [본인 쿠폰 목록](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponListApiIntegrationTest.java) | 실제 캠페인 부하에서의 대기시간과 발급 처리량 |
| 주간 랭킹 | [같은 JobInstance로 체크포인트부터 재시작](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/RankingJobRestartTest.java) · [구매 활동을 반영한 새 revision과 공개 API 조회](../../apps/commerce-streamer/src/test/java/com/loopers/week9/WeeklyRankingFlowTest.java) | 운영 규모의 한 주 구매 활동을 재계산하는 시간 |
| 이번 사고 | [승인 응답 유실, 대사 중단과 재개](../week10/evidence/README.md) · [62번 완료 후 65·68번만 재개한 기록](../week10/evidence/raw/interrupted-reconcile.jsonl) | 실제 고객의 결제 복구 시간과 월간 SLO 달성 여부 |
