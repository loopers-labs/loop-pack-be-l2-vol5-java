# 시나리오 1: 지속적인 PG 조회 지연

## 질문과 경계

PG 조회가 HTTP timeout보다 오래 걸리는 상황에서, 같은 호출을 계속 보내는 비용을 Circuit Breaker가 줄이는지 확인합니다. PG 거래를 새로 만들거나 결과를 변경하는 시나리오는 추가하지 않습니다.

이번 실행에는 정상 주문 흐름의 5분 측정, adapter의 순차 조회, adapter의 고정 도착률 조회, 실제 reconcile·DB 검증이 있습니다. 각각의 경계와 기간이 다르므로 정상 부하의 p99와 순차 장애 호출 평균을 직접 비교하지 않습니다. 동일 경계의 OFF·ON끼리 비교합니다.

## 통제 조건

| 항목 | 조건 |
| --- | --- |
| 외부 PG | Testcontainers의 별도 HTTP 프로세스, MANUAL 처리 |
| 주입 지연 | 조회 응답에 1,500ms, 생성·거래 결과는 그대로 유지 |
| HTTP timeout | 연결 1초, 응답 500ms, OFF·ON 동일 |
| 순차 비교 | 조회 정상 OFF 8회 → 조회 지연 OFF 8회 → 같은 지연 ON의 최초 8회 |
| 동시 비교 | 4초간 초당 조회 10회, 총 40회, 작업 스레드 10개, OFF·ON 동일 |
| 회로 정책 | 창 5회·최소 표본 5회·실패율 50%·OPEN 대기 2초·HALF_OPEN 허가 2회 |
| 실패 분류 | `PaymentLookupObservation.Unavailable` 반환을 실패 표본으로 기록 |
| 기타 | 자동 retry 없음, 생성 POST는 조회 회로 밖 |

500ms·1,500ms와 작은 표본 수는 통제된 실패와 전이를 빠르게 재현하기 위한 실험값입니다. 정상 부하의 응답 timeout 3초를 운영 정책으로 500ms로 바꾼 것이 아닙니다. 기본 프로필의 Circuit Breaker는 계속 비활성입니다.

조회 지연은 테스트 전용 `PUT /test-control/v1/lookup`에 `{"delayMillis":1500}`을 보내고, 회복 때 0으로 바꿉니다. PG를 재시작하지 않습니다. 별도 순차 실험에서는 장애가 계속되는 HALF_OPEN 탐색과 지연 해제 뒤 회복 탐색을 구분합니다.

## 문제 관측

응답 timeout만 둔 상태에서 지연된 순차 조회 8회가 모두 실제 PG로 나갔습니다. 거래 키 조회의 요청 대기 합은 4,042.977ms였고 모두 `Unavailable`로 끝났습니다. 한 호출의 대기는 제한했지만 다음 호출도 같은 지연을 반복해서 기다렸습니다.

## 적용과 결과

기존 Resilience4j 2.4.0 core decorator를 활성화했습니다. `lookup`·`lookupByOrder`는 `pgLookup` 회로를 공유하고 `create`는 직접 adapter로 위임합니다. adapter가 예외를 `Unavailable` 반환값으로 바꾸므로 `recordResult`로 이 결과를 실패 표본에 넣습니다.

| 거래 키 조회, 순차 8회 | 실제 PG 조회 | 로컬 차단 | 요청 대기 합 |
| --- | --- | --- | --- |
| 지연 1,500ms·OFF | 8회 | 0회 | 4,042.977ms |
| 지연 1,500ms·ON | 5회 | 3회 | 2,521.099ms |

| 조회 10건/초·4초 | 회로 | 실제 PG 조회 | 로컬 차단 | 요청 대기 합 |
| --- | --- | --- | --- | --- |
| 거래 키 | OFF | 40회 | 0회 | 20,139.931ms |
| 거래 키 | ON | 12회 | 28회 | 6,047.959ms |
| 주문 식별자 | OFF | 40회 | 0회 | 20,138.219ms |
| 주문 식별자 | ON | 12회 | 28회 | 6,047.116ms |

동시 비교의 개별 호출 시작 지연은 최대 약 5ms였습니다. 대기 합은 동시 요청 각각의 소요 시간을 더한 값으로, 실험의 벽시계 실행 시간이 아닙니다. 로컬 차단을 PG 성공으로 집계하지 않습니다.

동시 비교에서는 최소 표본이 5개여도 실제 PG 조회가 5회에서 멈추지 않았습니다. 이번에는 CLOSED 동안 이미 허가된 10회와 HALF_OPEN 탐색 2회가 나갔습니다. OPEN은 이미 발송한 호출을 되돌리지 않습니다.

## 회복과 업무 상태

