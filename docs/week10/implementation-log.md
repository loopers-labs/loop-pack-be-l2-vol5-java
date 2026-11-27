# 10주차 구현 기록 — 결제 사고 관측·진단·복구

현재 설계는 [design.md](design.md), 결정은 [ADR 0096](../adr/0096-log-pg-boundary-and-failed-result-save.md)부터 따른다.

## 1단계: 환경 확인

### 생성 응답 유실 시 남는 상태

`PaymentIncidentSingleOrderExperimentTest`(확인용 probe)에서 fixture의 `dropNextCreateResponse()`로 생성 응답을 끊었다.

- confirm은 약 0.3초 뒤 끝났다. 결제 시도 UNKNOWN·거래 키 없음, 주문 PAYMENT_PENDING, 예약 RESERVED, 재고 5 → 2 유지, PG 생성 호출 1·거래 1.
- fixture는 응답 없이 연결을 닫는다. read timeout(1초)을 기다리지 않고 연결 종료로 UNKNOWN이 된다.
- fixture가 저장한 거래 상태는 PENDING이다. 장애 주문을 PG SUCCESS로 두려면 상태 지정이 필요하다.
- 결과를 확인하지 못한 이유(`UnknownCause`)는 DB·로그 어디에도 남지 않았다.

### 자동으로 결과를 확인하는 경로

- FakePaymentServer는 callback을 보내지 않는다. 기존 테스트가 callback API를 직접 호출한다.
- `PaymentRecoveryScheduler`는 `payment.recovery.scheduling-enabled=true`일 때만 생기고, 생겨도 만든 지 10분 지난 시도만 본다.

### 로그와 traceId

- 결제 경로의 로그는 결과 충돌 `WARN`(`OrderPaymentTransactions`)과 복구 배치 실패 `ERROR`(`PaymentRecoveryBatch`)뿐이었다.
- `micrometer-tracing-bridge-brave`가 있어, 테스트 context에서도 HTTP 요청 안 MDC에 traceId가 있었다. 기본 콘솔 로그 형식에 correlation 칸이 있다.

### 기준선 회귀

이번 주 코드 변경 전, `62e0119`에 probe 테스트만 더한 상태에서 실행했다.

`./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --continue --rerun-tasks` → `BUILD SUCCESSFUL`, 2시간 25분.

| 모듈 | 테스트 | 실패·에러·skip | Checkstyle (main·test) | ArchUnit |
| --- | --- | --- | --- | --- |
| commerce-api | 686 (probe 2 포함) | 0 | 0·0 | `LayeredArchitectureTest` 통과 |
| commerce-streamer | 91 | 0 | 0·0 | `LayeredArchitectureTest` 통과 |
| commerce-batch | 35 | 0 | 0·0 | 해당 없음 |

streamer의 `KafkaReplicationFailureExperimentTest`(약 40분)와 `OrderConfirmedKafkaErrorHandlingIntegrationTest`(약 29분)가 대부분의 시간을 썼다. 두 테스트의 코드상 대기 한도는 단계마다 수십 초다. 테스트 컨테이너를 돌린 colima VM이 CPU 2개·메모리 2GiB였고, 자원 부족이 원인인지는 확인하지 않았다.

### 로컬 Prometheus·Grafana

infra-compose(MySQL·Redis·Kafka), `runFakePg`(18080), commerce-api local 실행, monitoring-compose를 띄웠다. Prometheus가 `host.docker.internal:8081`을 `up`으로 수집했고, Grafana `commerce-api` 대시보드(HTTP 요청 수·p99·수집 상태)가 Prometheus 값을 받아 왔다. 대시보드는 `docker/grafana/provisioning/dashboards/`에서 불러오고, 데이터 소스 uid는 `prometheus`로 고정했다.

## 2단계: 관측

### 2-1. PG 경계 로그 ([ADR 0096](../adr/0096-log-pg-boundary-and-failed-result-save.md))

- `TestPgPaymentAdapter`: 생성·조회마다 요청 한 줄과 결과 한 줄. 각 호출 결과를 예외와 함께 돌려받아, 결과를 확인하지 못했으면 가장 안쪽 예외 이름을 남긴다.
- `OrderFacade`: TX B(`recordCreateResponse`·`applyLookup`)와 TX C(`applyRecordedResult`) 호출 실패를 남기고 다시 던진다.

`PaymentBoundaryLogTest` 2건 통과.

| 케이스 | DB | 로그 |
| --- | --- | --- |
| 생성 응답 1.5초 지연 (read timeout 1초) | 결제 시도 UNKNOWN | `PG 응답 없음: op=create ... cause=TEMPORARY_FAILURE error=SocketTimeoutException elapsedMs=1129`, 카드번호 없음 |
| 생성 응답 끊김 → PG SUCCESS → reconcile TX B 이력 저장 예외 | 결제 시도 UNKNOWN·거래 키 없음 (생성 직후와 같음) | 생성 `error=NoHttpResponseException elapsedMs=2`, 조회 `transactions=[TEST-TX-1:SUCCESS:3000]`, `결제 결과 저장 실패: orderId=1 step=TX_B:RECONCILE error=IllegalStateException` |

지연 케이스에서 fixture는 지연 뒤에 거래를 만든다. 그 처리가 다음 테스트의 장애 설정을 가져가 첫 실행이 실패했고, 거래가 생길 때까지 기다리도록 테스트를 고쳤다.

관련 회귀: `./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*Payment*' --tests '*OrderV1ControllerTest' --tests '*LayeredArchitectureTest' --tests '*OrderConfirmation*'` → 25개 클래스 216건 통과(실패·에러·skip 0), Checkstyle main·test 위반 0, `LayeredArchitectureTest` 통과. `OrderPaymentIntegrationTest`·`PaymentCallbackE2ETest`·`PaymentRecoveryBatchTest`·`PaymentChaosTest`·`PaymentResultApplicationTest` 등 W5 결제 테스트가 포함됐다.

### A. PG 503 응답과 read timeout 구분

기존 조회 계약 테스트에 경계 로그 검증을 추가했다. 수정 전에는 503이 `PG 응답 없음`으로 찍혀 해당 검증이 실패했다. 수정 후 실제 503 응답에는 `PG 응답`과 `httpStatus=503`, read timeout에는 `PG 응답 없음`과 `error=SocketTimeoutException`이 기록됐다. 두 조회의 결제 판정은 모두 `UNKNOWN(TEMPORARY_FAILURE)`였다. [ADR 0097](../adr/0097-log-pg-response-http-status.md)

로그 차이를 검증한 메서드는 `PaymentProviderContractTest.lookupTimeoutAndServerErrorAreTemporaryFailures`다. 생성 503도 `serverErrorAndUnprovenRejectionAreUnknownWithoutRetries`에서 `PG 응답: op=create ... httpStatus=503 result=UNKNOWN cause=TEMPORARY_FAILURE error=ServiceUnavailable`로 확인했다. [조회 계약 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PaymentProviderContractTest.java)

이 변경을 검증한 실행에서 `PaymentProviderContractTest` 32건과 `LayeredArchitectureTest` 1건이 통과했다(실패·에러·skip 0). commerce-api의 Checkstyle main·test도 통과했다.

### A. 결제 생성 422의 두 판정

