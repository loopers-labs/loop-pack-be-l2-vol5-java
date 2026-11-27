# 10주차 결제 사고 실험 결과

## 1. S5 기준선과 장애 재현

제출 대상으로 보존한 실행은 **`20261126-221445-c02e381c`**다. 아래 표와 로그는 이 실행의 `run.json`, `baseline.json`, `fault.json` 및 Gradle JUnit XML에 근거한다. 실행 시각은 2026-11-26 22:14:45.868225Z부터 22:15:12.199741Z까지다. 앞선 `20261126-221331-a72bcf37`도 별도로 통과했으며, 이 보고서의 관측값에는 합산하지 않았다.

### 목적과 검증 가설

- 정상 거래 10건에서 PG가 성공을 기록하고 기존 callback이 권위 목록 조회를 거쳐 내부 주문 확정과 예약 소비로 이어지는지 확인했다.
- PG가 성공을 저장한 뒤 생성 응답만 유실하면 내부 시도는 UNKNOWN, 주문은 PAYMENT_PENDING, 예약은 RESERVED에 머물 것으로 예상했다. 대상 주문마다 실제 create가 한 번 도달하고, 전체 DB의 UNKNOWN 지표가 자동 갱신되어 3건을 나타내며 Health는 UP으로 유지되는지 함께 검증했다.

### 환경과 통제 조건

| 항목 | 기본 설정 또는 의존성 | S5 실효 설정과 출처 |
| --- | --- | --- |
| 실행 환경 | Spring Boot 3.4.4, Spring Web 6.2.5, Resilience4j 2.4.0, Micrometer 1.14.5, Gradle Wrapper 8.13 | Spring·라이브러리 버전은 프로젝트 설정, 기존 의존성 확인 기록 및 Boot BOM 기준이다. 실행 원장은 `test` 프로필, JDK 23.0.2, MySQL 8.0.46을 기록했다. |
| HTTP 경계 | 관리 HTTP 포트 8081, PG 읽기 timeout 3초 | API 59048, Actuator 59049, PG fixture 58965. 모두 `127.0.0.1`에 바인딩했다. 기존 어댑터에 fixture URL을 주입했고 timeout은 500ms였다. |
| 결제 조회 | 자동 조회 활성, 기본 간격 30초 | 자동 조회 비활성. 정상 주문에는 HTTP callback을 직접 전달하고 장애 세 건에는 전달하지 않았다. |
| 지표와 시간 | 지표 고정 지연 10초 | 지연 200ms, UTC 시스템 Clock, 최대 10초 유한 대기. Actuator 테스트의 Prometheus export를 활성화했다. |
| 후속 처리 | 제품 경로에 Outbox relay와 Kafka·배치 경계가 있음 | S5 테스트에서 relay, Kafka listener 및 Batch 실행을 보류했다. |

초기 주문·시도·예약·PG 거래·create 횟수는 모두 0이었다. 합성 사용자 1명과 단가 1,000원, 가용 재고 30개의 상품 한 종을 사용했다. 주문당 한 개를 구매했다. 두 단계는 한 테스트 메서드에서 DB와 PG 원장, 호출 기록, 조회 서킷을 공유했다.

1. **BASELINE**에서 실제 HTTP로 주문 10건을 생성하고 각 결제를 시작했다. PG의 202 PENDING 응답 뒤 fixture에 SUCCESS를 기록하고, 실제 callback API를 호출해 권위 조회 및 내부 확정을 확인했다. DB를 다시 읽고 Health·Prometheus 원문과 경계 로그를 보존했다.
2. **FAULT**에서 새 주문 10건을 생성하고, 생성된 목록의 앞 세 주문에만 응답 유실을 지정했다. fixture는 요청과 호출 횟수를 기록하고 거래를 생성한 뒤 SUCCESS를 저장하고 응답을 보류한다. 나머지 7건은 BASELINE과 같은 callback 경로로 확정했다. 장애 세 건은 응답 timeout 이후 내부 UNKNOWN으로 남았다. 같은 DB와 원장에서 누적 결과를 재조회했다.

### 기대 결과와 관측 결과

| 검증 항목 | BASELINE 기대 | BASELINE 관측 | FAULT 기대 | FAULT 관측 |
| --- | ---: | ---: | ---: | ---: |
| 해당 단계 주문 | 10 | 10 | 10 | 10 |
| 해당 단계 내부 SUCCESS / UNKNOWN | 10 / 0 | 10 / 0 | 7 / 3 | 7 / 3 |
| 해당 단계 PG SUCCESS | 10 | 10 | 10 | 10 |
| 누적 내부 SUCCESS / PG SUCCESS | 10 / 10 | 10 / 10 | 17 / 20 | 17 / 20 |
| 누적 PG create / 주문별 create | 10 / 1 | 10 / 1 | 20 / 1 | 20 / 1 |
| 누적 예약 CONSUMED / RESERVED | 10 / 0 | 10 / 0 | 17 / 3 | 17 / 3 |
| 가용 재고 | 20 | 20 | 10 | 10 |
| Health HTTP / 상태 | 200 / UP | 200 / UP | 200 / UP | 200 / UP |
| Prometheus HTTP / UNKNOWN | 200 / 0 | 200 / 0.0 | 200 / 3 | 200 / 3.0 |

BASELINE 주문 ID는 1~10, FAULT 주문 ID는 11~20이었다. 초기 재고 30에서 예약 20개만큼 차감되어 10개가 남았다. 장애 주문별 예약 수량은 1개로 유지됐고 세 건 모두 RESERVED였다. 정상 결제의 예약은 CONSUMED였다.

