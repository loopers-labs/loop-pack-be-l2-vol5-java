# 9주차 사전 조사: commerce-batch 모듈과 주변 코드 현황

- 작성일: 2026-11-20
- 성격: 설계 전 읽기 전용 조사. 코드 수정·브랜치 생성·커밋 없음. 로컬 전용 문서(커밋하지 않음).
- 표기: 별도 표시 없는 문장은 **코드로 확인한 사실**. 추측은 `[추측]`. 못 찾은 것은 `없음(찾아본 위치: ...)`.
- 경로는 저장소 루트(`/Users/hyun/dev/loopers-spring-java-template`) 기준 상대 경로.

---

## 0. 현재 상태

- 브랜치: `applejeong/volume-9` (`git branch --show-current`)
- git status: 추적 파일 변경 없음. 추적되지 않은 파일만 존재 (`git status --short`):
  - `apps/commerce-api/src/perfTest/` (8주차 측정용 소스셋, 미커밋)
  - `apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/CouponV1ControllerTest.java`
  - `docs/week5/commerce-assignment.pdf`, `docs/week6/*.md` 2개, `docs/week7/*.pdf` 2개, `docs/week8/reference/`, `docs/week8/week8-implementation-prompt.md`
- 최근 커밋 5개 (`git log --oneline -5`):
  1. `22ffcd6` docs(week8): 설계 문서 설정값·지표·거절 사유 표 추가
  2. `6ec8f79` feat: claim 시 승격 만료 확인, DB 장애 후 승격 만료 유예
  3. `f8aea3f` docs(week8): 승격 만료 확인, 장애 후 만료 유예 반영
  4. `7f5cba9` feat: 시스템 사정 실패 시 승격 시간 재충전, 실제 Redis 중단 테스트 추가
  5. `295a782` docs(week8): 승격 시간 재충전, 서킷 자동 반열림, 스레드 공유 한계 반영
- git tag: `w6-before-outbox` 1개 (`git tag`)

**9주차 과제에 미치는 영향**: 작업 트리에 미커밋 파일이 많으므로 9주차 커밋 시 `git add` 범위를 파일 단위로 지정해야 한다. `perfTest` 소스셋은 commerce-api 전용이고 batch와 무관.

---

## 1. commerce-batch 모듈 구성

### 1-1. 의존성 (`apps/commerce-batch/build.gradle.kts`)

| 종류 | 의존 | 근거 |
|---|---|---|
| 모듈 | `:modules:jpa`, `:modules:redis` | `build.gradle.kts:3-4` |
| supports | `:supports:jackson`, `:supports:logging`, `:supports:monitoring` | `build.gradle.kts:5-7` |
| batch | `spring-boot-starter-batch` (implementation), `spring-batch-test` (testImplementation) | `build.gradle.kts:10-11` |
| querydsl apt | `querydsl-apt::jakarta`, `jakarta.persistence-api`, `jakarta.annotation-api` | `build.gradle.kts:14-16` |
| 테스트 픽스처 | `testFixtures(:modules:jpa)`, `testFixtures(:modules:redis)` | `build.gradle.kts:19-20` |

- `:modules:kafka` 의존 **없음**, `:apps:commerce-streamer`·`:apps:commerce-api` 의존 **없음** (`build.gradle.kts` 전체 21줄).
- 루트 `build.gradle.kts:56-78`의 `subprojects` 공통 의존이 추가로 붙음: `spring-boot-starter`, `spring-boot-starter-validation`(runtimeOnly), jackson-jsr310, lombok, `spring-boot-starter-test`, springmockk, mockito, instancio, testcontainers(core·junit-jupiter·spring-boot-testcontainers), `mysql-connector-j`(testRuntimeOnly).
- 루트 공통에 `spring-boot-starter-jdbc` 직접 의존은 없으나, `:modules:jpa`가 `spring-boot-starter-data-jpa`를 `api`로 노출 (`modules/jpa/build.gradle.kts:8`) → JdbcTemplate·DataSource 클래스는 batch classpath에 있음.

### 1-2. Spring Batch 버전

`./gradlew :apps:commerce-batch:dependencyInsight --dependency spring-batch-core --configuration runtimeClasspath` 결과:

```
org.springframework.batch:spring-batch-core:5.2.2 (selected by rule)
\--- org.springframework.boot:spring-boot-starter-batch:3.4.4
```

→ Spring Batch **5.2.2**, Spring Boot **3.4.4**, JVM target 23 속성(`org.gradle.jvm.version 23`) 표시. 루트 toolchain은 Java 21 (`build.gradle.kts:20-24`).

### 1-3. 폴더 구조

```
apps/commerce-batch/
├── build.gradle.kts
└── src
    ├── main
    │   ├── java/com/loopers
    │   │   ├── CommerceBatchApplication.java
    │   │   └── batch
    │   │       ├── job/demo/DemoJobConfig.java
    │   │       ├── job/demo/step/DemoTasklet.java
    │   │       └── listener/{ChunkListener, JobListener, StepMonitorListener}.java
    │   └── resources/application.yml
    └── test/java/com/loopers
        ├── CommerceBatchApplicationTest.java
        └── job/demo/DemoJobE2ETest.java
```

- `src/test/resources` 없음(찾아본 위치: `find apps/commerce-batch/src/test -type f`).

### 1-4. main 클래스

`apps/commerce-batch/src/main/java/com/loopers/CommerceBatchApplication.java`
- `@ConfigurationPropertiesScan`, `@SpringBootApplication` (`:10-11`)
- `@PostConstruct`에서 `TimeZone.setDefault(Asia/Seoul)` (`:14-18`)
- `main`: `SpringApplication.exit(SpringApplication.run(...))` 후 `System.exit(exitCode)` (`:20-23`) → 1회 실행 후 종료하는 CLI형.
- `@EnableBatchProcessing` **없음** (`CommerceBatchApplication.java` 전체, 아래 2절 참고).
- commerce-api와 달리 `Clock` 빈 **없음** (api는 `apps/commerce-api/src/main/java/com/loopers/CommerceApiApplication.java:26-29`에 정의).

