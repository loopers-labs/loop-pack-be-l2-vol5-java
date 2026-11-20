# 9주차 §3-1 probe 결과: DB chunk 재시작 연결 실험

- 실행일: 2026-11-20, 브랜치 `applejeong/volume-9`, 커밋 안 함(로컬 전용 문서).
- 목적: 랭킹 정답이 아니라 "reader 상태(ExecutionContext)와 업무 DB commit 이 같은 경계에서 저장되고, 같은 JobParameters 재실행이 그 지점부터 이어지는지" 확인.
- 추가 파일 2개 (기존 파일 수정 없음):
  - `apps/commerce-batch/src/main/java/com/loopers/domain/ranking/WeeklyRankingInput.java` (입력 엔티티, `weekly_ranking_inputs`, BaseEntity 상속 = streamer `ProductPurchaseActivity` 패턴)
  - `apps/commerce-batch/src/test/java/com/loopers/job/ranking/PurchaseInputProbeRestartTest.java` (probe 테스트 1 메서드)
- 실행 명령과 결과:

```
./gradlew :apps:commerce-batch:test --tests '*PurchaseInputProbeRestartTest'   → BUILD SUCCESSFUL, tests=1 failures=0 errors=0 skipped=0
./gradlew :apps:commerce-batch:check                                            → BUILD SUCCESSFUL (checkstyleMain·checkstyleTest·test)
   CommerceBatchApplicationTest 1건, DemoJobE2ETest 2건, PurchaseInputProbeRestartTest 1건 모두 통과
```

## 1. 첫 실행 후 StepExecution ExecutionContext 전체 (JobExplorer 로 DB 재조회)

| 키 | 값 |
|---|---|
| `purchaseProductProbe.read.count` | `2` |
| `batch.version` | `5.2.2` |
| `batch.taskletType` | `org.springframework.batch.core.step.item.ChunkOrientedTasklet` |
| `batch.stepType` | `org.springframework.batch.core.step.tasklet.TaskletStep` |

- 키 이름은 과제 문서 예상(`purchaseProductProbe.read.count`)과 일치해 assert 수정 없음.
- 재실행 후 같은 Step 의 ExecutionContext: `purchaseProductProbe.read.count = 4` (2 에서 이어 받아 상품 9 읽음 → 3, 끝(null) 읽기에서 4). 나머지 3개 키 동일.

## 2. 실행 식별자·상태·카운트

| 항목 | 첫 실행 (failOnNine=true) | 재실행 (failOnNine=false) |
|---|---|---|
| JobInstance ID | 1 | **1 (같음)** |
| JobExecution ID | 1 | **2 (다름)** |
| StepExecution ID | 1 | 2 |
| Job status | FAILED | COMPLETED |
| Step status | FAILED | COMPLETED |
| readCount | 3 | 1 |
| writeCount | 2 | 1 |
| commitCount | 1 | 1 |
| rollbackCount | 1 | 0 |
| `batch_restart_probe` 행 (snapshot_id='probe-1') | [7, 8] | [7, 8, 9] |

- JobParameters: `snapshotId=probe-1 (identifying=true)` 단일. RunIdIncrementer·run.id·시각 파라미터 없음. 두 실행의 파라미터 완전 동일.
- 첫 실행 readCount 가 3 인 이유: 상품 9 를 읽은 뒤 processor 에서 예외 → chunk 롤백. StepExecution 의 readCount 는 롤백 후에도 3 으로 저장되지만, **reader 상태인 ExecutionContext 의 `read.count` 는 마지막 commit 시점 값 2 로 저장**됨. 재시작은 ExecutionContext 를 기준으로 하므로 상품 9 부터 이어졌다(재실행 readCount 1, writeCount 1).
- 확인된 결론: **chunk commit(업무 테이블 [7,8])과 reader 상태(read.count=2)가 같은 경계에서 저장**되었고, 같은 파라미터 재실행은 새 JobExecution 으로 같은 JobInstance 를 이어 받아 남은 상품만 처리했다.

## 3. 런타임에 선택된 PlatformTransactionManager

```
[probe] PlatformTransactionManager = org.springframework.orm.jpa.JpaTransactionManager
```

- 테스트에서 주입받은 `PlatformTransactionManager` 빈의 `getClass().getName()` 출력. 이 빈을 Step 의 `chunk(2, tx)` 에 그대로 넘겼고 JobRepository 도 Boot 자동 구성 그대로 사용(추가 TX 매니저·메모리 JobRepository·`@EnableBatchProcessing` 없음).

## 4. JobRepository 의 DataSource == mySqlMainDataSource 확인

- 방법: Boot 자동 구성 빈 `DefaultBatchConfiguration`(실제 클래스 `BatchAutoConfiguration$SpringBootBatchConfiguration`)을 주입받아 `ReflectionTestUtils.getField(batchConfiguration, "dataSource")` 로 꺼낸 뒤, 테스트에 주입된 `DataSource` 와 `isSameAs` 비교(assert 통과).
- 결과:

```
[probe] injected DataSource = HikariDataSource (mysql-main-pool) / batch DataSource = HikariDataSource (mysql-main-pool) / same instance = true
```

- 풀 이름 `mysql-main-pool` 은 `modules/jpa/src/main/resources/jpa.yml:21` 의 `datasource.mysql-jpa.main.pool-name` → `DataSourceConfig.mySqlMainDataSource` 빈. 즉 JobRepository 메타데이터와 업무 테이블(`weekly_ranking_inputs`, `batch_restart_probe`)이 같은 DataSource·같은 스키마에 있고, `JpaTransactionManager` 한 개로 chunk 와 메타데이터가 함께 commit/rollback 된다.

