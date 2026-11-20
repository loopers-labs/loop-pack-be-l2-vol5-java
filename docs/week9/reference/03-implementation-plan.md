# 9주차 구현 계획 · 단계별 파일 목록

- 기준 문서: `docs/week9/weekly-ranking-design.md` (2026-11-20 확정). 결정 번호 D1~D15, 불변 규칙 R1~R7 은 그 문서의 것.
- 성격: 로컬 전용. 코드 작성 전 파일 목록 합의용("4절 공통" 결정). 각 단계는 **그 단계만 검증하는 gradle 명령**과 **커밋 메시지 초안**을 가진다.
- 표기: `신규` = 새 파일, `수정` = 기존 파일 변경(바뀌는 부분 명시). 패키지 루트는 `apps/commerce-batch/src/main/java/com/loopers` (batch), `apps/commerce-api/src/main/java/com/loopers` (api).
- 이미 있는 것: `domain/ranking/WeeklyRankingInput.java`(D2, nullable=false 반영 완료), `src/test/java/com/loopers/job/ranking/PurchaseInputProbeRestartTest.java`(§3-1 probe). 둘은 이번 계획에서 수정하지 않는다.
- 커밋 메시지 스타일: 최근 로그(`git log --oneline -20`)와 같게 `feat:` / `test:` / `docs(week9):` 접두어 + 한글 설명 한 줄.

---

## ① 저장 구조 — 엔티티 3개 · BatchClockConfig

| 파일 | 구분 | 역할 | 설계 |
|---|---|---|---|
| `domain/ranking/WeeklyRankingRevision.java` | 신규 | 회차(snapshot 정보) 엔티티. `@Id snapshotId`(String 64), periodStart(LocalDate), revision, status(`CALCULATING`/`COMPLETED` 문자열 또는 enum `@Enumerated(STRING)`), frozenAt, inputCount, expectedProductCount, inputChecksum, resultChecksum(null 허용), createdAt·updatedAt 직접. BaseEntity 미상속. UNIQUE `uk_wrr_period_revision` | D3, D6, D7 |
| `domain/ranking/WeeklyRankingScore.java` | 신규 | 점수 행 엔티티. BaseEntity 상속, snapshotId·productId·score(long)·ranking(Integer, null 허용). UNIQUE `uk_wrs_snapshot_product`, INDEX `idx_wrs_snapshot_ranking` | D4, R1~R7 |
| `domain/ranking/WeeklyRankingPublication.java` | 신규 | 공개 pointer 엔티티. `@Id periodStart`(LocalDate), snapshotId, revision, publishedAt, updatedAt. BaseEntity 미상속 | D5 |
| `config/BatchClockConfig.java` | 신규 | `@Configuration` + `@Bean Clock clock()`(systemDefaultZone). streamer `ClockConfig` 와 같은 패턴, 이름만 다름 | D12 |

- 회차의 `status VARCHAR(20)` 과 `input_checksum`·`result_checksum CHAR(64)` 는 `columnDefinition` 으로 타입을 고정한다. 이유: Hibernate 6 는 MySQL 에서 enum 을 네이티브 ENUM 으로 만들어 값 추가 시 ALTER 가 필요하고, `length` 만 주면 CHAR 가 VARCHAR 가 된다. VARCHAR/CHAR 로 두면 api 가 JDBC 로 읽을 때도 단순하다 (2026-11-20 구현 중 확인).
- 엔티티는 테이블 생성(`ddl-auto: create`)과 DDL 원본 역할만 한다. 쓰기는 JdbcTemplate (D9~D11). 리포지터리 인터페이스는 만들지 않는다(쓰는 곳이 없으면 깨지는 게 없음).
- 검증: 컨텍스트 기동으로 네 테이블이 생기는지, 기존 테스트가 깨지지 않는지.

```bash
./gradlew :apps:commerce-batch:test --tests '*CommerceBatchApplicationTest' --tests '*PurchaseInputProbeRestartTest'
```
- 커밋: `feat: 주간 랭킹 회차·점수·공개 pointer 엔티티와 batch Clock 빈 추가`

---

## ② Step 1 — 입력 고정 (`freezeWeeklyInputStep`)