**9주차 과제에 미치는 영향**: 랭킹 Job은 streamer 엔티티·kafka 없이 JPA/JDBC로만 집계해야 하며, 입력 테이블 접근을 위해 엔티티를 어떻게 참조할지(6절) 결정이 필요하다. 시각 기준이 필요하면 `Clock` 빈을 batch에 새로 정의해야 한다.

---

## 2. Demo Job 분석

### 2-1. DemoJobConfig (`apps/commerce-batch/src/main/java/com/loopers/batch/job/demo/DemoJobConfig.java`)
- 클래스 조건: `@ConditionalOnProperty(name="spring.batch.job.name", havingValue="demoJob")` (`:19`), `@Configuration` (`:21`).
- 주입: `JobRepository`, `JobListener`, `StepMonitorListener`, `DemoTasklet` (`:26-29`).
- `@Bean("demoJob")` `Job demoJob()` : `new JobBuilder(JOB_NAME, jobRepository).incrementer(new RunIdIncrementer()).start(categorySyncStep()).listener(jobListener).build()` (`:31-38`).
  - **RunIdIncrementer 사용 위치**: `DemoJobConfig.java:34` 단 1곳.
- `@JobScope @Bean("demoSimpleTask")` `Step categorySyncStep()` : `new StepBuilder(..., jobRepository).tasklet(demoTasklet, new ResourcelessTransactionManager()).listener(stepMonitorListener).build()` (`:40-47`).
  - **ResourcelessTransactionManager 사용 위치**: `DemoJobConfig.java:44`, Step `demoSimpleTask`(Tasklet step)에 한정. JobRepository에는 쓰이지 않음.

### 2-2. DemoTasklet (`.../job/demo/step/DemoTasklet.java`)
- `@StepScope`, 동일 `@ConditionalOnProperty`, `@Component` (`:14-17`).
- `@Value("#{jobParameters['requestDate']}") String requestDate` (`:19-20`).
- `execute`: requestDate null이면 `RuntimeException` (`:24-26`), 아니면 println 두 번과 `Thread.sleep(1000)` 후 `RepeatStatus.FINISHED` (`:27-30`). DB 접근 없음.

### 2-3. 리스너 (`.../batch/listener/`)
- `JobListener.java`: `@BeforeJob`에서 executionContext에 `startTime` 저장 (`:19-23`), `@AfterJob`에서 소요 시간 로그 (`:25-52`). 로그 메시지가 `${...}` Kotlin 템플릿 형식 그대로여서 값이 치환되지 않음 (`:21`) — 언급만 함, 수정 대상 아님.
- `StepMonitorListener.java`: `StepExecutionListener` 구현. `afterStep`에서 실패 예외가 있으면 `ExitStatus.FAILED`, 없으면 `COMPLETED` 반환 (`:24-43`).
- `ChunkListener.java`: `@AfterChunk` 로그만 (`:14-20`). 어떤 Step에도 연결되어 있지 않음(찾아본 위치: `grep -rn ChunkListener apps/commerce-batch/src/main` → 선언 파일만).

### 2-4. DemoJobE2ETest (`apps/commerce-batch/src/test/java/com/loopers/job/demo/DemoJobE2ETest.java`)
- `@SpringBootTest`, `@SpringBatchTest`, `@TestPropertySource(properties="spring.batch.job.name=demoJob")` (`:22-24`).
- `JobLauncherTestUtils` 주입(`@SpringBatchTest`가 등록) (`:29-30`), `@Qualifier("demoJob") Job` (`:32-34`).
- 테스트 1: 파라미터 없이 `launchJob()` → `FAILED` 기대 (`:41-55`).
- 테스트 2: `addLocalDate("requestDate", LocalDate.now())` → `COMPLETED` 기대 (`:57-74`).
- DB 정리(`DatabaseCleanUp`) 호출 없음. `spring.batch.job.enabled` 비활성화 **없음**.

### 2-5. CommerceBatchApplicationTest (`.../CommerceBatchApplicationTest.java`)
- `@SpringBootTest(properties="spring.batch.job.enabled=false")`, `contextLoads()` (`:6-9`).

### 2-6. @EnableBatchProcessing
- 없음(찾아본 위치: `grep -rn EnableBatchProcessing apps modules supports` → 0건). Boot 3.x 자동 구성(`BatchAutoConfiguration`)에 의존 (3절).

### 2-7. Job 실행 방식
- **운영**: `spring.batch.job.name=${job.name:NONE}` (`application.yml:16`) → `-Djob.name=demoJob` 등 외부 값으로 Job 이름 지정. Boot `JobLauncherApplicationRunner`가 기동 시 자동 실행. 조건: `spring.batch.job.enabled`가 `true`이거나 미설정(`matchIfMissing=true`) — Boot 3.4.4 `BatchAutoConfiguration` 바이트코드의 `@ConditionalOnProperty(prefix="spring.batch.job", name="enabled", havingValue="true", matchIfMissing=true)`로 확인(javap).
- `job.name` 미지정 시 이름이 `NONE` → 등록된 Job 중 일치하는 것이 없어 실행되는 Job 없음. Boot 3.4.4의 러너 바이트코드에서 "No job found with name" 류의 예외 문자열은 없고 `Skipped job: %s` 문자열만 있음(javap) → `[추측]` 미일치 Job은 건너뛰기만 하고 예외 없음.
- **테스트**: `DemoJobE2ETest`는 `JobLauncherTestUtils.launchJob(...)` 수동 실행 (`:48`, `:67`). 단, 이 테스트는 `job.enabled=false`를 두지 않아 `[추측]` 컨텍스트 기동 시 러너가 demoJob을 파라미터 없이 1회 자동 실행(FAILED)한 뒤 테스트 메서드가 다시 실행하는 형태. 메타데이터 테이블에 실행 기록이 추가로 남는다.

**9주차 과제에 미치는 영향**: Demo는 Tasklet + `ResourcelessTransactionManager`라 DB 쓰기 Step의 참고 모델이 되지 못한다. 랭킹 Step은 JobRepository와 같은 `PlatformTransactionManager`(3절)를 명시해야 한다. 테스트는 `@SpringBatchTest + JobLauncherTestUtils` 패턴을 그대로 쓸 수 있으나 `spring.batch.job.enabled=false`를 함께 두는 것이 안전하다.

---

## 3. DataSource·트랜잭션 매니저

