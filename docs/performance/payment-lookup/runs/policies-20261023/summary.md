# Retry, RateLimiter, TimeLimiter 측정

2026-10-23 KST 실행입니다. 원본의 `recordedAt`은 UTC이며 `2026-10-22T15:...Z`가 같은 시각입니다. 커밋과 작업 트리, 소스 해시는 환경 원본(`environment.json`)에 보존했습니다. 실험 당시 작업 트리에 변경 사항이 있었으며 커밋은 만들지 않았습니다.

## 경계와 통제

- Java 21, Spring Boot 3.4.4, Spring 6.2.5, Resilience4j 2.4.0입니다.
- 별도 test-pg 컨테이너에 실제 HTTP를 보내며, 거래는 수동 처리합니다. 준비 단계에 정상 HTTP 조회를 확인한 뒤 측정합니다.
- 각 조건은 별도 거래, 새 회로와 새 RateLimiter로 시작합니다. PG 거래 상태와 create는 그대로 두고 조회 응답에만 오류와 지연을 줍니다.
- 최종 실행 순서는 `CircuitBreaker → TimeLimiter → Retry → RateLimiter → HTTP`입니다. 옵션을 하나씩 더한 전후를 비교합니다.
- 이번 경계 실험은 정상 주문 5분 SLO 측정과 다릅니다. 적은 표본의 시간 범위이며 p99나 장기 운영 보장을 뜻하지 않습니다.

## Retry

첫 조회에만 503을 반환합니다. 최대 총 3회, 100ms, 200ms에 ±20% jitter입니다. 502, 503, 504와 네트워크, 응답 timeout만 재시도합니다.

| 조회 경로 | Retry OFF | Retry ON |
| --- | --- | --- |
| 거래 키 | 1회, Unavailable, 3.62ms | 2회, Found, 102.44ms |
| 주문 식별자 | 1회, Unavailable, 6.59ms | 2회, Found, 113.52ms |

계속 503인 논리적 조회 5개는 HTTP 15회 뒤 OPEN이 됐습니다. 이후 3개는 발송 없이 차단했습니다. OPEN 대기 2초 뒤 HALF_OPEN 탐색 2개에 각각 503, 정상 결과를 주자 HTTP 4회로 CLOSED가 됐습니다. 총 HTTP는 19회이며 생성과 거래는 각각 1건입니다.

회로는 Retry 전체의 최종 관찰 하나를 집계합니다. 중간 HTTP 실패는 성공한 논리적 조회의 실패율에 남지 않으므로 실제 시도 수를 함께 기록합니다.

## RateLimiter

정상 PG에 조회 50건/초, 4초 동안 총 200개를 예약 시각에 맞춰 발송합니다. virtual thread로 작업을 예약했으며 실제 시작 지연도 원본에 기록했습니다. 20회/1초 고정 주기의 인스턴스별 공유 허가이고 대기는 0입니다.

| 초당 조회 50건, 4초 | RateLimiter OFF | RateLimiter ON |
| --- | --- | --- |
| 실제 PG 조회 | 200회 | 80회 |
| 로컬 차단 | 0회 | 120회 |
| 회로 실패 표본 | 0개 | 0개 |
| 회로 상태 | CLOSED | CLOSED |

최대 시작 지연은 OFF 31.87ms, ON 5.07ms였습니다. 200개 모두 실행됐으며 로컬 차단을 생성기 누락으로 세지 않습니다. 주기 경계를 걸친 임의의 연속 1초 구간이나 다중 인스턴스의 전역 한도는 보장하지 않습니다.

로컬 속도 제한, 호출자 취소는 회로의 성공, 실패 표본에서 모두 제외합니다. 별도 허가 2개 테스트에서는 첫 두 HTTP 실패 뒤 세 번째 시도가 로컬 차단돼 추가 HTTP와 회로 표본이 없었습니다.

## TimeLimiter

Retry와 RateLimiter는 켠 채 전체 시간 제한만 비교합니다. HTTP 응답 timeout 500ms, PG 조회 응답 지연 1500ms, 논리적 조회 전체 예산 900ms입니다.