| 파일 | 구분 | 역할 | 설계 |
|---|---|---|---|
| `domain/ranking/WeeklySnapshotId.java` | 신규 | 순수 계산 1(record): `periodStart` 파싱(월요일 검증), `[start, end)` UTC 경계, `snapshotId` 파싱(`weekly-YYYYMMDD-rN` → 날짜·revision, periodStart 와 일치 검증 → `IllegalArgumentException`). record 또는 final class | D1, D12 |
| `domain/ranking/RankingChecksum.java` | 신규 | 순수 계산 2: 입력/결과 행 직렬화(TAB·LF, confirmedAt 고정 6자리 UTC)와 SHA-256 소문자 hex | D13 |
| `batch/job/ranking/step/FreezeWeeklyInputTasklet.java` | 신규 | `@StepScope` Tasklet. 한 TX 안에서 D1 검증 → D6 충돌 조회 → `INSERT ... SELECT`(confirmed_at 범위, UTC LocalDateTime 바인딩) → 복사본 재조회로 count·distinct·checksum → 회차 INSERT(CALCULATING, frozenAt=Clock) | D9, D6, D12, D13 |
| `src/test/java/com/loopers/domain/ranking/WeeklySnapshotIdTest.java` | 신규 | 단위: 월요일 아님·형식 오류·snapshotId 불일치·경계 포함/제외 | D1, D12 |
| `src/test/java/com/loopers/domain/ranking/RankingChecksumTest.java` | 신규 | 단위: §6 fixture 입력 직렬화 첫 행 `42\t7\t2\t2026-08-17T01:00:00.000000Z\n`, 결과 직렬화 `7\t5\t1\n9\t5\t2\n8\t1\t3\n`, 빈 입력 checksum | D13 |

- 2026-11-20 구현 메모: Tasklet 의 `INSERT ... SELECT` 에 `AND deleted_at IS NULL` 을 추가했다(지시). 설계 D9 SQL 에는 없던 조건이라 D9 갱신 필요. 또 Tasklet 의 `@ConditionalOnProperty(havingValue="weeklyRanking")` 은 리터럴이며 ⑤에서 `WeeklyRankingJobConfig.JOB_NAME` 참조로 바꾼다. §6 fixture 기대 checksum: 입력 `3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298`, 결과 `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`, 빈 목록 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- Step 빈 등록(`StepBuilder.tasklet(..., transactionManager).transactionAttribute(READ_COMMITTED)`)은 ⑤의 `WeeklyRankingJobConfig` 에서 한다. 이 단계에서는 Tasklet 을 직접 호출하는 통합 테스트까지는 두지 않고(⑥에서 Job 단위로 검증), 순수 계산만 단위 테스트한다.
- 검증:

```bash
./gradlew :apps:commerce-batch:test --tests '*WeeklySnapshotIdTest' --tests '*RankingChecksumTest'
```
- 커밋: `feat: 주간 기간·snapshotId 규칙과 checksum 직렬화, 입력 고정 Tasklet 구현`

---

## ③ Step 2 — 점수 계산 (`calculateWeeklyScoresStep`)

| 파일 | 구분 | 역할 | 설계 |
|---|---|---|---|
| `batch/job/ranking/step/WeeklyScoreStepComponents.java` | 신규 | `@Configuration`. `@StepScope` 빈 3개: reader `weeklyProductIdReader`(JdbcCursorItemReader, saveState, `#{jobParameters['snapshotId']}`), processor(상품당 `SUM(quantity)` → `ProductScore` record, 합계 ≤ 0 이면 예외), writer(`INSERT ... ON DUPLICATE KEY UPDATE score=VALUES(score)`, ranking 미접촉) | D10, R1, R2 |
| `domain/ranking/ProductScore.java` | 신규 | chunk item record(productId, score). 2026-11-20 지시로 `domain/ranking` 에 별도 파일로 둠 | D10 |

- 2026-11-20 구현 메모: SQL 3개는 `static final String` 상수(`DISTINCT_PRODUCT_IDS_SQL`·`SUM_QUANTITY_SQL`·`UPSERT_SCORE_SQL`)로 분리해 R2(ranking 미접촉)를 SQL 한 줄로 확인할 수 있게 했다. processor·writer 도 snapshotId 바인딩이 필요해 셋 모두 `@StepScope`. writer 는 `JdbcTemplate.batchUpdate` 로 chunk 일괄 처리. `@ConditionalOnProperty` 리터럴은 ⑤에서 상수 참조로 교체.
- 파일 1개에 묶는 이유: 세 빈은 Step 2 에서만 쓰이고 서로 같은 테이블·파라미터를 공유한다. 각각 클래스로 나누면 "없으면 깨지는가" 에 답이 없다. 리뷰에서 가독성 문제가 있으면 분리.
- 검증: 이 단계 단독으로는 Step 빈이 없어 실행 불가. 컴파일·Checkstyle 만.

