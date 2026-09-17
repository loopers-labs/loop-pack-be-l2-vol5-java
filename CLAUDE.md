# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

Loopers 에서 제공하는 Spring Boot(Java 21) 멀티 모듈 커머스 템플릿 프로젝트입니다. `commerce-api`, `commerce-batch`, `commerce-streamer` 세 개의 실행 가능한 애플리케이션이 공통 모듈(`modules`)과 부가 기능 모듈(`supports`)을 공유하는 구조입니다.

세 앱은 서로의 프로젝트를 참조하지 않는 완전히 독립된 Gradle 모듈입니다. 도메인 모델(JPA Entity 등)을 공유하는 별도 모듈도 없기 때문에, 여러 앱이 같은 DB 테이블을 다뤄야 한다면 각 앱이 자신만의 엔티티/리포지토리를 각각 정의해야 합니다. `modules`가 앱들 사이에서 공유해주는 것은 도메인 모델이 아니라 인프라 설정과 테스트 환경(아래 참고)뿐입니다.

## 로컬 개발 환경

`local` 프로필로 애플리케이션을 실행하려면 먼저 인프라(MySQL, Redis)를 띄워야 합니다.

```shell
docker-compose -f ./docker/infra-compose.yml up
```

작업이 끝나면 종료합니다.

```shell
docker-compose -f ./docker/infra-compose.yml down
```

모니터링(Prometheus + Grafana)이 필요하면 별도로 실행합니다. 실행 후 http://localhost:3000 (admin/admin) 으로 접속합니다.

```shell
docker-compose -f ./docker/monitoring-compose.yml up
```

모니터링도 사용이 끝나면 종료합니다.

```shell
docker-compose -f ./docker/monitoring-compose.yml down
```

## 빌드 및 테스트 명령어

- 전체 빌드: `./gradlew build`
- 특정 앱 실행: `./gradlew :apps:commerce-api:bootRun` (`commerce-batch`, `commerce-streamer` 도 동일한 패턴)
- 전체 테스트: `./gradlew test`
- 특정 모듈 테스트: `./gradlew :apps:commerce-api:test`
- 특정 테스트 클래스만 실행: `./gradlew :apps:commerce-api:test --tests "com.loopers.domain.example.ExampleServiceIntegrationTest"`
- 특정 테스트 메소드만 실행: `./gradlew :apps:commerce-api:test --tests "com.loopers.domain.example.ExampleServiceIntegrationTest.returnsExampleInfo_whenValidIdIsProvided"`
- Jacoco 커버리지 리포트: `./gradlew :apps:commerce-api:jacocoTestReport` (XML만 생성됨, HTML/CSV는 비활성화)

테스트는 `apps`, `modules`, `supports` 등 컨테이너 프로젝트 자체에서는 실행되지 않습니다(루트 `build.gradle.kts`에서 해당 프로젝트들의 모든 task를 비활성화함). 반드시 하위 모듈(`:apps:commerce-api` 등)을 지정해서 실행해야 합니다.

테스트는 `maxParallelForks = 1`로 순차 실행되며, `spring.profiles.active=test`, `user.timezone=Asia/Seoul`이 강제 적용됩니다. 통합/E2E 테스트는 Testcontainers(MySQL, Redis, Kafka)를 사용하므로 Docker가 실행 중이어야 합니다.

## 멀티 모듈 구조

```
Root
├── apps      (실행 가능한 SpringBootApplication, 서로 독립적)
│   ├── commerce-api        - REST API. domain/application/infrastructure 4계층 예시(example)가 구현되어 있음
│   ├── commerce-batch      - 배치 작업(spring-boot-starter-batch). 현재는 데모 Job만 있고 도메인 레이어는 비어있음
│   └── commerce-streamer   - Kafka Consumer 애플리케이션. 현재는 데모 Consumer만 있고 도메인 레이어는 비어있음
├── modules   (특정 구현/도메인에 의존하지 않는 재사용 가능한 설정 + 테스트 환경)
│   ├── jpa    - DataSource/JPA/QueryDSL 설정, BaseEntity
│   ├── redis  - Redis 설정
│   └── kafka  - Kafka 설정
└── supports  (부가 기능 add-on)
    ├── jackson    - Jackson(Kotlin/JSR310) 모듈 설정
    ├── logging    - 로깅, Slack Appender
    └── monitoring - Actuator/Micrometer(Prometheus)
```

