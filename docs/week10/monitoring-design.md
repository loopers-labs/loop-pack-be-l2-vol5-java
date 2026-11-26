# Week 10 결제 미확정 사고 모니터링 설계

## 진단할 질문

상태가 `DRAFT / UNKNOWN`인 주문이 있는데 process health가 UP이면 다음을 구분해야 한다.

1. PG가 아직 거래를 완료하지 않았는가?
2. PG는 SUCCESS 거래를 저장했지만 승인 응답이 commerce까지 오지 않았는가?
3. commerce가 성공 조회 결과를 받았지만 내부 결과 적용 트랜잭션이 rollback됐는가?

health UP과 주문 완료는 서로 다른 질문이다. health는 애플리케이션과 설정된 의존성의 probe 상태를 보여 주고, 업무 metric은 미확정 결제 규모를 보여 주며, 개별 주문의 원인은 제한된 합성 로그와 PG 거래 조회로 대사한다.

## 신호와 범위

| 신호 | 질문에 답하는 범위 | 실제 관찰 | 한계 |
| --- | --- | --- | --- |
| Actuator health | 프로세스·설정된 liveness/readiness 상태가 정상인가 | 실제 `/actuator/health` JSON 응답 | 주문별 결제 완료를 뜻하지 않음 |
| `commerce.payment.unresolved` | DB에서 마지막 집계 시점에 미확정 주문이 몇 건인가 | 실제 `/actuator/prometheus` exposition | DB count를 5초 fixed delay로 조회하므로 마지막 성공 조회 후 최대 5초 + query 시간만큼 늦을 수 있음 |
| 상관 구조화 로그 | 특정 주문에서 adapter 조회·내부 적용이 어디까지 갔는가 | 합성 테스트에서 주문/attempt 필드로 검색 | 이 로그만으로 원격 분산 trace가 구성됐다고 주장하지 않음 |
| PG fixture 원장 | 같은 계정/주문/금액의 거래가 생성됐고 현재 무엇인가 | fixture가 보유한 합성 transaction 상태·create 시도 수·조회 수 | 실제 외부 PG나 실계정 상태가 아님 |
| MySQL 주문/예약/Outbox | commerce가 PG 사실을 어디까지 commit했는가 | 요청 후 DB를 다시 읽은 값 | database 외부의 broker/consumer 상태는 별도 확인 필요 |
| Kafka/구매 활동/랭킹 | 확정 사실이 downstream에서 어디까지 처리됐는가 | 실제 격리 topic, activity DB, 새 ranking API | consumer offset/retry와 업무 활동 건수는 별도 단위 |

## 업무 metric 계약

- 이름: `commerce.payment.unresolved`
- 유형: gauge, label 없음
- 값: 조회 성공 시 `PaymentStatus.PENDING` 또는 `PaymentStatus.UNKNOWN`인 주문의 수
- refresh: 5초 fixed delay로 MySQL count를 실행한다. 초기 값을 임의의 0건 증거로 쓰지 않고 첫 조회 성공 이후의 값만 관찰한다.
- 오류: refresh query 실패 시 마지막 정상 gauge를 유지하고 실패를 0으로 바꾸지 않는다.
- 검증: 테스트는 실제 management HTTP port의 Prometheus 응답에서 이름과 값을 읽고, 기준선 대비 값 변화를 유한 대기 안에서 확인한다.
- cardinality: 주문 ID·user ID·attempt key·trace ID를 label로 사용하지 않는다.

이 값은 요청 순간의 동기 집계가 아니라 마지막 DB 조회의 snapshot이다. `UNKNOWN`만 따로 세는 metric으로 해석하지 않는다.

## 상관 로그 필드

승인 adapter 경계는 `providerOrderId`, `paymentAttemptKey`, `pgOperation`, `pgOutcome`, 제한된 `adapterFailure`, UTC `observedAt`를 기록한다. 주문의 reconcile 경계는 실제 `orderId`와 같은 attempt key로 조회 결과·내부 전 상태·후 상태·적용/rollback 결과를 남긴다. 두 경계는 attempt key와 provider order ID로 연결한다.

각 경계에서 최소한 다음 필드를 연결한다.

| 필드 | 의미 |
| --- | --- |
| `orderId` | 내부 주문 식별자 |
| `paymentAttemptKey` | 주문의 저장된 PG 결제 시도 |
| `paymentStatusBefore`, `paymentStatusAfter` | 내부 상태 변경 여부 |
| `providerOrderId` | 합성 PG 주문 correlation |
| `pgOperation`, `pgOutcome` | approve/lookup 및 결과 분류(timeout, unknown, success, failed) |
| `amountMatched` | PG 거래 금액이 주문 합계와 일치하는지 |
| `applicationOutcome` | applied, unchanged, rolled_back 등 내부 결과 |
| `observedAt` | 해당 경계를 기록한 UTC 시각 |

실제 PG secret, Authorization header, 카드/개인정보, environment variable 값은 기록하지 않는다. PG의 원문 body를 무제한 로그에 복사하지 않는다. 해당 테스트의 접근 제한 증거에는 fixture가 가진 합성 필드만 저장한다.

## 가설과 판정 기준

| 가설 | 예상 관찰 | 판정에 필요한 증거 |
| --- | --- | --- |
| PG 처리 지연 | 조회가 PENDING/미확정 | 동일 payment key의 권위 조회 결과와 시각 |
| PG 성공 응답 유실 | fixture 거래 SUCCESS, 내부 UNKNOWN, 승인 adapter timeout/reset 기록 | 거래 원장·create 응답 결과·주문/예약 상태 |
| 성공 결과의 내부 반영 실패 | 승인/조회 결과는 SUCCESS, 내부 적용 시도 뒤 transaction rollback | adapter 조회 결과·transaction 예외/rollback·주문 및 예약 재조회 |

PG SUCCESS + 내부 UNKNOWN만으로 두 번째와 세 번째 원인을 구별하지 않는다. 경계 로그나 transaction 결과가 없으면 원인 판정을 `미확정`으로 남긴다.

## 접근·테스트 경계

- management endpoint는 기존 health·prometheus 노출과 관리 접근 보호를 유지한다.
- env, heapdump, configprops 등 관리 endpoint를 추가로 노출하지 않는다.
- 테스트는 `management.server.port=0`으로 별도 port를 선택하고 실제 주입 주소를 사용한다.
- fault 주입 및 callback/자동 재조정 보류는 integration test 제어에 한정한다. 제품 흐름의 W5 lookup·예약·중복 방지를 제거하지 않는다.
- trace backend가 없으면 구조화 로그 correlation이라고만 기록한다.