### 3-1. DataSource
- 정의 위치: `modules/jpa/src/main/java/com/loopers/config/jpa/DataSourceConfig.java`
  - `@Bean @ConfigurationProperties(prefix="datasource.mysql-jpa.main") HikariConfig mySqlMainHikariConfig()` (`:13-17`)
  - **`@Primary @Bean HikariDataSource mySqlMainDataSource(...)`** (`:19-23`) → 빈 이름 `mySqlMainDataSource`, `@Primary` 있음.
- 설정값: `modules/jpa/src/main/resources/jpa.yml:14-31` (`jdbc-url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}`, 풀 40/idle 30 등). local 프로필은 `localhost:3306/loopers`, `application/application` (`:42-47`); test 프로필은 풀 10/idle 5만 덮음 (`:58-62`, URL은 Testcontainers가 시스템 속성으로 주입, 5절).
- commerce-batch는 `:modules:jpa`를 implementation 의존하고(`apps/commerce-batch/build.gradle.kts:3`) `application.yml:10`에서 `jpa.yml`을 import → 같은 `mySqlMainDataSource`를 쓴다.
- Batch 전용 DataSource(`@BatchDataSource`) 없음(찾아본 위치: `grep -rn BatchDataSource apps modules supports` → 0건).

### 3-2. PlatformTransactionManager
- 직접 정의한 `PlatformTransactionManager`/`JpaTransactionManager`/`DataSourceTransactionManager` 빈 **없음**(찾아본 위치: `grep -rn` 위 3개 키워드 `apps modules supports` 의 `src/main` → 주입받는 쪽만 있음: `apps/commerce-api/src/main/java/com/loopers/application/order/OrderConfirmedOutboxRelay.java:43`, `.../domain/coupon/CouponIssueService.java:41`).
- `@EnableTransactionManagement`는 `modules/jpa/src/main/java/com/loopers/config/jpa/JpaConfig.java:9`.
- Boot 자동 구성 순서(spring-boot-autoconfigure 3.4.4 jar를 javap로 확인):
  - `HibernateJpaAutoConfiguration`: `before = {TransactionAutoConfiguration, DataSourceTransactionManagerAutoConfiguration}`
  - `DataSourceTransactionManagerAutoConfiguration`: `before = TransactionAutoConfiguration` (JPA 뒤에 옴)
  - → `[추측, 근거 강함]` 컨텍스트의 유일한 TX 매니저는 Hibernate JPA 자동 구성이 만드는 **`JpaTransactionManager`**(빈 이름 `transactionManager`). `@BatchTransactionManager` 지정 없음(`grep` 0건).

### 3-3. JobRepository
- 직접 정의 없음(`grep -rn "JobRepository" apps/commerce-batch/src/main` → `DemoJobConfig`에서 주입만, `:26`).
- Boot 3.4.4 `BatchAutoConfiguration`은 `after = {HibernateJpaAutoConfiguration, TransactionAutoConfiguration}`이고, 내부 `SpringBootBatchConfiguration extends DefaultBatchConfiguration`이 `getDataSource()`·`getTransactionManager()`를 생성자 주입된 `DataSource`·`PlatformTransactionManager`로 돌려줌(javap 멤버 목록). `@BatchDataSource`/`@BatchTransactionManager` 후보가 없으므로 **JobRepository = `mySqlMainDataSource` + 컨텍스트 TX 매니저(3-2의 JpaTransactionManager)**.
- 따라서 Demo의 Step만 `ResourcelessTransactionManager`로 분리되어 있고(2-1), 메타데이터 저장은 업무 DB와 **같은 DataSource·같은 스키마**.

### 3-4. JdbcTemplate
- `src/main`에 JdbcTemplate 빈 정의·사용 없음. Boot `JdbcTemplateAutoConfiguration`에 의해 자동 등록되며 commerce-api 테스트들이 주입받아 사용 중 (예: `apps/commerce-api/src/test/java/com/loopers/application/brand/BrandFacadeIntegrationTest.java:27`, `.../order/OrderFacadeIntegrationTest.java:46`). `[추측]` batch 컨텍스트에서도 동일하게 주입 가능(`spring-boot-starter-data-jpa` → `spring-jdbc` 전이).

**9주차 과제에 미치는 영향**: 랭킹 집계 Step(JdbcCursorItemReader·JpaPagingItemReader·JdbcBatchItemWriter 등)과 JobRepository가 같은 DataSource·TX 매니저를 쓰므로 chunk 단위 커밋이 메타데이터와 함께 원자적으로 처리된다. Step 빌더에 `transactionManager` 빈을 명시하면 된다(Resourceless 사용 금지). 별도 batch DataSource는 과제 범위 밖이면 만들지 않는 것이 단순하다.

---

## 4. 설정 파일(yml)

`apps/commerce-batch/src/main/resources/application.yml`

| 키 | 기본(공통) | local, test | dev / qa / prd | 근거 |
|---|---|---|---|---|
| `spring.main.web-application-type` | `none` | 상속 | 상속 | `:2-3` |
| `spring.profiles.active` | `local` | — | — | `:6-7` |
| `spring.config.import` | `jpa.yml, redis.yml, logging.yml, monitoring.yml` | — | — | `:8-13` |
| `spring.batch.job.name` | `${job.name:NONE}` | 상속 | 상속 | `:14-16` |
| `spring.batch.job.enabled` | **미설정**(Boot 기본 true) | — | — | 파일 전체 |
| `spring.batch.jdbc.initialize-schema` | `never` | **`always`** | `never`(상속) | `:17-18`, `:26-32` |
| `spring.batch.jdbc.table-prefix` | 미설정(기본 `BATCH_`) | — | — | 파일 전체 |
| `management.health.defaults.enabled` | `false` | — | — | `:20-23` |
| `springdoc.api-docs.enabled` | `false` (prd 섹션 아래 위치, 문서 구분자 뒤라 prd에만 적용) | — | prd | `:47-54` |