순차 실험에서 다음 전이를 자연 호출로 확인했습니다. 상태를 강제로 바꿔 결과를 만든 비교가 아닙니다.

```text
CLOSED → OPEN
OPEN → HALF_OPEN → OPEN       (지연 유지, 탐색 실패)
OPEN → HALF_OPEN → CLOSED     (지연 해제, 탐색 성공)
```

실제 Commerce reconcile·MySQL 검증에서는 다음을 확인했습니다.

- timeout·회로 차단에 HTTP 202와 기존 PAYMENT_PENDING·PENDING 상태를 유지합니다.
- 상품별 가용 재고는 예약 후 8개로 유지하며 포인트는 사용자별 100,000으로 유지합니다.
- PG를 성공 처리하고 지연을 해제한 뒤 두 기존 거래를 조회해 두 주문을 확정합니다.
- 원래 transactionKey를 보존하며 주문당 create·외부 거래는 각각 1회입니다.
- 종결 뒤 reconcile 재요청은 PG 조회와 추가 재고 효과를 만들지 않습니다.

## 재현과 원본

```bash
./gradlew :apps:commerce-api:test \
  --tests '*CircuitBreakingPaymentGatewayTest' \
  --tests '*PgLookupBaselineExperimentTest' \
  --tests '*PgLookupCircuitBreakerExperimentTest' \
  --tests '*PgLookupCircuitBreakerArrivalExperimentTest' \
  --tests '*OrderPaymentCircuitBreakerE2ETest' \
  --tests '*TestPgPaymentGateway*Test'
python3 docs/performance/payment-lookup/collect-results.py --include-arrivals \
  --output docs/performance/payment-lookup/runs/circuit-next
```

보완 후 계약·취소 재현·회로·업무 테스트 43개가 모두 통과했습니다. [이번 실행의 비교 표](runs/circuit-20261022-cancellation-fixed/summary.md)와 상태·호출 원본(`runs/circuit-20261022-cancellation-fixed/circuit.json`), 도착률 원본(`runs/circuit-20261022-cancellation-fixed/arrivals.json`), reconcile·DB 원본(`runs/circuit-20261022-cancellation-fixed/business.json`)을 보존했습니다. 이전 `results/` 기록은 덮어쓰지 않았습니다.

## 전체 검사에서 발견한 취소 예외

대상 테스트 14개는 통과했지만 첫 전체 `check`는 동시 조회 중 직접 `CancellationException`이 올라와 실패했습니다. 첫 전체 검사 실패(`runs/circuit-20261022T115131Z/full-check-failure.json`)를 보존했습니다.

스택은 Spring JDK request factory의 응답 timeout 처리에서 Future를 직접 취소하는 경로를 가리켰습니다. [Spring 6.2.5 소스](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.5/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequest.java)에서는 `ExecutionException`에 싸인 취소를 timeout으로 처리하지만, `get()`이 직접 던지는 취소는 별도 경로입니다.

취소된 JDK 응답 Future를 실제 Spring request factory·RestClient에 연결한 재현 테스트에서 생성·두 조회 모두 의도대로 실패했습니다. Red 기록(`runs/circuit-20261022T115131Z/cancellation-red.json`)을 남기고, adapter가 `CancellationException`도 생성 UNKNOWN·조회 Unavailable로 처리하도록 최소 수정했습니다. 취소는 외부 효과가 없다는 증거가 아니므로 실패 확정·예약 반환·자동 재발송으로 연결하지 않습니다. 보완 후 세 재현 테스트의 Green 기록(`runs/circuit-20261022-cancellation-fixed/cancellation-green.json`)도 남겼습니다.

## 최종 검사

`./gradlew :apps:commerce-api:check :apps:test-pg:check`가 통과했습니다. Commerce 635개·test-pg 97개 테스트와 양쪽 Checkstyle·ArchUnit을 확인했습니다. 첫 전체 검사 실패와 Red 기록을 보존했으며, 최종 검사 기록(`runs/circuit-20261022-cancellation-fixed/full-verification.json`)을 추가했습니다.

## 한계와 다음 질문

확인한 효과는 지속 지연에 대한 외부 조회와 호출자 대기의 감소, 상태 보존과 회복입니다. 장애 중 결제 결과를 계속 제공하는 가용성이나 상품 API의 SLO는 검증하지 않았습니다. 네트워크·HTTP·형식 오류를 포괄하는 `Unavailable` 분류와 임계값은 운영 적용 전에 별도 검토가 필요합니다.

다음 Retry 단계에서는 조회가 한 번 실패한 뒤 회복하는 조건을 통제하고, 재조회가 성공률·총 지연·실제 호출 수에 주는 영향을 비교합니다. 이 문서에는 Retry를 적용한 결과를 포함하지 않습니다.