| 주문 / 시도 / 예약 ID | 시도 키 | PG 주문 ID | PG 거래 키 | PG 상태 / 내부 시도 / 주문 / 예약 | create / PG 조회 |
| --- | --- | --- | --- | --- | --- |
| 11 / 11 / 11 | `s5-20261126-221445-c02e381c-11` | `LP-ORD-0000000011` | `TEST-TX-000011` | SUCCESS / UNKNOWN / PAYMENT_PENDING / RESERVED | 1 / 0 |
| 12 / 12 / 12 | `s5-20261126-221445-c02e381c-12` | `LP-ORD-0000000012` | `TEST-TX-000012` | SUCCESS / UNKNOWN / PAYMENT_PENDING / RESERVED | 1 / 0 |
| 13 / 13 / 13 | `s5-20261126-221445-c02e381c-13` | `LP-ORD-0000000013` | `TEST-TX-000013` | SUCCESS / UNKNOWN / PAYMENT_PENDING / RESERVED | 1 / 0 |

세 건의 PG 금액과 내부 시도 저장 금액은 각각 1,000원이었다. 내부 주문의 `paid_amount`는 0이고 저장된 내부 `transaction_key`는 null이었다. 이 null 값은 JSON에서 필드가 생략된다.

### Health와 Prometheus 원문

두 단계의 Health HTTP 200 본문은 동일했다.

```json
{"status":"UP","groups":["liveness","readiness"]}
```

Prometheus HTTP 200 본문 중 UNKNOWN과 집계 상태 시계열을 발췌했다. 전체 원문은 각 단계 JSON의 `observation.prometheus.body`에 있다.

**BASELINE**

```text
commerce_payment_unresolved{application="commerce-api",state="unknown"} 0.0
commerce_payment_unresolved_oldest_created_timestamp_seconds{application="commerce-api",state="unknown"} NaN
commerce_payment_unresolved_refresh_total{application="commerce-api",result="failure"} 0.0
commerce_payment_unresolved_refresh_total{application="commerce-api",result="success"} 11.0
commerce_payment_unresolved_refresh_last_success_timestamp_seconds{application="commerce-api"} 1.795731309218E9
```

**FAULT**

```text
commerce_payment_unresolved{application="commerce-api",state="unknown"} 3.0
commerce_payment_unresolved_oldest_created_timestamp_seconds{application="commerce-api",state="unknown"} 1.795731309542E9
commerce_payment_unresolved_refresh_total{application="commerce-api",result="failure"} 0.0
commerce_payment_unresolved_refresh_total{application="commerce-api",result="success"} 23.0
commerce_payment_unresolved_refresh_last_success_timestamp_seconds{application="commerce-api"} 1.795731311709E9
```

`refresh()` 직접 호출 없이 두 단계 각각에서 단계 완료 이후의 성공 시각을 관측했다. 마지막 성공 시각은 1795731309.218 → 1795731311.709(epoch 초)였고, 원문을 수집한 시점의 경과는 각각 0.149초와 0.104초였다. 두 값은 테스트의 최신성 기대 범위인 0~5초에 들었다. 이 값들은 관측된 두 scrape의 경과 시간이다.

### Circuit Breaker와 경계 로그

조회 서킷의 실효 설정은 COUNT_BASED, 창 10건, 최소 5건, 실패율 50%, OPEN 대기 10초, HALF_OPEN 시험 2건이다. 생성 호출은 조회 서킷의 적용 범위 밖이다. 장애 세 주문의 실제 create 수신 기록이 각각 1건이고 PG 원장이 SUCCESS였으므로, 이 세 건을 호출 차단으로 분류할 근거는 없다. 성공한 정상 callback의 조회는 FOUND였고 해당 실행에서 `CIRCUIT_OPEN`은 관측하지 않았다. 서킷 전이 자체를 유도하는 실험은 S5 범위에 없었다.

아래는 저장된 UTC 로그 시각·레벨·메시지 발췌다. BASELINE 첫 정상 주문과 FAULT의 세 장애 주문을 제시했다.

```text
2026-11-26T22:15:08.084Z INFO pg.create outcome=ACCEPTED providerOrderId=LP-ORD-0000000001 transactionKey=TEST-TX-000001
2026-11-26T22:15:08.091Z INFO payment.create outcome=ACCEPTED orderId=1 attemptId=1 providerOrderId=LP-ORD-0000000001 pendingRows=1
2026-11-26T22:15:08.143Z INFO pg.lookup outcome=FOUND orderId=LP-ORD-0000000001 count=1 statuses=[SUCCESS]
2026-11-26T22:15:08.188Z INFO payment.apply phase=committed attemptId=1 orderId=1 appliedStatus=SUCCESS confirmedAt=2026-11-26T22:15:08.151Z outboxEventId=order-confirmed:v1:1

2026-11-26T22:15:10.056Z WARN pg.create outcome=UNKNOWN providerOrderId=LP-ORD-0000000011 exception=ResourceAccessException causeChain=[ResourceAccessException, HttpTimeoutException] interrupted=false
2026-11-26T22:15:10.068Z WARN payment.create outcome=UNKNOWN orderId=11 attemptId=11 providerOrderId=LP-ORD-0000000011 unknownRows=1 conflict=null
2026-11-26T22:15:10.614Z WARN pg.create outcome=UNKNOWN providerOrderId=LP-ORD-0000000012 exception=ResourceAccessException causeChain=[ResourceAccessException, HttpTimeoutException] interrupted=false
2026-11-26T22:15:10.625Z WARN payment.create outcome=UNKNOWN orderId=12 attemptId=12 providerOrderId=LP-ORD-0000000012 unknownRows=1 conflict=null
2026-11-26T22:15:11.175Z WARN pg.create outcome=UNKNOWN providerOrderId=LP-ORD-0000000013 exception=ResourceAccessException causeChain=[ResourceAccessException, HttpTimeoutException] interrupted=false
2026-11-26T22:15:11.187Z WARN payment.create outcome=UNKNOWN orderId=13 attemptId=13 providerOrderId=LP-ORD-0000000013 unknownRows=1 conflict=null
```

