# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

Loopers 에서 제공하는 Spring Boot(Java 21) 멀티 모듈 커머스 템플릿 프로젝트입니다. `commerce-api`, `commerce-batch`, `commerce-streamer` 세 개의 실행 가능한 애플리케이션이 공통 모듈(`modules`)과 부가 기능 모듈(`supports`)을 공유하는 구조입니다.

세 앱은 서로의 프로젝트를 참조하지 않는 완전히 독립된 Gradle 모듈입니다. 도메인 모델(JPA Entity 등)을 공유하는 별도 모듈도 없기 때문에, 여러 앱이 같은 DB 테이블을 다뤄야 한다면 각 앱이 자신만의 엔티티/리포지토리를 각각 정의해야 합니다. `modules`가 앱들 사이에서 공유해주는 것은 도메인 모델이 아니라 인프라 설정과 테스트 환경(아래 참고)뿐입니다.

## 로컬 개발 환경

실행·빌드 명령어와 모듈 구조 전체는 `docs/guide/development-environment.md`를 참고합니다.

설계 문서는 `docs/design/{주차}/`에 두며, 사람용(`{주제}.md`, 80줄 이내)과 AI용 상세(`{주제}.ai.md`)로 나눕니다.

`local` 프로필 실행 전에 `docker/infra-compose.yml`로 인프라(MySQL, Redis)를 먼저 띄웁니다. 모니터링(Prometheus + Grafana)은 `docker/monitoring-compose.yml`로 별도 실행하며 http://localhost:3000 (admin/admin) 으로 접속합니다.

## 빌드 및 테스트 명령어

- 특정 테스트 클래스만 실행: `./gradlew :apps:commerce-api:test --tests "com.loopers.interfaces.api.ErrorTypeHttpStatusMapperTest"`
- 특정 테스트 메소드만 실행: `./gradlew :apps:commerce-api:test --tests "com.loopers.interfaces.api.ErrorTypeHttpStatusMapperTest.<메소드명>"`
- 최종 검사(Checkstyle + 테스트): `./gradlew :apps:commerce-api:check`
- 아키텍처(ArchUnit) 테스트만 실행: `./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'`
- Jacoco 커버리지 리포트: `./gradlew :apps:commerce-api:jacocoTestReport` (XML만 생성됨, HTML/CSV는 비활성화)

테스트는 `apps`, `modules`, `supports` 등 컨테이너 프로젝트 자체에서는 실행되지 않습니다(루트 `build.gradle.kts`에서 해당 프로젝트들의 모든 task를 비활성화함). 반드시 하위 모듈(`:apps:commerce-api` 등)을 지정해서 실행해야 합니다.

테스트는 `maxParallelForks = 1`로 순차 실행되며, `spring.profiles.active=test`, `user.timezone=Asia/Seoul`이 강제 적용됩니다. 통합/E2E 테스트는 Testcontainers(MySQL, Redis, Kafka)를 사용하므로 Docker가 실행 중이어야 합니다.

## 멀티 모듈 구조

`apps`(commerce-api/batch/streamer, 서로 독립), `modules`(jpa/redis/kafka), `supports`(jackson/logging/monitoring) 구조입니다. 현재 `commerce-batch`·`commerce-streamer`는 데모 코드만 있고 도메인 레이어가 비어 있습니다.

- `apps` 하위 모듈만 `BootJar`가 활성화되고(실행 가능한 jar), `modules`/`supports`는 일반 `Jar`로 라이브러리처럼 소비됩니다.
- 각 `apps` 모듈은 필요한 `modules`/`supports`를 `build.gradle.kts`의 `dependencies`에서 명시적으로 `implementation(project(":modules:..."))`로 선언합니다. 새 앱이나 도메인 기능을 추가할 때 어떤 모듈이 필요한지는 이 패턴을 따라 판단하면 됩니다.
- `annotationProcessor`(QueryDSL Q-타입 생성 등) 설정은 모듈 간에 전이(transitive)되지 않습니다. `modules:jpa`가 자신을 위해 선언한 `querydsl-apt`/`jakarta.persistence-api`/`jakarta.annotation-api` annotationProcessor는 이를 사용하는 `apps` 모듈에 자동으로 전파되지 않으므로, 자체 엔티티를 갖는 앱(`commerce-api`, `commerce-batch`, `commerce-streamer` 모두 해당)은 동일한 3줄을 자신의 `build.gradle.kts`에도 반복 선언해야 합니다. 단, `commerce-api`는 예외입니다. `querydsl-apt` 5.1.0이 `@Embeddable` record를 처리하지 못해(`Illegal type`) 이 3줄을 두지 않고 값 객체를 `@Embedded` record로 매핑합니다. `commerce-api`에서 QueryDSL 쿼리가 필요해지면 record를 지원하는 OpenFeign 포크(`io.github.openfeign.querydsl`)로 교체하는 것을 먼저 검토합니다.

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

