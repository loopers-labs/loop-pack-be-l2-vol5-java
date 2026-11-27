# 10주차 설계 — health UP인 결제 사고의 관측·진단·복구

과제: [assignment.md](assignment.md), 구현 기록: [implementation-log.md](implementation-log.md), 로그 지도: [log-map.md](log-map.md), 가설과 판정: [d-hypotheses.md](d-hypotheses.md), 대응 절차: [runbook](../runbook/week10.md)

## 1. 사고 범위

PG는 거래를 SUCCESS로 저장했지만 생성 응답이 우리 서버에 오지 않아, 내부 결제 시도가 UNKNOWN으로 남는 경우를 다룬다. 서버 health는 UP이고 에러 응답도 없다.

| 보는 곳 | 상태 |
| --- | --- |
| PG | 거래 SUCCESS |
| 결제 시도 | UNKNOWN, 거래 키 없음 |
| 주문·예약 | PAYMENT_PENDING, RESERVED (재고 차감 유지) |
| health | UP |

B의 단건 실험에서 `SUCCESS`는 PG가 자기 쪽에 저장한 결제 결과다. 가짜 PG의 생성 응답 본문은 여전히 `PENDING`이다. 응답을 보내기 전에 거래를 `SUCCESS`로 저장하고 첫 응답만 보류해야, 이미 결제가 끝났는데 우리 서비스가 접수 응답조차 받지 못한 상황을 만들 수 있다. 내부에 거래 키가 없으므로 저장된 PG 주문 번호로 읽기 전용 조회를 한다. 이 조회는 주문을 확정하지 않아 같은 주문의 PG 결과와 복구 전 DB 상태를 함께 관찰할 수 있다.

같은 DB 상태를 만드는 다른 원인이 있다. 조회로 SUCCESS를 받았지만 결과 저장 TX(TX B)가 rollback되면 결제 시도는 UNKNOWN 그대로이고 결과 이력도 함께 사라진다. 두 원인은 DB로 구분되지 않으므로 경계 로그로 구분한다.

생성 응답(202·PENDING)을 받은 뒤 그 응답을 저장하는 TX B가 실패하면 결과가 다르다. 결제 시도는 PG 호출 전에 TX A가 저장한 `REQUESTED`로 남는다. DB로는 구분되지만 UNKNOWN 집계에는 잡히지 않는다(3절).

진단 조회 결과는 운영자가 복구를 진행할 근거다. 서비스에는 조회 결과를 직접 넘기지 않고 주문을 소유한 사용자가 `POST /api/v1/orders/{orderId}/payment/reconcile`을 호출한다. 이 사례에서는 내부 거래 키가 없어 서비스가 저장된 PG 주문 번호로 다시 GET을 보낸다. 서비스가 직접 확인한 결과를 결제 시도에 저장하고, 주문과 예약에 반영한다. 첫 reconcile 한 번을 검증 단위로 삼으면 그 호출이 만든 DB 변화와 PG 생성 횟수 차이를 같은 주문에서 비교할 수 있다. [기존 거래 조회 정책](../adr/0028-test-pg-http-boundary-and-no-create-retry.md) · [결제 결과 저장과 주문 반영](../adr/0032-persist-payment-result-before-order-application.md)

응답 유실은 read timeout(1초)보다 늦은 응답으로 재현한다. 응답 유실의 대표 형태이고 사용자 대기가 가장 길다. 응답 없이 연결이 바로 끊기는 경우도 우리 코드에서는 같은 `UNKNOWN(TEMPORARY_FAILURE)`이다. PG 장애만이 아니라 중간 네트워크 장비가 끊어도 생기며, 그때도 PG에 거래가 있을 수 있다.

