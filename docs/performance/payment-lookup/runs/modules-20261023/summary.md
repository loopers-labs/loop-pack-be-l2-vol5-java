# 직접 decorator 구성의 의존성 정리

현재 구현에서 사용하는 CircuitBreaker, Retry, RateLimiter, TimeLimiter 네 기능 모듈과 Resilience4j BOM을 남기고 Spring Boot starter를 제거했습니다. 개별 `implementation`에서는 버전을 생략하고 BOM 2.4.0으로 관리합니다. 정책 생성과 실행 순서, 취소 처리, 프로필 활성화는 바꾸지 않았습니다.

## 의존성 확인

```bash
./gradlew :apps:commerce-api:dependencyInsight --dependency resilience4j --configuration runtimeClasspath
```

결과에는 `core`, `circuitbreaker`, `retry`, `ratelimiter`, `timelimiter` 다섯 모듈만 있고 모두 2.4.0으로 선택됐습니다. Resilience4j의 Spring Boot, Spring 6, bulkhead, Micrometer 자동 연동 모듈은 제외됐습니다. 기존 Actuator와 Prometheus 의존성은 유지했습니다.

현재 관측 경로는 구조화한 실험 로그, 테스트 PG 통계와 DB 결과입니다. Resilience4j의 Registry를 통한 Prometheus 지표 연결은 이번 범위에 추가하지 않았습니다.

## 단언 보강

- 실제 회로 OPEN의 BLOCKED 단계에서 HTTP 202뿐 아니라 `lookupUnavailable: true`도 확인합니다. 차단 구간의 PG 조회 증가 0회와 예약 유지도 기존 단언으로 확인합니다.
- adapter 생성 계약 테스트에서 `Accepted` 타입뿐 아니라 `transactionKey: TEST-TX-0042` 매핑을 확인합니다.
- 대상 테스트 30개와 Checkstyle이 통과했습니다.

## 전체 검사

```bash
./gradlew :apps:commerce-api:check :apps:test-pg:check
```

Commerce 테스트 692개, 실패 0개이며 Checkstyle과 ArchUnit 2개도 통과했습니다. test-pg는 변경이 없어 `UP-TO-DATE`였고 기존 테스트 112개, 실패 0개 결과를 재사용했습니다. 실행 시간은 4분 14초입니다. 검사 결과와 소스 해시(`final-check.json`)

중간 starter 전환과 당시 검증 기록은 [이전 기록](../starter-20261023/summary.md)으로 보존했습니다. 기존 측정 표는 바꾸지 않았으며 정상 결제 5분 SLO 부하는 다시 실행하지 않았습니다. 설계 문서와 블로그 MD, HTML은 현재 구성으로 갱신했습니다.