**아래 4계층 패턴은 현재 `commerce-api`에만 실제로 구현되어 있습니다(`user`·`point`·`brand`·`product`·`like`·`order` 기능이 예시).** `commerce-batch`, `commerce-streamer`는 아직 데모 코드(배치 Job, Kafka Consumer)만 있을 뿐 이 레이어 구조를 따르는 도메인 코드가 없으므로, 새로 도메인을 추가할 때 이 패턴을 따르면 됩니다. 각 `apps` 모듈은 독립적이므로 동일 도메인이라도 앱마다 각자 이 4계층을 따로 구현해야 합니다(위 "멀티 모듈 구조" 참고).

각 `apps` 모듈은 도메인별로 다음 4개 레이어를 따릅니다 (`apps/commerce-api/src/main/java/com/loopers/{layer}/{domain}` 참고):

1. **`interfaces.api`** — Controller. API 스펙은 `{Domain}ApiSpec` 인터페이스(Swagger 어노테이션 등 문서화 목적)와 이를 구현하는 `{Domain}Controller`로 분리합니다. 요청/응답 DTO는 `{Domain}Dto`에 정적 record로 모아둡니다. 클래스 이름에는 버전(`V1`)을 붙이지 않고 URL 경로(`/api/v1/...`)로만 버전을 표현합니다. 관리자 API(`/api-admin/v1/...`)는 `interfaces.api.admin.{feature}` 패키지에 두고 클래스 이름에 `Admin` 접두사를 붙입니다(`AdminBrandController` 등. 단순명이 같은 Controller는 Spring 빈 이름이 충돌합니다). application의 관리자 Facade도 같은 기능 패키지에서 `Admin{Feature}Facade`로 구분합니다. 컨트롤러는 요청을 받아 `application` 레이어의 Facade만 호출하고, 응답은 공통 `ApiResponse<T>`(`interfaces.api.ApiResponse`)로 감쌉니다.
2. **`application`** — Facade + Info. `{Domain}Facade`는 하나 이상의 `domain.Service`를 조합(오케스트레이션)하고, 도메인 모델을 `interfaces` 레이어에 노출할 `{Domain}Info`(record)로 변환합니다. 여러 도메인을 넘나드는 유스케이스 조합은 이 레이어의 책임입니다. Facade만 쓰는 외부 기술(캐시 등)의 포트 인터페이스도 이 레이어에 두며(`application.product.cache.ProductListCache`), 입출력은 application의 타입으로만 정의합니다. 데이터를 나르는 타입은 역할 접미사로 끝나게 짓고(`Info` 출력 값, `Command` 쓰기 입력, `Query` 읽기 입력, `Result` 처리 결과) `application.{domain}.dto` 하위 패키지에 두어 Facade·포트·컴포넌트와 섞이지 않게 하고, 외부 시스템 계약 타입은 기능별 하위 패키지(`application.payment.pg`)에 둡니다.
3. **`domain`** — Model(JPA Entity), Service, Repository(인터페이스). 비즈니스 규칙(유효성 검증 등)은 Model 생성자/메소드 내부에서 `CoreException`을 던지는 방식으로 캡슐화합니다. Repository는 `domain` 레이어에 인터페이스로만 정의합니다. **트랜잭션 경계는 `application`의 Facade에서 `@Transactional`로 관리**하며(조회 유스케이스는 `readOnly = true`), 도메인 `Service`에는 `@Transactional`을 붙이지 않고 Facade가 연 트랜잭션에 참여합니다. 단, 외부 호출(PG 등)을 사이에 둔 유스케이스(`OrderFacade.confirm`, `PaymentResultCheckFacade`)는 메소드에 `@Transactional`을 달지 않고, 필요한 구간만 `TransactionTemplate`으로 감쌉니다(`docs/design/week5/pg-payment-design.md`). 외부 호출 동안 DB 연결과 잠금을 쥐지 않기 위해서입니다.
4. **`infrastructure`** — `domain.Repository` 인터페이스의 실제 구현체(`{Domain}RepositoryImpl`)와 Spring Data JPA 인터페이스(`{Domain}JpaRepository`)가 위치합니다. `RepositoryImpl`은 `JpaRepository`에 위임하는 어댑터 역할만 합니다. application 포트의 구현(예: Redis 캐시)도 이 레이어에 두고, 키·직렬화·TTL·Redis 예외 처리를 여기서 숨깁니다.

