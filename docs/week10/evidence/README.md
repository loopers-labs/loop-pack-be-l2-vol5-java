# 결제 장애 복구 증거

2026년 11월 24일 로컬 모의 PG가 **22·25·28번 주문을 각각 7,000원씩 승인**했지만, 쇼핑몰은 응답을 받는 도중 연결이 끊겨 세 결제를 미확인으로 남겼다. 기존 PG 거래를 한 번씩 조회해 세 주문을 확정했다. **복구 중 새 결제 요청은 0회였다.**

## 같은 주문이 복구됐는지 확인하기

| 관찰 단계 | 쇼핑몰 주문번호 | 확정 주문 | 미확인 결제 | 대상 PG 성공 거래 | 누적 PG 생성 요청 | 누적 PG 조회 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 정상 기준선 | 11~20 | 10 | 0 | 10 | 10 | 10 |
| 응답 유실 직후 | 21~30 | 7 | 3 | 10 | 20 | 20 |
| 세 주문 대사 직후 | 같은 21~30 | 10 | 0 | 10 | 20 | 23 |

세 시점 모두 서버 상태는 `UP`이었다. Prometheus에서 읽은 미확인 결제 수는 **0 → 3 → 0**이었다. [서버 상태와 업무 지표](raw/actuator.txt) · [정상 기준선](raw/baseline.json) · [장애 당시 주문](raw/fault.json) · [복구 후 주문](raw/recovery.json)

## 22번 주문에서 확인한 원인

1. **쇼핑몰**은 생성 요청을 보낸 뒤 `ConnectionClosedException`을 기록했다. 주문은 `PAYMENT_PENDING`, 결제는 `UNKNOWN`이었다. [생성 요청 로그](raw/fault-create.log)
2. **모의 PG**에서 같은 주문번호 `LP-ORD-0000000022`를 읽기 전용으로 조회했다. 7,000원 승인 거래 `TEST-TX-22`가 이미 있었다. 이 조회 결과와 생성 요청 로그를 연결해 승인 응답 유실을 확인했다. [세 주문의 PG 조회 결과](raw/fault-pg-lookups.json)
3. **대사 후** 쇼핑몰에 같은 거래 키가 저장됐고 주문은 `CONFIRMED`가 됐다. 예약 1개는 `HELD → CONSUMED`로 바뀌었다. 22·25·28번을 한 건씩 대사한 뒤에도 가용 재고 **3개**, 구매자 잔액 **50,000포인트**, 보유 쿠폰 **1번 한 장**이 유지됐다. [복구 후 주문·예약](raw/recovery.json) · [주문별 대사 전후 값](raw/reconcile-balances.json)

## 구매 기록과 주간 랭킹

별도 통합 테스트에서 같은 사고를 재현해 복구 주문마다 Outbox 발행과 Kafka 소비 기록의 이벤트 ID를 대조했다. 세 주문 모두 구매 기록과 주간 집계 입력에 **각각 한 줄, 수량 1**로 남았다. 11월 16일 시작 주간의 랭킹 API에서 1위 상품은 **대사 전 17점 → 대사 후 20점 → 같은 이벤트 재전송 후 20점**이었다. [주문별 이벤트·구매 수량·랭킹 API 응답](raw/weekly-ranking.json)

결제 증거는 `CheckoutIncidentTest`의 `run-410560037659151293`, 랭킹 증거는 `WeeklyRankingFlowTest`의 `weekly-ranking-15897424616177648637.json`에서 가져왔다. `raw/`에서 `shasum -a 256 -c fault.sha256`을 실행하면 장애 당시 주문 원본을 검증할 수 있다. [대사 중단·재진입 절차와 회귀 결과](../../runbook/week10.md)

## 11월 27일 재현에서 확인한 Kafka 재전송

스트리머 통합 테스트 실행 `weekly-ranking-11404689905900932741`에서 장애 집합 21~30번을 주문별로 대조했다. 응답을 잃은 22·25·28번은 대사 전까지 구매 기록이 없었다. 대사 후 열 주문 모두 구매 활동과 주간 스냅샷 입력에 수량 1로 한 번씩 남았다.