### 실행 검증과 해석

```bash
./gradlew :apps:commerce-api:crossAppTest \
  --tests '*CheckoutIncidentE2ETest' --rerun --console=plain

./gradlew :apps:commerce-api:test \
  --tests '*PaymentV1ApiE2ETest' --tests '*PaymentUnresolvedMetrics*' \
  --tests '*HttpPaymentGatewayContractTest*' --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleTest :apps:commerce-api:checkstyleCrossAppTest --console=plain
```

제출 실행의 S5 JUnit 결과는 1건, 실패·오류·skip 각각 0건이다. 별도 관련 회귀 실행은 XML 합계 82건, 실패·오류·skip 각각 0건이고 Checkstyle 두 작업도 통과했다. 이 회귀 실행의 건수는 사고 증거와 별개다.

PG의 SUCCESS 거래와 내부 미종결 상태가 함께 존재해도 Health는 UP일 수 있었다. 이 실행에서는 PG 원장, create 수신 기록, Gateway의 미확인 로그, 주문별 DB 상태를 함께 대조해 예정된 장애가 재현됐음을 확인했다. 계획 초안의 단계별 테스트와 고정 시계 대신, 한 테스트 메서드와 UTC 시스템 Clock으로 두 단계의 상태 연속성과 갱신 시각을 측정했다.

실제 외부 PG, 운영 부하, Scheduler 비활성 전환 및 DB 조회 실패 뒤 자동 회복, 다중 인스턴스 scrape, JDK HTTP 클라이언트의 직접 `CancellationException`, 운영 관리 포트 접근 제어와 전역 예외 로그의 마스킹은 이 실행에서 검증하지 않았다. 이 실행은 FAULT 증거 보존 후 종료했다.

## 2. 증거 보존

생성 위치는 `apps/commerce-api/build/incident-evidence/<runId>/`다. 앞선 실행 `20261126-221331-a72bcf37`의 별도 원본도 당시 그 경로에서 확인했다. 다만 S10 최종 회귀에서 `clean`을 실행했으므로 **빌드 산출물의 원본은 지금 남아 있지 않다.** 지금 조회할 수 있는 것은 `docs/week10/evidence/`로 복사해 둔 실행들뿐이고, 이 실행은 복사 대상이 아니었다. 남아 있는 두 실행의 매니페스트는 모두 PASSED였으며 실패 매니페스트는 발견되지 않았다.

제출 실행의 아래 네 파일을 바이트 그대로 복사했고, 복사본을 원본과 대조했다. `fault.json.sha256`은 복사본의 FAULT 본문과 일치한다.

- [run.json](evidence/20261126-221445-c02e381c/run.json) · 실행 환경, 초기 상태, 단계별 결과
- [baseline.json](evidence/20261126-221445-c02e381c/baseline.json) · DB 조회, PG 원장·create 수, Health·Prometheus 본문, 경계 로그
- [fault.json](evidence/20261126-221445-c02e381c/fault.json) · 장애 주문 선택 결과와 같은 형식의 실제 관측값
- [fault.json.sha256](evidence/20261126-221445-c02e381c/fault.json.sha256) · FAULT 원문 체크섬
- [test-result.json](evidence/20261126-221445-c02e381c/test-result.json) · 기존 JUnit XML에서 동일 runId를 확인한 뒤 추출한 테스트 이름·시각·실행 결과. 원본 XML의 런타임 출력은 제출 복사본에 포함하지 않았다.

## 3. S5 전 선행 실험

아래 네 항목은 S5와 별도 실행이다. 현재 남은 계약 테스트 소스는 검증 조건을 확인하는 데 사용했다. 과거 실행의 원문이 남아 있지 않은 경우 수치와 결과는 [구현 계획서](implementation-plan.md)와 [관측 설계](monitoring-design.md)의 기존 실행 기록에 근거한다.

### 3.1. 5만 행 집계 쿼리와 인덱스 비교

| 구분 | 기록 |
| --- | --- |
| 문제 발견 | 상태별 미종결 집계를 반복 실행할 때 기존 테이블에 상태 인덱스가 없어 추가 조회 비용을 확인할 필요가 있었다. |
| 수정 전 재현 | 기존 실행 기록은 로컬 MySQL 8.0의 probe 테이블에 SUCCESS 49,500, PENDING 450, UNKNOWN 50, REQUESTED 0행을 적재했다고 보고한다. 인덱스 없이 상태별 건수 쿼리는 `type=ALL`, 예상 스캔 50,000행, 29.7ms였다. |
| 원인 | 미종결 비율이 1%여도 상태 인덱스가 없으면 전체 스캔이 필요했다. REQUESTED가 비어 있어 SQL 결과에 해당 그룹도 없었다. |
| 해결 방법 | probe에 `status` 단일 인덱스를 붙여 비교했다. 애플리케이션은 상태 그룹 0건 보완 및 scrape와 별도의 주기 집계를 적용했고, 제품 스키마에는 인덱스를 추가하지 않았다. |
| 수정 후 검증 | 인덱스가 있는 probe는 `type=range`, 예상 스캔 501행, 커버링 인덱스, 0.42ms로 보고됐다. 인덱스 약 1.6MB 및 생성 241ms도 기존 기록의 값이다. |
| 남은 한계 | probe는 삭제됐고 원본 SQL 실행 로그는 보존 자료에서 확인되지 않았다. `MIN(created_at)` 추가 전의 쿼리였으며 쓰기 비용, 운영 동시 부하, 50만 행 실측은 포함하지 않는다. |

### 3.2. CancellationException 처리 누락

