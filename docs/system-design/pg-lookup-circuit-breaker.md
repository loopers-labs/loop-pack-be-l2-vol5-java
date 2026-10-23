# PG 조회 회복 정책

기존 PG 거래 조회에 CircuitBreaker, Retry, RateLimiter와 TimeLimiter를 함께 적용한다. PG 생성과 주문·재고의 트랜잭션은 기존 경계를 유지한다.

## 동작 배경과 적용 경계

PG 생성은 로컬 TX A commit 뒤 수행하고, 확인한 결과는 TX B에서 주문과 재고에 한 번 반영한다. callback과 reconcile은 기존 거래를 권위 조회하며, 일반 주문 GET은 저장 상태만 읽는다.

두 조회 GET은 거래 키 조회와 주문 식별자 조회다. 두 경로가 하나의 pgLookup 회로와 인스턴스별 호출 한도를 공유한다. 실행 순서는 다음과 같다.

```text
CircuitBreaker → TimeLimiter → Retry → RateLimiter → HTTP adapter
```

- 회로 OPEN이면 안쪽 정책과 실제 HTTP에 진입하지 않는다.
- TimeLimiter는 재시도와 backoff를 포함한 논리적 조회 전체를 제한한다.
- Retry의 각 실제 HTTP 시도는 RateLimiter 허가를 소비한다.
- 생성 POST는 모든 조회 정책을 우회하며 자동 재시도하지 않는다. 미확인 생성 요청을 다시 발송하지 않는다.
- HTTP 연결 timeout은 기본 1초, 응답 timeout은 3초다. test의 응답 timeout은 1초, 실험 프로필은 500ms다.

## CircuitBreaker

COUNT_BASED 최근 5회와 최소 표본 5회, 실패율 50%, OPEN 2초, HALF_OPEN 허가 2회를 사용한다. 자동 HALF_OPEN 전환은 꺼져 있어 OPEN 대기 뒤 다음 조회가 탐색을 시작한다. 탐색 결과에 따라 CLOSED로 회복하거나 OPEN으로 재차단한다.

느린 호출은 60초와 100%로 두어 이번 재현에서는 HTTP timeout 실패율을 관찰한다. 이 값은 동시 호출 한도나 운영 권장값이 아니다.

회로는 재시도 각각이 아닌 논리적 조회 하나의 최종 결과를 집계한다.

| 관찰 | 회로 표본 |
| --- | --- |
| 정상 거래 조회: PENDING, SUCCESS, FAILED | 성공 |
| 단건 404와 빈 목록 | 성공 |
| timeout, 네트워크, HTTP 오류, 잘못된 응답으로 Unavailable | 실패 |
| 전체 조회 예산 초과 DEADLINE_EXCEEDED | 실패 |
| 로컬 호출 제한 RATE_LIMITED, 호출자 취소 CANCELLED | 성공과 실패에서 제외 |
| OPEN 중 로컬 차단 | 외부 호출과 새 실패 표본 없음 |

조회 정책 내부의 로컬 차단과 호출자 취소는 LocalRejection 예외로 회로에 전달하고 ignoreExceptions로 제외한다. 회로 밖에서 Unavailable 관찰로 변환한다. 전체 예산 초과는 실패 표본으로 기록한 뒤 같은 확인 불가 관찰로 변환한다.

## Retry와 RateLimiter

Retry는 최초 포함 최대 3회다. backoff는 100ms, 200ms에 ±20% jitter를 사용한다. HTTP 502, 503, 504와 네트워크 오류, 응답 timeout만 재조회한다. 다른 HTTP 오류, 잘못된 응답, 정상 업무 결과와 거래 없음, 로컬 차단과 취소는 재시도하지 않는다.

RateLimiter는 인스턴스마다 1초 주기로 20회 허가를 제공하고 대기 없이 초과 호출을 차단한다. 재시도도 매번 허가를 소비한다. 여러 인스턴스가 공유하는 전역 한도나 임의의 연속 1초 구간 한도를 보장하지 않는다.

## TimeLimiter와 취소

TimeLimiter는 재시도와 backoff를 포함한 논리적 조회 전체를 900ms로 제한한다. Java 21의 virtual thread에서 작업을 실행하고, 시간이 초과되거나 호출자가 중단되면 Future 취소와 하위 HTTP 중단을 요청한다. 취소 후 늦게 돌아온 결과를 버리고 후속 재시도를 막는다. 외부 PG 거래나 DB 결과 반영을 취소하는 장치는 아니다.