- dev/qa/prd 프로필 블록은 비어 있음 (`:34-50`).
- `spring.jpa.hibernate.ddl-auto` (`modules/jpa/src/main/resources/jpa.yml`): 공통 `none` (`:7`), **local `create`** (`:40`), **test `create`** (`:56`), dev/qa/prd 미설정→`none`. `generate-ddl: false` (`:4`).
- Hibernate 시간대: `timezone.default_storage: NORMALIZE_UTC`, `jdbc.time_zone: UTC` (`jpa.yml:11-12`).
- Batch 메타데이터 테이블 생성 방식: `initialize-schema: always`(local/test) → Boot `BatchDataSourceScriptDatabaseInitializer`가 `classpath:org/springframework/batch/core/schema-@@platform@@.sql`(BatchProperties 기본값, javap)을 실행. spring-batch-core 5.2.2 jar 내 `schema-mysql.sql`은 `CREATE TABLE BATCH_JOB_INSTANCE / BATCH_JOB_EXECUTION / BATCH_JOB_EXECUTION_PARAMS / BATCH_STEP_EXECUTION / BATCH_STEP_EXECUTION_CONTEXT / BATCH_JOB_EXECUTION_CONTEXT / BATCH_STEP_EXECUTION_SEQ / BATCH_JOB_EXECUTION_SEQ / BATCH_JOB_SEQ` 9개(`unzip -p ... | grep CREATE TABLE`). `IF NOT EXISTS` 없음. Boot 초기화기는 `setContinueOnError(true)`를 호출(javap) → 이미 있으면 오류를 무시하고 진행.
- 저장소 내 SQL 스키마 파일: 없음(찾아본 위치: `find apps modules docker -name '*.sql'` → `apps/commerce-api/src/test/resources/sql/product-perf-{seed,verify}.sql`만, 무관).
- 운영(dev/qa/prd)에서는 `initialize-schema: never` + `ddl-auto: none` → **BATCH_ 테이블과 업무 테이블 모두 외부에서 미리 만들어져 있어야 함**.

**9주차 과제에 미치는 영향**: 랭킹 결과 테이블은 local/test에선 `ddl-auto: create`로 자동 생성되지만, 운영 프로필엔 DDL이 없으므로 설계 문서에 DDL을 명시해야 한다. `job.name`은 외부 주입 전제이므로 랭킹 Job도 `@ConditionalOnProperty(spring.batch.job.name)` 패턴을 따르면 Demo와 공존 가능.

---

## 5. 테스트 인프라

### 5-1. MySQL Testcontainers 연결
- `modules/jpa/src/testFixtures/java/com/loopers/testcontainers/MySqlTestContainersConfig.java`
  - `@Configuration` 클래스 (`:7-8`), **static 초기화 블록**에서 `mysql:8.0` 컨테이너를 1회 기동 (`:10-23`, DB `loopers`, user/pw `test`).
  - `System.setProperty("datasource.mysql-jpa.main.jdbc-url|username|password", ...)` (`:32-34`) → `jpa.yml`의 값을 덮음.
- batch는 `testImplementation(testFixtures(project(":modules:jpa")))` (`apps/commerce-batch/build.gradle.kts:19`)로 이 클래스를 test classpath에 올리고, 패키지 `com.loopers.testcontainers`가 `@SpringBootApplication`(`com.loopers`) 스캔 범위라 자동 등록된다. batch 테스트에서 명시 import/`@Import`는 없음(`grep -rn TestContainersConfig apps/commerce-batch` → 0건).
- 프로필: 루트 `build.gradle.kts:88-94`의 `tasks.test`에서 `spring.profiles.active=test`, `user.timezone=Asia/Seoul`, `maxParallelForks=1`, `-Xshare:off`.

### 5-2. DatabaseCleanUp
- `modules/jpa/src/testFixtures/java/com/loopers/utils/DatabaseCleanUp.java`
  - `@Component` (`:14`), `afterPropertiesSet`에서 **JPA 메타모델의 `@Entity` 클래스에 붙은 `@Table.name`만** 수집 (`:22-28`).
  - `truncateAllTables()`: `SET FOREIGN_KEY_CHECKS=0` → 수집한 테이블 각각 `TRUNCATE TABLE` → `=1` (`:30-40`).
  - **BATCH_ 메타데이터 테이블은 JPA 엔티티가 아니므로 truncate 대상이 아님** (코드 상 명확). `@Table` 없는 엔티티가 있으면 `getAnnotation(Table.class).name()`에서 NPE(`:26`) — 현재 엔티티는 모두 `@Table` 보유 `[추측]`(streamer·api 엔티티 샘플만 확인).
- batch 테스트는 `DatabaseCleanUp`을 사용하지 않음(`grep -rn DatabaseCleanUp apps/commerce-batch` → 0건). api/streamer는 `@AfterEach`에서 호출 (예: `apps/commerce-api/src/test/java/com/loopers/application/brand/BrandFacadeIntegrationTest.java:45-47`, `apps/commerce-streamer/src/test/java/com/loopers/infrastructure/purchase/PurchaseActivityRepositoryTest.java:47-49`).
- 추가로 `RedisCleanUp.truncateAll()`은 `flushAll` (`modules/redis/src/testFixtures/java/com/loopers/utils/RedisCleanUp.java:15-19`).

### 5-3. 테스트 클래스 간 DB 공유
- 컨테이너는 static 필드 1개 → **JVM(Gradle test worker) 당 1개를 모든 테스트 클래스가 공유** (`MySqlTestContainersConfig.java:10-23`). `maxParallelForks=1`이라 모듈 내 테스트는 한 JVM에서 순차 실행.
- 스키마: test 프로필 `ddl-auto: create` (`jpa.yml:56`) → **Spring 컨텍스트가 새로 뜰 때마다 업무 테이블 drop/create**. 캐시된 컨텍스트가 재사용되면 데이터가 남는다. `DemoJobE2ETest`(`job.name=demoJob`)와 `CommerceBatchApplicationTest`(`job.enabled=false`)는 속성이 달라 컨텍스트 2개.
- BATCH_ 테이블은 Hibernate가 관리하지 않으므로 `ddl-auto: create`로 지워지지 않고, `initialize-schema: always`는 `continueOnError`로 기존 테이블을 유지 → **같은 JVM 내 테스트 전체에 Job 실행 기록이 누적**된다.