22번의 이벤트 `order-confirmed:v1:22`는 처음 소비할 때 `recorded`, 같은 이벤트를 다시 전송했을 때 `duplicate`로 기록됐다. 25·28번도 같았다. 장애 집합의 발행 로그는 10줄, 최초 저장은 10줄, 중복 확인은 3줄이었다. 주간 랭킹 API 점수는 대사 전 17점, 대사 후 20점, 재전송 후 20점이었다. [장애 집합 열 건의 발행·소비 로그 원문](raw/async-order-events.log)

## 대사 도중 PG 조회가 막혔을 때

로컬 실행 `run-10463238630453517195`에서 62번 대사를 마친 뒤 PG가 65번 주문의 조회 요청에 HTTP 400을 반환했다. 쇼핑몰 대사 API는 HTTP 202, `lookupAvailable=false`, `paymentStatus=UNKNOWN`을 반환했다. 중단 기록에는 `reason=pg_lookup_unavailable`과 미시도 주문 68번이 남았다. 같은 주문의 서버 로그에서 `reason=http_error pgHttpStatus=400`을 확인했다. 65·68번의 예약과 기존 PG 거래를 다시 확인한 뒤 두 주문만 대사했다. PG 생성 요청은 20회로 유지됐다. [중단·재개 원문](raw/interrupted-reconcile.jsonl) · [PG와 Outbox 실패 로그](raw/boundary-failures.log)

별도 Outbox 테스트는 Kafka가 이벤트를 받은 직후 상태 저장에 예외를 주입했다. 발행기는 `outcome=mark_published_failed brokerAck=true`를 기록했고, DB 행은 `PENDING`으로 남았다. 재시도 후 행은 `PUBLISHED`로 바뀌었다. Kafka에서 같은 키와 내용의 레코드 두 개를 서로 다른 오프셋에서 읽었다. [실패 주입과 Kafka 재조회](../../../apps/commerce-api/src/test/java/com/loopers/application/payment/OrderConfirmedOutboxDeliveryTest.java)

별도 정상 복구 실행 `run-7450624646105360358`에서 전체 Prometheus 응답의 클라이언트·서버 HTTP 요청 수를 확인했다. 정상·장애·복구 세 단계 모두 주문번호가 들어갈 수 있는 URI 레이블 값은 주문 상세, 결제 요청, 대사 세 가지였고 모두 `{orderId}`를 사용했다. [복구 시점의 HTTP 지표 6줄](raw/actuator.txt#L26)

## 같은 주문의 세 결제 요청과 PG 조회 회차

로컬 HTTP 통합 테스트 `run-7312496789995465281`에서 같은 주문에 같은 시도키로 두 번, 다른 시도키로 한 번 결제를 요청했다. `send_to_pg`, `reuse_existing`, `attempt_key_conflict`가 각각 `INFO` 한 줄로 남았다. 충돌 요청은 HTTP 409로 거절됐고 세 요청을 통틀어 모의 PG가 받은 결제 생성 요청은 한 건이었다.

이어서 대사 API를 두 번 호출했다. 첫 대사의 PG 조회는 1회차에 HTTP 503, 2회차에 성공했고 다음 대사는 1회차에 성공했다. 테스트에서 다섯 HTTP 요청에 같은 상위 trace를 전달했다. 운영 프로필의 JSON 인코더로 출력한 로그에서 결제 요청과 PG 조회 회차를 추린 여섯 줄에는 같은 `traceId`와 요청별 `spanId` 다섯 개가 남았다. [JSON 로그 원문](raw/payment-requests.jsonl) · [필드 검색 예시](../../runbook/week10.md#같은-주문의-재요청과-pg-조회-재시도를-구분한다)

대사와 콜백 각각에서 PG 조회가 두 번 모두 503으로 끝나면 같은 `spanId`에 회차별 `INFO` 두 줄과 최종 `WARN` 한 줄이 남는다. 최종 경고는 `step=pg_lookup`, `outcome=unavailable`로 찾는다. `lookupBy`는 대사에서 `order_id`, 콜백에서 `transaction_key`이고, 두 경우 모두 `reason=http_error`, `pgHttpStatus=503`을 기록한다. [대사·콜백 JSON 회귀 테스트](../../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/PaymentStructuredLoggingTest.java)