- `apps` 하위 모듈만 `BootJar`가 활성화되고(실행 가능한 jar), `modules`/`supports`는 일반 `Jar`로 라이브러리처럼 소비됩니다.
- 각 `apps` 모듈은 필요한 `modules`/`supports`를 `build.gradle.kts`의 `dependencies`에서 명시적으로 `implementation(project(":modules:..."))`로 선언합니다. 새 앱이나 도메인 기능을 추가할 때 어떤 모듈이 필요한지는 이 패턴을 따라 판단하면 됩니다.
- `annotationProcessor`(QueryDSL Q-타입 생성 등) 설정은 모듈 간에 전이(transitive)되지 않습니다. `modules:jpa`가 자신을 위해 선언한 `querydsl-apt`/`jakarta.persistence-api`/`jakarta.annotation-api` annotationProcessor는 이를 사용하는 `apps` 모듈에 자동으로 전파되지 않으므로, 자체 엔티티를 갖는 앱(`commerce-api`, `commerce-batch`, `commerce-streamer` 모두 해당)은 동일한 3줄을 자신의 `build.gradle.kts`에도 반복 선언해야 합니다.

### 앱 실행과 포트

`apps` 하위 3개 앱은 서로 독립된 프로세스이므로 필요할 때 개별적으로 `bootRun`하면 되지만, 포트가 자동으로 분리되어 있지 않아 동시에 띄울 때는 주의가 필요합니다.

- **`commerce-api`**: 웹 서버 기본 포트 `8080`, Actuator(관리) 포트는 `8081`(`supports/monitoring`의 `monitoring.yml`에서 고정).
- **`commerce-streamer`**: 웹 서버 기본 포트도 `8080`, Actuator도 동일하게 `8081`(같은 `monitoring.yml`을 가져다 씀) — `commerce-api`와 동시에 띄우면 포트가 충돌하므로, 둘을 같이 실행해야 한다면 한쪽에 `--server.port`/`--management.server.port`를 오버라이드해야 합니다.
- **`commerce-batch`**: `spring.main.web-application-type: none`으로 설정되어 있어 웹 서버 자체가 없습니다. 계속 떠 있는 서버가 아니라 배치 Job 하나를 실행하고 종료되는 프로세스입니다.

### `java-test-fixtures`를 통한 테스트 환경 재사용

`modules`, `supports`는 `java-library` + `java-test-fixtures` 플러그인을 사용해 테스트 유틸(Testcontainers 부트스트랩, 데이터 클린업 등)을 별도 소스셋(`src/testFixtures`)으로 노출하고, `apps`에서 `testImplementation(testFixtures(project(":modules:jpa")))` 형태로 가져다 씁니다. 덕분에 앱마다 "테이블 truncate 로직"이나 "Testcontainers 초기화 코드"를 중복 작성하지 않아도 됩니다.

모듈별 구현 상태는 다음과 같이 다릅니다:

- **`modules:jpa`** — 완성. `MySqlTestContainersConfig`(MySQL Testcontainers 기동), `DatabaseCleanUp`(모든 테이블 truncate)이 구현되어 있음.
- **`modules:redis`** — 완성. `RedisTestContainersConfig`, `RedisCleanUp`이 구현되어 있음.
- **`modules:kafka`** — 미완성. `build.gradle.kts`에 `java-test-fixtures` 플러그인과 `testFixturesImplementation("org.testcontainers:kafka")`가 선언되어 있지만, `src/testFixtures` 디렉토리 자체가 아직 없어 실제 Kafka Testcontainers 설정 클래스는 구현되어 있지 않습니다. `commerce-streamer`에서 Kafka 기반 통합 테스트가 필요하면 이 부분을 직접 구현해야 합니다.

## 애플리케이션 내부 레이어드 아키텍처

**아래 4계층 패턴은 현재 `commerce-api`의 `example` 도메인에만 실제로 구현되어 있는 템플릿 예시입니다.** `commerce-batch`, `commerce-streamer`는 아직 데모 코드(배치 Job, Kafka Consumer)만 있을 뿐 이 레이어 구조를 따르는 도메인 코드가 없으므로, 새로 도메인을 추가할 때 이 패턴을 그대로 이식하면 됩니다. 각 `apps` 모듈은 독립적이므로 동일 도메인이라도 앱마다 각자 이 4계층을 따로 구현해야 합니다(위 "멀티 모듈 구조" 참고).

각 `apps` 모듈은 도메인별로 다음 4개 레이어를 따릅니다 (`apps/commerce-api/src/main/java/com/loopers/{layer}/{domain}` 참고):