### 5-4. Redis/Kafka 픽스처
- Redis: `testFixtures(:modules:redis)` 의존(`apps/commerce-batch/build.gradle.kts:20`) → `RedisTestContainersConfig`(`modules/redis/src/testFixtures/java/com/loopers/testcontainers/RedisTestContainersConfig.java:7-21`)가 static으로 `redis:latest` 컨테이너를 띄우고 시스템 속성 주입. batch 컨텍스트에도 자동 등록 → **batch 테스트는 Docker에 MySQL+Redis 2개 컨테이너를 필요로 함**.
- Kafka: `modules/kafka` 의존·픽스처 **없음**(`apps/commerce-batch/build.gradle.kts` 전체). `KafkaTestContainersConfig`는 `modules/kafka/src/testFixtures/java/com/loopers/testcontainers/KafkaTestContainersConfig.java:21`에 있고 api/streamer만 사용.

**9주차 과제에 미치는 영향**: 랭킹 Job 테스트에서 `DatabaseCleanUp`을 쓰면 입력·결과 테이블은 비워지지만 **BATCH_ 실행 이력은 남는다**. 같은 JobParameters로 재실행하면 `JobInstanceAlreadyCompleteException`이 날 수 있으므로 테스트마다 고유 파라미터(또는 `RunIdIncrementer`+`JobLauncherTestUtils.getUniqueJobParameters`)를 써야 한다. Redis 컨테이너가 불필요하게 기동되지만 제거는 범위 밖.

---

## 6. 랭킹 입력 원본 (W6·W7 구매 활동)

### 6-1. product_purchase_activity
- 엔티티: `apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/ProductPurchaseActivity.java` (**commerce-streamer 앱 내부**, 공유 모듈 아님).
- `@Table(name="product_purchase_activity", uniqueConstraints=@UniqueConstraint(name="uk_ppa_order_product", columnNames={"order_id","product_id"}))` (`:17-20`). `indexes` 속성 없음.
- 컬럼(모두 `@Column` 없음, 기본 네이밍으로 snake_case `[추측]` Boot 기본 `CamelCaseToUnderscoresNamingStrategy`):

| 필드 | Java 타입 | DB 컬럼(추정) | 근거 |
|---|---|---|---|
| `orderId` | `Long` | `order_id` | `:23` |
| `productId` | `Long` | `product_id` | `:25` |
| `quantity` | `int` | `quantity` | `:27` |
| `confirmedAt` | `ZonedDateTime` | `confirmed_at` | `:29` |
| (상속) `id`, `createdAt`, `updatedAt`, `deletedAt` | `Long`, `ZonedDateTime`×3 | `id`, `created_at`, `updated_at`, `deleted_at` | `modules/jpa/.../BaseEntity.java:21-32` |

- 생성자 검증: orderId/productId/confirmedAt null 금지, quantity ≥ 1 (`:33-45`).
- 시간대: `ZonedDateTime` + Hibernate `timezone.default_storage: NORMALIZE_UTC`, `jdbc.time_zone: UTC` (`jpa.yml:11-12`) → `[추측]` MySQL `datetime(6)`에 UTC 정규화 저장, 읽을 때 JVM 기본 존(Asia/Seoul, `CommerceBatchApplication.java:17`)으로 복원. 주간 경계 계산 시 KST/UTC 기준을 설계에서 명시해야 함.
- 쓰기 지점: `apps/commerce-streamer/src/main/java/com/loopers/application/purchase/PurchaseActivityService.java:31-44` (`@Transactional consume`, 장부 1행 + 품목 N행 한 TX), 호출 `.../interfaces/consumer/OrderConfirmedListener.java:56`.
- 리포지터리: 포트 `.../domain/purchase/PurchaseActivityRepository.java:10-21`, JPA `.../infrastructure/purchase/ProductPurchaseActivityJpaRepository.java:10` (`findAllByOrderIdOrderByProductIdAsc`), 구현 `.../infrastructure/purchase/PurchaseActivityRepositoryImpl.java:14`.

### 6-2. processed_order_confirmation
- 엔티티: `apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/ProcessedOrderConfirmation.java`
- `@Table(name="processed_order_confirmation", uniqueConstraints={uk_poc_event_id(event_id), uk_poc_order_id(order_id)})` (`:17-23`).
- 컬럼: `eventId String`, `orderId Long`, `confirmedAt ZonedDateTime`, `fingerprint String`, `processedAt ZonedDateTime` (`:26-34`) + BaseEntity.
- **confirmedAt은 두 테이블 모두에 존재**(`ProductPurchaseActivity.java:29`, `ProcessedOrderConfirmation.java:30`). 같은 원본 `event.confirmedAt()`을 양쪽에 기록 (`PurchaseActivityService.java:40,43`). → 랭킹 집계는 `product_purchase_activity` 단독으로 가능(조인 불필요).

### 6-3. DB 접근 가능 여부
- 세 앱 모두 `:modules:jpa`의 동일 `datasource.mysql-jpa.main` 설정(`jpa.yml:14-20`)을 쓰고 local URL은 `jdbc:mysql://localhost:3306/loopers`(`:45`)로 같다 → **같은 MySQL 스키마**. 테스트도 같은 픽스처(5-1).
- 단, `@EntityScan("com.loopers")`(`JpaConfig.java:10`)은 **classpath에 있는 엔티티만** 스캔한다. batch classpath에 streamer 엔티티가 없으므로 batch 컨텍스트가 test 프로필로 뜨면 `ddl-auto: create`가 `product_purchase_activity`를 **만들지 않는다**(엔티티를 모르므로).

### 6-4. 엔티티 재사용 가능성
- `apps/commerce-streamer`를 참조하는 곳은 `apps/commerce-api/build.gradle.kts:49`(`crossAppTestImplementation`)뿐. commerce-batch에는 없음.
- streamer의 plain jar는 켜져 있음 (`apps/commerce-streamer/build.gradle.kts:27` `tasks.jar { enabled = true }`) → 기술적으로 `implementation(project(":apps:commerce-streamer"))` 추가는 가능하나, 그러면 streamer의 `@KafkaListener`·KafkaConfig·`ClockConfig` 등 빈이 batch 컨텍스트에 함께 올라온다(`[추측]` crossAppTest가 `CrossAppTestConfig.java:48`에서 ClockConfig 등을 제외 스캔해야 했던 것과 같은 충돌 발생).
- 선택지 `[추측]`: (a) batch에 읽기용 엔티티/레코드를 따로 두고 JDBC(`JdbcCursorItemReader`)로 `product_purchase_activity`를 읽는다 — 테스트에서는 DDL을 직접 실행하거나 batch 쪽 읽기 전용 `@Entity`를 둬 `ddl-auto: create`로 생성. (b) 엔티티를 `modules/*` 공유 모듈로 이동 — 7주차 코드 변경이라 범위·영향 확인 필요. (c) streamer 모듈 의존 — 위 충돌로 비권장.