```bash
./gradlew :apps:commerce-batch:compileJava :apps:commerce-batch:checkstyleMain
```
- 커밋: `feat: 상품별 주간 점수 계산 reader·processor·절대값 upsert writer 구현`

---

## ④ Step 3 — 공개 (`openWeeklyRankingStep`)

| 파일 | 구분 | 역할 | 설계 |
|---|---|---|---|
| `batch/job/ranking/step/OpenWeeklyRankingTasklet.java` | 신규 | `@StepScope` Tasklet. 한 TX: (1) 행 수 == expected 검증 (2) `UPDATE ... JOIN (ROW_NUMBER())` 로 ranking (3) 결과 checksum → `result_checksum` (4) 회차 COMPLETED (5) pointer 두 단계 갱신(UPDATE `revision<=?` → 0건이면 SELECT → 있으면 거절·없으면 INSERT). 거절 시 WARN + `contribution.setExitStatus(new ExitStatus("COMPLETED", "NOT_PUBLISHED: newer revision rN already published"))` | D11, D5, D8, R3, R4, R6 |

- 2026-11-20 구현 메모: 단계 1~6 을 메서드로 분리(`lockRevision`·`verifyScoreCount`·`assignRanking`·`verifyRankingShape`·`completeRevision`·`publishPointer`), SQL 8개는 `static final` 상수. 설계 보강 3건(1단계 `FOR UPDATE`, 5단계 result_checksum 불일치 실패, 6a `published_at` CASE 유지)을 D5·D11 에 반영. 6단계는 UPDATED/UNCHANGED 로그 구분을 위해 `SELECT revision` 을 6a **앞**에서 한 번 읽고 6b 판정에 재사용한다(쿼리 수 동일, 순서만 지시와 다름). `@ConditionalOnProperty` 리터럴은 ⑤에서 상수 참조로 교체. DB 동작 테스트(공개 TX 중간 실패 rollback·수렴·D8·0건 완성본)는 ⑥.
- 검증: 컴파일·Checkstyle 만 (Job 단위 검증은 ⑥).

```bash
./gradlew :apps:commerce-batch:compileJava :apps:commerce-batch:checkstyleMain
```
- 커밋: `feat: 순위 부여·회차 완성·공개 pointer 조건부 갱신을 한 TX 로 묶은 공개 Tasklet 구현`

---

## ⑤ Job 설정 · yml

| 파일 | 구분 | 역할 | 설계 |
|---|---|---|---|
| `batch/job/ranking/WeeklyRankingJobConfig.java` | 신규 | `@ConditionalOnProperty(spring.batch.job.name=weeklyRanking)`. Job `weeklyRanking` = Step 1(Tasklet, `transactionAttribute` READ_COMMITTED) → Step 2(`chunk(2, transactionManager)`) → Step 3(Tasklet). `RunIdIncrementer` 없음. 기존 `JobListener`·`StepMonitorListener` 연결 | D1, D9, D10, D11, 4절 공통 |
| `batch/job/ranking/step/{FreezeWeeklyInputTasklet,WeeklyScoreStepComponents,OpenWeeklyRankingTasklet}.java` | **수정** | `@ConditionalOnProperty` 의 리터럴 `"weeklyRanking"` → `WeeklyRankingJobConfig.JOB_NAME` 상수 참조 (import 1줄 추가) | 4절 공통 |
| `src/test/java/com/loopers/job/ranking/WeeklyRankingJobRestartTest.java` | 신규 | Red 골격: 컨텍스트 기동 + Job 이름·Step 3개 순서·restartable·incrementer null 확인 1건. §6 시나리오는 ⑥에서 채움 | D1 |
| `src/main/resources/application.yml` | 수정 없음 | 변경 없음이 기본. `spring.batch.job.name=${job.name:NONE}` 이 이미 외부 주입이라 추가 키 불필요. 수정이 필요해지면(예: chunk size 외부화) 그때 diff 로 확인 | 4절 공통 |