1. **`interfaces.api`** — Controller. API 스펙은 `{Domain}ApiSpec` 인터페이스(Swagger 어노테이션 등 문서화 목적)와 이를 구현하는 `{Domain}Controller`로 분리합니다. 요청/응답 DTO는 `{Domain}Dto`에 정적 record로 모아둡니다. 클래스 이름에는 API 버전(`V1`)을 붙이지 않고, 버전은 경로(`/api/v1/...`)로만 표현합니다. starter 예시(`ExampleV1Controller` 등)만 예외로 `V1`이 붙어 있습니다. 컨트롤러는 요청을 받아 `application` 레이어의 Facade만 호출하고, 응답은 공통 `ApiResponse<T>`(`interfaces.api.ApiResponse`)로 감쌉니다.
2. **`application`** — Facade + Info. `{Domain}Facade`는 하나 이상의 `domain.Service`를 조합(오케스트레이션)하고, 도메인 모델을 `interfaces` 레이어에 노출할 `{Domain}Info`(record)로 변환합니다. 여러 도메인을 넘나드는 유스케이스 조합은 이 레이어의 책임입니다.
3. **`domain`** — Model(JPA Entity), Service, Repository(인터페이스). 비즈니스 규칙(유효성 검증 등)은 Model 생성자/메소드 내부에서 `CoreException`을 던지는 방식으로 캡슐화합니다. Repository는 `domain` 레이어에 인터페이스로만 정의하고, 트랜잭션 경계는 `Service`에서 `@Transactional`로 관리합니다.
4. **`infrastructure`** — `domain.Repository` 인터페이스의 실제 구현체(`{Domain}RepositoryImpl`)와 Spring Data JPA 인터페이스(`{Domain}JpaRepository`)가 위치합니다. `RepositoryImpl`은 `JpaRepository`에 위임하는 어댑터 역할만 합니다.

공통 지원 코드는 `support.error`에 있습니다: `CoreException`(비즈니스 예외, `ErrorType` 보유) + `ErrorType`(HTTP status/code/message enum) 조합을 사용하고, `interfaces.api.ApiControllerAdvice`(`@RestControllerAdvice`)에서 이를 포함한 각종 예외를 `ApiResponse.fail(...)`로 일괄 변환합니다. 새로운 에러 케이스를 추가할 때는 `ErrorType`에 항목을 추가하고 `CoreException`으로 던지는 기존 패턴을 따르세요.

`BaseEntity`(`modules:jpa`)는 모든 JPA Entity가 상속하는 `@MappedSuperclass`로, `id`/`createdAt`/`updatedAt`/`deletedAt`과 멱등한 `delete()`/`restore()`를 제공하며 `guard()`를 오버라이드해 `@PrePersist`/`@PreUpdate` 시점 검증을 넣을 수 있습니다. 재사용성을 위해 이 외의 컬럼/동작은 추가하지 않는 것이 원칙입니다.

## AI 작업 규칙

- 합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
- 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
- 작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
- 검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다.
- 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.

## 개발 규칙 검사

- **Checkstyle**: `apps/commerce-api`에 적용. 규칙은 `config/checkstyle/checkstyle.xml`(`AvoidStarImport`, `UnusedImports`)이며 경고 0개를 요구한다. `check` task에 연결되어 있다.
- **ArchUnit**: `apps/commerce-api/src/test/java/com/loopers/architecture/ArchitectureTest.java`가 계층 의존 방향을 검사한다 — `domain`은 `interfaces`·`application`·`infrastructure`에, `application`은 `interfaces`·`infrastructure`에, `interfaces`는 `infrastructure`에 의존하지 않는다.
- 최종 검사: `./gradlew :apps:commerce-api:check` (Checkstyle + 전체 테스트)

## 테스트 컨벤션

- 테스트는 `@Nested` 클래스로 시나리오(예: `Get`, `Create`)를 그룹화하고, `@DisplayName`을 한국어로 작성해 테스트 트리를 문서처럼 읽히게 합니다. 메소드명은 `동작_when조건` 형태의 영어(예: `returnsExampleInfo_whenValidIdIsProvided`)를 사용합니다.
- 도메인/서비스 통합 테스트는 `@SpringBootTest` + `DatabaseCleanUp`(testFixtures)을 `@AfterEach`에서 호출해 모든 테이블을 truncate합니다.
- API E2E 테스트는 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`로 실제 HTTP 요청을 보내고, 응답을 공통 `ApiResponse<T>`로 역직렬화해 검증합니다.
- MySQL/Redis가 필요한 테스트는 각 모듈의 testFixtures에 있는 Testcontainers 설정(`MySqlTestContainersConfig`, `RedisTestContainersConfig`)을 사용합니다(Docker 필요). Kafka는 위에서 설명한 대로 testFixtures가 아직 구현되어 있지 않으므로 필요 시 직접 구성해야 합니다.
- `*Test.java` 파일은 `.editorconfig`에서 `max_line_length` 제한이 해제되어 있습니다.
