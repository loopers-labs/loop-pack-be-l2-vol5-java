# 최종 코드 기준 조회 회복 실험 재실행

2026-10-23 04:13~04:17 KST(`2026-10-22T19:13Z~19:17Z`)에 현재 코드로 조회 회복 실험 전체를 다시 실행했습니다. 이전 기록은 Resilience4j BOM 적용 전이라 하위 `resilience4j-core`가 2.2.0이었습니다. 이번 실행은 기능 모듈 4개와 `core`가 모두 2.4.0이며, 취소 경계와 복수 거래 보완을 포함합니다.

- 기준 커밋 `56e31e8c`. 실행 당시 작업 트리에는 테스트 단언 1개와 test-pg `Dockerfile`, README 변경이 있었고, 실험 대상인 main 코드 변경은 없었습니다.
- Java 21.0.9, Spring Boot 3.4.4, Spring 6.2.5, Resilience4j 2.4.0입니다.
- 별도 test-pg 컨테이너에 실제 HTTP를 보내며, 조회 응답에만 지연과 오류를 줍니다. 결제 생성과 거래 상태는 바꾸지 않습니다.
- 같은 거래를 반복 조회하는 짧은 통제 실험입니다. 정상 주문 5분 부하의 SLO 측정과 경계가 다릅니다.

```bash
./gradlew :apps:commerce-api:test --rerun :apps:test-pg:test --rerun :apps:commerce-api:check :apps:test-pg:check
python3 docs/performance/payment-lookup/collect-results.py --include-arrivals --output docs/performance/payment-lookup/runs/rerun-20261023/circuit
python3 docs/performance/payment-lookup/collect-policy-results.py --out docs/performance/payment-lookup/runs/rerun-20261023/policies
```

Commerce 701개, test-pg 113개 테스트가 실패, 오류, 건너뜀 없이 통과했고 두 모듈의 Checkstyle과 Commerce ArchUnit도 통과했습니다. 실행 전후에 다른 Gradle 검사가 없음을 확인했습니다. 테스트 수(`policies/checks.json`), 환경과 소스 해시(`policies/environment.json`)

## Circuit Breaker

HTTP 응답 timeout 500ms, 조회 응답 지연 1,500ms, 최근 5개 중 실패율 50%, OPEN 2초, HALF_OPEN 탐색 2개입니다.

| 같은 거래 순차 조회 8회 | 실제 PG 조회 / 로컬 차단 | 요청 대기 합 | 요청 평균 |
| --- | --- | ---: | ---: |
| 지연 0ms, 회로 없음 | 8회 / 0회 | 19.698ms | 2.462ms |
| 지연 1,500ms, 회로 없음 | 8회 / 0회 | 4,040.813ms | 505.102ms |
| 지연 1,500ms, 회로 적용 | 5회 / 3회 | 2,522.556ms | 315.320ms |

주문 식별자 조회도 같은 결과입니다(지연 시 4,038.503ms, 회로 적용 시 2,520.017ms). 실패 탐색 2회와 회복 탐색 2회를 더하면 회로 적용 조건의 실제 PG 조회는 9회이며, `CLOSED → OPEN → HALF_OPEN → OPEN → HALF_OPEN → CLOSED`로 전이했습니다.

| 초당 10건, 4초 | 실제 PG 조회 / 로컬 차단 | 요청 대기 합 | 최대 시작 지연 |
| --- | --- | ---: | ---: |
| 거래 키, 회로 OFF | 40회 / 0회 | 20,130.655ms | 5.067ms |
| 거래 키, 회로 ON | 12회 / 28회 | 6,079.250ms | 5.069ms |
| 주문 식별자, 회로 OFF | 40회 / 0회 | 20,156.945ms | 5.089ms |
| 주문 식별자, 회로 ON | 12회 / 28회 | 6,056.133ms | 5.062ms |

거래 키 조회의 회로 ON에서 0.91초에 OPEN, 3.00초에 HALF_OPEN, 3.61초에 다시 OPEN이 됐습니다. OPEN 전에 시작한 10회와 탐색 2회가 실제로 발송됐습니다. [비교 표](circuit/summary.md), 도착률 원본(`circuit/arrivals.json`)

실제 reconcile과 MySQL의 업무 검증에서 지연 중 reconcile 8번은 모두 `202`였고 처음 5번 뒤 회로가 열렸습니다. 회복 탐색 2번으로 두 주문을 원래 거래로 확정했고, 상품별 가용 재고 8, 포인트 100,000, 주문별 생성 요청 1회를 유지했습니다. 종결 뒤 재확인은 PG 조회를 추가하지 않았습니다. 업무 원본(`circuit/business.json`)

## Retry, RateLimiter, TimeLimiter

| 첫 조회만 503 | Retry OFF | Retry ON |
| --- | --- | --- |
| 거래 키 | 실제 1회, Unavailable, 2.194ms | 실제 2회, Found, 124.628ms |
| 주문 식별자 | 실제 1회, Unavailable, 2.706ms | 실제 2회, Found, 119.346ms |

계속 503인 논리적 조회 5개는 실제 HTTP 15회 뒤 OPEN이 됐고, 이후 3개는 발송 없이 차단했습니다. HALF_OPEN 탐색 2개에 각각 첫 시도 503, 두 번째 정상 응답을 주자 실제 HTTP 4회로 CLOSED가 됐습니다. 총 실제 조회는 19회, 생성과 거래는 각각 1건입니다.

| 초당 조회 50건, 4초 | RateLimiter OFF | RateLimiter ON |
| --- | ---: | ---: |
| 실제 PG 조회 | 200회 | 80회 |
| 로컬 차단 | 0회 | 120회 |
| 회로 실패 표본 | 0개 | 0개 |
| 회로 상태 | CLOSED | CLOSED |
| 최대 시작 지연 | 18.47ms | 5.008ms |

| 지연된 조회 3개 | TimeLimiter OFF | TimeLimiter ON |
| --- | --- | --- |
| 논리적 조회 한 번의 소요 시간 | 1,823.829~1,832.288ms | 903.733~919.320ms |
| 실제 PG 조회 합계 | 9회 | 6회 |
| 회로 실패 표본 | 3개 | 3개 |
| 최종 관찰 | TIMEOUT | DEADLINE_EXCEEDED |

네 정책을 모두 켠 `pg-time-limit-experiment`의 실제 reconcile, MySQL 검증에서 전체 시간 초과 뒤 `202`(937.565ms), 정상 조회 100건 중 81건이 로컬 제한된 직후 `202`, PG 성공 뒤 `200`, 종결 뒤 재확인 `200`이었습니다. 대기 중 가용 재고 8, 미완료 예약 2, 포인트 100,000을 유지했고, 확정 뒤 원래 거래 키, PG 생성 1건, 거래 1건, 미완료 예약 0을 확인했습니다. HTTP별 원본(`policies/http-experiments.json`), 업무 원본(`policies/business.json`)

## 이전 기록과 비교

수치는 이전 기록과 같은 범위입니다. 호출 수와 상태 전이는 모두 같았고, 시간은 측정마다 수 ms 차이가 있었습니다. 순차 지연의 대기 합은 4,043ms에서 4,041ms, 회로 적용은 2,521ms에서 2,523ms, Retry 성공은 102~114ms에서 119~125ms, TimeLimiter OFF는 1,788~1,821ms에서 1,824~1,832ms였습니다. [이전 회로 기록](../circuit-20261022-cancellation-fixed/summary.md), [이전 정책 기록](../policies-20261023/summary.md)
