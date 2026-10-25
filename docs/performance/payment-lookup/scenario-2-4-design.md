# 조회 회복 정책의 후속 실험

사용자와 합의한 실험값으로 Retry, RateLimiter, TimeLimiter를 순차 적용합니다. 정상 요청량과 SLO는 유지하고, 생성 POST는 자동 재시도하지 않습니다. 기본 프로필에서는 새 정책을 끕니다.

## 정책과 실행 순서

```text
CircuitBreaker → TimeLimiter → Retry → RateLimiter → 실제 HTTP 조회
```

- Retry는 최초를 포함해 최대 3회입니다. 대기는 100ms와 200ms에 각각 ±20% jitter를 적용합니다.
- 재시도 대상은 HTTP 502, 503, 504, 네트워크 오류와 응답 timeout입니다. 4xx, 다른 HTTP 오류, 응답 형식 오류, 정상 PENDING과 FAILED, 빈 목록과 404는 재시도하지 않습니다.
- RateLimiter는 인스턴스의 두 조회가 공유합니다. 1초 주기마다 실제 HTTP 시도에 허가 20개를 제공하고 대기 없이 초과 호출을 차단합니다. 여러 인스턴스의 전역 한도나 임의의 연속 1초 구간 한도는 아닙니다.
- TimeLimiter는 재시도와 대기를 포함한 논리적 조회를 900ms로 제한합니다. Java 21의 virtual thread에서 실행하는 Future에 취소를 전달하고, 하위 작업 종료와 추가 발송 중단을 확인합니다.
- 회로는 논리적 조회 하나의 최종 결과를 집계합니다. 로컬 속도 제한과 호출자 취소는 회로 표본에서 제외하고, 외부 조회 지연으로 전체 예산을 넘긴 작업은 실패로 집계합니다.
- OPEN이면 TimeLimiter와 Retry, RateLimiter에 진입하지 않습니다. 생성 POST는 모든 조회 정책을 우회합니다.

## 책임과 변경 범위

- test-pg의 조회 제어에 일시적 HTTP 실패 횟수를 추가합니다. 거래 결과와 생성 요청은 바꾸지 않습니다.
- HTTP adapter는 재시도 가능한 오류와 나머지 조회 불가를 구분합니다.
- infrastructure의 별도 조회 decorator가 Retry, RateLimiter, TimeLimiter를 합성합니다. 주문과 재고의 결과 적용 경계는 재사용합니다.
- 실험 프로필에 승인된 값과 단계별 활성화를 기록합니다. Resilience4j 모듈은 기존과 같은 2.4.0을 사용합니다.

## 검증할 기대값

| 단계 | 통제할 문제 | 적용 후 기대값 |
| --- | --- | --- |
| Retry | 첫 조회 503, 다음 조회 정상 | 같은 거래 조회 2회, 회로의 논리적 성공 표본 1개 |
| Retry | 계속 503 | 실제 조회 최대 3회, 최종 실패 표본 1개 |
| Retry | 4xx와 형식 오류, 정상 업무 결과 | 추가 조회 없음 |
| RateLimiter | 정상 PG에 조회 50건/초 | 실제 HTTP 조회 감소, 로컬 차단은 회로 성공과 실패 모두에 미포함 |
| RateLimiter | 재시도로 실제 시도 증가 | 각 HTTP 시도에 허가를 소비하고 로컬 차단을 재시도하지 않음 |
| TimeLimiter | 500ms timeout과 재시도 대기가 누적 | 약 900ms에 호출자 대기 종료, 작업 취소 전달, 이후 추가 조회 없음 |
| 합성 | 회로 OPEN과 HALF_OPEN | OPEN의 실제 HTTP 0회, 탐색 허가 수와 실제 재시도 수를 각각 확인 |
| 업무 | 조회 실패와 차단 후 회복 | 주문 대기와 예약, 포인트 유지, 원래 거래 결과를 한 번만 반영 |

각 단계는 작은 실제 HTTP 비교와 DB 결과 적용 검증으로 기록합니다. 정상 결제 5분 측정과 adapter의 짧은 통제 실험은 각 경계 안에서 비교합니다. 시간 제한은 PG 거래 취소를 뜻하지 않습니다.

## 설정 파일 배치

설정은 `apps/commerce-api/src/main/resources/pg-test/`에 모읍니다. `application.yml`의 실험 프로필 문서에서 직접 import하며 파일 간 연쇄 import는 사용하지 않습니다.

| 파일 | 활성화할 실험 프로필 |
| --- | --- |
| `circuit-breaker.yml` | 네 실험 프로필 모두 |
| `retry.yml` | `pg-retry-experiment`, `pg-rate-limit-experiment`, `pg-time-limit-experiment` |
| `rate-limiter.yml` | `pg-rate-limit-experiment`, `pg-time-limit-experiment` |
| `time-limiter.yml` | `pg-time-limit-experiment` |

일반 `local`은 기존 응답 timeout 3초와 회로 비활성화를 유지합니다. 일반 `test`도 기존 응답 timeout 1초와 회로 비활성화를 유지합니다. 실험 프로필을 함께 지정할 때만 500ms와 단계별 정책을 적용합니다. 과거 측정 JSON의 원본 경로와 소스 해시는 이동 전 기록으로 보존합니다.

설정 이동 전후에 `PgExperimentConfigTest`의 프로필 조합 10개와 기존 설정 검사 4개가 통과했습니다. 이동 후 `:apps:commerce-api:check`에서 전체 테스트 680개, Checkstyle, ArchUnit도 통과했습니다.

## 직접 decorator의 의존성

의존성은 CircuitBreaker, Retry, RateLimiter, TimeLimiter 네 모듈만 선언하고 Resilience4j BOM으로 core를 포함해 2.4.0으로 맞춥니다. 버전은 BOM에 두고 개별 `implementation`에는 반복하지 않습니다. BOM 적용 전에는 기능 모듈을 2.4.0으로 명시했어도 Spring Cloud 의존성 관리가 하위 `core`를 2.2.0으로 선택했습니다.

정책 생성과 실행 순서, 취소 처리는 기존 Java 설정과 decorator가 담당합니다. Spring Boot starter의 자동 Registry와 aspect를 사용하지 않아 해당 의존성을 제거했습니다. Resilience4j Prometheus 지표는 연결하지 않으며 관측은 실험 로그, 테스트 PG 통계와 DB 결과로 수행합니다. 기존 측정 JSON은 당시 값과 소스 해시를 그대로 보존합니다.

중간에 검토한 starter 전환과 당시의 검증 이력은 [전환 검증 기록](runs/starter-20261023/summary.md)에 보존했습니다. 현재 선택은 직접 decorator에 필요한 네 모듈과 BOM입니다.