TimeLimiter 활성화 여부와 무관하게 Retry backoff 중 호출자가 interrupt되면 CANCELLED로 반환하고 interrupt 상태를 보존한다. 추가 HTTP를 보내지 않으며 회로 표본에서 제외한다.

## 설정과 활성화

설정은 src/main/resources/pg-test 아래 기능별 YAML에 둔다. 아래 프로필은 이 저장소의 비교 실험용 구성이며 Resilience4j의 표준 프로필 이름이 아니다.

| 프로필 | 활성화 정책 |
| --- | --- |
| 기본 local, test | 네 정책 모두 꺼짐 |
| pg-lookup-experiment | CircuitBreaker |
| pg-retry-experiment | CircuitBreaker, Retry |
| pg-rate-limit-experiment | CircuitBreaker, Retry, RateLimiter |
| pg-time-limit-experiment | 네 정책 모두 |

모든 실험 프로필은 주기 조회를 끄고 수동 reconcile을 사용한다. 기본 주기 조회와 재시작 복구는 PG 기반에서 검증한다. 정책값과 운영 활성화 여부는 별도 판단이 필요하며 실험값을 기본 프로필에 자동 반영하지 않는다.

Resilience4j BOM 2.4.0으로 직접, 전이 의존성 버전을 맞추고 circuitbreaker, retry, ratelimiter와 timelimiter 모듈만 추가한다. Java 설정과 직접 decorator를 사용하며 Spring Boot starter, 자동 Registry와 AOP는 사용하지 않는다. 호출 수와 회로 전이는 테스트 PG 통계와 실험 로그로 확인하고 Resilience4j Prometheus 지표 연결은 범위에 포함하지 않는다.

## Fallback과 회복

회로 OPEN, 로컬 호출 제한, 전체 예산 초과와 timeout은 외부 결제 실패의 증거가 아니다. 저장된 주문·시도와 재고 예약을 유지하고 reconcile에는 lookupUnavailable, callback에는 조회 불가 503을 반환한다. 가짜 성공, 포인트 대체와 예약 반환을 만들지 않는다.

회복 뒤 기존 거래를 조회해 검증한 결과만 한 번 반영한다. 조회 한도나 시간 예산 소진을 새 결제 허용 근거로 사용하지 않는다.

## 검토 코드와 테스트

- [회로 decorator](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/CircuitBreakingPaymentGateway.java), [조회 정책 decorator](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/ResilientPgLookupGateway.java)
- [회로 설정](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerConfiguration.java), [조회 정책 연결](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PgLookupPolicyConfiguration.java)
- [표본과 취소 단위 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/CircuitBreakingPaymentGatewayTest.java), [정책 합성과 취소 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/ResilientPgLookupGatewayTest.java)
- [프로필별 설정 테스트](../../apps/commerce-api/src/test/java/com/loopers/PgExperimentConfigTest.java), [정책별 실제 HTTP 검증](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupPolicyExperimentTest.java)
- [회로 전이](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerExperimentTest.java), [동시 도착](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerArrivalExperimentTest.java)
- [실제 reconcile 회로 E2E](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderPaymentCircuitBreakerE2ETest.java), [전체 정책 E2E](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderPaymentPoliciesE2ETest.java)

## 검증과 실행 전제

순수 회로·정책 단위 테스트는 외부 PG 없이 실행한다. 실제 HTTP, DB와 callback 검증은 [PG 기반의 fixture 계약](pg-payment.md#검증과-외부-fixture)에 따라 로컬 실행 기반을 별도로 제공한다. 테스트 PG, DB·Docker 변경과 k6·측정 원본은 제출 변경에서 제외하며, 테스트를 삭제하거나 skip하지 않는다. 실험 테스트의 표준 출력은 실제 호출 수와 결과를 남기는 기록이다.

```bash
./gradlew :apps:commerce-api:test --tests '*CircuitBreakingPaymentGatewayTest' --tests '*ResilientPgLookupGatewayTest' --tests '*PgExperimentConfigTest' --tests '*TestPgPaymentGatewayFailureClassificationTest'
./gradlew :apps:commerce-api:check -PpgFixtureRoot=/path/to/pg-fixture
```

로컬 통합 후 전체 check에서 테스트 702개, 실패·오류·건너뜀 0이며 Checkstyle과 ArchUnit도 통과했다. 회로·정책·프로필·실패 분류 단위 테스트 41개는 외부 fixture 없이 별도로 통과했다.