**9주차 과제에 미치는 영향**: 입력 테이블은 batch classpath에 없다. 설계에서 "입력 테이블 접근 방식(엔티티 복제 vs 모듈 이동 vs JDBC + 테스트 DDL)"을 먼저 결정해야 하며, 이것이 파일 수·테스트 방식을 좌우한다. `confirmed_at`에 인덱스가 없어 주간 범위 스캔은 풀스캔 `[추측]` — 인덱스 추가는 streamer 엔티티 변경이므로 확인 필요.

---

## 7. commerce-api 쪽 (랭킹 API 연결용)

### 7-1. 상품 고객 공개 조건
- `Product`(`apps/commerce-api/src/main/java/com/loopers/domain/product/Product.java:23`, `@Table(name="products")`)에는 status/visibility 필드 없음(`:26-34`: brandId, name, price, stock). 공개 조건은 **`deleted_at IS NULL` 단독**.
- `@Where`/`@SQLRestriction` 없음(찾아본 위치: `Product.java`, `Brand.java`, `BaseEntity.java`). 필터는 QueryDSL 쿼리마다 수동:
  - `infrastructure/product/ProductRepositoryImpl.java:50` (`findAll`, `product.deletedAt.isNull()`), `:72` `findAllActiveByBrandId`, `:85` `findActiveStockQuantities`, `:102`, `:113`.
  - 단건은 서비스에서 메모리 체크: `domain/product/ProductService.java:117-125` (`findActiveProduct`, deletedAt != null → NOT_FOUND), 목록 ID 필터 `:103-107`.
  - 관리자 조회는 삭제 포함: `ProductRepositoryImpl.java:58-66`.
- 인덱스: `Product.java:19-21` `idx_products_brand_deleted_amount_id (brand_id, deleted_at, amount, id DESC)`.

### 7-2. 상품 표시 정보 조회 경로
- Controller `interfaces/api/product/ProductV1Controller.java:18` (목록 `:24-37`, 단건 `:39-45`)
- Facade `application/product/ProductFacade.java:27` (목록 `:45`, DB 경로 `:110-126`, 단건 `:204-211`, 브랜드명 일괄 조회 `:216-223`, 좋아요 수 `:116`)
- Service `domain/product/ProductService.java:85-88` (`getProducts`), `:75-77` (`getActiveProduct`)
- Repository 포트 `domain/product/ProductRepository.java:11` (`findById`), `:21` (`findAll`); JPA `infrastructure/product/ProductJpaRepository.java:9-12` (`findAllByIdIn`)
- 응답 DTO `interfaces/api/product/ProductV1Dto.java:6-26` `ProductResponse(productId, productName, price, stockQuantity, brandId, brandName, likeCount)`, 중간 `application/product/ProductInfo.java:5-24`.
- `ProductJpaRepository.findAllByIdIn`(`:9-12`) + `ProductService` ID 필터(`:103-107`)가 "ID 목록 → 공개 상품 표시 정보" 재사용 후보.

### 7-3. 공통 응답·에러·검증
- `interfaces/api/ApiResponse.java:3` `record ApiResponse<T>(Metadata meta, T data)`; `Metadata(Result, errorCode, message)` `:4`; 팩토리 `success()` `:22`, `success(T)` `:26`, `fail` `:35`.
- `interfaces/api/ApiControllerAdvice.java:25` `@RestControllerAdvice`: `CoreException`→`errorType.getStatus()` (`:27-30`), 타입 불일치·파라미터 누락→400 (`:33`, `:42`), 바디 파싱 오류→400 (`:50-92`), `NoResourceFoundException`→404 (`:106`), 그 외→500 (`:111-114`); 응답 생성 `:122-125`.
- `support/error/CoreException.java:6`, `support/error/ErrorType.java:11-19` (`INTERNAL_ERROR 500, BAD_REQUEST 400, UNAUTHORIZED 401, NOT_FOUND 404, CONFLICT 409, TOO_MANY_REQUESTS 429, SERVICE_UNAVAILABLE 503`).
- 요청 검증: jakarta validation(`@Valid` 등) **사용 0건**(찾아본 위치: `apps/commerce-api/src/main` grep). 컨트롤러는 `@RequestParam(defaultValue=...)`만(`ProductV1Controller.java:26-29`), 검증은 도메인/서비스에서 `CoreException(BAD_REQUEST)` (예: `domain/product/ProductSortType.java:22-31`, `Product.java:40-72`).

### 7-4. 가장 단순한 GET API 예시: `GET /api/v1/brands/{brandId}`
| 계층 | 파일 | 근거 |
|---|---|---|
| Controller | `interfaces/api/brand/BrandV1Controller.java` | `:15`, 메서드 `:19-26` |
| DTO | `interfaces/api/brand/BrandV1Dto.java` `BrandResponse(id, name)` | `:5-7` |
| Facade | `application/brand/BrandFacade.java` `getBrand` `@Transactional(readOnly=true)` | `:18`, `:29-30` |
| 도메인 서비스 | 없음(Facade가 Repository 직접 사용) | `domain/brand/` 디렉터리 |
| Repository 포트 | `domain/brand/BrandRepository.java` | `:7`, `findById :10` |
| 구현 | `infrastructure/brand/BrandRepositoryImpl.java`, `BrandJpaRepository.java` | `:17`, `:26` / `:10` |
| 엔티티 | `domain/brand/Brand.java` | `:13` |
- ApiSpec 인터페이스는 brand/product에 없고 `interfaces/api/example/ExampleV1ApiSpec.java:9`만 존재.
- 패키지 최상위: `application / config / domain / infrastructure / interfaces / support` (`apps/commerce-api/src/main/java/com/loopers`).
- 기존 `rank` 관련 코드: 운영 코드 0건(찾아본 위치: `apps/commerce-api/src/main` `grep -rni rank`). 테스트·쿠폰 대기열의 `waitingRank`만 존재(`domain/coupon/CouponAdmissionQueue.java`).

