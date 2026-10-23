# PG 조회 CircuitBreaker

이번 문서는 기존 PG 거래 조회에 적용한 CircuitBreaker만 설명한다. PG 생성과 주문·재고의 트랜잭션은 동작 배경이며 별도 리뷰 요청에서 제외한다.

## 동작 배경

PG 생성은 로컬 TX A commit 뒤 수행하고, 확인한 결과는 TX B에서 주문과 재고에 반영한다. callback과 reconcile은 기존 거래를 권위 조회하며, 일반 주문 GET은 저장 상태만 읽는다. 회로는 이 기존 거래 조회를 보호한다.

## 적용 경계

- 두 조회 GET을 `CircuitBreaker → HTTP adapter`로 감싸고 하나의 pgLookup 회로를 공유한다.
- 생성 POST는 회로를 우회하며 자동 재시도하지 않는다.
- HTTP 연결 timeout은 기본 1초, 응답 timeout은 3초다. test는 응답 1초, 회로 실험은 500ms로 둔다.
- 다른 세 회복 정책과 k6는 이번 리뷰 범위에 포함하지 않는다.

## 실패 분류

| 관찰 | 회로 표본 |
| --- | --- |
| 정상 거래 조회: PENDING, SUCCESS, FAILED | 성공 |
| 단건 404와 빈 목록 | 성공 |
| timeout, 네트워크, HTTP 오류, 잘못된 응답으로 Unavailable | 실패 |
| 호출자 취소 CANCELLED | 성공과 실패에서 제외 |
| OPEN 중 로컬 차단 | 외부 호출과 새 실패 표본 없음 |

CANCELLED는 회로 안에서 CancellationException으로 전달해 ignoreExceptions로 제외하고, 회로 밖에서 확인 불가 관찰로 변환한다. HTTP adapter를 직접 연결한 경로에서도 이 기준을 유지한다.

## 상태 전이와 설정

COUNT_BASED 최근 5회와 최소 표본 5회, 실패율 50%, OPEN 2초, HALF_OPEN 허가 2회를 사용한다. 자동 HALF_OPEN 전환은 꺼져 있어 OPEN 대기 뒤 다음 조회가 탐색을 시작한다. 탐색 결과에 따라 CLOSED로 회복하거나 OPEN으로 재차단한다.

느린 호출은 60초와 100%로 두어 이번 재현에서는 HTTP timeout 실패율의 효과를 분리했다. 이 값은 동시 호출 한도나 운영 권장값이 아니다.

설정은 `src/main/resources/pg-test/circuit-breaker.yml`에 두고 pg-lookup-experiment에서 활성화한다. 기본 local, test에서는 꺼져 있다. 실험 프로필은 주기 조회도 끈다. 의존성은 CircuitBreaker와 core 2.4.0이며 BOM으로 버전을 맞춘다.

## Fallback과 회복

회로 OPEN과 timeout은 외부 결제 실패의 증거가 아니다. 저장된 주문·시도와 예약을 유지하고 reconcile에는 lookupUnavailable, callback에는 조회 불가 503을 반환한다. 가짜 성공, 포인트 대체와 예약 반환을 만들지 않는다.

회복 뒤 기존 거래를 조회해 검증한 결과만 한 번 반영한다. 기존 결제가 미확인이라는 이유로 생성 POST를 다시 보내지 않는다.

## 검토 코드와 테스트

- [CircuitBreakingPaymentGateway](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/CircuitBreakingPaymentGateway.java), [회로 설정](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerConfiguration.java)
- [표본과 취소 단위 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/CircuitBreakingPaymentGatewayTest.java)
- [상태 전이 검증](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerExperimentTest.java), [동시 도착 검증](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/payment/PgLookupCircuitBreakerArrivalExperimentTest.java)
- [실제 reconcile 회로 E2E](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderPaymentCircuitBreakerE2ETest.java)

## 검증과 실행 전제

순수 회로 단위 테스트는 외부 PG 없이 실행한다. 실제 HTTP, DB와 callback 검증은 [PG 기반의 fixture 계약](pg-payment.md#검증과-외부-fixture)에 따라 로컬 실행 기반을 별도로 제공한다. 테스트를 삭제하거나 skip하지 않는다. 운영 임계값과 기본 활성화는 미정이다.

실제 회로 브랜치에서 외부 fixture 없이 회로·설정 단위 테스트 8개를 통과했다. fixtureRoot를 제공한 서비스 check는 테스트 668개, 실패·오류·건너뜀 0이며 Checkstyle과 ArchUnit도 통과했다.

```bash
./gradlew :apps:commerce-api:test --tests '*CircuitBreakingPaymentGatewayTest' --tests '*PgExperimentConfigTest'
./gradlew :apps:commerce-api:check -PpgFixtureRoot=/path/to/pg-fixture
```