기존 422 계약 테스트 두 곳에 로그 검증을 추가하자 수정 전 코드에서 두 검증이 실패했다. 어댑터가 HTTP 오류 응답의 상태를 읽도록 수정한 뒤, 거래 미생성 근거가 일치한 응답에서는 INFO `httpStatus=422 result=REJECTED`, 본문이 빈 응답에서는 WARN `httpStatus=422 result=UNKNOWN cause=UNEXPECTED_RESPONSE`를 확인했다. 두 경우 모두 `PG 응답`으로 기록한다. [ADR 0097](../adr/0097-log-pg-response-http-status.md#2-결제-생성-422-응답)

수정 후 commerce-api의 `PaymentProviderContractTest` 32건과 `LayeredArchitectureTest` 1건이 통과했다(실패·에러·skip 0). Checkstyle main·test도 통과했다.

### A. 정상 생성 접수 202

기존 정상 생성 계약 테스트에 로그 검증을 추가했다. 수정 전에는 PENDING 거래가 기록됐지만 HTTP 상태 필드가 빠져 새 검증이 실패했다. 어댑터가 응답에서 읽은 상태 코드를 전달하도록 수정한 뒤 INFO 로그에서 `httpStatus=202 result=OBSERVED transactions=[TEST-TX-1:PENDING:7000]`을 확인했다. 같은 테스트에서 PG 거래 상태를 SUCCESS로 바꾼 후 거래 키로 조회해 최종 상태도 확인했다. [ADR 0097](../adr/0097-log-pg-response-http-status.md#3-정상-생성-응답-202)

이번 실행에서 commerce-api의 결제 계약 테스트 32건과 ArchUnit 1건이 모두 통과했다(실패·에러·skip 0). Checkstyle main·test도 통과했다.

### A. 정상 조회 200과 로그 필드 확인

거래 키 조회와 주문 번호 조회에 같은 줄의 HTTP 상태·거래 키·상태·금액을 검증하는 assertion을 추가했다. 수정 전에는 두 테스트 모두 HTTP 상태 필드가 빠져 실패했다. 어댑터가 실제 조회 응답 코드를 전달한 뒤, 생성 응답 유실 후 주문 번호로 조회한 테스트에서 다음 결과를 확인했다.

```text
PG 응답: op=lookup providerOrderId=LP-ORD-0000000042 httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:SUCCESS:7000] elapsedMs=2
```

이 줄로 조회에 사용한 주문 번호와 반환된 거래 상태를 확인할 수 있다. 응답 본문의 주문 번호까지 요청값과 비교하는 데 필요한 필드는 별도 과제로 남겼다. TX B 롤백 테스트에서는 같은 주문의 200·SUCCESS 조회 로그와 결과 저장 실패 로그가 모두 남고 결제 시도는 UNKNOWN으로 유지됐다. [ADR 0097](../adr/0097-log-pg-response-http-status.md#4-정상-조회-응답-200)

첫 통합 테스트 실행은 Docker 연결 단계에서 HTTP 400으로 중단됐다. 재실행 시 테스트 프로세스에 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`를 적용했고, PG 계약 32건·경계 로그 2건·ArchUnit 1건 모두 통과했다(실패·에러·skip 0). Checkstyle main·test도 통과했다.

생성 503 로그 검증을 추가한 뒤 `PaymentProviderContractTest` 32건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle test도 통과했다.

### A. PG가 돌려준 거래별 주문 번호

결과 로그의 거래 목록을 `거래 키:주문 번호:상태:금액`으로 바꾸는 기대값을 먼저 넣었다. 수정 전 코드에서 `PaymentProviderContractTest` 3건과 `PaymentBoundaryLogTest` 1건이 실패했다. 어댑터가 거래마다 PG 응답 본문의 주문 번호를 함께 쓰도록 수정한 뒤 모두 통과했다. [ADR 0098](../adr/0098-log-pg-returned-order-number-per-transaction.md)

- PG가 다른 주문의 거래를 돌려준 생성 응답: `providerOrderId=LP-ORD-0000000042 ... transactions=[T-1:OTHER-ORDER:PENDING:8000]`
- TX B rollback 테스트의 주문 번호 조회:

```text
PG 응답: op=lookup providerOrderId=LP-ORD-0000000001 httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:SUCCESS:3000] elapsedMs=25
```

이번 실행에서 `PaymentProviderContractTest` 32건, `PaymentBoundaryLogTest` 2건, `LayeredArchitectureTest` 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test도 통과했다.

### A. 결과 확인 보류

`PaymentBoundaryLogTest`에 두 경우를 추가했다. 수정 전 코드에서 두 테스트 모두 보류 줄이 없어 실패했고, 나머지 검증(회로 열림 시 PG 조회 호출 수 유지, timeout 시 어댑터 로그)은 통과했다. `OrderFacade.resolve()`가 조회 결과 UNKNOWN에서 WARN 한 줄을 남기도록 수정한 뒤 모두 통과했다. [ADR 0099](../adr/0099-log-deferred-payment-result-check.md)

- 조회 회로를 열고 reconcile: PG 조회 호출 수 그대로, `PG 요청: op=lookup` 없음, `결제 결과 확인 보류: orderId=1 source=RECONCILE cause=CIRCUIT_OPEN`
- 조회 응답 0.7초 지연(조회 read timeout 0.5초): `PG 응답 없음: op=lookup ... error=SocketTimeoutException elapsedMs=502` 두 번 뒤 `결제 결과 확인 보류: orderId=1 source=RECONCILE cause=TEMPORARY_FAILURE`

`PaymentBoundaryLogTest` 4건, `PaymentProviderContractTest` 32건, `LayeredArchitectureTest` 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test도 통과했다.

관련 회귀: `./gradlew :apps:commerce-api:test --tests '*Payment*' --tests '*OrderV1ControllerTest' --tests '*LayeredArchitectureTest' --tests '*OrderConfirmation*'` → 25개 클래스 218건 통과(실패·에러·skip 0).

### 조회 응답이 200이지만 JSON을 읽을 수 없는 경우 — 로그 검증

`PaymentProviderContractTest.unusableLookupResponseIsAnUnexpectedResponse`에서 PG가 HTTP 200과 `not-json`을 반환하도록 했다. 결제 판정과 조회 호출 수 검증은 통과했다. 실제 로그에는 `PG 응답 없음: op=lookup transactionKey=TEST-TX-1 result=UNKNOWN cause=UNEXPECTED_RESPONSE error=JsonParseException`이 남았고, `PG 응답`과 `httpStatus=200`을 기대하는 새 검증은 실패했다. JSON 변환이 HTTP 상태를 어댑터의 결과로 전달하기 전에 실패하므로, 현재 문구만 보면 본문 해석 오류를 응답 유실로 오인할 수 있다. 이번 실행은 해당 테스트의 차이를 확인한 단계이며 수신 방식 변경은 결정 대기다.

### A. PG 조회 본문 해석 실패 시 HTTP 상태 보존

위에서 실패한 테스트를 기준으로 거래 키·주문 번호 조회에서 HTTP 상태를 먼저 읽도록 수정했다. JSON을 읽지 못했을 때 응답 유실로 오인하지 않도록 같은 WARN 결과 줄에 상태와 예외 종류를 남긴다. [ADR 0097](../adr/0097-log-pg-response-http-status.md#5-조회-본문을-해석할-수-없는-응답)

```text
PG 응답: op=lookup transactionKey=TEST-TX-1 httpStatus=200 result=UNKNOWN cause=UNEXPECTED_RESPONSE error=JsonParseException elapsedMs=1
PG 응답: op=lookup providerOrderId=LP-ORD-0000000042 httpStatus=200 result=UNKNOWN cause=UNEXPECTED_RESPONSE error=IllegalArgumentException elapsedMs=1
```

첫 줄은 HTTP 200에 본문이 `not-json`인 경우, 둘째 줄은 주문 번호 조회에서 거래 목록 대신 `{}`를 받은 경우다. PG 계약 32건·경계 로그 4건·ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test도 통과했다.

## B. 주문 한 건의 복구 전 관찰

`PaymentIncidentSingleOrderExperimentTest.settledPaymentRemainsUnknownBeforeReconcile`에서 실제 테스트 DB에 주문 1번을 만들었다. 가짜 PG는 3,000원 거래를 성공으로 저장하고 생성 응답을 보류했다. 기존 probe에서 연결을 끊었을 때는 PG에도 거래가 `PENDING`으로 남았으므로, 이번에는 결제가 이미 끝났다는 조건을 먼저 설정했다.

| 관찰 순서 | 주문 1번에서 읽은 값 |
| --- | --- |
| 생성 요청·결과 로그 | `LP-ORD-0000000001`, 3,000원. 1,003ms 뒤 `SocketTimeoutException` |
| 같은 PG 주문 번호로 읽기 전용 조회 | HTTP 200, 거래 1건. `TEST-TX-1`, 응답 주문 번호 일치, `SUCCESS`, 3,000원 |
| 내부 DB | 결제 시도 `attempt-1`은 `UNKNOWN`, 거래 키 없음. 주문은 `PAYMENT_PENDING`, 주문 금액 3,000원. 예약은 `RESERVED`, 재고는 최초 5개 중 3개를 예약해 2개 |
| PG 호출 수 | 생성 1회, 진단 조회 1회 |

생성 로그로는 우리 쪽 대기 시간이 끝났음을 확인했다. PG 성공은 별도의 읽기 전용 GET에서 확인했다. 여기서 실험을 종료했다. 다음에는 같은 테스트 안에서 이 관찰 직후 본인 주문 reconcile API를 호출해 거래 키·재고·생성 호출 수를 전후 비교한다. 테스트 종료 시 DB와 가짜 PG가 정리되므로 이번 실행의 주문 번호를 다음 실행에서 재사용하지 않는다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`

환경 probe 3건과 ArchUnit 1건 통과(실패·에러·skip 0). commerce-api Checkstyle main·test 통과(main은 변경이 없어 Gradle의 기존 검사 결과를 사용).

### 본인 주문 reconcile 1회

새 실행에서 복구 전 관찰부터 다시 재현했다. 이번 주문 1번의 생성 요청은 1,138ms 뒤 timeout으로 끝났다. 진단용 GET에서 주문 번호가 일치하는 3,000원 성공 거래가 정확히 한 건이었고, DB에는 미확인 결제와 예약이 남아 있었다. 이 근거로 같은 테스트 안에서 주문 소유자의 `POST /api/v1/orders/1/payment/reconcile`을 MockMvc로 실제 컨트롤러에 한 번 보냈다. 거래가 0건·복수 건이거나 주문 번호·금액이 다르거나 이번 진단 조회에서 성공을 확인하지 못하면, 테스트는 API 호출 전에 멈춘다.

| 확인한 값 | 진단 GET 직후 | reconcile 응답 직후 |
| --- | --- | --- |
| PG 조회에서 읽은 거래 | `TEST-TX-1`, SUCCESS, 3,000원 | 같은 키·상태·금액 |
| DB 결제 시도와 거래 키 | UNKNOWN, 키 없음 | SUCCESS, `TEST-TX-1` |
| DB 주문·예약·남은 재고 | PAYMENT_PENDING, RESERVED, 2개 | CONFIRMED, CONSUMED, 2개 |
| PG 누적 생성·조회 횟수 | 생성 1회, 조회 1회 | 생성 1회, 조회 2회 |

reconcile 응답은 HTTP 200·`checked=true`였다. DB에서 확정 금액 3,000원을 다시 읽었다. 진단 GET을 보낸 뒤에도 미확인 상태였던 주문이 reconcile 안의 GET과 내부 반영을 거쳐 확정됐다. PG에는 기존 거래 한 건이 남았고 복구 중 생성 증가량은 0이었다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`

`PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`을 포함해 probe 3건과 ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test 통과(main은 Gradle의 기존 검사 결과 사용).

## C. 단건 복구 전후의 DB 집계

같은 단건 테스트에 `countByStatus(PaymentAttemptStatus.UNKNOWN)` 조회를 추가했다. 주문 ID 조건 없이 현재 미확인 상태인 결제 시도 전체를 센다. 결제 요청 직전 **0건**, 생성 응답 timeout 직후(진단 GET 전) **1건**, 같은 주문 reconcile 직후 **0건**을 관찰했다. 마지막 시점에는 해당 결제 시도가 DB에 `SUCCESS`로 남아 있었다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`

이 값은 테스트에서 직접 읽은 DB 기준값이다. 다음 단계에서 같은 조건으로 Prometheus gauge를 노출하고, endpoint 값이 DB를 따라오는 시간을 측정할 수 있다. 이번 실행에서 probe 3건·ArchUnit 1건은 실패·에러·skip 없이 통과했고, commerce-api Checkstyle main·test도 통과했다.

## C. Prometheus에서 같은 결제 시도 관찰

실제 관리 포트에서 `GET /actuator/prometheus`를 호출했다. 지표 추가 전에는 HTTP 200을 받았지만 응답에 `# TYPE commerce_payment_attempts_unknown gauge`가 없어 첫 검증이 실패했다. 추가 후에는 같은 주문의 세 시점 모두 응답 본문에서 `commerce_payment_attempts_unknown{application="commerce-api"}` 값을 읽었다. `PaymentIncidentSingleOrderExperimentTest.reconcileConfirmsTheSameSettledPaymentAfterCreateTimeout`

| 시점 | DB의 UNKNOWN | endpoint 값 | DB 값을 읽은 뒤 같은 값을 처음 관찰하기까지 |
| --- | ---: | ---: | ---: |
| 결제 요청 전 | 0 | 0.0 | 295ms |
| 생성 응답 timeout 뒤 | 1 | 1.0 | 3,081ms |
| 본인 reconcile 뒤 | 0 | 0.0 | 4,741ms |

기본 갱신 간격 5초에서 실행했다. 테스트는 실제 DB 값을 읽은 직후부터 endpoint를 확인하고, 값이 다르면 100ms 쉬고 다시 조회했다. 표는 한 실행에서 관찰한 시간이며 HTTP 요청·폴링 대기도 포함한다. 첫 행은 초기값을 읽는 데 걸린 시간이다. DB 내부의 정확한 커밋 시각과 별도 Prometheus 서버가 수집한 시각은 이번 측정 범위 밖이다. [ADR 0100](../adr/0100-sample-unknown-payment-attempt-count.md)

commerce-api 환경 probe 3건·gauge 단위 테스트 1건·ArchUnit 1건 통과(실패·에러·skip 0). Checkstyle main·test 통과. 단위 테스트는 첫 측정 전 NaN, 정상 집계, DB 조회 실패 후 NaN, 다음 성공 시 0건 복귀를 검증했다.

## D. 주문 두 건에서 응답 보류 대상 확인

장애 대상으로 주문 1번(A)을 정하고, 결제 요청은 주문 2번(B)부터 보냈다. 기존 ‘다음 응답 보류’에서는 먼저 도착한 B가 UNKNOWN이 되어 검증에 실패했다. 주문 번호로 보류 대상을 지정한 뒤에는 같은 순서에서 다음 결과를 얻었다. `PaymentIncidentSingleOrderExperimentTest.onlySelectedOrderTimesOutWhenAnotherOrderIsConfirmedFirst`

| 요청 순서 | 생성 응답에서 관찰한 값 | DB 결제 시도 |
| --- | --- | --- |
| B · 주문 2번 · 먼저 요청 | 4ms, HTTP 202, 거래 PENDING | PENDING |
| A · 주문 1번 · 보류 대상 | 1,002ms, SocketTimeoutException | UNKNOWN |

가짜 PG에는 두 주문 각각 SUCCESS 거래가 한 건씩 저장됐고 생성 호출은 총 2회였다. 응답을 받은 B도 이 시점에는 접수 상태다. 이 실험은 callback이나 reconcile 전에 끝난다. 별도 HTTP 테스트에서는 A의 응답을 기다리는 동안 B가 202를 받고, A의 보류만 해제하면 PG를 닫지 않고 A도 202를 돌려주는지 확인했다. [실험 도구](design.md#주문-번호로-생성-응답-보류)

환경 probe 4건·가짜 PG 3건·PG 계약 32건·ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test 통과(main은 기존 검사 결과 사용).

## D. 주문별 결제 생성 요청 횟수

두 주문의 생성 요청이 총 2회여도, 주문 A가 두 번 요청하고 B는 요청하지 않은 경우와 구분되지 않는다. 가짜 PG가 수신한 POST를 PG 주문 번호별로 세는 검사를 추가했다. [실험 도구](design.md#주문별-결제-생성-요청-횟수)

대조군: `FakePaymentServerTest.rejectedSecondCreateCountsEvenWhenTheTotalMatchesTwoOrders`에서 같은 주문으로 두 번 요청하고 두 번째에 422를 지정했다. 거래는 1건만 남았지만 주문별 집계는 `{LP-ORD-A=2}`였고, "A·B 각각 1회" 검사는 실패했다. 거래 목록이나 총 호출 수만으로는 이 중복을 찾지 못한다. `FakePaymentServerTest.rejectedSecondCreateCountsEvenWhenTheTotalMatchesTwoOrders`

두 주문 실험: `PaymentIncidentSingleOrderExperimentTest.onlySelectedOrderTimesOutWhenAnotherOrderIsConfirmedFirst`에서 주문 2번(정상)을 먼저, 주문 1번(응답 보류 대상)을 나중에 요청했다. `PaymentIncidentSingleOrderExperimentTest.onlySelectedOrderTimesOutWhenAnotherOrderIsConfirmedFirst`

| 주문 | 생성 응답 | DB 결제 시도 | PG 생성 요청 |
| --- | --- | --- | ---: |
| 2번 · 정상 | 171ms, HTTP 202, PENDING | PENDING | 1 |
| 1번 · 보류 대상 | 1,006ms, SocketTimeoutException | UNKNOWN | 1 |

```text
[targeted-create] normalOrder=2 attempt=PENDING selectedOrder=1 attempt=UNKNOWN pgCreates=2 byOrder={LP-ORD-0000000002=1, LP-ORD-0000000001=1}
```

이번 실행에서 환경 probe 1건(`onlySelectedOrderTimesOutWhenAnotherOrderIsConfirmedFirst`)·가짜 PG 4건·ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test도 통과했다.

## C. gauge 집계 실패 처리

집계가 실패하면 `refresh()`가 값을 `NaN`으로 바꾼 뒤 예외를 다시 던지고 있었다. DB가 안 되는 동안 스케줄러가 집계 주기(기본 5초)마다 스택트레이스를 ERROR로 남기게 된다.

예를 들어 기본 간격 5초에서 DB가 1분 동안 응답하지 않으면, 계산상 예외 한 건마다 여러 줄인 스택트레이스 덩어리가 12번 남는다. 그 사이에 남는 결제 경계 로그와 보류 로그가 이 덩어리들에 묻힌다. 실제 DB 장애를 일으켜 측정한 값은 아니고, 집계 간격으로 계산한 예시다. 수정 후에는 같은 상황에서 다음 한 줄이 집계 주기마다 남고, gauge 값은 `NaN`이 되어 실제 0건과 구분된다.

```text
WARN ... UNKNOWN 결제 시도 집계 실패: error=DataAccessResourceFailureException
```

`UnknownPaymentAttemptGaugeTest`의 기대값을 "예외를 던지지 않고 `NaN`, WARN 한 줄(예외 이름), 스택트레이스 없음"으로 바꾸자 수정 전 코드에서 `DataAccessResourceFailureException`이 던져져 실패했다. 예외를 잡아 WARN을 남기도록 수정한 뒤 통과했다. 첫 집계 전 `NaN`, 정상 집계 1, 실패 뒤 `NaN`, 다음 성공 시 0 검증은 그대로다. [ADR 0100](../adr/0100-sample-unknown-payment-attempt-count.md)

이번 실행에서 gauge 단위 테스트 1건, 환경 probe 4건, ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test도 통과했다.


## D. 정상 10건 + 장애 10건 실험

`PaymentIncidentExperimentTest.selectedFaultsAreDetectedAndRecoveredWithoutNewPayments` 한 테스트에서 PG와 DB를 초기화하지 않고 BASELINE·FAULT·진단·RECOVERY를 이어서 실행했다. 상품 하나(재고 30)를 주문 20건이 1개씩 산다. 가짜 PG는 새 거래를 모두 SUCCESS로 저장한다. 가설은 [실행 전 기록](d-hypotheses.md)에 있다.

| 단계 | health (전체·liveness·readiness) | DB UNKNOWN | gauge (DB 값을 읽은 뒤 같은 값을 처음 본 시간) | 주문 상태 | PG 누적 생성·조회·거래 | 재고 |
| --- | --- | ---: | --- | --- | --- | ---: |
| BASELINE | 모두 200·UP | 0 | 0.0 (27ms) | 1~10번 CONFIRMED | 10·10·10 | 20 |
| FAULT | 모두 200·UP | 3 | 3.0 (3,835ms) | 13·16·19번 UNKNOWN·PAYMENT_PENDING·RESERVED, 나머지 7건 CONFIRMED | 20·17·20 | 10 |
| RECOVERY | 모두 200·UP | 0 | 0.0 (4,551ms) | 11~20번 CONFIRMED | 20·23·20 | 10 |

- FAULT: 장애 집합 10건 중 3·6·9번째(주문 13·16·19)만 생성 응답을 보류했다. 세 주문의 생성 로그는 `PG 응답 없음: op=create ... error=SocketTimeoutException`이었다. 다른 7건은 202·PENDING을 받고 callback으로 확정됐다. 이때도 health 세 경로는 모두 UP이었다.
- 진단: 세 주문을 주문 번호로 읽기 전용 조회했다. 각각 거래 1건, 응답 주문 번호 일치, SUCCESS, 1,000원(주문 금액과 같음), 내부 UNKNOWN·RESERVED여서 세 건 모두 진행으로 판단했다. 이 조회로 PG 조회 횟수가 17에서 20으로 늘었다.
- RECOVERY: 보류를 해제하고 비어 있음을 확인한 뒤, 주문자가 세 주문을 reconcile했다. 모두 HTTP 200·CONFIRMED·CONSUMED였다. 장애 집합 10건의 PG 거래 키는 복구 전후 같았고(`TEST-TX-11`~`TEST-TX-20`), 내부 결제 시도의 거래 키도 같은 값이 됐다. 복구 중 PG 생성 호출 증가는 0(20 → 20), 재고는 10 그대로였다.
- 반복과 거절: 복구된 주문 13에 reconcile·callback을 다시 보내자 둘 다 200, 주문은 CONFIRMED 그대로, 생성 호출은 20 그대로였다. 다른 사용자가 주문 16을 reconcile하자 HTTP 404 `주문을 찾을 수 없습니다.`였다.
- 주문별 생성 요청: 20개 주문 모두 정확히 1회였다.
- 실험 내내 `결제 결과 저장 실패` 로그는 없었다. 자동 복구 스케줄러 bean은 없었다(`recoveryScheduler=absent`).
- 이 첫 실행에서는 포인트·쿠폰을 준비하지 않아 복구 전후를 측정하지 않았다. 아래 재검증에서 기존 잔액과 발급 내역을 대조했다.

### 실행 중 바로잡은 것

- 첫 실행은 BASELINE의 전체 health에서 503·DOWN으로 멈췄다. 테스트 환경에 Redis가 없어 Redis health 검사가 실패했다. 전체 health는 DB·Redis 연결까지 보므로, 실제 환경처럼 테스트에서도 Redis 컨테이너를 띄우고 연결 주소를 설정했다. 이후 세 경로 모두 UP이었다. health는 서버와 저장소 연결 상태를 알려 줄 뿐, 결제가 멈췄는지는 알려 주지 않는다. FAULT에서 health UP과 gauge 3이 함께 관찰된 것이 그 차이다.
- 첫 통과 실행에서는 진단 조회의 경계 로그가 FAULT 기록 뒤에 생겨 어느 파일에도 담기지 않았다. 진단 로그를 `fault.txt`의 `[diagnosis-logs]`로 남기도록 고친 실행을 보존했다.

이번 실행에서 D 실험 1건, 가짜 PG 4건, ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle main·test도 통과했다. 테스트용 PG에 보류 중인 주문 목록(`heldCreateOrders()`)을 추가하고, 주문별 보류 테스트에서 지정·해제 전후 목록을 확인했다.

## D. 생성 응답 저장 실패 — UNKNOWN 집계에 보이지 않는 경우

D 실험의 "TX B 실패였다면" 비교를 쓰다가, 생성 응답을 받은 뒤 그 응답을 저장하는 TX B가 실패하면 결제 시도가 `UNKNOWN`이 아니라 `REQUESTED`로 남는다는 점을 코드에서 확인했다. 실행으로 확인하려고 `PaymentBoundaryLogTest.createResponseSaveFailureLeavesRequestedOutsideUnknownCount`를 추가했다. 가짜 PG는 거래를 SUCCESS로 저장하고 생성 응답은 202·PENDING으로 돌려준다. 생성 응답 이력(`CREATE_RESPONSE`) 저장에서 예외를 냈다. `PaymentBoundaryLogTest.createResponseSaveFailureLeavesRequestedOutsideUnknownCount`

```text
PG 응답: op=create providerOrderId=LP-ORD-0000000001 httpStatus=202 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:PENDING:3000] elapsedMs=4
결제 결과 저장 실패: orderId=1 step=TX_B:CREATE_RESPONSE error=IllegalStateException
[create-save-failure] orderId=1 attempt=REQUESTED internalTxKey=null unknownCount=0 requestedCount=1 pgCreates=1 ...
```

- 결제 시도는 `REQUESTED`·거래 키 없음으로 남았다. DB 집계는 UNKNOWN 0건, REQUESTED 1건이었다. 현재 UNKNOWN gauge는 이 주문을 세지 않는다.
- 저장 실패를 풀고 본인 reconcile을 호출하자 주문 번호 조회로 `SUCCESS`를 읽어 결제 시도 `SUCCESS`·`TEST-TX-1`, 주문 `CONFIRMED`가 됐다. PG 생성 호출은 1회 그대로였다.
- 당시에는 REQUESTED 지표를 보류했다. 이후 UNKNOWN gauge를 유지하고 오래된 REQUESTED는 DB 질의로 찾기로 했다. 이번 테스트는 Prometheus endpoint가 아니라 DB 집계를 확인했다.

`PaymentBoundaryLogTest` 5건과 ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle test도 통과했다.

## E. 복구한 주문을 랭킹까지 따라가기

D 실험 테스트(`PaymentIncidentExperimentTest`)에 E 단계를 이어 붙여, 같은 실행의 20건을 Outbox → Kafka → 실제 commerce-streamer(별도 JVM) → 구매 활동 → 실제 commerce-batch(별도 JVM) → 랭킹 API까지 따라갔다. 이 실행은 주문 확정 때 Outbox를 쓰도록(`commerce.order-events.enabled=true`) 켜고 D부터 다시 실행한 것이다.

| 시점 | 발행 | Kafka 레코드 (누적) | 구매 활동 | 랭킹 |
| --- | --- | ---: | --- | --- |
| 복구 전 (E-1) | 첫 실행은 PUBLISHED 저장 직전에 멈춤, 다시 실행해 17건 발행 | 18 | 확정된 17건 각 1건, 소비 완료 기록 각 1건 | r1 공개: 점수 17·1위 |
| 복구 후 (E-2·E-3) | 복구한 3건 발행 | 21 | 20건 각 1건, 소비 완료 기록 각 1건 | r2 공개: 점수 20·1위, revision 2 |

- 재발행으로 같은 이벤트가 한 번 더 Kafka에 갔다(레코드 21개). 구매 활동은 주문마다 1건이었다. 발행 시도 수와 실제 반영 수가 다르다는 것을 같은 실행에서 확인했다.
- r2를 공개한 뒤에도 r1의 완성 랭킹은 `1:17:1`, 입력은 17건 그대로였다. 복구 결과는 공개된 r1을 고치지 않고 새 revision r2로 설명된다.
- 구매 활동 20건의 `confirmed_at`은 모두 같은 UTC 주(2026-11-23 시작)였다. 주문 내부의 확정 시각과 직접 대조하지는 않았다.
- D 단계 결과는 Outbox를 켠 이번 실행에서도 같았다. FAULT health 3종 UP·gauge 3(DB 값을 읽은 뒤 2,857ms), 복구 중 PG 생성 증가 0, 거래 키 동일, 재고 10, 다른 사용자 reconcile 404, 20개 주문 각 생성 요청 1회.

### 실행 중 바로잡은 것

- 첫 실행은 랭킹 API에서 HTTP 500으로 멈췄다. 테스트가 상품을 저장소에 직접 저장해 좋아요 카운터가 없었고, 랭킹 API는 좋아요 수도 읽는다. 기존 E2E처럼 `BrandFacade`·`ProductFacade`로 만들도록 고쳤다. 서비스 코드 문제는 아니었다.
- 랭킹 테이블과 구매 활동 테이블은 테스트 정리(`truncateAllTables`)가 지우지 않는다. 당시에는 시작·종료 시 해당 주 랭킹 행과 종료 시 대상 주문의 활동·처리 기록을 지웠다. 이후 Week 9와 같이 실행했을 때 Batch 완료 이력이 남는 문제가 드러났다.

이번 실행에서 D+E 실험 1건과 ArchUnit 1건이 통과했다(실패·에러·skip 0, 실험 29.8초). commerce-api Checkstyle test도 통과했다.

## F. Grafana에서 UNKNOWN 결제 시도 0 → 3 → 0 보기

대시보드 `commerce-api`에 네 번째 패널 "결과 미확인(UNKNOWN) 결제 시도 수"를 추가했다. 쿼리는 `max(commerce_payment_attempts_unknown{application="commerce-api"})`다. 인스턴스마다 같은 DB 전체를 세므로, 여러 인스턴스를 `sum()`으로 합치면 값이 부풀어 `max()`를 쓴다. [대시보드](../../docker/grafana/provisioning/dashboards/commerce-api.json)

로컬에서 MySQL·Redis·Kafka·Prometheus·Grafana 컨테이너와 가짜 PG(18080, 제어 18081), commerce-api(local 프로필)를 띄웠다. 테스트 픽스처 없이 로컬 가짜 PG의 기존 제어(`/delay`, `/complete`)와 HTTP API로 사고를 만들고 복구했다. 사용자 1명은 DB에 직접 넣었고(사용자 생성 API가 없다), 브랜드·상품(재고 30)은 관리자 API로 만들었다.

| 시각 (UTC) | 한 일 | Prometheus 값 |
| --- | --- | ---: |
| 14:15:40 | 평소 | 0 |
| 14:16:49 | `/delay?ms=1500`으로 PG 응답을 늦추고 주문 1·2·3 결제. 3건 모두 `PG 응답 없음: op=create ... error=SocketTimeoutException`(약 1초), 결제 시도 UNKNOWN·거래 키 없음 | |
| 14:16:55 | | 1 |
| 14:17:00 | 이때 `/actuator/health`는 `UP` | 3 |
| 14:18:15 | `/delay?ms=0`, `/complete?status=SUCCESS` 뒤 본인 reconcile 3건. 모두 CONFIRMED, 결제 시도 SUCCESS(`TEST-TX-1`~`3`) | |
| 14:18:25 | | 0 |

- 가짜 PG 통계는 생성 3회·조회 3회·거래 3건이었다. 복구는 조회만 했고 생성 호출은 늘지 않았다.
- 14:16:55에 잠깐 1이 수집됐다. 세 주문이 약 1초 간격으로 차례로 timeout이 났고, 앱 집계(5초)와 Prometheus 수집(5초) 사이에 첫 주문만 반영된 순간이 찍힌 것이다. 지표는 수집 시점의 상태다.
- 복구는 PG 거래를 SUCCESS로 바꾼 뒤 했다. 이 로컬 지연 방식은 응답을 늦춘 뒤 거래를 PENDING으로 만들기 때문에, D 실험처럼 처음부터 SUCCESS로 저장된 상태는 아니다.
- Grafana 화면에서 0 → 3 → 0 그래프를 눈으로 확인하고 캡처했다. Prometheus의 5초 간격 값으로는 0 → 1 → 3 → 0이었다.

실행 뒤 앱·가짜 PG를 종료하고 컨테이너를 내렸다.

## G. 실험 테스트 이름 정리

환경 확인용으로 만든 `IncidentEnvironmentProbeTest`가 B·C 실험과 D 준비 실험을 담게 되어 `PaymentIncidentSingleOrderExperimentTest`로 이름을 바꿨다. 이 문서의 이전 절도 새 이름으로 고쳤다. 실험 내용과 실행 기록은 그대로다. 정상 10 + 장애 10 실험과 E는 `PaymentIncidentExperimentTest`에 있다.

이름을 바꾼 뒤 `PaymentIncidentSingleOrderExperimentTest` 4건과 ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle test도 통과했다. 전체 회귀는 이 시점에 실행하지 않았다.

## G. runbook과 문서 정리

- [runbook](../runbook/week10.md)을 썼다. 증상, 먼저 하지 말 것, 진단(DB 질의·로그 문구·읽기 전용 PG 조회), 판단 세 묶음과 조회 0건 규칙, reconcile 근거 문장, 중단 조건, 복구 확인, 재진입, 다루지 않는 것으로 나눴다. 문구·테이블·API는 이번 주 실행에서 확인한 이름을 썼다.
- [design.md](design.md)에 D·E 실험 구조(5절), 요청을 잇는 키(6절), UNKNOWN 집계가 세지 않는 경우(3절), 검증 범위와 남은 위험(7절)을 추가했다.
- 전체 회귀(`:apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check`)는 아직 실행하지 않았다.


## D+E 재검증 — 기존 데이터 보존과 주문별 대사

- Week 9와 Week 10을 같은 Gradle 작업에서 실행하자 Week 9의 r1이 `JobInstanceAlreadyCompleteException`으로 실패했다. 랭킹 행 정리로 Spring Batch의 완료 이력은 지워지지 않았다. Week 10의 주문 확정 시계와 집계 주를 고정한 뒤 같은 명령에서 두 실험과 ArchUnit이 모두 통과했다. commerce-api Checkstyle main·test도 통과했다. [수정 전후 JUnit 결과](measurements/de-lifecycle-02/regression.txt)
- 새 실행에서 health는 세 단계 모두 UP, UNKNOWN gauge는 0 → 3 → 0이었다. 장애 집합 10건 중 같은 3건을 reconcile해 7/3에서 10/0으로 복구했다. 전체 20건의 PG 생성은 주문마다 1회였고 복구 중 증가가 없었다. [기준선](measurements/de-lifecycle-02/baseline.txt) · [조치 전](measurements/de-lifecycle-02/fault.txt)
- 기존 주문자에게 5,000포인트를 충전하고 쿠폰 1장을 실제 발급했다. 첫 reconcile 전과 반복 reconcile·callback 후 잔액과 본인 쿠폰 목록이 같았다. 다른 사용자의 reconcile은 404였고 재고는 복구 전후 10개였다. [복구 원문](measurements/de-lifecycle-02/recovery.txt)
- 주문 원본과 구매 활동에서 주문 ID·상품 ID·수량·확정 시각을 직접 비교했다. 복구 전 17건과 복구 후 20건이 각각 일치했다. Kafka 레코드는 재발행을 포함해 21개였고 소비 완료 기록은 주문마다 1건이었다. 고정 주의 r2는 20점·1위, r1은 17점 그대로였다. [활동·공개 snapshot 원문](measurements/de-lifecycle-02/ranking.txt)
- runbook에서 생성 timeout과 단순 로그 누락을 구분했다. PG 실패가 확인된 주문은 `FAILED / PAYMENT_FAILED / RELEASED`를 기대한다. TX B 실패 뒤엔 PG를 다시 조회하고, TX C 실패 뒤엔 이미 저장된 종결 결과를 적용한다. D+E의 세 주문은 모두 SUCCESS였다. [운영 절차](../runbook/week10.md)
- 별도 실행에서 결제 결과 반영 10건, 주문 결제 통합 44건, 경계 로그 5건, 주간 배치 재시작 6건·공개 6건, ArchUnit 1건이 통과했다. D+E는 성공 거래의 연결을 검증하고 기존 결제 테스트는 FAILED·TX C 재실행을 검증한다. 전체 모듈 회귀는 이번 검증 범위에 포함하지 않았다. [검사 결과](measurements/de-lifecycle-02/regression.txt)
- 별도 복사본에서 복구 직전 포인트 기대값을 5,001로 주자 실제값 5,000에서 실패했다. 같은 작업공간에서 기대값을 복원해 통과시킨 뒤 두 실행 폴더를 대조했다. 실패 단계·이유와 그때까지의 네 단계 파일은 모두 이전과 바이트 단위로 같았다. [실패 원문](measurements/de-preservation-red-01/failure.txt) · [재실행 후 대조](measurements/de-preservation-red-01/verification.txt)

## H. 배치 한 번 뒤 수동 확인이 필요한 주문

생성 응답이 유실된 주문을 기존 배치가 얼마나 처리하는지 먼저 확인했다. D에서 생성 응답이 늦어지도록 보류한 것과 달리, 여기서는 가짜 PG가 성공 거래를 저장한 직후 응답을 끊었다. 주문 1·2·3에는 성공 거래가 한 건씩 있었다. 주문 4는 결제 시도와 예약만 생성하고 PG로 보내지 않아 거래가 없었다. 네 건 모두 생성 시각을 11분 전으로 조정한 뒤 callback·수동 reconcile 없이 배치 본체를 한 번 호출했다.

| 주문 | 배치 직전 | 배치 직후 | 근거 |
| --- | --- | --- | --- |
| 1·2·3 | UNKNOWN, 예약 유지, 내부 거래 키 없음 | SUCCESS · CONFIRMED · CONSUMED | 각 주문의 PG 성공 거래 키와 DB에 반영된 키 일치 |
| 4 | REQUESTED · PAYMENT_PENDING · RESERVED | 같은 상태, 거래 키 없음 | 주문 번호 조회가 빈 목록, 해당 시도의 BATCH 이력에 NO_TRANSACTION_YET |

배치 결과는 finished=3, unfinished=1, failed=0이었다. PG 조회 4회, 추가 생성 0회, 재고 96개 유지, UNKNOWN의 DB 집계는 3 → 0이었다. 4번은 runbook에 따른 확인 대상이다. 이번 실험은 알림 전송이나 배치 이후 자동 재시도 중단을 검증하지 않았다. `PaymentRecoveryBatchTest.recoversThreeLostResponsesAndLeavesOneUnsentOrderForReview`

이 실행에서 배치 본체는 92ms 걸렸다. 생성 시각을 조정했으므로 10분의 실제 대기, 스케줄 기동 시각, 운영 처리량은 측정하지 않았다. 배치 테스트 16건, ArchUnit 1건, commerce-api Checkstyle main·test 통과. 다음 판단은 고객에게 허용할 ‘확인 중’ 시간이 기존 배치 최소 대기보다 짧은지다. 그 근거가 필요할 때 짧은 자동 재조회 비용과 효과를 비교한다.

## H. 똑같이 미완료인 두 주문의 다음 조치

배치 결과의 "미완료"가 같은 뜻인지 확인했다. 두 주문 모두 생성 응답을 끊어 `UNKNOWN`으로 만들었다. PG 거래는 A(주문 1)가 `PENDING`·1,000원, B(주문 2)가 `SUCCESS`·2,000원(주문 금액 1,000원)이다. 생성 시각을 11분 전으로 옮겨 배치를 한 번 실행하고, A의 PG 거래만 `SUCCESS`로 바꾼 뒤 다시 실행했다. 테스트용 PG에 저장된 거래의 금액만 바꾸는 `changeAmount`를 추가했다. `PaymentRecoveryBatchTest.pendingOrderWaitsAndAmountMismatchStaysForReview`

| 회차 | 배치 결과 | A (주문 1) | B (주문 2) | DB의 UNKNOWN |
| --- | --- | --- | --- | ---: |
| 실행 전 | | `UNKNOWN`·키 없음·예약 유지 | `UNKNOWN`·키 없음·예약 유지 | 2 |
| 첫 회차 | 종결 0·미완료 2 | `PENDING`·`TEST-TX-1`·예약 유지 | `UNKNOWN`·키 없음·예약 유지 | 1 |
| A만 SUCCESS로 바꾼 뒤 | 종결 1·미완료 1 | `SUCCESS`·`TEST-TX-1`·확정·예약 소비 | 그대로 | 1 |

두 주문 모두 PG 생성 요청은 1회였고 재고는 98개(100 − 2)로 유지됐다.

### 기록만 보고 내린 판단 (첫 회차 직후)

장애 설정을 보지 않고 주문별 PG 응답 로그, 결제 이력(`payment_event`), 주문·예약 상태만 읽었다.

| 주문 | 실제 관찰 | 알 수 있는 것 | 다음 조치와 근거 |
| --- | --- | --- | --- |
| A | `PG 응답: op=lookup ... [TEST-TX-1:LP-ORD-0000000001:PENDING:1000]`, BATCH 이력 `APPLIED`·관찰 `PENDING`·1,000원, 결제 시도 `PENDING`·`TEST-TX-1`, 예약 유지 | PG에 이 주문의 거래가 한 건 있고, 주문 번호·금액이 맞고, 아직 처리 중이다. 결과를 모르던 상태에서 거래 키를 알게 됐다 | **기다린다.** 결과가 정해지지 않았을 뿐 이상한 점이 없다. 다음 배치가 이번에는 거래 키로 다시 조회한다. 예약은 유지한다 |
| B | `PG 결과 충돌: orderId=2 ... amount=2000, status=SUCCESS, reason=AMOUNT_MISMATCH`, BATCH 이력 `CONFLICT`·`AMOUNT_MISMATCH`·관찰 2,000원, 결제 시도 `UNKNOWN`·키 없음, 예약 유지 | PG에 이 주문 번호로 성공 거래가 있지만 금액이 주문(1,000원)과 다르다. 서비스는 이 거래를 적용하지 않았다 | **멈추고 사람이 본다.** 다시 조회해도 금액은 바뀌지 않는다. 어느 쪽이 맞는지(주문 금액, PG 거래, 다른 결제)는 자동으로 정할 수 없다. 예약과 이력을 보존한다 |

두 번째 회차에서 A는 거래 키 조회로 `SUCCESS`를 읽어 같은 키로 확정됐다. B는 같은 충돌이 한 번 더 기록됐다. 예상과 실제가 같았다.

### 관찰한 것

- **배치의 "미완료" 수만으로는 다음 조치를 가를 수 없다.** 첫 회차의 미완료 2건 중 1건은 기다리면 되고 1건은 사람이 봐야 했다. 주문별로 마지막 배치 이력의 결과(`APPLIED`·관찰 `PENDING` / `CONFLICT`·사유)를 봐야 갈린다.
- **UNKNOWN 집계는 첫 회차 뒤 2 → 1이 됐지만 끝난 주문은 없었다.** A가 `PENDING`으로 바뀌어 집계에서 빠졌다. 처리 중 주문은 기다리는 대상이라 이 지표에서 빼기로 한 결정(ADR 0100)대로다. 사람이 봐야 할 B는 계속 1로 남았다.
- **B는 배치가 돌 때마다 같은 PG 조회, 같은 충돌 이력, 같은 WARN 로그를 남긴다.** 두 회차에서 충돌 이력이 2건 쌓였다. 운영에서 배치가 1분마다 돌면 사람이 결정할 때까지 이 기록이 계속 늘어난다. 이번에는 바꾸지 않았고 남은 위험에 적는다.

`PaymentRecoveryBatchTest` 17건, `FakePaymentServerTest` 4건, ArchUnit 1건이 통과했다(실패·에러·skip 0). commerce-api Checkstyle test도 통과했다.

## H. 생성 timeout 완화 실험을 하지 않은 이유

계획에는 생성 응답을 1.5초 뒤에 주는 주문과 끝까지 주지 않는 주문을 timeout 1초·2초에서 비교하는 실험이 있었다. 하지 않았다. 가짜 PG에 넣은 지연 값이 결과를 정하므로, 2초에서 1.5초 응답이 살아나는 것은 실험 전에 이미 정해져 있다. 응답이 아예 오지 않으면 timeout을 늘려도 살지 않는다는 것은 D 실험의 응답 보류로 보였다. timeout을 바꿀지는 실제 생성 호출 시간 분포로 판단하며, 그 관측은 `commerce.pg.call` 히스토그램(생성·조회별, 결과별)으로 이미 남는다. 생성 timeout 1초는 바꾸지 않았다.


## G. runbook 보완 — 자동 처리 경계와 확인 질의

H 실험 결과로 runbook에 "0. 사람이 열기 전에 시스템이 하는 일"을 추가했다. 결제 요청 안·callback·복구 배치가 각각 끝내는 것과 남기는 것, 배치가 남긴 주문의 다음 조치(PENDING 대기·금액 충돌 사람 확인·빈 조회 0건 규칙·조회 보류)를 실험 원문과 연결했다. 복구 배치가 기본 설정에서 꺼져 있다는 점과 결과 미확인 결제에 만료가 없다는 점도 적었다. 조치 전 전제(주문 목록 고정, 한 주문씩 실행), 복구 확인 SQL, 남은 위험 표(대리 조정 API 없음, 예약 유지, 충돌 반복 조회 등)를 더했다. 관리자 주문 API가 조회만 제공하는 것은 `AdminOrderV1Controller`에서 확인했다. [runbook](../runbook/week10.md)

## I. 변경 뒤 전체 회귀

이번 주 변경을 모두 반영한 작업 트리(커밋 `62e0119` 위의 미커밋 변경)에서 기준선과 같은 명령을 다시 실행했다. JDK 21, Docker는 colima(`DOCKER_HOST=tcp://localhost:2375`)를 썼다.

`./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --continue --rerun-tasks` → `BUILD SUCCESSFUL`, 15분 27초.

| 모듈 | 테스트 클래스 | 테스트 | 실패·에러·skip | Checkstyle (main·test) | ArchUnit |
| --- | ---: | ---: | --- | --- | --- |
| commerce-api | 111 | 703 | 0 | 0·0 | `LayeredArchitectureTest` 통과 |
| commerce-streamer | 23 | 91 | 0 | 0·0 | `LayeredArchitectureTest` 통과 |
| commerce-batch | 10 | 35 | 0 | 0·0 | 해당 없음 |

- 건수는 각 모듈 `build/test-results/test`의 XML을 합산했다. `@Tag("experiment")`가 붙은 이전 주차의 설정 비교 실험은 `test`·`check`에서 빠지고 `experimentTest` 태스크로만 돈다. 이 실험들은 skip으로도 세지지 않으며 이번 실행에 포함되지 않았다. 이번 주에 추가한 테스트에는 이 태그가 없다.
- 기준선보다 api가 17건 늘었다(686 → 703). 이번 주에 추가·변경한 `PaymentBoundaryLogTest` 5, `UnknownPaymentAttemptGaugeTest` 1, `PaymentIncidentExperimentTest` 1, `PaymentIncidentSingleOrderExperimentTest` 4, `PaymentRecoveryBatchTest` 21, `PaymentProviderContractTest` 32, `FakePaymentServerTest` 4건이 이 실행에 포함돼 통과했다. streamer·batch 건수는 기준선과 같다.
- 실행 시간이 기준선(2시간 25분)보다 짧았다. 기준선에서 약 40분 걸린 `KafkaReplicationFailureExperimentTest`가 이번에는 48초였다. 원인은 확인하지 않았다.
- 실행 전 colima의 실행 중 컨테이너는 0개였다. 실행 직후에는 테스트 컨테이너 3개가 종료 중이었고, 결과를 집계한 시점에는 0개였다. Docker Desktop의 다른 프로젝트 컨테이너는 사용하지 않았다.

## J. runbook을 실험 결과와 다시 대조

runbook의 문장을 실험 결과·테스트·코드와 하나씩 대조했다. 실험 결과와 어긋난 문장은 고치고, 실험 없이 판단으로 넣은 규칙은 뺐다.

실험 결과와 어긋나 고친 것:

- runbook은 "결과를 모르는 결제에 만료가 없으므로 '결제됐는데 주문은 실패'가 생기지 않는다"고 했다. 실행에서는 주문이 `FAILED·PAYMENT_FAILED·RELEASED`로 끝난 뒤 PG 거래가 `SUCCESS`로 바뀌었고, callback은 `ALREADY_FINISHED`, 배치 결과는 `finished=0, unfinished=0`, 결제 이력은 2건 그대로였다. 문장을 "시간이 지났다는 이유로 실패 처리하지 않는다"로 좁히고, 이 경로를 남은 위험에 적었다. `PaymentRecoveryBatchTest.finishedFailedOrderIsNotRecheckedWhenPgLaterSaysSuccess`
- runbook은 지표가 세지 않는 것으로 `REQUESTED`만 들었다. TX C 실패 뒤 `unknownCount=0 requestedCount=0`이었다. 주문 반영 실패도 지표와 `REQUESTED` 질의에 보이지 않는다고 고쳤다. 재시도는 reconcile로 했고 PG 조회 수가 1 → 1로 늘지 않았다. 복구 배치가 이 주문을 처리하는지는 이 실행으로 확인하지 않았다. `PaymentRecoveryBatchTest.orderApplyFailureLeavesSuccessAttemptOutsideCountsAndRetriesWithoutLookup`

실험 근거가 없어 뺀 것:

- runbook의 "급한 정도(돈이 빠졌나)" 절. 실험이 없고, 앞 질문에서 완료로 끝난 주문은 PG를 조회하지 않아 이 기준을 적용할 수도 없었다.
- reconcile 직전에 주문이 아직 `PAYMENT_PENDING`인지 다시 보는 단계. D+E에서 같은 주문의 반복 reconcile·callback이 상태와 PG 조회 수(23)를 바꾸지 않았으므로 필요하지 않다. `PaymentIncidentExperimentTest`

runbook E에는 거래 2건 이상을 자동 적용하지 않는 근거로 `OrderPaymentIntegrationTest`(`MULTIPLE_TRANSACTIONS`)를 연결했다. 포인트·쿠폰 대조는 runbook 절차에 해당 단계가 없어 E에서 뺐고, 증거는 위 D+E 재검증에 남아 있다.

## K. 멈춘 주문을 결제 시도 기준과 주문 기준으로 세어 비교

결제가 끝나지 않은 주문을 두 기준으로 셌다. 결제 시도가 `REQUESTED`·`PENDING`·`UNKNOWN`인 것(결제 시도 기준), 주문이 `PAYMENT_PENDING`인 것(주문 기준). 두 기준 모두 경과 시간은 결제 시도를 만든 시각(결제를 시작한 때)부터 쟀다. 주문을 만든 시각부터 재면 주문해 두고 나중에 결제한 정상 주문도 오래된 것처럼 보이기 때문이다. 출발점이 같으므로 두 기준의 차이는 어떤 상태를 셌는지에서만 생긴다. `PaymentStuckSignalExperimentTest`

조건(모두 가정값이다):

| 항목 | 값 |
| --- | --- |
| 주문 도착 | 100ms마다 1건, 30초 동안 (주문 301건), 이후 10초 대기 |
| 가짜 PG | 모든 응답을 100ms 뒤에 준다. 생성 2초 뒤 거래를 `SUCCESS`로 바꾸고 callback 처리를 부른다 |
| 섞은 주문 (두 번째 실행) | 시작 5초 뒤 도착한 세 건. ① 생성 응답 저장(TX B) 실패, ② PG가 결과를 끝내지 않음, ③ PG 성공 저장 뒤 주문 반영(TX C) 실패 |
| 관찰 | 1초마다 실제 `/actuator/prometheus` 응답의 UNKNOWN gauge·주문 기준 gauge 값, DB에서 센 두 기준의 건수와 가장 오래된 세 주문의 ID·결제 시도 상태·경과 시간 |

정상 주문만 넣은 실행:

- 주문이 들어오는 동안 두 기준 모두 22건이었고, 가장 오래된 주문은 2.2초였다. 정상 주문의 최대 경과 시간은 두 기준 모두 2,191ms였다.
- 도착이 끝나고 3초 뒤(t=33s) 두 기준 모두 0건이 됐다. 301건 모두 `SUCCESS`·`CONFIRMED`였다.

멈춘 주문 세 건을 섞은 실행 (주문 51 ①, 52 ②, 53 ③):

| 시점 | UNKNOWN gauge | 결제 시도 기준 (DB) | 주문 기준 (DB) | 주문 기준 gauge |
| --- | ---: | --- | --- | ---: |
| t=7s | 0 | 22건, 51번이 2.0초로 정상 주문과 섞임 | 22건, 같음 | 2.271 |
| t=8s | 0 | 24건, 가장 오래된 주문 51(REQUESTED 3.0초)·52(PENDING 2.9초) | 25건, 51·52에 더해 53(결제 SUCCESS 2.8초) | 2.271 |
| t=10s | 0 | 51 5.0초·52 4.9초 | 51 5.0초·52 4.9초·53 4.8초 | 4.891 |
| t=30s | 0 | 24건, 51 25.0초·52 24.9초, 다음은 정상 주문 2.2초 | 25건, 51 25.0초·52 24.9초·53 24.8초 | 24.913 |
| t=33s 이후 | 0 | 2건 (51·52) | 3건 (51·52·53) | 24.913 → t=35s 29.928 |

- 마지막 상태는 51 `REQUESTED/PAYMENT_PENDING`, 52 `PENDING/PAYMENT_PENDING`, 53 `SUCCESS/PAYMENT_PENDING`이었고, 나머지 298건은 `SUCCESS/CONFIRMED`였다.
- 정상 주문의 최대 경과 시간은 결제 시도 기준 2,186ms, 주문 기준 2,268ms였다. 주문 기준이 더 긴 이유는 정상 주문도 TX B가 `SUCCESS`를 저장한 뒤 TX C가 끝나기 전까지 잠깐 주문 기준에만 잡히기 때문이다(t=3s에 8번이 `SUCCESS` 2.3초로 보였다).

같은 실행에서 세 주문에 남은 로그:

| 주문 | 시간 순서대로 남은 로그 | 마지막 줄이 말하는 것 |
| --- | --- | --- |
| 51 (①) | `PG 요청: op=create` → `PG 응답: op=create ... httpStatus=202 ... PENDING:1000] elapsedMs=106` → `ERROR 결제 결과 저장 실패: orderId=51 step=TX_B:CREATE_RESPONSE` | PG는 접수했는데 그 응답을 저장하지 못했다 |
| 52 (②) | `PG 요청: op=create` → `PG 응답: op=create ... httpStatus=202 ... PENDING:1000] elapsedMs=103` → 이후 줄 없음 | PG 접수까지 확인했고 최종 결과(callback·조회)는 한 번도 오지 않았다 |
| 53 (③) | `PG 요청: op=create` → `PG 응답: ... 202 ... PENDING` → 2초 뒤 `PG 요청: op=lookup transactionKey=TEST-TX-53` → `PG 응답: ... SUCCESS:1000` → `ERROR 결제 결과 저장 실패: orderId=53 step=TX_C` | PG 성공을 저장했는데 주문 반영에서 실패했다 |

- 세 주문 모두 PG 생성 응답을 약 100ms 만에 받았다. 경과 시간이 커진 것은 PG 응답을 기다려서가 아니라, 응답을 받은 뒤 주문이 끝나지 않았기 때문이다.
- PG 경계 로그는 주문 번호(`providerOrderId=LP-ORD-...`)로, 저장 실패 로그는 `orderId=`로만 남는다. 53번의 조회 요청 줄은 거래 키(`transactionKey=TEST-TX-53`)만 있고 주문 번호는 응답 줄의 거래 목록에만 있다. 한 주문의 로그를 모으려면 주문 번호·주문 ID·거래 키로 함께 검색해야 했다.
- 52번은 마지막 줄 뒤에 아무것도 없다는 것이 근거다. 로그만으로는 PG가 아직 처리 중인지 callback이 유실됐는지 가를 수 없어 PG 조회가 필요하다.

주문 기준 gauge(`commerce.payment.orders.unfinished.oldest`)는 5초마다 DB에서 결제를 가장 먼저 시작한 미완료 주문을 찾아, 그 순간의 경과 시간을 저장한다. 위 실행에서 정상 흐름의 gauge 값은 2.182~2.201초로 DB에서 센 최대(2.2초)를 넘지 않았다. 멈춘 주문은 집계 주기만큼 늦게 따라갔다(t=14s에 실제 9.0초, gauge 4.891). 섞은 실행이 끝난 뒤 이어진 정상 실행의 처음 4초 동안 gauge는 앞 실행의 마지막 값 34.944를 보였고, 첫 집계(t=5s)에서 2.182로 바뀌었다. 같은 Spring context에서 DB를 비운 뒤 다음 집계 전까지 이전 값이 남은 것이다.

처음 구현은 시작 시각만 저장하고 경과 시간을 Prometheus가 읽을 때마다 다시 계산했다. 그 실행에서는 정상 흐름의 gauge가 2.6~6.6초를 오르내렸고, 모든 주문이 끝난 뒤에도 5.6초·6.6초가 나왔다가 다음 집계에서 0이 됐다. 이미 끝난 주문의 시작 시각이 다음 집계까지 계속 늙었기 때문이다. 정상 흐름을 실제보다 크게 보이면 이 값을 기준으로 둔 알림이 오탐을 낸다. 그래서 집계 순간의 값을 저장하도록 바꿨다. 대신 멈춘 주문은 최대 집계 주기(5초)만큼 늦게 보인다.

관찰한 것:

- UNKNOWN gauge는 두 실행 내내 0이었다. 섞은 세 건은 모두 `UNKNOWN`이 아니라서 이 지표에는 나타나지 않았다.
- 건수만으로는 구분이 약했다. 주문이 흐르는 동안 정상 실행은 22건, 섞은 실행은 결제 시도 기준 24건·주문 기준 25건이었다. 흐름이 끝난 뒤에야 2건·3건이 남았다.
- 가장 오래된 주문의 경과 시간은 멈춘 주문이 정상 주문의 최대(약 2.2초)를 넘은 다음 관찰 시점(t=8s)부터 DB에서 따로 보였다. gauge로는 다음 집계(t=10s)부터 보였다. 정상 주문은 2.2초를 넘지 않았다.
- 결제 시도 기준으로는 51·52번만 보였다. 53번은 결제 시도가 이미 `SUCCESS`라서 빠졌고, 주문 기준에서만 보였다.
- 정상 주문의 최대 2.2초는 이 조건(PG 결과 확정 2초 가정)에서의 관찰값이다. 운영에서 몇 분 뒤 사람에게 넘길지는 고객이 기다릴 수 있는 시간과 실제 PG 결과 확정 시간이 있어야 정할 수 있어 정하지 않았다.
- 주문이 `FAILED`로 끝난 뒤 PG 거래가 `SUCCESS`로 바뀌는 경우는 두 기준 모두 종결 상태라 경과 시간으로 잡히지 않는다. 이 실험에 넣지 않았고, PG 거래와 내부 결과의 대사가 필요한 사례로 남긴다.

`@Tag("experiment")`라 `check`에서 빠지고 `./gradlew :apps:commerce-api:experimentTest --tests '*PaymentStuckSignalExperimentTest'`로 실행했다. 두 테스트가 통과했다(실패·에러·skip 0). 같은 실행에서 `UnfinishedOrderPaymentGaugeTest`·`UnknownPaymentAttemptGaugeTest`·`LayeredArchitectureTest`가 통과했고 commerce-api Checkstyle main·test 위반은 0건이었다. 첫 실행은 저장 시각을 JDBC `Timestamp`로 읽어 모든 경과 시간이 32,400초(9시간) 크게 나왔다. 엔티티의 생성 시각으로 읽도록 고친 뒤의 결과가 위 값이다. 원문은 실행할 때마다 `apps/commerce-api/build/week10-stuck-signal/`에 새로 쓴다.

## L. 주문 기준 경과 시간 gauge·패널과 runbook 반영

K의 결과로 주문 기준 경과 시간 gauge `commerce.payment.orders.unfinished.oldest`를 추가했다(ADR 0101). `PaymentAttemptJpaRepository.findEarliestPaymentStartByOrderStatus`가 주문이 `PAYMENT_PENDING`인 결제 시도의 가장 이른 생성 시각을 읽는다. Grafana `commerce-api` 대시보드에 이 값의 `max()` 패널을 추가했고, UNKNOWN 패널 제목에 응답 유실만 센다고 적었다.

runbook은 다음을 바꿨다.

- A-1의 `UNKNOWN` 질의와 "`REQUESTED` 1분" 질의를 주문 기준 질의 하나로 바꿨다. 결제 시도 상태 칸으로 어느 경우인지 가른다. 1분 기준은 실험으로 정한 값이 아니었고, 오래된 순서로 보면 필요하지 않다. 질의에서 DB의 현재 시각과 비교하지 않는다.
- A-2에 TX C 실패와 "202 PENDING 뒤 아무 줄 없음"의 로그 모양을 추가하고, 주문 번호·주문 ID·거래 키로 함께 검색하도록 했다.
- 후속 절을 새로 두고 알림 기준 N을 정할 조건과 알림에 넣을 값을 적었다. 알림은 만들지 않았다.

## M. 주문 기준 gauge를 넣은 뒤 전체 회귀

I 이후 운영 코드(`UnfinishedOrderPaymentGauge`, `PaymentAttemptJpaRepository.findEarliestPaymentStartByOrderStatus`)가 바뀌어 같은 명령을 다시 실행했다. JDK 21, colima.

`./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --continue --rerun-tasks` → `BUILD FAILED`, 15분. 실패는 1건이다.

| 모듈 | 테스트 클래스 | 테스트 | 실패 | 에러·skip | Checkstyle (main·test) | ArchUnit |
| --- | ---: | ---: | ---: | --- | --- | --- |
| commerce-api | 112 | 704 | 1 | 0 | 0·0 | `LayeredArchitectureTest` 통과 |
| commerce-streamer | 23 | 91 | 0 | 0 | 0·0 | `LayeredArchitectureTest` 통과 |
| commerce-batch | 10 | 35 | 0 | 0 | 0·0 | 해당 없음 |

- api는 I보다 1건 늘었다(`UnfinishedOrderPaymentGaugeTest`). `PaymentStuckSignalExperimentTest`는 `@Tag("experiment")`라 포함되지 않는다.
- 실패한 테스트는 `BrandRrBulkValidationTest.orderFirstCommitsItsStockReservationBeforeDeletion`이다. 메시지는 `lock wait observation failed`, 원인은 `no database lock wait observed on product:1`이다. 두 트랜잭션 중 하나가 DB 잠금을 기다리는 장면을 정해진 시간 안에 관찰하는 테스트다. I의 실행에서는 통과했다.
- 이 클래스만 다시 세 번 실행했고 세 번 모두 15건이 통과했다. 이번 변경은 결제 gauge와 결제 시도 조회 질의이고 브랜드 삭제·재고 예약 코드는 바꾸지 않았다. 실패 원인은 확인하지 않았다.
- 실행 전 colima의 실행 중 컨테이너는 0개, 실행 직후 3개(종료 중), 결과를 집계한 시점에는 0개였다.