### 7-5. Clock 빈
- 정의: `apps/commerce-api/src/main/java/com/loopers/CommerceApiApplication.java:26-29` (`Clock.systemDefaultZone()`). streamer는 `apps/commerce-streamer/src/main/java/com/loopers/config/ClockConfig.java:12`. batch에는 없음(1-4).
- 테스트 고정 방식: `@MockitoBean Clock` + `given(clock.instant())` (`apps/commerce-api/src/test/java/com/loopers/application/payment/PaymentScheduledLookupIntegrationTest.java:59-60`, `:379-380`; `.../order/OrderConfirmedOutboxRelayTest.java:113-114`), 단위는 `Clock.fixed` (`.../domain/payment/PaymentResultServiceTest.java:53`), 움직이는 클럭 `.../application/coupon/AdjustableClock.java:12`.

**9주차 과제에 미치는 영향**: 랭킹 API는 brand 예시의 4계층(Controller→Facade→Repository 포트→infrastructure 구현)을 그대로 따르면 되고, 상품 표시 정보는 `ProductJpaRepository.findAllByIdIn` + `deletedAt == null` 필터를 재사용할 수 있다. 랭킹 결과 테이블을 api가 읽으려면 **api 쪽에도 결과 엔티티(또는 JDBC 조회)**가 필요하며 batch와 테이블 정의가 이중화되는 점을 설계에서 정리해야 한다.

---

## 8. 규칙·검사

### 8-1. ArchUnit
- 유일한 규칙 파일: `apps/commerce-api/src/test/java/com/loopers/architecture/ArchitectureTest.java` (import 범위 `com.loopers`, 테스트 제외 `:19-21`)
  - `domain` → `interfaces/application/infrastructure` 의존 금지 (`:24-33`)
  - `application` → `interfaces/infrastructure` 금지 (`:35-44`)
  - `interfaces` → `infrastructure` 금지 (`:46-55`)
- archunit 의존은 `apps/commerce-api/build.gradle.kts:33`에만. **commerce-batch에는 ArchUnit 테스트·의존 없음**(찾아본 위치: `apps/commerce-batch/build.gradle.kts`, 루트 `build.gradle.kts:56-78`, `grep -rn archunit '*.kts'`).

### 8-2. Checkstyle
- 설정: `config/checkstyle/checkstyle.xml` — `severity=error` (`:6`), 규칙은 `AvoidStarImport`(`:9`), `UnusedImports`(`:10`) 2개.
- 적용: 루트 `build.gradle.kts:41` `apply(plugin="checkstyle")` in `subprojects`, 설정 `:43-48` (`toolVersion 10.26.1`, `isIgnoreFailures=false`, `maxWarnings=0`) → **commerce-batch 포함**. 소스셋 main/test → `checkstyleMain`, `checkstyleTest`.

### 8-3. `:apps:commerce-batch:check` 구성(설정 기반, 미실행)
- `check` ← `test`(`build.gradle.kts:88-94` 설정), `checkstyleMain`, `checkstyleTest`. jacoco 플러그인 적용(`:40`)이나 `jacocoTestReport`는 `mustRunAfter("test")`만 지정(`:96-98`)이라 `[추측]` check에 포함되지 않음. crossAppTest는 commerce-api 전용(`apps/commerce-api/build.gradle.kts:65`)이라 batch check에 없음.

**9주차 과제에 미치는 영향**: batch에 계층 규칙을 적용하려면 ArchUnit 의존·테스트를 새로 추가해야 한다(과제 요구 여부 확인 필요). Checkstyle은 import 규칙만이라 와일드카드 import와 미사용 import만 피하면 된다.

---

## 9. 7주차 E2E 연결 구조

### 9-1. crossAppTest 소스셋
- 정의: `apps/commerce-api/build.gradle.kts:39` (`sourceSets.create("crossAppTest")`), classpath 조립 `:40-43` (main·test output을 streamer jar 앞에 둬 commerce-api의 `application.yml` 사용), configuration 상속 `:45-46`.
- 의존: `:49` `crossAppTestImplementation(project(":apps:commerce-streamer"))`. testFixtures(jpa/redis/kafka)는 `testImplementation` 상속(`:28-30`).
- 태스크: `:52-63` (`Test` 타입, profile test, `shouldRunAfter(tasks.test)`), `tasks.check { dependsOn(crossAppTest) }` `:65`.
- streamer plain jar 활성화: `apps/commerce-streamer/build.gradle.kts:27`.
- 부트스트랩: `apps/commerce-api/src/crossAppTest/java/com/loopers/crossapp/CrossAppTestConfig.java:34-67` — `@EnableAutoConfiguration`, `@ComponentScan(com.loopers, 두 Application·crossapp·purchase·consumer·ClockConfig·OrderActivityKafkaConfig 제외)`, `@Import`로 streamer 빈 6개 + 가짜 PG/발행기 + `W6DirectPublishPath` (`:53-66`).

### 9-2. PG 확정 → Outbox relay → Kafka → 구매 활동 테스트
- **`OutboxDeliveryE2ETest.outboxDeliveryEndToEnd_withInterruptAfterBrokerAck`** — `apps/commerce-api/src/crossAppTest/java/com/loopers/crossapp/OutboxDeliveryE2ETest.java:186` (DisplayName `:184`: 주문 API → PG SUCCESS → 같은 TX Outbox → relay 중단 → 재발행 → consumer 2회 수신에도 완료 기록 1·활동 2행). 클래스 `:100`, `@SpringBootTest(classes=CrossAppTestConfig.class, properties={...})` `:86-99`.
- 자매 테스트: `W6GapReproductionAndRecoveryTest.reproducesW6GapState_thenRecovers` (`.../W6GapReproductionAndRecoveryTest.java:165-167`).
- 보존본(테스트 아님): `W6DirectPublishPath.java:44`.