| 지연 조회 3개 | TimeLimiter OFF | TimeLimiter ON |
| --- | --- | --- |
| 논리적 조회 한 번의 소요 시간 | 1,788.41~1,820.50ms | 905.35~924.62ms |
| 실제 PG 조회 합계 | 9회 | 6회 |
| 회로 실패 표본 | 3개 | 3개 |
| 최종 관찰 | TIMEOUT | DEADLINE_EXCEEDED |

각 조건의 작업 executor를 종료하고 실제 작업 종료를 기다린 뒤 PG 카운터를 확인했습니다. 실제 HTTP는 OFF 9회, ON 6회였고 취소 뒤 세 번째 재시도가 없었습니다. 별도 테스트는 HTTP 대기 중 deadline과 backoff 중 호출자 interrupt를 검증했습니다.

Future 대기의 900ms에 스케줄링과 취소 처리 비용이 더해질 수 있습니다. 작업 취소가 PG 서버의 처리나 거래 취소를 의미하지는 않습니다. 별도 Bulkhead는 추가하지 않았습니다.

## 실제 주문과 DB

최종 `pg-time-limit-experiment` 프로필에서 실제 reconcile HTTP와 MySQL을 사용했습니다.

- deadline 뒤 HTTP 202, 주문 PAYMENT_PENDING, 시도 PENDING을 유지했습니다.
- 정상 조회를 집중시켜 로컬 속도 제한을 확인하고 reconcile은 계속 202를 반환했습니다.
- 대기 중 가용 재고 8, 미완료 예약 수량 2, 포인트 100000을 유지했습니다.
- PG의 기존 거래가 성공한 뒤 reconcile은 200을 반환했습니다. 원래 transactionKey가 유지됐습니다.
- 재확인 후에도 가용 재고 8, 미완료 예약 0, 포인트 100000, PG 생성 1건, 거래 1건이었습니다.

HTTP별 원본(`http-experiments.json`), 업무 결과 원본(`business.json`)을 확인할 수 있습니다. 기존 CB 단독 검증도 전체 check에 포함돼 CLOSED, OPEN, HALF_OPEN 회복과 재차단을 검증했습니다.

## 검증과 재현

```bash
./gradlew :apps:commerce-api:check :apps:test-pg:check
python3 docs/performance/payment-lookup/collect-policy-results.py
```

Commerce 670개, test-pg 112개 테스트가 모두 통과했습니다. 두 모듈의 Checkstyle과 Commerce ArchUnit도 통과했습니다. 전체 테스트 수(`checks.json`)

TDD 실패 기록은 fixture Red(`fixture-red.json`), 오류 분류 Red(`classification-red.json`), 정책 Red(`policies-red.json`)에 보존했습니다. fixture Green 33개는 초기 조회 테스트만 실행한 결과이며 최종 전체 112개와 범위가 다릅니다.

첫 재시도 정책 테스트에서 허가 소모 기대값의 계산을 바로잡은 뒤 허가 2개의 작은 예제로 검증했습니다. 초기 Red 원본은 그대로 보존했습니다. 검사 기준이나 기존 테스트를 삭제, 완화하지 않았습니다.

Gradle 캐시와 Docker 접근, Mermaid 브라우저 실행은 샌드박스 밖 실행 권한으로 수행했습니다. 처음 차단된 명령은 환경 제약이었으며, 권한을 적용한 최종 검사에는 실패가 없었습니다.

## 측정 이후 의존성 정리

이 기록은 starter 전환 전 실행입니다. 당시 직접 선언한 CircuitBreaker, Retry, RateLimiter, TimeLimiter는 2.4.0이고, Spring Cloud의 의존성 관리로 하위 `resilience4j-core`는 2.2.0이 선택돼 있었습니다. 기존 환경 JSON의 `resilience4j: 2.4.0`은 직접 선언한 기능 모듈의 버전입니다. 원본 수치와 소스 해시는 변경하지 않았습니다.

이후 `resilience4j-spring-boot3:2.4.0`과 Resilience4j BOM으로 직접, 전이 모듈의 버전을 2.4.0으로 맞췄습니다. 정책과 취소 처리는 기존 decorator를 유지합니다.