| 구분 | 기록 |
| --- | --- |
| 문제 발견 | 실제 HTTP에서 응답을 잃었을 때 결과를 UNKNOWN으로 수렴시켜야 하는데, 기존 catch는 `RestClientException`만 처리했다. |
| 수정 전 재현 | 기존 S4 기록에 예외 누락 발견이 남아 있다. 수정 전 JDK가 직접 취소 예외를 던진 실행의 원문과 결정적 실패 XML은 현재 보존 자료에서 확인되지 않았다. |
| 원인 | spring-web 6.2.5의 JDK 요청 경로에서 timeout 처리 중 future 취소가 `CancellationException`으로 전파될 수 있다. 이 타입은 기존 catch에 해당하지 않았다. |
| 해결 방법 | create와 두 조회의 HTTP 호출 경계에서만 `CancellationException`을 추가 처리했다. create는 UNKNOWN, 조회는 UNAVAILABLE/CALL_FAILED이며 후자는 조회 서킷의 실패 표본이다. |
| 수정 후 검증 | 기존 결정적 주입 테스트는 공개 메서드의 위 매핑과 일반 런타임 오류 전파를 확인했다. 실제 HTTP fixture의 유실 테스트와 이번 S5에서 관측한 예외 사슬에는 `HttpTimeoutException`이 있었다. |
| 남은 한계 | JDK HTTP 호출이 직접 `CancellationException`을 던지는 경로는 미검증이다. 결정적 주입 결과와 실제 HTTP timeout 관측을 별개로 해석한다. |

### 3.3. PG create 필수 응답 필드 검증

| 구분 | 기록 |
| --- | --- |
| 문제 발견 | 주문 ID 또는 금액을 비교할 수 없는 응답도 생성 수락이나 효과 없는 사전 거절로 분류될 수 있었다. |
| 수정 전 재현 | 기존 HTTP fixture 실행 보고에서 202 다섯 건이 ACCEPTED, 422 다섯 건이 REJECTED_NO_EFFECT로 잘못 분류됐다. 누락, 오타입, 정수 범위 초과가 포함됐고 48건 중 10건이 실패했다. 수정 전 XML은 현재 남아 있지 않다. |
| 원인 | 비교 가능한 필드에 대해서만 불일치를 검사했고, 422의 코드와 boolean false 조건 이후에는 필드 유효성 확인 없이 매핑됐다. |
| 해결 방법 | 두 응답 모두 일치하는 문자열 주문 ID와 `long` 범위 JSON 정수를 요구한다. 202는 PENDING과 거래 키, 422는 지정 거절 코드와 `transactionCreated=false`를 추가로 확인한다. 비교 가능한 불일치에는 기존 충돌 사유를 유지한다. |
| 수정 후 검증 | 잘못된 10건이 UNKNOWN으로 수렴하고 정상 수락·사전 거절 및 불일치 계약을 유지했다. 당시 결제·주문·ArchUnit 261건 통과 기록과 실제 HTTP·MySQL에서 예약·재고 보존 및 추가 create 없는 복구 기록이 있다. |
| 남은 한계 | 수정 전 테스트가 직접 재현한 것은 어댑터의 잘못된 분류다. 그 422가 업무 실패로 종결되는 영향은 제품 경로에서 확인했고, 수정 전 업무 DB 커밋 원문은 보존 자료에 없다. |

### 3.4. PG 권위 조회 금액 범위 초과

| 구분 | 기록 |
| --- | --- |
| 문제 발견 | 조회 변환기가 JSON 정수 여부만 검사해 범위 밖 금액이 유효한 금액으로 해석될 수 있었다. |
| 수정 전 재현 | 기존 HTTP 및 MySQL 실행 보고에서 양·음 범위 밖 값이 7,000원으로 변환됐고, 혼합 목록이 FOUND로 반환됐다. 업무 통합 테스트는 잘못된 SUCCESS 및 FAILED 커밋을 각각 재현했다. 56건 중 5건이 실패했으며 수정 전 XML은 현재 남아 있지 않다. |
| 원인 | `asLong()`이 범위를 벗어난 정수의 상위 비트를 버려, 저장 금액과 같은 숫자가 될 수 있었다. |
| 해결 방법 | 목록과 단건 공통 변환에서 `isIntegralNumber()`와 `canConvertToLong()`을 모두 확인한 다음 변환한다. 목록의 잘못된 항목은 전체 CALL_FAILED로 처리하며, 유효한 금액 불일치는 기존 AMOUNT_MISMATCH다. |
| 수정 후 검증 | 기존 보고에서 96건 통과, 실패·오류·skip 0건 및 Checkstyle 통과가 확인됐다. HTTP·MySQL 복구에선 비정상 조회 후 예약·재고가 유지됐고 정상 금액의 동일 거래를 조회해 추가 create 없이 복구했다. |
| 남은 한계 | 로컬 fixture에서 다룬 입력과 주문 흐름의 결과다. 실제 PG 응답이나 운영 금융 정산을 검증한 실행은 없다. |

## 4. S6 경쟁 가설 진단

실행 `20261126-222348-37dab0a6`에서 BASELINE → FAULT → DIAGNOSE를 한 테스트 메서드로 실행했다. §1의 제출 실행과 별개의 새 실행이다. 이번 실행도 BASELINE에서 내부 SUCCESS 10, FAULT에서 누적 SUCCESS 17·UNKNOWN 3·PG SUCCESS 20을 관측했다. 두 단계의 Health는 HTTP 200/UP, Prometheus UNKNOWN은 0 → 3이었다.

진단은 2026-11-26 22:24:14.300418Z부터 22:24:14.331605Z까지 수행했다. 장애 주문별로 같은 합성 PG 계정으로 fixture의 실제 `GET /api/v1/payments?orderId=…`를 호출했다. 이 GET은 commerce의 결과 적용 경로를 거치지 않으므로, 진단 응답의 SUCCESS는 장애 당시 제품이 SUCCESS를 수신했다는 증거로 사용하지 않았다.