공통 지원 코드는 `support.error`에 있습니다: `CoreException`(비즈니스 예외, `ErrorType` 보유) + `ErrorType`(message를 가진 enum, `getCode()`는 상수명) 조합을 사용합니다. `ErrorType`은 HTTP 상태를 알지 못하며(`INTERNAL_ERROR`/`INVALID_INPUT`/`NOT_FOUND`/`CONFLICT`/`FORBIDDEN`과 주문 업무 규칙 위반 `ORDER_ALREADY_CONFIRMED`/`OUT_OF_STOCK`/`INSUFFICIENT_POINT`/`PRODUCT_UNAVAILABLE`), HTTP 상태 변환은 `interfaces.api.ErrorTypeHttpStatusMapper`가 전담합니다. `interfaces.api.ApiControllerAdvice`(`@RestControllerAdvice`)가 이를 포함한 각종 예외를 `ApiResponse.fail(...)`로 일괄 변환합니다. 새로운 에러 케이스를 추가할 때는 `ErrorType`에 항목을 추가하고 `ErrorTypeHttpStatusMapper`의 switch에도 매핑을 추가한 뒤(`default` 절이 없어 누락 시 컴파일 에러가 납니다) `CoreException`으로 던지세요.

`BaseEntity`(`modules:jpa`)는 모든 JPA Entity가 상속하는 `@MappedSuperclass`로, `id`/`createdAt`/`updatedAt`/`deletedAt`과 멱등한 `delete()`/`restore()`를 제공하며 `guard()`를 오버라이드해 `@PrePersist`/`@PreUpdate` 시점 검증을 넣을 수 있습니다. 재사용성을 위해 이 외의 컬럼/동작은 추가하지 않는 것이 원칙입니다.

### 목록 API와 응답 형식

- 목록 API의 `page`·`size` 검증은 `support.paging.PageRequests`를, 응답은 `interfaces.api.PageResponse<T>`를 씁니다. `@RequestParam`의 `defaultValue`와 Swagger 설명은 리터럴 대신 `PageRequests`의 상수(`DEFAULT_PAGE`, `DEFAULT_SIZE`, `PAGE_PARAM_DESCRIPTION`, `SIZE_PARAM_DESCRIPTION`)를 참조합니다.
- `supports:jackson`이 null 필드를 직렬화에서 뺍니다(`NON_NULL`). 값이 없는 필드는 `null`이 아니라 키 자체가 없는 응답이 됩니다.
- 지연 로딩 컬렉션의 N+1은 `modules:jpa`의 `default_batch_fetch_size`로 줄입니다. 쿼리 수가 중요한 목록은 `OrderListQueryCountTest`처럼 SQL 개수를 테스트로 고정합니다.
- 설계 결정의 이유와 알려진 한계는 `docs/design/week2/commerce-design.ai.md`(6장 결정 기록)에 있습니다.

### 보안 설정과 요청자 식별 (`config`)

