# Spring Boot starter 의존성 전환

의존성을 `resilience4j-spring-boot3:2.4.0` 하나로 정리했습니다. Resilience4j BOM도 적용해 관련 전이 모듈의 버전을 맞췄습니다. 정책 생성, 실행 순서와 취소 처리는 기존 Java 설정과 decorator가 담당합니다.

## 의존성 확인

```bash
./gradlew :apps:commerce-api:dependencyInsight --dependency resilience4j-core --configuration runtimeClasspath
./gradlew :apps:commerce-api:dependencyInsight --dependency resilience4j --configuration runtimeClasspath
./gradlew :apps:commerce-api:dependencyInsight --dependency aspectj --configuration runtimeClasspath
```

전환 전에는 직접 선언한 네 기능 모듈은 2.4.0, 하위 `resilience4j-core`는 Spring Cloud 의존성 관리에 의해 2.2.0으로 선택됐습니다. BOM 적용 후 core, circuitbreaker, retry, ratelimiter, timelimiter와 Spring 연동 모듈 모두 2.4.0으로 선택됐습니다.

Actuator는 기존 직접 의존성을 사용합니다. AOP 런타임인 `aspectjweaver:1.9.23`은 기존 `spring-boot-starter-data-jpa → spring-aspects` 경로로 제공됩니다.

## 검증 이력

정책 단위 테스트, 프로필 설정, 실제 주문 API와 MySQL을 사용하는 두 PG E2E 테스트는 통과했습니다.

첫 `./gradlew :apps:commerce-api:check`는 680개 중 70개가 Redis 연결 단계에서 실패했습니다. 테스트 Redis 포트에서 RESP 대신 HTTP 400 응답이 관측됐습니다. 첫 실패 기록(`first-check-failure.json`)을 보존했습니다. 연결 주소는 당시 테스트 컨테이너의 공개 포트와 일치했지만 HTTP 응답이 발생한 정확한 원인은 확정하지 않았습니다.

코드나 timeout 기준을 바꾸지 않고 새 테스트 컨테이너에서 실패했던 두 클래스를 재실행했고, 23개가 모두 통과했습니다. 재확인 결과(`redis-recheck.json`)

같은 코드의 전체 재검사에서 680개가 모두 통과했습니다. Checkstyle과 ArchUnit도 통과했습니다. 최종 검사 결과(`final-check.json`)

기존 측정 표와 JSON, 소스 해시는 전환 전 기록으로 보존했습니다. 이 변경은 정상 주문 5분 SLO 부하를 새로 측정한 결과를 포함하지 않습니다.