| 주문 / 시도 | 시도 키 | PG 주문 ID / 거래 키 | 진단 조회 | 진단 전후 내부 상태 |
| --- | --- | --- | --- | --- |
| 11 / 11 | `s5-20261126-222348-37dab0a6-11` | `LP-ORD-0000000011` / `TEST-TX-000011` | HTTP 200, 동일 거래 1건, SUCCESS, 1,000원 | UNKNOWN / PAYMENT_PENDING / RESERVED |
| 12 / 12 | `s5-20261126-222348-37dab0a6-12` | `LP-ORD-0000000012` / `TEST-TX-000012` | HTTP 200, 동일 거래 1건, SUCCESS, 1,000원 | UNKNOWN / PAYMENT_PENDING / RESERVED |
| 13 / 13 | `s5-20261126-222348-37dab0a6-13` | `LP-ORD-0000000013` / `TEST-TX-000013` | HTTP 200, 동일 거래 1건, SUCCESS, 1,000원 | UNKNOWN / PAYMENT_PENDING / RESERVED |

| 가설 | 예상 관찰 | 실제 관측 근거 | 반증 또는 대안 구분 근거 | 부족한 증거 | 판정 |
| --- | --- | --- | --- | --- | --- |
| 1. PG 거래 처리 지연 | 권위 조회가 PENDING을 반환하고 내부 결제가 미종결 | 내부 세 건은 미종결이었다. | FAULT 원장과 진단 GET 모두 동일 거래의 SUCCESS를 반환했다. 세 건 모두 금액과 키가 일치했다. | PG의 상태 변경 이력이 없어 조회 이전의 일시적 PENDING 구간은 판단할 수 없다. | 진단 시점의 계속된 처리 지연은 반증됨 |
| 2. PG SUCCESS 저장 이후 생성 응답 유실 | 동일 거래가 PG에 있고 생성 호출은 응답을 확인하지 못하며 내부에는 UNKNOWN이 기록됨 | 세 주문 모두 create 1회, 어댑터의 `pg.create outcome=UNKNOWN` 및 `HttpTimeoutException`, 내부의 `payment.create outcome=UNKNOWN unknownRows=1`가 연결됐다. 이후 권위 조회에서 기존 거래의 SUCCESS를 확인했다. | 진단 전 해당 주문의 PG 목록 조회는 0회였다. 정상군에서는 목록 조회 SUCCESS와 `payment.apply phase=committed`가 함께 관측됐다. | 권위 응답에 PG SUCCESS 저장 시각이 없어, SUCCESS 저장과 timeout의 정확한 선후는 관측 증거만으로 독립 확인할 수 없다. | 세 주문 모두 생성 응답 유실이 가장 지지됨. PG 저장의 정확한 시점은 미확정 |
| 3. PG 성공 결과 수신 이후 내부 TX 반영 실패 | 진단 전에 제품의 동일 거래 SUCCESS 수신이 관측되고, 적용 시도 뒤 rollback·완료 불명 또는 명시적 실패가 남음 | 세 주문에서 확인된 제품 경계는 생성 timeout과 UNKNOWN 기록이었다. 성공을 반환한 GET은 진단 시점의 호출이다. | 진단 이전 PG 목록 조회 기록이 0회이고 성공 결과 수신·적용·rollback의 직접 증거를 확보하지 못했다. | 로그 누락만으로 TX 미실행을 확정할 수 없다. 같은 실행에서 SUCCESS 수신 후 내부 TX를 실패시키는 대조 실험은 수행하지 않았다. | 이 세 주문의 원인으로 지지되지 않음 |

진단 후 주문·결제 시도·예약·Outbox 전체 조회 결과가 진단 전과 일치했다. Outbox는 17행, 가용 재고는 10, PG 거래와 create는 각각 20건이었다. 진단 중 관측된 PG 요청은 세 대상의 GET 3회였고, 제품의 reconcile 호출과 추가 PG create는 없었다. `fault.json`과 체크섬의 바이트도 진단 전후 동일했다.

```bash
./gradlew :apps:commerce-api:crossAppTest --tests '*CheckoutIncidentE2ETest' --rerun --console=plain
./gradlew :apps:commerce-api:checkstyleCrossAppTest --console=plain
```

동일 실행 통합 테스트는 1건 통과, 실패·오류·skip 각 0건이다. 증거는 `apps/commerce-api/build/incident-evidence/20261126-222348-37dab0a6/`에 생성했다. [실행 정보](evidence/20261126-222348-37dab0a6/run.json), [BASELINE](evidence/20261126-222348-37dab0a6/baseline.json), [FAULT](evidence/20261126-222348-37dab0a6/fault.json), [FAULT 체크섬](evidence/20261126-222348-37dab0a6/fault.json.sha256), [진단 원문과 전후 상태](evidence/20261126-222348-37dab0a6/diagnosis.json), [테스트 결과](evidence/20261126-222348-37dab0a6/test-result.json)를 별도 폴더에 보존했다.

## 5. S7 복구 전 r1 공개와 동일 거래 복구

실행 `20261127-050500-5e8f1e57`에서 BASELINE → FAULT → DIAGNOSE → 복구 전 r1 공개 → RECOVERY를 **한 테스트 메서드**로 이어 실행했다. §1·§4와 별개의 새 실행이며 단계 사이에 DB와 PG 원장을 초기화하지 않았다. 다섯 단계 모두 PASSED다.

### 5.1. 복구 이전 r1 공개 (선행 절차)

복구 전에 확정된 17건(BASELINE 10 + FAULT 정상 7)의 Outbox를 실제 relay로 발행하고 실제 Kafka와 streamer consumer로 소비했다. 장애 3건은 아직 확정되지 않아 Outbox 행도 없었다.

| 관측 | 값 |
| --- | --- |
| Outbox 발행 | 17건 (PENDING 17 → PUBLISHED 17) |
| `product_purchase_activity` | 17행, quantity 합계 17 |
| `processed_order_confirmation` | 17행 |
| 배치 실행 | `weekly-20261123-r1`, `COMPLETED` |
| `weekly_ranking_inputs` | 17행 |
| 랭킹 API | `PUBLISHED`, 상품 1, score **17**, rank 1 |

