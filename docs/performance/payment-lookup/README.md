# PG 조회 지연과 Circuit Breaker 실험

이번 점진 적용 단계의 [시나리오·관측·정책·검증 기록](scenario-1-circuit-breaker.md)과 [새 실행 원본](runs/circuit-20261022-cancellation-fixed/summary.md)을 추가했습니다. 기존 `results/`는 이전 실행으로 보존했습니다. 새 기록에는 순차 비교와 별도로 10건/초·4초의 adapter 조회 비교가 있습니다.

다음 단계의 [조회 Retry 설계 메모](retry-design-notes.md)에 참고 글에서 가져온 중첩 순서·집계 단위·시간 예산과 우리 계약에 맞춘 검토 사항을 정리했습니다. 후속 Retry, RateLimiter, TimeLimiter 구현과 실험은 [정책 설계](scenario-2-4-design.md)와 [측정 결과](runs/policies-20261023/summary.md)에 기록했습니다.

기능 모듈 4개와 BOM으로 정리한 현재 구성, 실제 회로 OPEN 응답과 거래 키 매핑의 보강 검증은 [최종 구성 검증](runs/modules-20261023/summary.md)에 기록했습니다.

TimeLimiter를 끈 Retry backoff의 호출자 취소는 [취소 경계 검증](runs/interrupt-20261023/summary.md), 복수 거래의 충돌 보존과 DB ENUM 확장은 [복수 거래 검증](runs/multiple-transactions-20261023/summary.md)에 기록했습니다.

검사 종료 뒤 임시 디렉터리와 미사용 PG 이미지를 정리했습니다. 모니터링 스택은 후속 측정에 사용하도록 유지합니다. [자원 정리 결과](runs/cleanup-20261023/summary.md)

후속 섹션 6 점검에서는 실제 PG 성공 뒤 저장 실패의 재조회 복구, 거래 키와 거래 수 단언, loopback 수신과 테스트 이미지 자동 정리를 보강했습니다. 기존 Testcontainers 이미지와 중간 빌드 이미지도 확인해 정리했습니다. [섹션 6 최종 검증](runs/section6-20261023/summary.md)

최종 코드로 조회 회복 실험 전체를 다시 실행했습니다. BOM으로 `resilience4j-core`까지 2.4.0에 맞춘 뒤의 기록이며, 호출 수와 상태 전이는 이전과 같고 시간은 수 ms 범위에서 달랐습니다. [최종 코드 재실행](runs/rerun-20261023/summary.md)

## 질문

HTTP timeout을 이미 적용한 결제 조회에 Circuit Breaker를 더하면 실제 PG 조회 수와 호출자의 대기 시간이 어떻게 달라지는가?
조회가 차단돼도 기존 결제·주문·재고 상태를 유지하며, PG가 회복한 뒤 기존 거래를 확인할 수 있는가?

## 실험 경계

- test-pg는 Commerce와 Java 객체·DB를 공유하지 않는 별도 프로세스다. 실제 HTTP를 사용한다.
- `PUT /test-control/v1/lookup`의 `delayMillis`로 조회 응답만 지연시킨다. 제어 API·결제 생성·거래 결과는 별도다.
- 오류를 무작위로 발생시키지 않고 지연 시작·해제와 호출 순서를 고정한다.
- 생성 POST의 자동 재시도는 하지 않는다. 조회에도 첫 비교 실험에서는 자동 retry를 섞지 않는다.
- 실제 운영에 적합한 임계값은 이 작은 실험만으로 결정하지 않는다. 실험 설정과 운영 정책을 구분한다.

## 진행 순서와 판정

| 단계 | 조작 | 관측·판정 |
| --- | --- | --- |
| 기준선 정상 | 조회 지연 0ms, Circuit Breaker 없음 | 조회 결과·요청 수·대기 시간 기록 |
| 기준선 지연 | HTTP timeout보다 긴 조회 지연 | 호출마다 timeout·실제 PG 요청 증가, 거래 수·생성 수 보존 |
| 회로 적용 | 같은 조회·같은 지연 | 최소 표본 이후 CLOSED → OPEN, 이후 호출의 PG 요청 증가 0 |
| 장애 지속 탐색 | OPEN 대기 뒤 지연을 유지 | HALF_OPEN 탐색도 실패하면 재차 OPEN |
| 회복 탐색 | PG를 재시작하지 않고 지연만 0ms로 해제 | OPEN 대기 뒤 HALF_OPEN → CLOSED, 같은 거래 조회 재개 |
| 업무 상태 확인 | 미완료 주문에서 위 흐름 반복 | 차단·timeout 동안 종결·재고 반환 없음. 검증한 최종 PG 결과만 한 번 반영 |