- 2026-11-20 구현 메모: 리스너(`JobListener`·`StepMonitorListener`)는 붙이지 않았다 — `StepMonitorListener.afterStep` 이 ExitStatus 를 돌려줘 Step 3 의 D8 exitDescription 과 합성되는데 그 동작은 지금 검증 범위가 아니다(⑥ 이후 재검토). Step 별 TX 속성: Step 1 = tasklet + `DefaultTransactionAttribute(REQUIRED, READ_COMMITTED)`, allowStartIfComplete 기본 false / Step 2 = `chunk(2)`, faultTolerant·skip·retry 없음 / Step 3 = tasklet, 기본 isolation. `useAffectedRows` 는 jdbc-url 어디에도 없음 → 설계 D11 한계에 기록.
- `DemoJobConfig`·`DemoTasklet`·`DemoJobE2ETest` 는 건드리지 않는다.
- 검증: `job.name=weeklyRanking` 컨텍스트가 뜨고 Job 빈이 있는지. 임시로 `CommerceBatchApplicationTest` 를 바꾸지 않고, ⑥의 재시작 테스트 클래스를 먼저 "컨텍스트 기동 + Job 빈 주입" 만으로 만들어 통과시킨 뒤 본 시나리오를 채운다(Red → Green 순서 유지).

```bash
./gradlew :apps:commerce-batch:test --tests '*WeeklyRankingJobRestartTest'
```
- 커밋: `feat: weeklyRanking Job 설정 (입력 고정 → 점수 계산 → 공개 Step 연결)`

---

## ⑥ batch 테스트 — §6 재시작 · R1~R7 · 수렴 · D8 · 동시 실행