기대값을 손으로 심지 않았다. `frozenAt`은 `2026-11-27T05:05:25Z`이고 주간 구간은 `2026-11-23 ~ 2026-11-30`이다. 주 소속은 내부 최초 확정 시각이 결정했고 PG 요청 시각이나 복구 시각으로 덮어쓰지 않았다.

### 5.2. 동일 거래 복구

응답 유실 주입을 해제한 뒤(`hasResponseDrops=false`) 장애 3건만 기존 `POST /api/v1/orders/{orderId}/payment/reconcile`로 복구했다. 세 건 모두 HTTP 200이었다.

| 주문 | PG 주문 ID | 응답 | 적용된 거래 키 |
| --- | --- | --- | --- |
| 11 | LP-ORD-0000000011 | 200 · CONFIRMED · SUCCESS · lookupUnavailable=false | TEST-TX-000011 |
| 12 | LP-ORD-0000000012 | 200 · CONFIRMED · SUCCESS · lookupUnavailable=false | TEST-TX-000012 |
| 13 | LP-ORD-0000000013 | 200 · CONFIRMED · SUCCESS · lookupUnavailable=false | TEST-TX-000013 |

세 거래 키는 FAULT 단계에서 PG가 저장한 것과 같다. 새 create는 보내지 않았고 DB를 직접 바꾸지 않았다.

### 5.3. 불변 조건 관측값

| 항목 | 복구 전 | 복구 후 |
| --- | --- | --- |
| 내부 SUCCESS | 17 | **20** |
| 내부 UNKNOWN | 3 | **0** |
| PG 거래 | 20 (모두 SUCCESS) | 20 (동일 집합) |
| PG create 누적 | 20 | **20** |
| 주문별 create | 모두 1 | 모두 1 |
| 가용 재고 | 10 | **10** |
| 예약 CONSUMED | 17 | **20** |
| Outbox | 17 | **20** (신규 3, event_id 중복 0) |
| 포인트·쿠폰 | 빈 상태 | 동일 |

반복 reconcile과 callback 재전송 뒤의 전체 스냅샷이 1차 복구 직후와 **완전히 같았다**. 다른 사용자의 reconcile은 404였다. 복구 후 Prometheus의 미확인 지표는 세 state 모두 0이고 oldest 시각은 NaN이며 집계 실패는 0, 성공은 31회였다.

```text
commerce_payment_unresolved{application="commerce-api",state="unknown"} 0.0
commerce_payment_unresolved{application="commerce-api",state="pending"} 0.0
commerce_payment_unresolved{application="commerce-api",state="requested"} 0.0
commerce_payment_unresolved_oldest_created_timestamp_seconds{application="commerce-api",state="unknown"} NaN
commerce_payment_unresolved_refresh_total{application="commerce-api",result="failure"} 0.0
commerce_payment_unresolved_refresh_total{application="commerce-api",result="success"} 31.0
```

이미 공개한 r1은 바뀌지 않았다. 복구 후 랭킹 API가 여전히 `weekly-20261123-r1`과 score 17을 돌려주고 `weekly_ranking_inputs`도 17행이었다. 복구된 3건을 반영하는 r2는 S8의 범위다.

경계 로그에서 세 주문 모두 `pg.lookup outcome=FOUND statuses=[SUCCESS]`, `payment.reconcile decision=APPLIED`, 반복 호출의 `decision=ALREADY_SETTLED`가 관측됐고 복구 구간에 `pg.create` 줄은 없었다.

### 5.4. 검증 범위와 미검증

- reconcile 202(조회 불가·계속 PENDING)와 callback 409(거래 불일치), 거래 0건·복수·금액 불일치의 복구 중단 규칙은 기존 `PaymentReconcileIntegrationTest`·`PaymentV1ApiE2ETest`가 검증한다. 사고 원장을 오염시키지 않으려고 이번 실행에서 인위적 충돌을 만들지 않았다.
- 집계 실패 후 스케줄러 자동 회복과 비활성 전환은 여전히 미검증이다.
- 동일 실행 통합 테스트는 1건 통과, 실패·오류·skip 각 0건이다. 증거는 `apps/commerce-api/build/incident-evidence/20261127-050500-5e8f1e57/`에 생성하고 제출본을 별도 폴더에 보존했다. [실행 정보](evidence/20261127-050500-5e8f1e57/run.json), [BASELINE](evidence/20261127-050500-5e8f1e57/baseline.json), [FAULT](evidence/20261127-050500-5e8f1e57/fault.json), [FAULT 체크섬](evidence/20261127-050500-5e8f1e57/fault.json.sha256), [진단](evidence/20261127-050500-5e8f1e57/diagnosis.json), [복구 전 r1](evidence/20261127-050500-5e8f1e57/ranking-r1.json), [복구](evidence/20261127-050500-5e8f1e57/recovery.json), [테스트 결과](evidence/20261127-050500-5e8f1e57/test-result.json).

## 6. S8 복구된 사실의 후속 결과와 r2 공개

실행 `20261127-051516-bb5ff22c`에서 BASELINE → FAULT → DIAGNOSE → RANKING R1 → RECOVERY → DOWNSTREAM을 **한 사고 실행**으로 이었다. §5와 별개의 새 runId이며 단계 사이에 DB와 PG 원장을 초기화하지 않았다. 여섯 단계 모두 PASSED다. 서로 다른 runId의 관측을 섞지 않았다.

### 6.1. 복구된 주문의 발행과 소비