## 기록할 값

- 실행 시각, JDK·Spring·Resilience4j 버전, commit과 작업 트리 변경 여부
- HTTP 연결·응답 timeout, 지연 값, 호출 수와 동시성
- 회로의 최소 표본·슬라이딩 윈도·실패 조건·OPEN 대기·탐색 수
- 요청별 결과·소요 시간·회로 상태와 상태 전이 이벤트
- PG의 실제 create·lookup·외부 거래 수 변화
- 내부 주문·시도 상태, 가용 재고·포인트의 전후 값

시간 측정은 부하 테스트의 p95·p99 자료와 구분한다. 소수의 순차 호출은 상태 전이와 실제 발송 여부를 확인하기 위한 것이다.
실험 결과가 아직 없는 항목을 성공으로 작성하지 않는다.

## 이번 실험의 구성

현재 의존성은 CircuitBreaker, Retry, RateLimiter, TimeLimiter 네 기능 모듈만 선언하고 Resilience4j BOM으로 core를 포함해 2.4.0에 맞춥니다. 정책은 직접 decorator로 연결하며 Spring Boot starter와 자동 Registry, AOP aspect는 추가하지 않습니다. 실험 로그와 PG 통계로 호출 수와 회로 전이를 확인하며 Resilience4j의 Prometheus 지표는 연결하지 않았습니다. 기존 측정 원본과 중간 starter 전환 기록은 당시 환경의 기록으로 보존합니다.
`CircuitBreakingPaymentGateway`가 실제 HTTP adapter를 감싸며, 두 조회 GET은 `pgLookup` 회로 하나를 공유한다. 생성 POST는 회로를 통과하지 않는다.
기본 프로필에서는 비활성화하며 첫 회로 비교는 `pg-lookup-experiment` 프로필로 실행합니다. 프로필의 값은 운영 정책으로 승인한 값이 아니다.

| 항목 | 실험 값 | 선택 이유 |
| --- | --- | --- |
| 실제 HTTP 응답 timeout | 500ms | 주입한 1500ms 지연으로 확실히 timeout을 관찰 |
| 관찰 창 / 최소 표본 | 최근 5회 / 5회 | 적은 호출로 전이를 재현. 동시 호출 한도가 아님 |
| 실패율 | 50% 이상 | 이번 단계는 5회 연속 조회 불가로 OPEN을 관찰 |
| OPEN 대기 | 2초 | 재차단·회복을 짧은 실험에서 관찰 |
| HALF_OPEN 허가 | 2회 | 탐색 일부 성공만으로 바로 회복하지 않음 |
| 느린 호출 판정 | 60초 / 비율 100% | 첫 실험의 500ms HTTP 예산에서는 실패율 효과를 분리 |
| 자동 HALF_OPEN 전환 | 끔 | 대기 후 다음 조회가 탐색을 시작. 타이머만 지나도 즉시 HALF_OPEN이 되는 설정이 아님 |
| retry | 없음 | 사용자 요청 수와 실제 HTTP 호출 수를 직접 비교 |

`Found`(거래의 PENDING·SUCCESS·FAILED), `NotFound`, 빈 목록은 조회 성공으로 센다. `Unavailable`은 조회 실패로 센다.
현재 `Unavailable`에는 네트워크·HTTP 오류·응답 형식 오류가 포함된다. 운영 적용 전 오류 원인별 분류와 임계값을 다시 검토한다.
OPEN·허가 소진에 의한 로컬 차단은 `Unavailable("CIRCUIT_OPEN")`으로 돌려주며 외부 실패 표본을 추가하지 않는다.

## 재현

저장소 루트에서 실행한다. JDK 21과 Docker가 필요하며 기존 로컬 PG·DB 대신 Testcontainers가 별도 PG·MySQL을 실행한다.
PG는 수동 처리 모드이고 업무 검증 주문은 `SKIP_CALLBACK`으로 등록해 자동 callback·스케줄러의 추가 조회가 표본에 섞이지 않도록 한다.