`config.SecurityConfig`가 관리자 체인(`/api-admin/**`, 관리자 역할만 허용)과 고객 체인(`/api/**`, CSRF 끔, STATELESS)을 한곳에서 정의합니다. 직접 정의한 `SecurityFilterChain`이 있으면 Spring Boot의 기본 보안 체인은 만들어지지 않습니다. 필터 단계의 오류는 `ApiControllerAdvice`가 처리하지 못하므로 `SecurityErrorResponseWriter`가 같은 `ApiResponse` 형식으로 씁니다.

- 자신의 데이터를 다루는 고객 API(좋아요·포인트·주문)는 `X-USER-ID` 헤더로 요청자를 식별합니다(로컬 실습·테스트용). `config.SecurityConfig.IDENTIFIED_PATHS`에 경로를 등록하면 `CustomerIdentityFilter`가 그 경로에서만 헤더 형식(400)과 사용자 존재(404)를 확인하고 `userId`를 인증 주체로 둡니다. Controller는 `@RequestHeader`가 아니라 `@Requester Long`(`interfaces.api.Requester`)으로 요청자를 받고, Facade는 요청자가 검증된 값이라고 보고 존재를 다시 조회하지도 `null`을 방어하지도 않습니다.
- 카탈로그 조회(브랜드 상세, 상품 목록·상세)는 식별하지 않으며 헤더를 읽지도 사용자를 조회하지도 않습니다. 이런 경로는 목록에 등록하지 않습니다.
- 목록에 등록하지 않은 식별 API는 식별 없이 열립니다. 이때 `@Requester` 파라미터를 만드는 시점에 `RequesterArgumentResolver`가 서버 설정 실수로 보고 `INTERNAL_ERROR`(500)로 거절하므로 Facade에 `null`이 닿지 않습니다. 그래도 식별이 필요한 API마다 "헤더 누락 400" E2E 테스트를 두어 등록 누락을 잡습니다.

## 테스트 컨벤션

- 테스트는 `@Nested` 클래스로 시나리오(예: `Get`, `Create`)를 그룹화하고, `@DisplayName`을 한국어로 작성해 테스트 트리를 문서처럼 읽히게 합니다. 메소드명은 `동작_when조건` 형태의 영어(예: `messageShouldBeErrorTypeMessage_whenCustomMessageIsNull`)를 사용합니다.
- 아래 통합/E2E 컨벤션의 실제 예시는 `UserRepositoryTest`(저장), `PointFacadeTest`(Facade 통합), `PointApiE2ETest`(API E2E)입니다. 새 도메인도 이 컨벤션대로 작성하세요.
- 도메인/서비스 통합 테스트는 `@SpringBootTest` + `DatabaseCleanUp`(testFixtures)을 `@AfterEach`에서 호출해 모든 테이블을 truncate합니다.
- API E2E 테스트는 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`로 실제 HTTP 요청을 보내고, 응답을 공통 `ApiResponse<T>`로 역직렬화해 검증합니다.
- MySQL/Redis가 필요한 테스트는 각 모듈의 testFixtures에 있는 Testcontainers 설정(`MySqlTestContainersConfig`, `RedisTestContainersConfig`)을 사용합니다(Docker 필요). Kafka는 위에서 설명한 대로 testFixtures가 아직 구현되어 있지 않으므로 필요 시 직접 구성해야 합니다.
- `*Test.java` 파일은 `.editorconfig`에서 `max_line_length` 제한이 해제되어 있습니다.

## 개발 규칙 (lint · ArchUnit)

- `commerce-api`는 Checkstyle(`config/checkstyle/checkstyle.xml`, `AvoidStarImport`·`UnusedImports`)을 `check`에 연결해 사용합니다. 경고도 허용하지 않습니다(`maxWarnings = 0`).
- `ArchitectureTest`가 레이어 의존 방향(`interfaces → application → domain`, `infrastructure → domain`)을 검사합니다. domain은 다른 레이어에, application은 interfaces·infrastructure에, interfaces는 infrastructure에 의존할 수 없습니다. 포트는 그것을 쓰는 레이어가 소유하므로 `infrastructure → application`은 application 포트 구현에 한해 허용합니다(검사 규칙은 없음, `docs/design/week2/commerce-design.ai.md` 2-3).

## AI 작업 규칙

- 합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
- 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
- 작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
- 검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다. 단, 사용자가 요청한 테스트 정리는 예외다.
- 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.