| 관측 | 값 |
| --- | --- |
| 이 단계에서 발행한 Outbox | 3 |
| 전체 Outbox | 20건 PUBLISHED, 비PUBLISHED 0 |
| `product_purchase_activity` | 20행, quantity 합계 20 |
| `processed_order_confirmation` | 20행 |
| 주문·상품·수량·확정시각 정합 | 20행 전부 일치 |
| event_id 규칙 | 20행 전부 `order-confirmed:v1:{order_id}` |
| 복구 3건의 활동·장부 | 각 주문당 정확히 1행 |

정합은 조인으로 확인했다. `product_purchase_activity`를 `orders`와 `order_items`에 상품·수량으로 조인하고 `confirmed_at`까지 같은 행이 20건이다. 누락도 중복도 없다.

```text
{"order_id": 1, "product_id": 1, "quantity": 1, "confirmed_at": "2026-11-27T05:15:35.669"}
{"event_id": "order-confirmed:v1:1", "order_id": 1, "confirmed_at": "2026-11-27T05:15:35.669"}
```

### 6.2. 중복 발행과 중복 소비

복구된 3건의 Outbox payload를 실제 sender로 다시 발행했다. 발행은 3회 성공했고, 그 뒤 유한 대기 동안 구매 활동과 장부가 **20행에서 변하지 않았다.** 활동 테이블의 전체 행을 재발행 전후로 비교해 동일함을 확인했다. 발행 횟수와 업무 활동 수를 같은 수로 취급하지 않는다.

### 6.3. r2 공개와 r1 불변

| 관측 | 값 |
| --- | --- |
| r2 snapshotId | `weekly-20261123-r2` |
| 배치 | `COMPLETED`, revision status `COMPLETED` |
| r2 frozen input | 20행 |
| 공개 포인터 | `weekly-20261123-r2`, revision 2 |
| 랭킹 API | `PUBLISHED`, score **20**, rank 1 |
| r1 frozen input | **17행 그대로** |
| r1 점수 행 | `[{product_id=1, score=17, ranking=1}]` 전체 행 불변 |

```text
{"status":"PUBLISHED","periodStart":"2026-11-23","periodEnd":"2026-11-30","snapshotId":"weekly-20261123-r2",
  "frozenAt":"2026-11-27T05:15:44Z","items":[{"productId":1,"productName":"S5 synthetic product","score":20,"rank":1,"calcRank":1}]}
```

늦게 도착한 사실이 이미 공개한 snapshot을 바꾸지 않았고, 새 revision이 복구를 반영했다. 주간 소속은 내부 최초 확정 시각이 결정했다. 기존 17건의 `confirmed_at`은 r1 공개 시점의 값과 모두 같았고, 복구된 3건에만 새 확정 시각이 생겼으며 모두 `2026-11-23 ~ 2026-11-29` 안이다.

### 6.4. 결제와 증거 불변

PG create 누적 20회, PG 거래 20건, 가용 재고 10개, 내부 SUCCESS 20건, UNKNOWN 0건이 후속 처리 뒤에도 유지됐다. `fault.json`과 체크섬의 바이트도 이 단계 전후로 동일하다(`faultEvidenceUnchanged=true`).

동일 실행 통합 테스트는 1건 통과, 실패·오류·skip 각 0건이다. 증거는 `apps/commerce-api/build/incident-evidence/20261127-051516-bb5ff22c/`에 생성하고 제출본을 보존했다. [실행 정보](evidence/20261127-051516-bb5ff22c/run.json), [BASELINE](evidence/20261127-051516-bb5ff22c/baseline.json), [FAULT](evidence/20261127-051516-bb5ff22c/fault.json), [FAULT 체크섬](evidence/20261127-051516-bb5ff22c/fault.json.sha256), [진단](evidence/20261127-051516-bb5ff22c/diagnosis.json), [r1](evidence/20261127-051516-bb5ff22c/ranking-r1.json), [복구](evidence/20261127-051516-bb5ff22c/recovery.json), [후속 결과](evidence/20261127-051516-bb5ff22c/downstream.json), [테스트 결과](evidence/20261127-051516-bb5ff22c/test-result.json).

## 7. S9 전 보완 검증

실행 `20261127-052251-d8d97825`에서 S8의 네 항목을 보완해 다시 전 단계를 이어 실행했다. 여섯 단계 모두 PASSED다.

| 보완 항목 | 이전 | 이번 관측 |
| --- | --- | --- |
| 중복 재발행의 실제 소비 완료 대기 | 수만 3초간 불변 확인 | 소비자 그룹 **committed offset 23**(20 + 재발행 3)까지 기다린 뒤 활동·장부 20행 불변 확인 |
| r1 frozen input 전체 행 | 행 수 17만 비교 | `(order_id, product_id, quantity, confirmed_at)` **17행 전체 내용** 동일 |
| Outbox·장부 event_id 집합 | 규칙 일치만 확인 | 양방향 차집합 0, 정렬 목록 비교로 **집합 일치** |
| 포인트·쿠폰 보존 | 빈 상태 유지 | 잔액 500,000 충전과 쿠폰 1건을 미리 심고 **복구 전후 동일** 확인 |

포인트는 기존 `POST /api/v1/points/charge`로 충전했고 쿠폰은 행만 심었다. 쿠폰과 PG의 도메인 구조는 바꾸지 않았다. 이로써 불변 조건이 "빈 상태 유지"가 아니라 **실제 데이터 보존**으로 검증된다.

r2는 `weekly-20261123-r2`로 공개되고 포인터 revision 2, 랭킹 API score 20이며 r1은 17로 남았다.

증거는 [실행 정보](evidence/20261127-052251-d8d97825/run.json), [r1](evidence/20261127-052251-d8d97825/ranking-r1.json), [복구](evidence/20261127-052251-d8d97825/recovery.json), [후속 결과](evidence/20261127-052251-d8d97825/downstream.json), [테스트 결과](evidence/20261127-052251-d8d97825/test-result.json)에 있다.

## 8. S10 최종 회귀 검증