### 9-3. commerce-batch를 이 구조에 붙일 때 걸릴 것 `[추측]`
1. **의존 방향**: crossAppTest는 commerce-api 안에 있고 streamer를 jar로 끌어온다. batch를 붙이려면 `crossAppTestImplementation(project(":apps:commerce-batch"))` + batch plain jar 활성화(`tasks.jar { enabled = true }`, 현재 루트 정책으로 apps 하위 Jar는 비활성 `build.gradle.kts:83-86`)가 필요.
2. **컨텍스트 충돌**: batch jar가 들어오면 `spring-boot-starter-batch` 자동 구성(JobRepository, `JobLauncherApplicationRunner`, BATCH_ 스키마 초기화)이 crossAppTest 컨텍스트에 함께 올라온다. `spring.batch.job.enabled=false`와 `@ConditionalOnProperty(job.name)` 덕에 Job 자동 실행은 막을 수 있지만, 메타데이터 테이블 초기화(`initialize-schema`)는 commerce-api의 `application.yml`에 설정이 없어 Boot 기본 `embedded`로 동작 → MySQL에서는 **BATCH_ 테이블이 생성되지 않아 JobRepository 사용 시 실패**. 속성 추가가 필요.
3. **web-application-type**: batch는 `none`(`application.yml:3`)이지만 crossAppTest는 api의 yml을 읽어 web 컨텍스트. batch 빈은 web 여부에 무관하므로 큰 문제는 아니나 `CommerceBatchApplication`은 `CrossAppTestConfig`의 Application 제외 패턴(`:48`)에 추가해야 함.
4. **입력 테이블 생성**: 6-3에 따라 랭킹 Job이 읽는 `product_purchase_activity`는 streamer 엔티티가 crossAppTest classpath에 있으므로 거기선 생성되지만, **batch 단독 테스트에서는 생성되지 않는다**. 두 환경에서 동일한 접근 방식(6-4의 선택)이 필요.
5. **Clock**: crossAppTest는 양 앱의 Clock 빈을 제외하고 `@MockitoBean Clock`(`OutboxDeliveryE2ETest.java:140-141`) 1개를 주입. batch에 Clock 빈을 새로 만들면 같은 제외 목록에 넣어야 한다.
6. **실행 트리거**: 기존 E2E는 Kafka consumer까지 대기(Awaitility 등 `[추측]`)로 끝난다. 그 뒤 batch Job을 `JobLauncher`로 수동 호출해 결과 테이블을 검증하는 형태가 자연스럽다.

**9주차 과제에 미치는 영향**: §7 연결 테스트를 crossAppTest에 두면 build 설정 변경(batch jar 활성·의존 추가·속성)이 불가피하다. 대안으로 batch 모듈 단독 테스트에서 `product_purchase_activity`에 직접 행을 넣고 Job→결과→(api 조회는 별도) 검증하는 것이 변경 범위가 작다. 어느 쪽인지 설계에서 먼저 결정.

---

## 요약

### 9주차 과제와 충돌하거나 주의해야 할 점 Top 5
1. **입력 엔티티가 batch classpath에 없다.** `ProductPurchaseActivity`는 `apps/commerce-streamer` 내부(`.../domain/purchase/ProductPurchaseActivity.java:21`)이고 batch는 streamer에 의존하지 않는다(`apps/commerce-batch/build.gradle.kts` 전체). batch 테스트의 `ddl-auto: create`는 이 테이블을 만들지 않으므로 접근 방식(엔티티 복제/모듈 이동/JDBC+DDL)을 먼저 결정해야 한다.
2. **Demo Step은 `ResourcelessTransactionManager`를 쓴다** (`DemoJobConfig.java:44`). DB 쓰기 Step에 그대로 복사하면 chunk 커밋이 되지 않는다. JobRepository와 동일한 컨텍스트 `PlatformTransactionManager`(JPA 자동 구성, 3-2)를 Step에 명시해야 한다.
3. **`DatabaseCleanUp`은 BATCH_ 메타데이터를 지우지 않는다** (`DatabaseCleanUp.java:22-28`은 JPA `@Entity`의 `@Table.name`만 수집). 같은 JVM의 모든 테스트가 실행 이력을 누적하므로 Job 재실행 테스트는 고유 JobParameters가 필요하다. 또 `initialize-schema: always`는 local/test만이고 운영은 `never`(`application.yml:17-18, 30-32`)라 운영 DDL은 별도 준비.
4. **운영 프로필에 DDL이 없다.** `ddl-auto`는 local/test만 `create`(`jpa.yml:40, 56`), 저장소에 스키마 SQL 없음. 랭킹 결과 테이블·입력 테이블 인덱스(`confirmed_at` 인덱스 없음, `ProductPurchaseActivity.java:17-20`)는 설계 문서에 DDL로 명시해야 한다.
5. **시간대 이중 처리.** `ZonedDateTime` + `NORMALIZE_UTC`/`jdbc.time_zone: UTC`(`jpa.yml:11-12`) + JVM 기본 Asia/Seoul(`CommerceBatchApplication.java:17`). 주간 경계(월요일 00:00 KST 등)를 SQL에서 자를 때 UTC 저장값과 KST 경계의 변환을 설계에서 고정하고 테스트로 못 박아야 한다. batch에는 `Clock` 빈도 없다(1-4).

추가 주의: `DemoJobE2ETest`는 `spring.batch.job.enabled=false`가 없어 컨텍스트 기동 시 Job이 1회 자동 실행된다`[추측]`(2-7). 랭킹 Job 테스트는 비활성화를 명시하는 것이 안전하다. batch에 ArchUnit이 없고(8-1), Checkstyle은 import 규칙 2개뿐(8-2).

### 확인하지 못한 것
- 실제 생성되는 MySQL 컬럼 타입(`datetime(6)` 여부)과 네이밍 전략 적용 결과 — 테스트 실행이 필요해 확인하지 않음(읽기 전용 제약).
- `JpaTransactionManager`가 실제로 선택되는지 — 자동 구성 순서 애너테이션(javap)으로 추론했고 런타임 빈 목록은 확인하지 않음.
- `DemoJobE2ETest` 기동 시 러너의 자동 실행 여부와 그 결과 — 실행하지 않음.
- `:apps:commerce-batch:check`의 실제 태스크 그래프 — 실행 금지 지시에 따라 설정만 확인.
- 9주차 과제 PDF/요구사항 원문 — 본 조사 범위 밖(설계 단계에서 확인).
- `ChunkListener`가 쓰이지 않는 이유, `JobListener`의 `${}` 로그 포맷(값 미치환) — 데드코드/오타로 보이나 언급만 하고 건드리지 않음.