여러 주문의 응답 유실을 재현할 때는 가짜 PG의 `holdCreateResponseFor(providerOrderId)`로 대상을 지정한다. 거래 저장 뒤 생성 응답만 보류하므로, 같은 시점에 들어오는 다른 주문은 평소처럼 202·PENDING을 받을 수 있다. `releaseCreateResponseFor(providerOrderId)`로 해당 주문의 보류를 풀어도 PG 거래와 호출 횟수는 유지된다. [4절 실험 도구](#4-실험-도구--테스트용-pg)

## 2. PG 경계 로그

경계 로그 필드는 [ADR 0096](../adr/0096-log-pg-boundary-and-failed-result-save.md), 받은 응답의 HTTP 상태 표기는 [ADR 0097](../adr/0097-log-pg-response-http-status.md), 거래별 주문 번호는 [ADR 0098](../adr/0098-log-pg-returned-order-number-per-transaction.md)을 따른다.

장면별 로그 예시와 전체 순서는 [결제 로그 지도](log-map.md)에 모았다.

`TestPgPaymentAdapter`는 PG 호출마다 요청과 결과를 한 줄씩 남긴다. 카드 정보는 남기지 않는다.

```
PG 요청: op=create providerOrderId=LP-ORD-0000000001 amount=3000
PG 응답: op=create providerOrderId=LP-ORD-0000000001 httpStatus=202 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:PENDING:3000] elapsedMs=<ms>
PG 응답 없음: op=create providerOrderId=LP-ORD-0000000001 result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException elapsedMs=1129
PG 요청: op=lookup providerOrderId=LP-ORD-0000000001
PG 응답: op=lookup providerOrderId=LP-ORD-0000000001 httpStatus=200 result=OBSERVED transactions=[TEST-TX-1:LP-ORD-0000000001:SUCCESS:3000] elapsedMs=25
```

`OrderFacade`는 TX B·TX C 호출이 예외로 끝나면 남기고 예외를 그대로 던진다.

```
결제 결과 저장 실패: orderId=1 step=TX_B:RECONCILE error=IllegalStateException
```

reconcile·callback·복구 배치의 조회 결과가 UNKNOWN이면 `OrderFacade`는 저장 없이 끝내면서 이유를 남긴다. 회로가 열린 조회는 PG 어댑터에 닿지 않아, 이 줄이 그 주문의 유일한 기록이다. [ADR 0099](../adr/0099-log-deferred-payment-result-check.md)

```
결제 결과 확인 보류: orderId=1 source=RECONCILE cause=CIRCUIT_OPEN
```

| 원인 | 남는 로그 |
| --- | --- |
| 생성 422의 거절 코드·거래 미생성 표시·주문 번호·금액이 계약과 일치 | INFO `PG 응답: ... httpStatus=422 result=REJECTED` |
| 생성 422의 본문이 비어 거래 미생성을 입증할 수 없음 | WARN `PG 응답: ... httpStatus=422 result=UNKNOWN cause=UNEXPECTED_RESPONSE` |
| PG가 HTTP 503을 반환해 거래 상태를 확인할 수 없음 | `PG 응답: ... httpStatus=503 result=UNKNOWN cause=TEMPORARY_FAILURE error=ServiceUnavailable` |
| PG 조회 응답을 기다리다 read timeout 발생 | `PG 응답 없음: op=lookup ... result=UNKNOWN cause=TEMPORARY_FAILURE error=SocketTimeoutException` |
| 거래 키 조회에서 200을 받았지만 본문이 `not-json` | `PG 응답: op=lookup ... httpStatus=200 result=UNKNOWN cause=UNEXPECTED_RESPONSE error=JsonParseException` |
| 주문 번호 조회에서 200을 받았지만 본문이 거래 목록 대신 객체 | `PG 응답: op=lookup ... httpStatus=200 result=UNKNOWN cause=UNEXPECTED_RESPONSE error=IllegalArgumentException` |
| 생성 응답을 받지 못함 | `PG 요청: op=create` 뒤 `PG 응답 없음: ... result=UNKNOWN` |
| 생성 응답이 202가 아니거나 본문이 PENDING 거래가 아님 | 응답을 받았지만 현재는 HTTP 상태 없이 `PG 응답 없음: op=create ... result=UNKNOWN cause=UNEXPECTED_RESPONSE`로 남는다 (결정 대기) |
| 조회로 SUCCESS를 받은 뒤 TX B 실패 | `PG 응답: op=lookup ... SUCCESS`와 `결제 결과 저장 실패: ... step=TX_B:<source>` |
| PG가 아직 처리 중 | `PG 응답: op=lookup ... PENDING` |

`error`는 가장 안쪽 예외 이름이다. read timeout은 `SocketTimeoutException`, 응답 없이 끊긴 연결은 `NoHttpResponseException`으로 남는다.

422의 HTTP 상태와 거절 근거를 기록하는 결정은 [ADR 0097](../adr/0097-log-pg-response-http-status.md#2-결제-생성-422-응답)에 남겼다.

정상 생성 로그의 202는 PG의 요청 접수를, PENDING은 생성 응답에 담긴 거래 상태를 나타낸다. [ADR 0097](../adr/0097-log-pg-response-http-status.md#3-정상-생성-응답-202)

### 정상 조회 결과를 한 줄로 남기는 이유

주문이 계속 ‘결제 확인 중’일 때, PG가 조회 시점에도 처리 중이었는지, 우리 서버가 이미 SUCCESS를 읽고도 저장에 실패했는지 구분해야 한다. 두 경우 모두 HTTP 200을 받을 수 있다. 나중에 같은 거래를 다시 조회하면 그때의 상태만 돌아오므로, 어댑터는 처음 읽은 거래 키·주문 번호·상태·금액을 그 조회의 결과 로그에 함께 남긴다. 실제 TX B 실패 테스트에서는 같은 주문의 조회 결과 SUCCESS와 저장 실패 기록을 연결해 멈춘 위치를 확인했다. [구현 기록](implementation-log.md#2-1-pg-경계-로그-adr-0096)

거래 키 조회와 주문 번호 조회 모두 기존 결과 줄에 실제 `httpStatus=200`을 남긴다. 조회 키와 반환 거래의 키·주문 번호·상태·금액을 한 줄에 두면 여러 번 조회한 주문도 호출별로 읽을 수 있다. [ADR 0097](../adr/0097-log-pg-response-http-status.md#4-정상-조회-응답-200)

로그 앞의 `providerOrderId`는 요청에 쓴 값이고, 거래 목록의 각 항목(`거래 키:주문 번호:상태:금액`)에 있는 주문 번호는 PG 응답 본문의 값이다. 주문 번호가 다르면 TX B가 `ORDER_MISMATCH` 이력을 남기지만, TX B가 rollback되면 그 이력도 사라진다. 그래서 PG가 돌려준 주문 번호를 로그에 남겨 요청한 주문과 한 줄에서 대조한다. [ADR 0098](../adr/0098-log-pg-returned-order-number-per-transaction.md)

## 3. 현재 결과를 모르는 결제는 몇 건인가

`PaymentAttemptJpaRepository.countByStatus(UNKNOWN)`으로 DB 전체에서 현재 상태가 `UNKNOWN`인 결제 시도 수를 읽는다. 주문 번호를 몰라도 미확인 결제가 얼마나 남았는지 알기 위해서다. 생성 요청이 정상 접수된 주문도 `PENDING`을 거치므로 조회 조건은 `UNKNOWN` 하나다. 복구하면 같은 결제 시도가 `SUCCESS`로 바뀌어 집계에서 빠진다.

Prometheus 내보내기가 활성화된 commerce-api 인스턴스는 이전 집계를 마치고 기본 5초 뒤 다시 DB를 센다. `/actuator/prometheus`의 `commerce_payment_attempts_unknown`은 마지막 집계 결과다. 첫 집계 전이나 집계 실패 시에는 `NaN`으로 표시한다. 집계 실패는 WARN 한 줄로 남기고 예외를 다시 던지지 않는다. 로컬 Prometheus의 수집 주기도 5초여서 DB가 바뀐 시각, endpoint에 반영된 시각, Prometheus가 수집한 시각은 다를 수 있다. [ADR 0100](../adr/0100-sample-unknown-payment-attempt-count.md)

Grafana `commerce-api` 대시보드는 `max(commerce_payment_attempts_unknown{application="commerce-api"})`와 아래 주문 기준 경과 시간 `max(commerce_payment_orders_unfinished_oldest_seconds{application="commerce-api"})`를 그린다. 인스턴스마다 같은 DB 전체를 세므로 여러 인스턴스를 `sum()`으로 합치면 값이 부푼다. UNKNOWN 패널 제목에는 응답 유실만 센다고 적었다. [대시보드](../../docker/grafana/provisioning/dashboards/commerce-api.json)

### UNKNOWN 집계가 세지 않는 경우

생성 응답을 받고 저장에 실패하거나 PG 호출 도중 서버가 멈추면 결제 시도는 `REQUESTED`로 남는다. 이 집계는 이 상태를 세지 않는다. 생성 응답 저장 실패는 실행으로 확인했다(UNKNOWN 0건·REQUESTED 1건). `PaymentBoundaryLogTest.createResponseSaveFailureLeavesRequestedOutsideUnknownCount` 이 주문은 아래 주문 기준 경과 시간에 잡힌다.

주문 반영(TX C)만 실패해도 이 집계에 잡히지 않는다. TX B가 저장한 결제 시도는 `SUCCESS`이고 주문만 `PAYMENT_PENDING`·예약 `RESERVED`로 남기 때문이다. 실행에서 UNKNOWN 0건·REQUESTED 0건이었으므로 runbook의 `REQUESTED` 질의로도 찾을 수 없다. 같은 주문을 reconcile하면 PG 조회 없이 TX C만 다시 실행해 확정됐다. `PaymentRecoveryBatchTest.orderApplyFailureLeavesSuccessAttemptOutsideCountsAndRetriesWithoutLookup` 이 주문도 아래 주문 기준 경과 시간에 잡힌다.

### 결제를 시작한 뒤 끝나지 않은 주문

`commerce.payment.orders.unfinished.oldest`(초)는 주문이 `PAYMENT_PENDING`인 결제 시도 중 생성 시각이 가장 이른 것을 찾아, 집계한 순간의 경과 시간을 저장한다. 없으면 0, 첫 집계 전과 실패 뒤에는 `NaN`이다. 집계 주기와 실패 처리는 UNKNOWN gauge와 같다. [ADR 0101](../adr/0101-sample-oldest-unfinished-order-elapsed-time.md)

결제 시도 상태가 아니라 주문 상태로 세는 이유는 실험 결과다. 정상 주문 흐름에 멈춘 주문 세 건(`REQUESTED`, 끝나지 않는 `PENDING`, TX C만 실패한 `SUCCESS`)을 섞자 UNKNOWN gauge는 내내 0이었다. 결제 시도 상태로 세면 두 건, 주문 상태로 세면 세 건이 보였다. 건수는 정상 흐름에서도 22건이라 구분이 약했고, 경과 시간은 정상 주문이 2.2초를 넘지 않는 동안 멈춘 주문만 늘어났다. [실험 기록](implementation-log.md#k-멈춘-주문을-결제-시도-기준과-주문-기준으로-세어-비교)

- 출발점은 결제 시도 생성(결제 시작)이다. 주문 생성부터 재면 주문해 두고 나중에 결제한 정상 주문도 오래된 것처럼 보인다.
- 경과 시간은 집계 순간에 계산한다. 읽을 때마다 다시 계산하면 이미 끝난 주문의 시작 시각이 다음 집계까지 계속 늙어 정상 흐름이 최대 6.6초로 보였다. 대신 멈춘 주문은 최대 집계 주기만큼 늦게 보인다.
- 정상 주문도 TX B 저장 뒤 TX C가 끝나기 전까지 잠깐 포함된다.
- 몇 초부터 사람이 볼지는 정하지 않았다. 실험의 2.2초는 PG가 2초 뒤 결과를 확정한다고 가정한 관찰값이다.
- 어느 주문인지는 지표에 없다. runbook의 주문 기준 질의로 찾고, 어디서 멈췄는지는 주문 번호·주문 ID·거래 키로 로그를 찾는다. 실험에서는 세 주문 모두 마지막 로그 줄이 멈춘 단계를 보여 줬다.

## 4. 실험 도구 — 테스트용 PG

운영 코드가 아니라 사고를 재현하고 결과를 세는 테스트용 PG(`FakePaymentServer`)에 대한 선택이다.

### 주문 번호로 생성 응답 보류

10건 중 미리 고른 3건을 복구 전후에 비교하려면 요청 도착 순서가 바뀌어도 장애 대상은 같아야 한다. 두 주문 실험에서 A를 대상으로 정하고 B의 결제를 먼저 요청했더니 기존 `holdNextCreateResponse()`가 B의 응답을 보류했다. DB에 B가 UNKNOWN, A가 PENDING으로 남았다.

테스트는 결제 요청 전에 가짜 PG에 대상 `providerOrderId`를 지정한다. PG는 거래를 저장한 뒤 해당 주문의 생성 응답만 기다린다. 주문별 보류를 풀면 PG 서버를 계속 쓰면서 거래와 호출 기록도 보존할 수 있다. 지금 보류 중인 주문 번호는 `heldCreateOrders()`로 확인해, 실험 단계마다 보류가 남아 있지 않은지 본다. 보류 중인 요청은 최대 5초를 기다리고, 그때까지 해제되지 않으면 응답 없이 종료한다. 이후 요청에 대한 보류 설정은 명시적으로 해제한다.

단건 실험의 “다음 응답 한 건 보류”는 그대로 쓸 수 있다. 결제 시도의 UNKNOWN을 DB에 직접 써 넣으면 생성 HTTP 응답 timeout이 실제로 그 상태를 만드는지 확인할 수 없으므로, 실험은 PG 응답만 제어하고 우리 서비스가 저장한 상태를 읽는다.

### 주문별 결제 생성 요청 횟수

실험의 결제 생성 횟수는 가짜 PG가 받은 POST를 주문 번호별로 센다. 같은 주문을 두 번 요청하고 두 번째가 거절되면 거래는 한 건이어도 호출은 두 번이다. 두 주문 실험에서 `createCallsByOrder()`로 받은 전체 집계가 기대한 주문 두 건에 각각 1회인지 확인한다.

Week 10 실험에서는 고정한 각 주문이 PG에 결제 생성을 한 번씩 요청했는지 확인한다. A와 B가 각각 한 번 호출한 경우와 A만 두 번 호출한 경우는 모두 총 2회다. PG가 두 번째 요청에 422를 반환한 실험에서는 거래 한 건만 남아 거래 목록으로도 중복 시도 횟수를 알 수 없었다. `FakePaymentServerTest.rejectedSecondCreateCountsEvenWhenTheTotalMatchesTwoOrders`

가짜 PG는 응답이나 거래 저장에 앞서 수신한 요청을 기록한다. 검사할 때 이 기록의 스냅샷에서 POST 본문의 PG 주문 번호별 횟수를 구한다. 진단·복구 GET은 집계에서 빠진다. 별도 카운터의 초기화나 동시 갱신을 관리할 필요가 없고, 응답을 거절하거나 보류해도 해당 POST가 남는다.

주문 실험은 실제 횟수를 출력하고 대상 주문별 기대 횟수 1과 맵 전체를 대조한다. 요청 누락, 중복, 대상 외 주문이 있으면 이 검사에서 실패한다. PG 응답 정책은 해당 실험의 설정을 따른다. 중복 대조군의 422는 테스트가 두 번째 응답에 지정한 값이다.

#### 검증

`FakePaymentServerTest.rejectedSecondCreateCountsEvenWhenTheTotalMatchesTwoOrders`에서 같은 주문의 두 번째 요청에 422를 지정하자 거래는 1건이었지만 집계는 `{LP-ORD-A=2}`였다. `PaymentIncidentSingleOrderExperimentTest.onlySelectedOrderTimesOutWhenAnotherOrderIsConfirmedFirst`에서는 정상 주문과 응답 보류 대상 주문이 각각 1회였다. [구현 기록](implementation-log.md#d-주문별-결제-생성-요청-횟수)

## 5. 사고 실험 구조 (D·E)

`PaymentIncidentExperimentTest` 한 테스트에서 PG와 DB를 단계 사이에 초기화하지 않고 진행한다. 같은 20건을 사고 → 진단 → 복구 → 랭킹까지 끝까지 대사하기 위해서다. 상품 하나(재고 30)를 20건이 1개씩 산다.

```
BASELINE  정상 10건 → callback으로 확정                     health UP, gauge 0
FAULT     새 10건 중 3·6·9번째만 생성 응답 보류              health UP, gauge 3
진단      세 주문을 주문 번호로 읽기 전용 조회 → 가설 판정
E-1       확정된 17건 발행(첫 발행은 PUBLISHED 저장 전에 멈춤) → r1 공개(점수 17)
RECOVERY  같은 3건만 본인 reconcile                         gauge 0, 생성 증가 0
E-2·E-3   복구한 3건 발행·소비 → r2 공개(점수 20), r1은 17 그대로
```

- 장애 대상은 실제 주문 ID로 미리 정한다. 정상 주문 사이에 섞어야 요청 순서와 상관없이 지정한 주문만 장애가 되는지 확인할 수 있다.
- 정상 주문은 기존 callback API로 확정한다. 가짜 PG는 callback을 보내지 않고, 서비스는 callback을 받으면 PG를 다시 조회한다. 자동 복구 스케줄러는 설정이 없어 만들어지지 않으므로, 장애 주문은 수동 reconcile 전에 복구되지 않는다.
- 전체 health는 DB·Redis 연결까지 본다. 실제 환경처럼 Redis 컨테이너를 띄워야 health가 앱 상태를 나타낸다.
- 랭킹은 Kafka 테스트 브로커, 실제 commerce-streamer·commerce-batch(별도 JVM), 랭킹 API로 확인한다. 복구 전 r1을 공개하고 복구 뒤 r2를 공개해, 이미 공개한 snapshot을 바꾸지 않고 새 revision으로 복구 결과를 설명한다.
- Week 10의 주문 확정과 배치 입력을 고정된 UTC 주(2020-01-06 시작)에 둔다. Week 9와 같은 현재 주를 쓰자 랭킹 행을 정리해도 Spring Batch의 완료 이력이 남아 같은 r1 실행이 거절됐다. 업무 시각만 고정하고 health·지표·로그 관찰에는 실제 시계를 쓴다. [같은 Gradle 실행에서 검증한 결과](measurements/de-lifecycle-02/regression.txt)
- 실행마다 `build/incident-evidence/d-lifecycle/run-*/`를 새로 만든다. 실험 본문에서 실패하면 이미 쓴 관찰 뒤에 같은 폴더의 `failure.txt`로 단계와 예외를 남긴다. 다음 실행은 별도 폴더에 기록한다. 제출할 실행만 복사한다. [D+E 원문](measurements/de-lifecycle-02/)
- 결제와 별개인 사용자 데이터를 검증하기 위해 실제 포인트 5,000과 발급 쿠폰 1장을 준비한다. 테스트의 DB_ONLY 발급 경로로 본인 쿠폰을 만든 뒤 잔액과 쿠폰 목록을 첫 reconcile 직전과 반복 호출 뒤에 비교한다. Redis 수용 성능은 이 실험의 측정 대상에서 제외한다.
- 구매 활동은 주문 ID·상품 ID·수량·최초 확정 시각을 주문 원본과 행 단위로 맞춘다. 잘못된 상품이나 수량이 저장돼도 행 수는 같을 수 있기 때문이다. 별도로 소비 완료 기록이 주문별 1건인지 확인한다. [구매 활동과 공개본](measurements/de-lifecycle-02/ranking.txt)

세 주문의 생성 timeout, 진단 시점의 같은 금액 SUCCESS 거래 한 건, DB의 UNKNOWN·RESERVED를 함께 대조해 조치했다. 로그를 찾지 못한 경우에는 원인을 미확정으로 둔다. [가설과 판정](d-hypotheses.md)

## 6. 요청을 잇는 키

처음 결제 요청과 이후 reconcile·callback·복구 배치는 서로 다른 HTTP 요청이어서 traceId가 다르다. 첫 요청의 traceId를 저장해 잇지 않고, 업무 키 `orderId` → `providerOrderId`(`LP-ORD-...`) → `transactionKey`로 잇는다. D+E 실행 원문에서 주문 13의 PG 경계 로그를 이 키로 모두 찾았다. 찾지 못한 것은 reconcile 요청 수신, callback 수신, Outbox 발행·소비처럼 앱이 성공 시 로그를 남기지 않는 지점이었다. 이 사실들은 DB(`payment_event`의 출처와 시각, `order_outbox` 상태, 구매 활동)에 있고, traceId를 저장해도 붙일 로그 줄이 없다. 요청 안의 traceId는 지금처럼 로그에 남긴다.

### 자동 복구 중 무엇을 기록하고, 운영자는 언제 주문을 열어볼까?

콜백이나 기존 복구 배치는 주문 번호·금액이 일치하는 종결 거래를 확인하면 그 결과를 반영한다. 조회가 비었거나 결과가 불확실하면 주문과 예약을 보존한다. 주문 담당자가 확인하는 동안에도 스케줄러가 활성화돼 있으면 미완료 주문은 다음 배치의 후보로 남는다.

| 운영자가 알고 싶은 것 | 관측 시점과 근거 |
| --- | --- |
| 지금 결과를 모르는 결제 시도가 얼마나 쌓이는가? | 현재 Grafana에서 UNKNOWN 건수의 추세를 본다. 자동 처리 전부터 수집하므로 갑자기 미확인이 늘어나는 시점도 보인다. |
| 자동 처리가 어디까지 진행됐는가? | **대시보드 제안.** 마지막 배치 완료 시각과 그 회차의 종결·미완료·처리 예외 건수를 함께 표시한다. 미실행과 미완료 0건을 구분한다. 같은 주문이 다음 회차에 다시 잡힐 수 있어 회차별 미완료 수를 누적해 주문 수로 해석하면 안 된다. |
| 남은 주문에서 무엇을 조사할까? | PG 요청·응답과 저장 실패·조회 보류는 발생 시점에 기록한다. 개별 주문을 조사할 때 주문 ID로 경계 로그, 현재 DB 상태, 마지막 배치 조회 이력을 맞춘다. 대시보드에는 집계를 표시하고 주문 ID는 검색에 쓴다. |

H 실험에서는 네 주문 중 세 건을 사람이 개입하기 전에 종결했다. UNKNOWN 집계가 0으로 내려간 뒤에도 주문 4는 REQUESTED였고, 생성 10분 이후 배치 조회에서도 거래가 0건이었다. 이 주문을 열어볼 근거는 현재 대기 상태와 BATCH 이력의 NO_TRANSACTION_YET이다. 미완료 수만으로 개별 주문의 원인이나 결제 실패 여부까지 판단하지 않는다. 배치 결과 패널은 이 관찰에서 도출한 설계 제안이며 이번 주 범위는 판단 기준을 문서로 남기는 데까지다. `PaymentRecoveryBatchTest.recoversThreeLostResponsesAndLeavesOneUnsentOrderForReview`

#### 어느 주문부터 사람이 알아야 할까?

- **자동으로 끝난 주문**은 개별 확인을 요청할 필요가 없다. H 실험에서 최초 응답을 놓친 세 주문은 기존 거래와 주문·예약까지 일치한 상태로 끝났다. 최초 오류 로그는 보존하고 빈도는 집계로 관찰한다.
- **안전하게 기다릴 수 있는 주문**은 예약을 유지한다. 거래가 처리 중이고 다음 콜백이나 실제 가동 중인 배치에서 재확인할 수 있다면, 한 회차의 미완료만으로 사람이 주문을 열 이유는 없다. 이런 주문이 쌓이거나 오래 남는지는 대시보드로 볼 대상이다. PENDING·일시적 조회 장애의 인계 기한은 아직 정하지 않았다.
- **금전 판단에 필요한 근거가 충돌하거나 정해 둔 확인 기준을 넘긴 주문**은 사람이 본다. 주문 금액이 다르거나 거래가 두 건이면 처음 발견한 시점부터 조사한다. 조회 0건에는 별도 기준이 있다. H 실험의 4번처럼 생성 10분이 지난 뒤에도 거래가 없으면 기존 runbook에 따라 확인한다.

H의 네 주문은 ‘성공 거래를 찾음’과 ‘조회했지만 거래가 없음’ 두 조건만 비교했다. 아래는 현재 코드와 기존 테스트를 기준으로 나눈 운영 판단이다. 배치의 세 집계값만으로는 각 행을 판별할 수 없다.

| 같은 주문에서 관찰한 것 | 배치 한 회차의 분류 | 어떤 근거로 다음 행동을 정하나 |
| --- | --- | --- |
| PG 거래가 하나이고 주문·금액·키가 일치하며 결과가 종결됨 | 주문과 예약 반영까지 끝나면 finished | SUCCESS는 주문 확정·예약 소비, FAILED는 결제 실패·예약 해제까지 확인한다. 둘 다 배치가 마친 주문이다. |
| 정상 조회에서 일치하는 거래 한 건이 PENDING | unfinished | PG에 거래가 있고 처리 중이다. 예약을 유지하고 다음 알림이나 조회 결과를 기다린다. |
| 정상 조회 응답에 거래가 0건 | unfinished | payment_event의 NO_TRANSACTION_YET와 시도 나이를 본다. 생성 10분 뒤에도 0건인 주문은 runbook에 따라 사람이 확인한다. |
| 조회가 timeout·5xx로 끝났거나 회로가 열림 | unfinished | 거래 수 자체를 모른다. 보류 로그의 source·cause와 해당 시도의 PG 요청·응답을 본다. 회로가 열렸다면 PG 호출을 건너뛸 수 있다. |
| 복수 거래, 주문 번호·금액·기존 키 불일치 | 미종결 주문이면 unfinished | CONFLICT 이력과 사유를 대조한다. 적용할 거래를 임의로 고르지 않고 사람이 조사한다. |
| 확인한 결과를 DB에 저장하거나 주문에 적용하는 중 예외 | failed | 저장 실패 로그의 TX B·TX C와 현재 DB 상태를 맞춰 어디부터 재개할지 정한다. 이 숫자는 배치 처리 예외 건수다. |

기존 배치 테스트에는 PENDING·빈 조회·회로 열림·처리 예외가 있고, 충돌 이력은 주문 결제 통합 테스트에 있다. 개입 경계를 추가 검증한다면 같은 unfinished 중에서도 다음 자동 조회에서 결론이 나는 주문과, 거래 금액이 달라 자동 적용을 보류해야 하는 주문을 대조한다. 조회 결과나 로그가 누락됐으면 원인을 미확정으로 둔다.

## 7. 검증 범위와 남은 위험

| 기능 | 확인한 것 | 확인 방법 |
| --- | --- | --- |
| PG 경계 로그 | 5xx·422·202·조회 200·조회 본문 오류·timeout·연결 끊김이 서로 다른 줄로 남고 카드번호가 없다 | `PaymentProviderContractTest`, `PaymentBoundaryLogTest` |
| 응답 유실과 저장 실패 구분 | 같은 주문의 응답 수신과 TX B 예외를 연결한다. 생성 timeout은 별도의 응답 대기 예외로 확인한다 | `PaymentBoundaryLogTest` |
| 결과 확인 보류 로그 | 회로 열림·조회 timeout에서 주문 ID와 이유가 남는다 | `PaymentBoundaryLogTest` |
| UNKNOWN gauge | DB 집계와 endpoint 값이 함께 0 → 3 → 0으로 바뀐다. 집계 실패는 `NaN`과 WARN 한 줄이다 | D 실험, `UnknownPaymentAttemptGaugeTest`, 로컬 Prometheus·Grafana |
| 주문 기준 미완료 경과 시간 gauge | 정상 흐름 2.182~2.201초(DB 최대 2.2초), 멈춘 세 주문을 집계 주기만큼 늦게 따라감, 집계 실패는 `NaN`과 WARN 한 줄 | 신호 비교 실험(implementation-log K), `UnfinishedOrderPaymentGaugeTest` |
| 사고 재현과 진단 | health UP인 채로 지정한 3건만 UNKNOWN, 가설 판정 | D 실험 |
| 같은 주문 복구 | 같은 3건만 확정, 생성 증가 0, 거래 키 동일, 재고 추가 차감 없음, 포인트와 본인 쿠폰 목록 보존, 다른 사용자 거절, 반복 호출 수렴 | D 실험 |
| 복구 배치 한 차례 뒤 남은 주문 | 생성 응답 유실 3건은 기존 PG 거래로 종결, 거래가 없는 REQUESTED 1건은 예약 유지. UNKNOWN 집계와 배치 미완료 건수는 서로 다른 질문에 답한다 | `PaymentRecoveryBatchTest.recoversThreeLostResponsesAndLeavesOneUnsentOrderForReview` |
| 후속 결과 | 구매 활동과 주문 항목의 상품·수량·확정 시각 일치, 재발행으로 Kafka 레코드가 더 많아도 활동은 1번, 새 revision 점수 20·1위, 이전 공개본 유지 | D+E 실험 |

| 남은 위험 | 상태 |
| --- | --- |
| `REQUESTED`에 멈춘 결제 시도는 UNKNOWN gauge에 보이지 않는다 | 생성 응답 저장 실패는 실행으로 확인, PG 호출 중 서버 중단은 실행하지 않음. 주문 기준 경과 시간과 runbook의 주문 기준 질의로 찾는다 |
| 주문 반영(TX C)만 실패한 주문은 UNKNOWN gauge와 결제 시도 상태 기준 집계에 보이지 않는다 | 실행으로 확인. 결제 시도 `SUCCESS`·주문 `PAYMENT_PENDING`. 주문 기준 경과 시간에는 보인다 |
| 주문 기준 경과 시간을 몇 초부터 사람이 볼지, 알림 기준 | 정하지 않음. 실제 PG 결과 확정 시간, 고객 대기 허용 시간, 복구 배치를 켤지(켜면 10분 대상 기준 + 1분 주기)가 필요하다 |
| `orders.status`에 인덱스가 없다 | 주문 기준 gauge가 집계마다 주문 테이블을 훑는다. 행 수가 늘면 확인이 필요하다 |
| 주문이 `FAILED`로 끝난 뒤 PG 거래가 `SUCCESS`로 바뀌면 callback·reconcile·복구 배치 모두 다시 조회하지 않는다 | 실행으로 확인(이력 2건 유지, 배치 대상 0). 이 경우의 절차는 정하지 않음. `PaymentRecoveryBatchTest.finishedFailedOrderIsNotRecheckedWhenPgLaterSaysSuccess` |
| 생성 응답이 202가 아니거나 본문이 PENDING 거래가 아니면 HTTP 상태 없이 `PG 응답 없음`으로 남는다 | 결정 대기 |
| consumer가 멈추면 Outbox는 PUBLISHED인데 구매 활동이 없고, 결제 gauge·health로는 보이지 않는다 | 실행하지 않음. Kafka consumer lag와 DB 대사로 알아챌 수 있다 |
| Kafka 메시지에 trace 헤더를 싣는지 | 확인하지 않음 |
| 생성 응답 timeout 1초가 적절한지 | 실제 PG 응답 분포로 검증하지 않음. 판단에 쓸 관측은 `commerce.pg.call`의 생성 호출 시간 분포와 결과별 비율. 통제 실험에서는 주입한 지연이 결과를 정해 timeout 비교 실험을 하지 않음 |
| 금액·주문 번호 충돌처럼 사람이 결정해야 하는 주문도 복구 배치가 돌 때마다 다시 조회하고 같은 충돌 이력·WARN을 남긴다 | 두 회차 실행에서 충돌 이력 2건을 확인. 배치에서 제외하거나 한 번만 알리는 방식은 정하지 않음 |
| 결제 결과 저장 뒤 주문 확정(TX C)만 실패(③-b), 주 마지막 날 UNKNOWN이 다음 주에 복구되는 경우 | D+E에서는 주입하지 않았다. 기존 결제 결과 반영·주간 배치 테스트를 별도로 재실행했다. [검증 범위](measurements/de-lifecycle-02/regression.txt) |
| 실제 외부 PG, 실계정·실카드, 여러 서버 동시 실행, 부하, 운영 배포 환경의 health·지표 수집 | 범위 밖 |

### 기능별 회귀 범위

커밋한 코드로 실행한 최신 전체 회귀(`commerce-api`·`commerce-streamer`·`commerce-batch`의 `check`)에서 830건이 모두 통과했다(실패·에러·skip 0, Checkstyle 위반 0, ArchUnit 통과). [실행 기록](implementation-log.md#n-커밋-뒤-전체-회귀-재실행) 그 전 실행에서는 `BrandRrBulkValidationTest.orderFirstCommitsItsStockReservationBeforeDeletion` 1건이 DB 잠금 대기를 관찰하지 못해 실패했고, 원인은 확인하지 않았다. [이전 실행](implementation-log.md#m-주문-기준-gauge를-넣은-뒤-전체-회귀) 아래는 기능별로 이 실행에 포함된 대표 테스트다. 각 테스트가 검증하는 범위는 테스트 이름과 이전 주차 문서 기준이며, 이번 주에 테스트 내용을 다시 대조하지 않았다.

| 기능 | 이 실행에서 통과한 대표 테스트 | 확인하지 않은 것 |
| --- | --- | --- |
| 상품·브랜드·좋아요·관리자 | `BrandRemovalTransactionTest`, `ProductV1ControllerTest`, `LikeConcurrencyTest`, `PricePageCacheIntegrationTest`, `AdminBoundaryIntegrationTest` | 운영 트래픽에서의 캐시·DB 부하 |
| 포인트·주문·PG | `OrderPaymentIntegrationTest`, `PaymentResultApplicationTest`, `PaymentProviderContractTest`, `PaymentRecoveryBatchTest`, `PointConcurrencyTest` | 실제 PG의 응답 시간·거래 반영 지연 |
| 이벤트·Outbox | `OrderConfirmationOutboxAtomicityTest`, `OrderOutboxRecoveryTest`, `OutboxDeliveryE2ETest`, streamer `PurchaseActivityRecorderTest`·`OrderConfirmedKafkaListenerIntegrationTest` | consumer가 멈춘 사고 (위 남은 위험) |
| 쿠폰 | `CouponQueueIssueIntegrationTest`, `CouponRedisIssueIntegrationTest`, `CouponIssueGateTwoServersTest`, `CouponClosedCampaignIntegrationTest` | 실제 캠페인 부하의 대기 시간·처리량 |
| 랭킹 | batch `RankingJobRestartTest`·`WeeklyRankingPublishTest`·`WeeklySnapshotFreezeTest`, api `WeeklyRankingApiTest`·`WeeklyRankingFlowE2ETest` | 운영 규모 한 주 재계산 시간 |
| 이번 사고 | `PaymentIncidentExperimentTest`(D+E), `PaymentIncidentSingleOrderExperimentTest`, `PaymentBoundaryLogTest`, `UnknownPaymentAttemptGaugeTest`, `UnfinishedOrderPaymentGaugeTest` | 위 남은 위험 표 |