작업 트리 최종 상태에서 `clean` 후 전 모듈을 다시 실행했다. 이전 실행의 산출물을 재사용하지 않았고 `UP-TO-DATE`로 넘어간 테스트 task 는 없다.

### 8.1. 실행 명령과 결과

```bash
./gradlew clean :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check
./gradlew :modules:jpa:check :modules:kafka:check :modules:redis:check :modules:ranking-schema:check \
          :supports:jackson:check :supports:logging:check :supports:monitoring:check
```

| 대상 | 실행 | 성공 | 실패 | 오류 | skip |
| --- | ---: | ---: | ---: | ---: | ---: |
| commerce-api `test` | 638 | 638 | 0 | 0 | 0 |
| commerce-api `crossAppTest` | 4 | 4 | 0 | 0 | 0 |
| commerce-streamer `test` | 43 | 43 | 0 | 0 | 0 |
| commerce-batch `test` | 24 | 24 | 0 | 0 | 0 |
| modules/kafka `test` | 3 | 3 | 0 | 0 | 0 |
| modules/jpa·redis·ranking-schema, supports/* | 0 | — | — | — | — |

마지막 줄은 **테스트가 없는 모듈**이다. 0건을 통과로 세지 않는다. `check`는 실행했고 Checkstyle 만 적용됐다.

Checkstyle 은 리포트가 생성된 11개 소스셋에서 모두 `errors=0`이다. commerce-api 의 `main`·`test`·`crossAppTest`, commerce-streamer·commerce-batch 의 `main`·`test`, modules/kafka 3개, modules/jpa·redis 2개씩, modules/ranking-schema 와 supports/jackson 각 1개다. supports/logging·monitoring 은 Java 소스가 없어 리포트가 없다.

ArchUnit(`ArchitectureTest`)은 3건 실행·0 실패다. 계층 의존 세 규칙이 적용됐다.

### 8.2. 누적 기능 회귀 (실행 건수)

| 기능 | 대표 테스트 | 실행 |
| --- | --- | ---: |
| PG 결제·조정 | `PaymentReconcileIntegrationTest` / `PaymentScheduledLookupIntegrationTest` / `PaymentV1ApiE2ETest` | 12 / 9 / 16 |
| 주문·결제 연동 | `OrderPaymentIntegrationTest` | 14 |
| adapter 계약 | `HttpPaymentGatewayContractTest` | 54 |
| Outbox·소비 | `OrderConfirmedOutboxRelayTest` / `PurchaseActivityIntegrationTest` | 9 / 3 |
| 쿠폰 | `Coupon*` 전체 | 96 |
| 랭킹 배치 | `WeeklyRankingJobRestartTest` / `OpenWeeklyRankingConvergenceTest` / `WeeklyRankingRevisionRejectTest` | 3 / 1 / 1 |
| 10주차 신규 | `PaymentAttemptRepositoryIntegrationTest` / `PaymentUnresolvedMetricsTest` / `PaymentUnresolvedMetricsPrometheusTest` / `PaymentApplyLogIntegrationTest` / `TestPaymentPgServerIncidentTest` / `HttpPaymentGatewayUnknownMappingTest` | 6 / 8 / 1 / 1 / 6 / 6 |

### 8.3. 최종 제출 증거

최종 제출 증거는 **`20261127-064920-a0cedcfa`** 하나다. S10 회귀에서 실행된 사고 테스트의 결과이며 BASELINE 부터 DOWNSTREAM 까지 **같은 runId**다. 서로 다른 실행의 관측을 섞지 않았다.

| 단계 | 결과 |
| --- | --- |
| baseline / fault / diagnose | PASSED |
| ranking-r1 / recovery / downstream | PASSED |

핵심 관측값은 committed offset 23, r1 frozen input 17행 유지, r2 `weekly-20261123-r2` 공개, 랭킹 API score 20, 포인트 잔액 500,000 과 쿠폰 1건 불변이다. `fault.json` 체크섬 검증은 OK 다.

개발 과정의 이전 실행 증거 4건(`20261126-221445-c02e381c`, `20261126-222348-37dab0a6`, `20261127-050500-5e8f1e57`, `20261127-051516-bb5ff22c`, `20261127-052251-d8d97825`)도 덮어쓰지 않고 그대로 보존했다.

### 8.4. 검증되지 않은 상태로 남긴 것

다음은 이번에도 **미검증**이다. 완료로 바꾸지 않는다.

- 운영 배포, 실계정, 실제 PG, 부하 상황, 월간 SLO
- 분산 추적. trace id 는 한 요청 안의 로그만 잇고 PG·Kafka 로 문맥을 전파하지 않는다
- 지표 집계 실패 후 스케줄러 자동 회복과 비활성 전환
- `STATUS_UNKNOWN` 트랜잭션 완료 경로의 실행
- management 엔드포인트 접근 제한 등 운영 보안 정책
- 운영자가 사용자를 대신해 복구하는 권한 경로
- 쿠폰 발급과 PG 결제의 상호 무영향을 직접 검증하는 회귀 테스트. 이번 사고 실행의 쿠폰 1건 보존 관측이 유일한 근거다

## 9. 후속 실험 기록란

| 단계 | 상태 | 이 문서에 추가할 결과 |
| --- | --- | --- |
| S7 복구 | 예정 | 동일 거래 키의 복구 전후 상태, 추가 PG create 여부, 재고 및 예약 정합성 |
| S8 후속 | 예정 | Outbox 및 Kafka 처리, 구매 활동·장부 정합성, 복구 전 r1과 복구 후 r2의 입력·점수 비교 |

후속 결과는 실제 실행의 runId 아래에 추가한다. 같은 lifecycle을 재실행했다면 그 실행에서 얻은 BASELINE, FAULT, DIAGNOSE를 함께 기록한다. 과거 실행의 관측을 다른 실행의 복구·랭킹 결과에 이어 붙이지 않는다.