```bash
./gradlew :apps:commerce-api:test \
  --tests '*CircuitBreakingPaymentGatewayTest' \
  --tests '*PgLookupBaselineExperimentTest' \
  --tests '*PgLookupCircuitBreakerExperimentTest' \
  --tests '*OrderPaymentCircuitBreakerE2ETest'
python3 docs/performance/payment-lookup/collect-results.py
```

기준선은 같은 HTTP 설정에서 정상·지연을 각각 순차 8회 조회한다. 회로 비교는 처음 5회 실패와 다음 3회 차단을 같은 8회 구간으로 비교한다.
장애 지속 탐색 2회와 회복 탐색 2회는 별도 구간이다. 이 시간을 최초 8회 응답 시간에 합치지 않는다.
측정 기록은 JUnit XML 출력에서 수집하고 commit·작업 트리 변경 여부·주요 소스 SHA-256을 함께 보관한다.
json 원본은 저장소에 올리지 않고 로컬에 보관한다. 아래와 각 실행 요약에 적은 json 경로는 로컬 원본의 위치다.

- [실측 비교 표](results/summary.md)
- 기준선 원시 기록(`results/baseline.json`)
- 회로 상태·호출 원시 기록(`results/circuit.json`)
- 실제 reconcile·DB 결과(`results/business.json`)

## 공식 참고 자료

- [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker): 최소 표본, 실패율·느린 호출 비율, 상태 전이와 호출 허가.
- [Resilience4j Spring Boot 연결](https://resilience4j.readme.io/docs/getting-started-3): starter·AOP 요구사항과 decorator/annotation 선택 시 확인할 점.
- [Resilience4j 2.4.0 릴리스](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0): 도입 후보 버전의 변경 내역. 실제 적용 버전은 의존성 해석 결과와 함께 기록한다.

## 후속 정책 재현

설정 파일은 `apps/commerce-api/src/main/resources/pg-test/`에 모았습니다. `application.yml`에서 직접 import하고 파일의 프로필 조건으로 적용 범위를 정합니다. 기존 `pg-lookup-experiment`, `pg-retry-experiment`, `pg-rate-limit-experiment`, `pg-time-limit-experiment` 프로필 이름을 유지합니다. 최종 순서는 `CircuitBreaker → TimeLimiter → Retry → RateLimiter → HTTP`이며 기본 프로필은 비활성화입니다. 생성 POST는 조회 정책을 우회합니다.

```bash
./gradlew :apps:commerce-api:check :apps:test-pg:check
python3 docs/performance/payment-lookup/collect-policy-results.py
```

조회 503은 전역 제어의 `failureCount`로 통제합니다. RateLimiter 비교는 정상 거래 하나를 초당 50회, 총 200회 조회하며, 지연 비교는 각 조건 3회입니다. 정상 주문 5분 SLO 측정과 경계가 다릅니다. 원본 수집기는 JUnit 출력의 실제 횟수와 지연, 환경과 소스 해시를 보존합니다.

## 결과 파일을 분리한 전체 검사

동시에 실행한 Gradle 검사의 XML 결과 파일이 겹치지 않도록 프로젝트별 build 디렉터리와 프로젝트 캐시를 분리할 수 있습니다. 저장소의 [init script](isolated-build.init.gradle)를 사용하며, Commerce가 실행하는 PG jar 경로도 함께 바꿉니다. 테스트 프로필과 검사 규칙은 동일합니다.

```bash
pg_check_tmp="$(mktemp -d)"
./gradlew \
  --init-script docs/performance/payment-lookup/isolated-build.init.gradle \
  --project-cache-dir "$pg_check_tmp/project-cache" \
  "-Dpg.isolated.build=$pg_check_tmp/build" \
  :apps:commerce-api:check :apps:test-pg:check
```

결과는 `$pg_check_tmp/build/apps/commerce-api/build/test-results/test`와 `$pg_check_tmp/build/apps/test-pg/build/test-results/test`에 저장됩니다. 이전 실행 JSON의 `command`는 당시 실제 임시 경로를 보존한 기록이며, `reproductionCommand`로 저장소의 스크립트를 사용해 같은 검사를 재현할 수 있습니다.