## 5. confirmed_at 저장값 (UTC 확인)

- 입력: fixture 의 `'2026-08-17T01:00:00Z'` 등을 `ZonedDateTime.parse(...).withZoneSameInstant(UTC).toLocalDateTime()` 으로 변환해 `LocalDateTime` 으로 바인딩(JVM 기본 시간대 Asia/Seoul 에 의한 드라이버 변환을 피함). `created_at`/`updated_at` 은 `current_timestamp(6)`.
- 검증: `date_format(confirmed_at, '%Y-%m-%dT%H:%i:%sZ')` 로 컬럼 원문 조회.

```
[probe] stored confirmed_at (raw column) = [2026-08-17T01:00:00Z, 2026-08-17T01:00:00Z, 2026-08-18T01:00:00Z, 2026-08-19T01:00:00Z]
```

- 저장 컬럼 값이 UTC 벽시계와 일치(assert 통과). 이는 Hibernate 설정 `timezone.default_storage: NORMALIZE_UTC` + `jdbc.time_zone: UTC`(`jpa.yml:11-12`)로 엔티티 경유 저장될 값과 같은 표현이다.
- 미확인: Hibernate 가 생성한 `confirmed_at` 의 실제 컬럼 타입(`datetime(6)` 여부)은 information_schema 를 조회하지 않아 확인하지 않음. 엔티티 경유(`EntityManager.persist`) 저장과 JDBC 저장의 값 일치는 이번 실험 범위 밖.

## 6. 조사 문서(01-batch-module-survey.md)의 [추측] 중 확인된 항목

| 조사 문서 항목 | 추측 내용 | 결과 |
|---|---|---|
| 3-2 | 컨텍스트의 유일한 TX 매니저는 `JpaTransactionManager` | **사실** (3절 출력) |
| 3-3 | JobRepository = `mySqlMainDataSource` + 컨텍스트 TX 매니저 | **사실** (4절, 동일 인스턴스 확인). TX 매니저 쪽은 `SpringBootBatchConfiguration` 의 `transactionManager` 필드까지는 비교하지 않았으나 컨텍스트에 TX 매니저 빈이 하나뿐이므로 동일 |
| 3-4 | batch 컨텍스트에서도 `JdbcTemplate` 주입 가능 | **사실** (생성자 주입 성공) |
| 4절 / 5-3 | `initialize-schema: always` 가 `continueOnError` 로 BATCH_ 테이블을 유지하며 같은 JVM 에서 컨텍스트가 여러 개여도 문제 없음 | **사실** — `check` 실행에서 컨텍스트 3개(`job.enabled=false`·`job.name=demoJob`·probe) 가 같은 컨테이너에 순차로 올라와 모두 통과. probe 의 JobInstance ID 가 1 인 것은 probe 가 먼저 실행된 결과 |
| 6-1 | 컬럼은 기본 네이밍 전략으로 snake_case | **사실** — `@Column(name)` 없이 `snapshot_id`, `order_id`, `product_id`, `confirmed_at` 로 JDBC insert/select 성공 |
| 6-3 | `@EntityScan("com.loopers")` 범위 안의 엔티티는 test 프로필 `ddl-auto: create` 로 생성됨 | **사실** — `com.loopers.domain.ranking.WeeklyRankingInput` 만 추가하고 DDL 없이 `weekly_ranking_inputs` 에 insert 성공 |
| 5-3 | DemoJobE2ETest 와 다른 속성의 컨텍스트가 분리됨 | 사실(위 컨텍스트 3개) |
| 2-7 | DemoJobE2ETest 기동 시 러너가 demoJob 을 자동 실행 | **미확인** (이번 실험 범위 밖, 로그를 따로 보지 않음) |

## 7. 막힌 것·우회한 것

- 막힌 것 없음. 테스트는 첫 실행에서 통과했고 "하지 말 것" 항목을 모두 지켰다: 파라미터 추가 없음, failOnNine 은 `probe(...)` 메서드 인자, TX 매니저·JobRepository 는 기존 빈, 테스트에 `@Transactional` 없음, 두 실행 사이 어떤 테이블도 초기화하지 않음, `DatabaseCleanUp` 미사용, Demo 관련 파일 미수정.
- 우회라 할 만한 선택 2가지:
  1. `@BeforeAll` 대신 테스트 메서드 시작부에서 fixture 를 넣었다(메서드가 1개라 동일 효과, static 제약 회피).
  2. `confirmed_at` 을 `LocalDateTime`(UTC 벽시계)으로 바인딩했다. `Timestamp`/`Instant` 로 넣으면 드라이버가 JVM 시간대(Asia/Seoul)로 변환해 Hibernate 의 UTC 저장과 어긋날 수 있어서다. 프로덕션 코드가 아니라 fixture 전용 선택이며, 실제 랭킹 구현에서는 엔티티 경유 저장 또는 Hibernate 와 동일한 변환 규칙이 필요하다.
- 기타 관찰: Gradle `-i` 로그에 `Variant Selection Exception ... NoCompatibleVariantsFailure` 2줄이 보였으나 의존성 그래프 해석 중의 정보성 출력이며 빌드는 성공. 이번 변경과 무관(기존에도 출력됨 [추측]).
- 메타데이터 잔존: probe 가 남긴 JobInstance(`purchaseInputProbe`, `snapshotId=probe-1`)는 COMPLETED 상태로 컨테이너에 남는다. 컨테이너는 JVM 당 새로 뜨므로 재실행마다 깨끗하지만, 같은 컨테이너에서 두 번 돌리면 `JobInstanceAlreadyCompleteException` 이 난다(설계 §3 재시작 전략에서 다룰 사항).