| 파일 | 구분 | 역할 | 설계 |
|---|---|---|---|
| `src/test/java/com/loopers/job/ranking/WeeklyRankingTestSupport.java` | 신규 | 테스트 공통: `product_purchase_activity` DDL 상수 문자열(streamer 엔티티와 같은 컬럼), §6 fixture 4행 JDBC insert(UTC LocalDateTime), JobParameters 빌더, 테이블 조회 helper, 고정 Clock 값 | 8절 공통 전제, D12 |
| `src/test/java/com/loopers/job/ranking/WeeklyRankingJobRestartTest.java` | 신규 | §6 시나리오: Step 2 상품 9 직전 실패(`@MockitoBean` 또는 `@Primary` 로 processor 감싸기, 파라미터 아님) → FAILED·같은 instance·stage 2행·ranking NULL·pointer 불변·read.count=2 → 같은 파라미터 재실행 → COMPLETED·read 1/write 1·ranking [1,2,3]·checksum → 별도 기간 정상 1회와 비교. `information_schema.COLUMNS` 로 `DATETIME(6)` 확인. Step 1 실패 → 반쪽 없음(7절 #1) | 8절 §6, 7절 #1·#2·#3, D13 |
| `src/test/java/com/loopers/job/ranking/WeeklyRankingInvariantsTest.java` | 신규 | R1~R7 각 1개 (`r1_…` ~ `r7_…`). R5 는 batch 쪽에서 "pointer 없음 → 공개 경로 조회 0행" 으로 확인하고 API 응답은 ⑦에서 | R1~R7 |
| `src/test/java/com/loopers/job/ranking/WeeklyRankingPublishTest.java` | 신규 | Step 3 수렴(재실행 변화 없음), 공개 TX 중간 실패(ranking 전부 NULL·pointer 불변), D8(r2 공개 뒤 r1 → COMPLETED·pointer r2·exitDescription) | 7절 #4·#5·#6, D5, D8, R3, R6 |
| `src/test/java/com/loopers/job/ranking/WeeklyRankingConcurrentLaunchTest.java` | 신규 | 테스트 전용 Job(이름 `weeklyRankingLatchProbe`, CountDownLatch Tasklet)을 별도 스레드에서 동기 launch → 같은 파라미터 2차 launch 가 `JobExecutionAlreadyRunningException` → latch 해제 → COMPLETED. 운영 JobLauncher 설정 불변 | 7절 #8 |

- 2026-11-20 구현 메모: 파일은 헬퍼 `RankingTestSupport`(DDL 상수·fixture·조회) + `RankingTestConfig`(`FailOnceProcessListener`) + 테스트 5개 `WeeklyRankingJobRestartTest`·`FreezeWeeklyInputStepTest`·`OpenWeeklyRankingConvergenceTest`·`WeeklyRankingRevisionRejectTest`·`WeeklyRankingConcurrentLaunchTest`. 계획의 `WeeklyRankingInvariantsTest`·`WeeklyRankingPublishTest` 는 지시에 따라 위 5개에 흡수(R1·R2 → Restart, R3·R6 → Convergence, R4·R7 → Restart, R5 는 ⑦ API 에서). main 에 seam 1개 추가: `domain/ranking/ScoreProcessingHook` + `WeeklyScoreStepComponents.noOpScoreProcessingHook()`(`@ConditionalOnMissingBean`) → **같은 날 교체**: 커스텀 seam 을 삭제하고 Spring Batch 표준 `ItemProcessListener` 로 바꿨다. `WeeklyRankingJobConfig` 가 `ObjectProvider` 로 선택 주입받아 Step 2 에 등록, 테스트는 `RankingTestConfig.FailOnceProcessListener`(productId 하나, 한 번만). 테스트 3 의 "공개 TX 중간 실패" 는 방법 B(hook 으로 Step 2 에서 멈춤 → result_checksum 오염 → 재시작 → 5단계 불일치) 사용. 배정표의 0행 period 2026-07-20 은 경계 테스트의 '시작 전' 행이 속하는 주라 2026-07-06 으로 옮김. 모든 테스트가 같은 `@Import(RankingTestConfig)` + 같은 속성이라 컨텍스트 1개 공유. 결과: batch 10 클래스 22건 통과. 실측값은 설계 9-1·9-2.
- Step 1 의 DB 동작(경계 포함/제외·D6 충돌·0행 회차 생성·실패 시 전체 rollback)은 ②에서 단위 테스트하지 않았다. 이 단계에서 `product_purchase_activity` 테스트 DDL 과 함께 `WeeklyRankingJobRestartTest`(또는 별도 `FreezeWeeklyInputStepTest`)로 작성한다.
- 모든 테스트: `@SpringBootTest(properties = {"spring.batch.job.enabled=false", "spring.batch.job.name=weeklyRanking"})`, `DatabaseCleanUp` 미사용, 테스트마다 다른 `periodStart`/`snapshotId`.
- 검증:

```bash
./gradlew :apps:commerce-batch:test --tests '*WeeklyRanking*Test'
./gradlew :apps:commerce-batch:check
```
- 커밋 (둘로 나눔): `test: 주간 랭킹 §6 재시작·R1~R7 불변 규칙·공개 수렴·D8 거절 테스트 추가` / `test: 같은 JobInstance 동시 실행 거절을 latch 로 결정적으로 검증`

---

## ⑦ api 조회 — Controller · Facade · 포트 · 구현 · DTO + API 테스트

| 파일 | 구분 | 역할 | 설계 |
|---|---|---|---|
| `domain/ranking/PublishedWeeklyRanking.java` | 신규 | 조회 결과 모델(record): snapshotId, frozenAt, `List<Entry(productId, score, ranking)>`. Facade·Controller 가 함께 쓴다 | D14 |
| `domain/ranking/PublishedWeeklyRankingRepository.java` | 신규 | 포트: `Optional<PublishedWeeklyRanking> findByPeriodStart(LocalDate)` | D14, R5 |
| `infrastructure/ranking/JdbcPublishedWeeklyRankingRepository.java` | 신규 | JdbcTemplate 구현. 쿼리 ①(pointer JOIN revisions COMPLETED) → ②(scores by snapshotId ORDER BY ranking). `@Entity` 없음 | D14 |
| `application/ranking/WeeklyRankingFacade.java` | 신규 | `@Transactional(readOnly=true)`. periodStart String 파싱·월요일 검증·limit 1~100 검증(`CoreException(BAD_REQUEST)`) → 리포지터리 → `ProductService.findActiveProducts` 로 삭제 제외 → limit → 응답 조립 | D14, D12 |
| `interfaces/api/ranking/WeeklyRankingV1Controller.java` | 신규 | `GET /api/v1/rankings/weekly`, `@RequestParam periodStart`(String, required), `limit`(defaultValue 10). X-USER-ID 없음 | D14 |
| `interfaces/api/ranking/WeeklyRankingV1Dto.java` | 신규 | `WeeklyRankingResponse(status, periodStart, periodEnd, snapshotId, frozenAt, items[])`, `Item(productId, productName, score, ranking)` | D14 |
| `apps/commerce-api/src/test/java/com/loopers/interfaces/api/ranking/WeeklyRankingV1ApiE2ETest.java` | 신규 | 테이블 4개 DDL 상수 실행 + JDBC fixture. 유효 / periodStart 누락·형식·월요일 아님 400 / limit 0·101 400 / 미공개 200 NOT_PUBLISHED / 0건 완성본 PUBLISHED+빈 items / 삭제 상품 제외 뒤 limit(2위 삭제, limit 2 → 1·3위) / R5(회차 COMPLETED 인데 pointer 없음 → NOT_PUBLISHED) | D14, R5, 7절 #9 |

- 2026-11-20 구현 메모: 실제 파일명은 계획과 다르다 — 포트 `WeeklyRankingReader`(`findPublished`·`findRankedScores`) + record `PublishedSnapshot`·`RankedScore` + `WeeklyPeriod`(domain, 4개), `WeeklyRankingJdbcReader`(infrastructure), `WeeklyRankingFacade`·`WeeklyRankingInfo`(application), `RankingV1Controller`·`RankingV1Dto`(interfaces). 테스트는 `WeeklyRankingV1ApiTest`(Published 7 + BadRequest 6 = 13건) + `WeeklyRankingTableSupport`(DDL 4개·기간 단위 DELETE). ②SQL 에 `AND ranking IS NOT NULL` 을 R5 방어선으로 추가(설계 D14 와 다른 유일한 점). frozenAt 은 API 표시용 초 단위 `yyyy-MM-dd'T'HH:mm:ss'Z'`. 결과: ranking 13건 + ArchitectureTest 3건 통과, Checkstyle main·test 통과. 실행 중 세션 시작 전부터 있던 untracked `interfaces/api/coupon/CouponV1ControllerTest.java`(W8 잔여물, main 과 불일치)가 test 컴파일을 막아 사용자가 `~/loopers-leftover/week8/` 로 옮긴 뒤 실행했다.
- 2026-11-20 보강(7번 (c)): ②SQL 의 `AND ranking IS NOT NULL` 제거, ①에 `expected_product_count` 추가, Facade `verifyPublishedShape` 로 R4 를 읽기 시점에 재검증(행 수·NULL·1..N 연속) → 위반 시 ERROR 로그 + `CoreException(INTERNAL_ERROR)` 500. 테스트 `InvariantViolation` 2건 추가 → ranking API 15건 + ArchUnit 3건 통과, crossAppTest 1건 재통과. 설계 7건 불일치(테스트 이름·§6 비교 r2·D5 순서·D10 COALESCE·D12 식·4절 현황·D14)도 같은 날 문서에 반영.
- `WeeklyPeriod` 의 월요일·형식 규칙은 batch 와 api 양쪽에 필요하다. 두 모듈이 공유 모듈을 갖지 않으므로 api 쪽 Facade 에 같은 규칙을 작게 다시 쓴다(공유 모듈 신설은 범위 밖, 한계로 기록). 테스트가 양쪽 규칙을 같은 fixture 로 묶는다.
- ArchUnit(`ArchitectureTest`) 대상이므로 domain ← application ← interfaces, infrastructure 는 domain 포트만 구현.
- 검증:

```bash
./gradlew :apps:commerce-api:test --tests '*WeeklyRankingV1ApiE2ETest' --tests '*ArchitectureTest'
./gradlew :apps:commerce-api:check
```
- 커밋: `feat: 주간 구매 랭킹 조회 API (공개 pointer 경로, 삭제 상품 제외 뒤 limit)`

---

## ⑧ §7 crossAppTest 확장 — build 변경 포함

| 파일 | 구분 | 바뀌는 부분 | 설계 |
|---|---|---|---|
| `apps/commerce-batch/build.gradle.kts` | **수정** | 끝에 `tasks.jar { enabled = true }` 추가(streamer `build.gradle.kts:27` 과 같은 이유 주석) | 8절 §7, 01-survey 9-3 |
| `apps/commerce-api/build.gradle.kts` | **수정** | `dependencies { "crossAppTestImplementation"(project(":apps:commerce-batch")) }` 한 줄 추가(`:49` 옆). `crossAppTest` 태스크 `systemProperty` 에 `spring.batch.job.enabled=false`, `spring.batch.jdbc.initialize-schema=always`, `spring.batch.job.name=weeklyRanking` 추가 | 8절 §7 |
| `apps/commerce-api/src/crossAppTest/java/com/loopers/crossapp/CrossAppTestConfig.java` | **수정** | `:42` ASSIGNABLE_TYPE 제외에 `CommerceBatchApplication.class`, `:43-49` REGEX 제외에 `com\\.loopers\\.config\\.BatchClockConfig`. `@Import` 에 `WeeklyRankingJobConfig`·Step 컴포넌트 추가 | D12, 8절 §7 |
| `apps/commerce-api/src/crossAppTest/java/com/loopers/crossapp/WeeklyRankingFlowTest.java` | 신규 | 기존 `OutboxDeliveryE2ETest` 경로로 PG 확정 → relay → Kafka → 구매 활동 생성 → `JobLauncher` 로 `weeklyRanking` r1 → API 응답 비교. 같은 이벤트 재발행 → 활동·점수 무증가. 늦은 구매 insert → r1 재시작 불변 → r2 실행 → 공개 전·후 API 전환 | 8절 §7, D15, 7절 #7 |

- 2026-11-20 구현 메모: 변경은 **5건**이 됐다 — 승인 4건 + `crossAppTestImplementation("org.springframework.boot:spring-boot-starter-batch")`(batch 의 implementation 의존은 소비 측 컴파일 classpath 에 노출되지 않아 `Job`·`JobLauncher` 타입을 테스트가 직접 쓰려면 필요. 별도 diff 확인 뒤 적용). 기존 crossAppTest 2개는 batch jar 포함 상태에서 먼저 돌려 통과를 확인한 뒤 새 테스트를 썼다. `WeeklyRankingFlowE2ETest` 는 `RANDOM_PORT` + `TestRestTemplate` 로 실제 엔드포인트를 호출하고, hook(`ItemProcessListener` 구현 `FailOnceProcessListener`)은 중첩 `@TestConfiguration`(`classes` 를 명시하면 자동 등록되지 않아 `classes = {CrossAppTestConfig, HookConfig}` 로 함께 넘김). 기존 E2E helper 는 공통화하지 않고 private helper 로 다시 썼다(공통화하면 기존 테스트 파일 수정 필요). 테이블은 두 jar 의 엔티티를 `@EntityScan` 이 잡아 ddl-auto 로 전부 생성돼 DDL 상수가 필요 없었다. 결과: crossAppTest 3건 통과. 실측값은 설계 9-3.
- 이 단계의 build·설정 변경은 **코드 작성 직전에 diff 를 먼저 보여주고 확인받는다** (설계 10절 확정).
- 검증:

```bash
./gradlew :apps:commerce-api:crossAppTest --tests '*WeeklyRankingFlowTest'
./gradlew :apps:commerce-batch:check :apps:commerce-api:check :apps:commerce-api:crossAppTest
```
- 커밋 (둘로 나눔): `test: crossAppTest 에 commerce-batch 연결 (plain jar·의존·Batch 속성·제외 패턴)` / `test: PG 확정 → Kafka → 구매 활동 → 주간 랭킹 Job → API 연결과 늦은 구매 revision 검증`

---

## 마무리 — 문서

| 파일 | 구분 | 바뀌는 부분 |
|---|---|---|
| `docs/week9/weekly-ranking-design.md` | **수정** | 9절 실험 기록 표 채움(ID·카운트·ExecutionContext·checksum·pointer·회귀 건수), 구현 중 바뀐 결정이 있으면 해당 D 갱신 |

- 커밋: `docs(week9): 주간 랭킹 실험 관찰값과 회귀 결과 기록`

---

## 파일 수 요약

| 단계 | 신규 | 수정 |
|---|---|---|
| ① | 4 | 0 |
| ② | 5 (main 3, test 2) | 0 |
| ③ | 1 | 0 |
| ④ | 1 | 0 |
| ⑤ | 1 | 0 (yml 은 변경 없음이 기본) |
| ⑥ | 5 (test) | 0 |
| ⑦ | 7 (main 6, test 1) | 0 |
| ⑧ | 1 (test) | 3 (build 2, CrossAppTestConfig 1) |
| 합계 | 25 | 3 |

batch main 은 10개(엔티티 3 + Clock 1 + 순수 계산 2 + Tasklet 2 + Step 컴포넌트 1 + JobConfig 1), api main 은 6개. 리뷰에서 줄일 후보: `PublishedWeeklyRanking` 을 리포지터리 안 record 로, `WeeklyScoreStepComponents` 를 `WeeklyRankingJobConfig` 안으로 — 둘 다 "없으면 깨지는가" 기준으로 구현 시 다시 판단한다.
