# 주간 랭킹 구현·실행 기록

계약의 원본은 [주간 랭킹 설계](../features/weekly-ranking.md)와 상세 문서다. 이 문서는 구현 단위별 실제 실행 조건·관찰값·한계만 남기며 설계를 중복 정의하지 않는다.

## 1단위 — 랭킹 Entity 매핑과 작은 DB 재시작 probe (2026-11-17)

### 범위

- 사용자 착수 승인(2026-11-17): [§9.10](../features/weekly-ranking/input-storage.md#910-entity-매핑쓰기-소유권과-최초-스키마-준비) Entity 5개·SnapshotStatus, [§11.3](../features/weekly-ranking/verification.md#113-미리-준비할-fixture와-batch-검증의-책임)의 probe-1·batch_restart_probe 작은 재시작 연결, [§4.11](../features/weekly-ranking/execution-recovery.md#411-job-등록-조건과-실행준비테스트-설정) R1의 batch Test `ddl-auto=update`.
- 제외: 제품 freeze·weeklyRanking Job·점수 계산·공개·Runner·initializer·API, PG/Kafka 실제 연결, streamer Entity·원본 DB 인덱스 적용, 의존성 변경, 전역 jpa.yml 변경, batch ArchUnit.
- probe-1 입력은 원문 §6 4행을 테스트가 `ranking_snapshot_input`에 직접 INSERT한 fixture다. 제품 freeze를 거친 입력이 아니며 canonical snapshotId 검증 예외를 만들지 않았다. 이 probe는 점수·순위·공개 정답 검증이 아니다.

### 변경 파일

|파일|내용|
|---|---|
|`apps/commerce-batch/src/main/java/com/loopers/domain/ranking/`|RankingSnapshotModel·RankingSnapshotInputModel·RankingScoreStageModel·RankingResultModel·RankingPublicationModel, SnapshotStatus. BaseEntity 상속, 이름 명시 UNIQUE/INDEX, ASCII binary ID/checksum, DATETIME(6), DECIMAL(65,0). 연관관계·FK·Repository·생성 메서드 없음|
|`apps/commerce-batch/build.gradle.kts`|batch의 모든 Test 작업에 `spring.jpa.hibernate.ddl-auto=update` 시스템 속성. API/streamer와 같은 Checkstyle 플러그인 설정(10.26.1, `config/checkstyle/checkstyle.xml`, maxWarnings 0) 추가|
|`apps/commerce-batch/src/test/java/com/loopers/job/demo/DemoJobE2ETest.java`|Checkstyle 위반이던 미사용 import 한 줄 삭제|
|`apps/commerce-batch/src/test/resources/fixtures/weekly-ranking-source-schema.sql`|streamer 매핑 기준 source 테이블 + 승인 `idx_ppa_confirmed_at`|
|`apps/commerce-batch/src/test/resources/fixtures/batch-restart-probe-schema.sql`|원문 §3의 확인용 테이블|
|`apps/commerce-batch/src/test/java/com/loopers/job/ranking/RankingSchemaSupport.java`|없을 때만 SQL 생성, 있으면 핵심 정의 확인 후 재사용. information_schema의 열(타입·null·ASCII collation·auto_increment)·인덱스(이름·유일성·열 순서)·CHECK/FK·InnoDB를 기대 문자열 집합과 정확히 대조. 자동 DROP/ALTER 없음|
|`apps/commerce-batch/src/test/java/com/loopers/job/ranking/RankingJobRestartTest.java`|최초 준비 확인 1건, 재시작 probe 1건|

status 열은 `@Enumerated(STRING)`에 `columnDefinition="varchar(16)"`을 주었다. 실제 DDL은 `status varchar(16) not null`이며 Hibernate 자동 CHECK가 생기지 않았다(설계 §9.2의 추가 CHECK 없음과 일치). status의 collation은 설계가 정하지 않아 확인 대상에서 제외했다.

### 의존성 실제 해석

`./gradlew :apps:commerce-batch:dependencyInsight --dependency spring-batch-core --configuration runtimeClasspath` (2026-11-17T14:47+09:00): `spring-batch-core:5.2.2`(spring-boot-starter-batch 3.4.4 경유). 같은 방식으로 `mysql-connector-j:9.1.0`(runtime·testRuntime), `hibernate-core:6.6.11.Final`. 설계 근거 버전(Batch 5.2.2·Connector/J 9.1.0)과 같다.

### 실행 결과

코드 상태: HEAD `fab1d46` + 위 미커밋 변경(staged 없음). 실제 MySQL 8.0 testcontainers fixture(`MySqlTestContainersConfig`), Docker 29.7.2. 개발 DB 미사용.

|검사|명령·시각|결과|
|---|---|---|
|batch check(최종 코드)|`./gradlew :apps:commerce-batch:check --rerun-tasks` 15:46:03|checkstyleMain 12파일·checkstyleTest 4파일 오류 0, test 3클래스 5건 통과(CommerceBatchApplicationTest 1, DemoJobE2ETest 2, RankingJobRestartTest 2), failure/error/skip 0|

이전 실행 이력: 최초 코드에서 probe 단독(14:53:22)·batch 전체(14:53:59) 모두 통과, Checkstyle(14:54:35)은 기존 DemoJobE2ETest 미사용 import로 test 작업 실패. 사용자 승인으로 그 import 한 줄을 지운 뒤 재실행(15:36) 통과. 이어 사용자 요청으로 주석·테스트 코드를 줄인 최종 코드에서 위 표대로 다시 실행했고(15:38 테스트·임시 init script Checkstyle 통과), 사용자 승인으로 batch build에 Checkstyle 플러그인을 정식 추가한 뒤 위 `check`로 최종 확인했다. 동작·단언 대상은 줄이지 않았고, 정리 뒤 중복 확인이던 새 연결의 execution 수 조회만 JobExplorer 확인으로 대신했다.

batch Checkstyle은 이제 일반 `check`에 포함된다. DemoJobE2ETest의 기존 위반(`lombok.RequiredArgsConstructor` 미사용 import)은 사용자 승인으로 해당 import만 지웠으며 Job 선택·실행·단언은 그대로다. batch에는 ArchUnit이 없으며 batch 계층 검증을 주장하지 않는다.

### 최초 준비 관찰

- TX/DataSource: 주입된 transactionManager는 `JpaTransactionManager`이고 그 DataSource는 `mySqlMainDataSource`와 같은 객체다. Boot `SpringBootBatchConfiguration`(DefaultBatchConfiguration)의 `getDataSource()`·`getTransactionManager()`도 같은 객체다(테스트 reflection 호출).
- 스키마: 랭킹 5개(Hibernate update 생성)와 source(SQL 생성)가 기준 정의와 일치했다. InnoDB, 물리 FK 0, source CHECK는 `ck_ppa_order_id/product_id/quantity`, 랭킹 테이블 CHECK 없음.
- 유효 설정: 앱 pool 연결의 `sql_mode`에 `STRICT_TRANS_TABLES` 포함, `transaction_isolation=REPEATABLE-READ`.
- DATE: `setObject(LocalDate)` → `getObject(LocalDate.class)` 2026-08-17 일치.
- DATETIME(6): JVM 기본 시간대 Asia/Seoul에서 `setObject(LocalDateTime 2026-08-23T23:59:59.999999)` 저장 → DB 문자열 `2026-08-23 23:59:59.999999`, `getObject(LocalDateTime.class)` 동일. 9시간 이동·마이크로초 손실 없음.
- 큰 정수: 같은 상품 두 행 `Long.MAX_VALUE` 합의 `SUM` → `getBigDecimal().toBigIntegerExact()` = 18446744073709551614. `DECIMAL(65,0)`에 65자리 최대값 저장·조회 정확, 10^65 INSERT는 strict mode 오류(DataAccessException)로 실패.
- 확인용 `probe-jdbc-1` 입력/stage 행은 확인 뒤 삭제하고 0건을 확인했다.

### 재시작 핵심 증거

최종 코드의 batch 전체 실행 기준(최초 단독 실행에서도 ID만 다르고 같은 관찰). context 설정은 `spring.batch.job.name=weeklyRanking`, `spring.batch.job.enabled=false`, 기본 동기 JobLauncher, 테스트 자동 rollback 없음.

|항목|1차 실행|2차 실행|
|---|---|---|
|JobInstance / JobExecution|4 / 4|4 / 5|
|상태|FAILED|COMPLETED|
|processor 호출 ID|7, 8, 9(9에서 예외)|9|
|writer 기록 ID|7, 8|9|
|영속 `purchaseProductProbe.read.count`(JobExplorer 재조회)|2|4|
|StepExecution|4 (commit 1, rollback 1)|5|
|새 JDBC 연결로 조회한 batch_restart_probe|7, 8|7, 8, 9|

- 식별 parameter는 `snapshotId=probe-1`(identifying) 하나다. 실패 스위치는 Java 인자이며 run.id·현재 시각을 넣지 않았다. 매 실행 새 reader/step/job 객체를 만들었다.
- writer 호출 때마다 실제 TX 활성과 DataSource 연결 바인딩(`TransactionSynchronizationManager`)이 참이었다.
- 별도 연결 조회: `BATCH_JOB_EXECUTION` 4=FAILED, 5=COMPLETED. 두 실행 사이 probe-1 입력 4행 유지.
- 2차 완료 후 read.count 4는 끝을 확인한 마지막 read까지 센 값이다. 재개 위치 근거는 1차 뒤의 값 2와 2차 처리 ID 9다.
- 단독 실행(14:53:22)의 ID는 instance 1, execution 1(FAILED)/2(COMPLETED), step 1/2였다.

### R1(update) 관찰

같은 JVM에서 CommerceBatchApplicationTest·DemoJobE2ETest·RankingJobRestartTest가 서로 다른 context 3개를 띄웠다. 세 로그 모두 `drop table`이 없다. 랭킹 테이블 `create table` 5건과 UNIQUE의 `drop index`→`add constraint`는 처음 만든 context에서만 나왔고 이후 두 context는 랭킹 DDL을 실행하지 않았다. 이어 실행한 probe의 스키마 확인도 통과했다. 기존 Demo/기동 테스트의 Job 선택·단언은 바꾸지 않았다.

### 자원 정리

- probe 성공 경로 끝에서 `JobRepository.deleteJobInstance`로 probe instance와 execution을 지우고 JobExplorer 재조회로 instance 없음을 확인했다. probe-1 입력 행 삭제, 테스트가 만든 batch_restart_probe 테이블 DROP을 확인했다.
- 실패한 경우에는 증거 보존을 위해 정리하지 않는다. 테스트 DB는 JVM 종료 때 testcontainers 수명과 함께 사라진다.
- 다른 테스트의 Demo instance, 다섯 랭킹 테이블, SQL로 만든 source 테이블(행 없음)은 지우지 않고 컨테이너 수명에 맡겼다.
- 실행 뒤 Hikari pool·EntityManagerFactory 종료 로그를 확인했다. 남은 자식 프로세스는 없다(사용하지 않음). 종료 뒤 `docker ps -a --filter label=org.testcontainers=true` 결과 0건으로 테스트 컨테이너가 남지 않았다. 기존 로컬 개발 컨테이너는 건드리지 않았다.

### 한계

- 제품 freeze·manifest·checksum·점수 upsert·공개는 구현하지 않았다. 원문 §6의 기대 점수/순위, 정상/재시작 checksum 비교, 기존 공개본 유지는 다음 단위 범위다.
- JobRepository 갱신이 writer와 같은 물리 연결을 썼는지는 직접 관찰하지 않았다. 같은 transactionManager/DataSource 객체와, 실패 chunk의 행·위치가 함께 남지 않은 결과로 간접 확인했다.
- 원본 DB에 idx_ppa_confirmed_at를 적용하거나 streamer Entity를 수정하지 않았다.

## 2단위 — 입력 고정·계산 Step과 실제 Job 재시작 (2026-11-17)

### 범위

- 사용자 착수 승인(2026-11-17): [§8.1~8.6](../features/weekly-ranking/calculation-publication.md#86-클래스-배치포트와-호출-순서)의 요청·parameter 변환, 입력 고정 tasklet(§8.2·§9.7·§9.12), 계산 Step(Reader·SUM Processor·절대 점수 upsert Writer, chunk=2), [§4.11](../features/weekly-ranking/execution-recovery.md#411-job-등록-조건과-실행준비테스트-설정) 조건부 JobConfig, 원문 4행 정상/재시작 테스트.
- 제외(후속 단위): 공개 Step·결과 checksum·순위, Runner·revision 자동 결정·initializer, API, 실제 연결. 이번 Job은 `freezeWeeklyRankingInput → calculateWeeklyScores` 두 Step이며 공개 Step은 3단위에서 붙인다. 테스트는 revision r1/r2를 직접 지정해 JobLauncher로 실행했다.

### 구현

|위치|내용|
|---|---|
|`domain/ranking`|WeeklyRankingRequest(엄격 날짜·월요일·양수 revision·canonical ID), RankingInputException(INVALID_REQUEST·SNAPSHOT_CONTRACT_MISMATCH·INCONSISTENT_INPUT), RankingInputManifest, RankingProductScore, RankingChecksum(입력 행), 두 Repository 포트|
|`infrastructure/ranking`|JdbcRankingInputRepository(§9.7 INSERT SELECT·UTC_TIMESTAMP(6)·count·정렬 읽기·READY INSERT, 고아 확인), JdbcRankingScoreRepository(SUM→BigDecimal→toBigIntegerExact, ON DUPLICATE KEY 절대값 upsert). 날짜·시각은 setObject/getObject|
|`application/ranking/RankingInputService`|MANDATORY. 기존 snapshot 재사용 시 manifest·건수·checksum 재검사, 신규 시 고아 확인→복사→DB 시각→건수→checksum→READY 저장|
|`batch/job/weeklyranking`|WeeklyRankingParameters(identifying String/Long/String 검사, validator 공용), WeeklyRankingJobConfig(조건부 등록, 기존 JobRepository·transactionManager), 입력 고정 tasklet, step-scoped Reader(`weeklyRankingProductReader`)·Processor(beforeStep에서 READY manifest 확인)·Writer|
|test `job/ranking/process/WeeklyRankingFaultSupport`|스테레오타입 없는 명시 등록 클래스. BeanPostProcessor가 step-scoped Processor target을 감싸 arm한 상품의 process 직전에 한 번 예외. 실제 위임 상품 ID 기록|
|test `RankingJobRestartTest`|`@Import(WeeklyRankingFaultSupport)`, 새 메서드 `weeklyRankingRestartsCalculationFromThirdProduct`|

### 실행 결과

코드 상태: HEAD `6d61104`(1단위 커밋) + 2단위 미커밋 변경. 실제 MySQL 8.0 testcontainers. 15:55 첫 통과 뒤 사용자 요청으로 미사용 `disarm`·중복 Step 조회 helper를 정리하고 다시 실행했다(execution ID·관찰 동일).

|검사|명령·시각|결과|
|---|---|---|
|batch check(정리 후 최종 코드)|`./gradlew :apps:commerce-batch:check --rerun-tasks` 15:58:05|checkstyleMain 28파일·checkstyleTest 5파일 오류 0. 테스트 3클래스 6건 통과(CommerceBatchApplicationTest 1, DemoJobE2ETest 2, RankingJobRestartTest 3), failure/error/skip 0. (처음 기록한 7건은 합계 오류로 Codex 검토 뒤 정정)|

### 재시작 증거

source는 원문 4행(주문 42/42/43/44, 2026-08-17 주)을 `product_purchase_activity`에 넣고 제품 입력 고정을 거쳤다. 입력 checksum 기대값 `3e90276e…7c357a298`는 제품 코드와 별도로 원문 4행을 §3.5 규칙 문자열로 만들어 `shasum -a 256`으로 계산했다.

|실행|instance / execution|상태|위임 상품|관찰(새 연결·JobExplorer)|
|---|---|---|---|---|
|r1 정상|- / 6|COMPLETED|7, 8, 9|manifest READY·입력 4·상품 3·checksum 기대값, stage 7=5·8=1·9=5|
|r2 1차|6 / 7|FAILED(9 직전 테스트 예외 1회)|7, 8|freeze COMPLETED·calculate FAILED, `weeklyRankingProductReader.read.count=2`, stage 7=5·8=1, manifest 동일|
|r2 재실행|6 / 8|COMPLETED|9|이 실행의 Step은 calculateWeeklyScores 하나(완료한 입력 고정 생략), stage 7=5·8=1·9=5 = r1, 입력 4행·checksum 불변|

r1 instance ID는 로그에 남기지 않았다. 원문 기대 순위(7→9→8)·결과 checksum·기존 공개본 유지는 공개 Step이 없어 이번에 확인하지 않았다.

### 한계

- writer 같은 상품 재기록, chunk commit 전 실패, 잘못된 ID·기간·revision, 고아·손상 입력, freeze rollback, 빈 주, 기간 경계는 구현은 했거나 경로가 있으나 테스트하지 않았다. 3단위(WeeklyRankingBatchContractTest)에서 확인한다.
- 실패 주입은 같은 JVM의 테스트 BeanPostProcessor다. 새 JVM 재시작은 실제 연결 단위에서 확인한다.
- Batch 테이블·랭킹 데이터는 테스트 컨테이너 수명에 맡겼다(§11.6 보존 원칙).

## 3단위 — 공개 Step과 batch 계약 시나리오 (2026-11-17)

### 범위

- 사용자 착수 승인(2026-11-17): [§6.1~6.9](../features/weekly-ranking/calculation-publication.md#68-클래스메서드tx-처리-순서)의 공개 Step(완성 확인·ROW_NUMBER 순위·결과 checksum·COMPLETED·조건부 pointer), 원문 재시작의 순위·공개 단언, [§11.3](../features/weekly-ranking/verification.md#113-미리-준비할-fixture와-batch-검증의-책임)의 WeeklyRankingBatchContractTest.
- 제외(후속 단위): Runner·revision 자동 결정·initializer, API, 실제 연결. 테스트는 revision을 직접 지정해 JobLauncher로 실행했다.

### 구현

|위치|내용|
|---|---|
|`domain/ranking`|PublicationOutcome·PublicationResult·RankingPublicationException(6 reason)·CurrentPublication·RankingPublicationRepository, RankingChecksum.addResultRow|
|`infrastructure/ranking/JdbcRankingPublicationRepository`|§6.5 완성 count, §6.6 ROW_NUMBER INSERT SELECT·순위순 읽기·COMPLETED 조건부 UPDATE, §6.7 JOIN 조건부 pointer UPDATE·FOR UPDATE current read·최초 INSERT|
|`application/ranking/RankingPublicationService`|MANDATORY. READY면 완성 후 공개 판정, COMPLETED면 결과 건수 확인 뒤 판정. UPDATE 0행은 current read로 최초 INSERT·ALREADY_PUBLISHED·SKIPPED_NEWER·정합성 오류로 분류|
|`batch/job/weeklyranking`|PublishWeeklyRankingTasklet(outcome·대상·공개 snapshotId를 Step context에 기록), Job에 `publishWeeklyRanking` Step 추가|
|test `RankingTestDb`|두 테스트가 같이 쓰는 fixture 입력·새 연결 조회·JobExplorer 재조회 helper. RankingJobRestartTest의 중복 helper를 옮김|
|test `BatchMetadataFaultSupport`|JobRepository 테스트 대역. 지정 Step의 StepExecution 저장을 한 번 실패시킴. IN_CHUNK=업무 쓰기와 같은 chunk TX 안, FINAL=업무 commit 뒤 Step 최종 저장|
|test `WeeklyRankingBatchContractTest`|아래 7개 시나리오, 주마다 격리|

### 실행 결과

코드 상태: HEAD `baeaa5d`(2단위 커밋) + 3단위 미커밋 변경. 실제 MySQL 8.0 testcontainers.

|검사|명령·시각|결과|
|---|---|---|
|batch check(정리 후 최종 코드)|`./gradlew :apps:commerce-batch:check --rerun-tasks` 16:11:14|checkstyleMain·checkstyleTest 오류 0. 테스트 4클래스 13건 통과(CommerceBatchApplicationTest 1, DemoJobE2ETest 2, RankingJobRestartTest 3, WeeklyRankingBatchContractTest 7), failure/error/skip 0. 종료 뒤 테스트 컨테이너 0개|

### 원문 재시작(2026-08-17)

- r1 정상(execution 6): COMPLETED, 순위 `7:5:1, 9:5:2, 8:1:3`, 결과 checksum `745d50bb…3b0a67d2`(기대 순위를 §3.5 규칙 문자열로 만들어 shasum 계산), pointer=r1.
- r2 1차(instance 6 / execution 7): 9 직전 FAILED, read.count=2, stage 7/8, r2 manifest READY·결과 checksum 없음·ranking 0행, **pointer는 r1 그대로(published_at 동일)**.
- r2 재실행(execution 8): Step은 calculateWeeklyScores·publishWeeklyRanking만 실행, outcome PUBLISHED, 순위·결과 checksum이 r1과 같음, pointer=r2.

### 계약 시나리오

|주|시나리오|관찰|
|---|---|---|
|2026-08-24|같은 점수 재기록|두 번 upsert해도 7=5|
||계산 chunk TX의 metadata 저장 실패(IN_CHUNK)|instance 7: execution 9 FAILED, 위임 7·8 뒤 stage 0행·read.count 없음 → execution 10에서 7·8·9 처리, 순위 정상|
||잘못된 요청|월요일 아님·snapshotId 불일치·revision 0 모두 JobParametersInvalidException|
||고아 stage|snapshot 없이 stage가 있으면 FAILED, snapshot 미생성·기존 행 보존|
||ID/revision 불일치|같은 ID가 revision 6 행을 가리키면 FAILED, 기존 행 보존|
||저장 입력 손상|입력 1행 삭제 뒤 재사용 요청은 INCONSISTENT_INPUT|
|2026-08-31|입력 고정 TX의 metadata 저장 실패(IN_CHUNK)|execution 14 FAILED, 복사 입력 0행·manifest 없음 → execution 15에서 4행 고정·완료|
|2026-07-20|주 경계|07-19T23:59:59.999999·07-27T00:00 제외, 07-20T00:00·07-26T23:59:59.999999 포함. 입력 checksum이 shasum 기대값과 일치(마이크로초·UTC 유지), 순위 `8:3:1, 7:2:2`|
|2026-09-14|빈 주|COMPLETED·입력/상품 0·두 checksum 모두 0바이트 SHA-256, 결과 0행, pointer 생성|
|2026-09-21|공개 TX의 metadata 저장 실패(IN_CHUNK)|r2 READY 유지·결과 0행, pointer r1 유지 → 재실행은 공개 Step만 수행해 pointer r2|
|2026-09-28|공개 멱등·역전 방지|r2 공개 뒤 같은 대상 재공개는 ALREADY_PUBLISHED, pointer·manifest 불변. 이어 r1 실행은 완성 결과만 저장하고 SKIPPED_NEWER, pointer r2 유지|
|2026-10-05|공개 commit 뒤 최종 Step 저장 실패(FINAL)|execution 13 UNKNOWN(영속 Job UNKNOWN, 공개 Step STARTED). 완성본·pointer는 commit됨. Job restart 없이 공개 서비스를 별도 TX로 다시 호출하면 ALREADY_PUBLISHED, pointer 불변|

경계 시나리오는 [§11.3.2](../features/weekly-ranking/verification.md#1132-독립-시나리오의-주업무-id-격리) 예시의 2026-09-07 대신 2026-07-20 주를 썼다. 09-07 주의 시작 직전 행과 끝 행이 각각 08-31(입력 고정 rollback)과 09-14(빈 주) 시나리오 주에 들어가 격리가 깨지기 때문이다. 표는 "고정 배정 예시"이므로 계약 변경은 아니지만, 설계 표에는 아직 반영하지 않았다.

### 한계

- 실패 주입은 같은 JVM의 테스트 대역(processor BeanPostProcessor, JobRepository 대역)이다. 새 JVM 재시작은 실제 연결 단위에서 확인한다.
- 입력 고정 rollback은 "복사·manifest 저장 뒤, 같은 TX의 metadata 저장 실패"로 재현했다. 복사와 manifest 사이 지점의 별도 실패 주입은 하지 않았다.
- UNKNOWN 실행의 운영 복구 절차(§5.6~5.8)는 구현 범위가 아니며 확인하지 않았다.

## 4단위 — 명시 실행 진입·revision 자동 결정 (2026-11-17)

### 범위

- 사용자 착수 승인(2026-11-17): [§4.3~4.12](../features/weekly-ranking/execution-recovery.md#47-실행-옵션과-최초-준비-전제)의 WeeklyRankingLaunchRunner(new/restart)·revision 자동 결정·실행 옵션 Properties·initializer·`CommerceBatchApplication.createApplication`·시작 로그 listener.
- 제외(후속 단위): API, 실제 연결과 batch 자식 JVM.

### 구현

|위치|내용|
|---|---|
|`CommerceBatchApplication`|`createApplication(Class<?>...)`가 SpringApplication 조립과 initializer 등록을 맡고 main이 이를 사용|
|`domain/ranking/RankingLaunchRepository`·`infrastructure/ranking/JdbcRankingLaunchRepository`|`SELECT MAX(revision) FROM ranking_snapshot WHERE period_start = ?`(삭제 행 포함)|
|`batch/job/weeklyranking/WeeklyRankingLaunchProperties`|`weekly-ranking` prefix, unknown field 거절, `launch.enabled` 기본 false|
|`WeeklyRankingLaunchContextInitializer`|launch.enabled=true면 job.name=weeklyRanking·job.enabled=false·ddl-auto=none·initialize-schema=never가 아니면 refresh 전 IllegalStateException|
|`WeeklyRankingLaunchRunner`|ApplicationRunner·ExitCodeGenerator. 옵션 조합·non-option 인자·Boot 자동 실행·registry 확인 → new는 JobExplorer 전체 이력과 snapshot 최대값의 다음 revision으로 `JobOperator.start`, restart는 최신 FAILED/STOPPED 실행만 `JobOperator.restart`. 같은 기간의 진행 중·UNKNOWN·손상 이력은 거절. JobExplorer로 다시 읽은 상태가 COMPLETED일 때만 0|
|`WeeklyRankingJobConfig`|launch.enabled=true일 때만 Runner 등록, Job의 beforeJob 시작 로그(instance·execution·parameter)|
|test `WeeklyRankingLaunchTest`|Runner를 옵션별로 직접 만들어 호출하는 4개 시나리오|

### 실행 결과

코드 상태: HEAD `5260c74`(3단위 커밋) + 4단위 미커밋 변경. 실제 MySQL 8.0 testcontainers.

|검사|명령·시각|결과|
|---|---|---|
|batch check|`./gradlew :apps:commerce-batch:check --rerun-tasks` 16:23:58|checkstyleMain·checkstyleTest 오류 0. 테스트 5클래스 17건 통과(기존 13 + WeeklyRankingLaunchTest 4), failure/error/skip 0. 종료 뒤 테스트 컨테이너 0개|

|주|시나리오|관찰|
|---|---|---|
|2026-06-01|자동 revision|new → r1(execution 23) 종료 코드 0. 입력 고정 TX 실패로 snapshot 없이 이력만 남은 r2(execution 24, FAILED, 1). 다음 new는 r3(execution 25)로 r2를 재사용하지 않음, pointer r3|
|2026-06-08|명시 재시작|new 실패(execution 27) → restart 27도 9 직전 실패(28, 같은 instance 22) → 과거 ID 27 restart는 NOT_LATEST_EXECUTION(실행 수 2 유지) → restart 28 성공(29, 0) → 완료 ID restart는 NOT_RESTARTABLE. new+execution-id, restart+period-start, mode 누락, 월요일 아님, non-option 인자는 INVALID_OPTION|
|2026-06-15|UNKNOWN 보호|공개 commit 뒤 최종 Step 저장 실패로 UNKNOWN(종료 코드 1). 같은 주 new·UNKNOWN 실행 restart 모두 IN_PROGRESS_OR_UNKNOWN, instance 수 불변|
|-|initializer|올바른 네 설정은 통과, 각 설정을 바꾸면 해당 key를 담은 IllegalStateException. `createApplication().run(... ddl-auto=update)`도 refresh 전에 같은 예외|

### 한계

- Runner는 테스트에서 직접 생성해 호출했다. launch.enabled=true context에서 Boot가 ApplicationRunner로 실행하고 `SpringApplication.exit`가 종료 코드를 받는 경로는 실제 연결 단위의 batch 자식 JVM에서 확인한다.
- 프로세스 생존 확인 등 §5.6의 수동 절차는 Runner 범위가 아니다. 같은 기간 직렬 실행 전제이며 다중 프로세스 번호 발급 원자성은 보장하지 않는다(§4.5).
- 로그의 거절 사유 집계: INVALID_OPTION 5, IN_PROGRESS_OR_UNKNOWN 2, NOT_LATEST_EXECUTION 1, NOT_RESTARTABLE 1.

## 실행 JDK 정정 (2026-11-17)

로컬 `~/.gradle/gradle.properties`가 Gradle daemon을 JDK 17로 지정하고, 하위 프로젝트에는 toolchain 설정이 없어 1~4단위의 batch 명령은 JDK 17로 컴파일·실행됐다(쿠폰 실행 기록과 같은 환경 문제). commerce-api는 Java 21 문법 때문에 JDK 17에서 컴파일되지 않아 이 문제가 드러났다. 저장소 설정은 바꾸지 않고 이후 명령에 `-Dorg.gradle.java.home="$JAVA_HOME"`(JDK 21.0.12)을 붙였다. 4단위까지의 최종 코드에 대해 `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-batch:check --rerun-tasks`(16:56:05)를 다시 실행해 Checkstyle 오류 0, 5클래스 17건 통과·failure/error/skip 0을 확인했다.

## 5단위 — 랭킹 API (2026-11-17)

### 범위

- 사용자 착수 승인(2026-11-17): [§7](../features/weekly-ranking/api-contract.md#7-api-응답)의 `GET /api/v1/rankings/weekly`, [§11.3.1](../features/weekly-ranking/verification.md#1131-단독-테스트의-준비-파일순서소유권)의 API 단독 테스트 SQL 준비와 WeeklyRankingApiTest.
- 제외: 실제 연결(6단위). API 테스트의 공개본은 batch 실행이 아니라 SQL로 준비한 fixture다.

### 구현 (commerce-api)

|위치|내용|
|---|---|
|`domain/ranking/WeeklyRankingQuery`|raw periodStart·limit 검증. 날짜 필수·형식·실제 날짜·월요일(INVALID_REQUEST) → limit 정수(BAD_REQUEST)·1~100(INVALID_REQUEST), 기본 10|
|`domain/ranking/WeeklyRankingQueryRepository`·`WeeklyRankingQueryResult`|§7.8 두 조회 포트와 Publication·SnapshotSummary·Item projection|
|`infrastructure/ranking/WeeklyRankingQueryRepositoryImpl`|§7.8 두 SQL(LEFT JOIN pointer·snapshot, 현재 Product·Brand 미삭제 JOIN 뒤 rank 순 LIMIT). 시각은 getObject(LocalDateTime), score는 toBigIntegerExact|
|`application/ranking/RankingFacade`·`WeeklyRankingInfo`|readOnly TX 하나에서 pointer 확인 → 같은 snapshotId로 항목 조회. pointer 없음은 NOT_PUBLISHED, 깨진 공개 상태는 INTERNAL_ERROR|
|`interfaces/api/ranking`|Controller·ApiSpec·Dto. score는 문자열, inputFixedAt은 UTC `…SSSSSS'Z'`, 미공개면 snapshotId·inputFixedAt null(기존 NON_NULL로 생략)|
|test `fixtures/weekly-ranking-schema.sql`·`WeeklyRankingSchemaSupport`|batch 매핑과 같은 다섯 테이블. 모두 없을 때만 생성, 모두 있으면 열·인덱스 이름/순서/유일성·CHECK/FK 없음·InnoDB 확인, 일부만 있으면 실패|
|test `WeeklyRankingApiTest`|아래 6개 테스트(파라미터 case 포함 15건)|

### 실행 결과

코드 상태: HEAD `ff18269`(4단위 커밋) + 5단위 미커밋 변경. JDK 21.0.12, 실제 MySQL 8.0 testcontainers.

|검사|명령·시각|결과|
|---|---|---|
|API 관련 테스트·Checkstyle·ArchUnit|`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*WeeklyRankingApiTest' --tests '*ArchitectureTest' --rerun-tasks` 16:54:56|Checkstyle main/test 오류 0. WeeklyRankingApiTest 15건, ArchitectureTest 3건 통과, failure/error/skip 0|
|전체 Controller 영향 확인|같은 방식으로 `--tests '*ContractClassificationTest' --tests '*AdminAccessBoundaryTest' --tests '*CommerceApiContextTest'` 16:55:28|기동 1, 관리자 경계 6, 계약 분류 4건 통과|

API 전체 회귀는 실행하지 않았다(관련 테스트만 실행).

|주|시나리오|관찰|
|---|---|---|
|2026-11-02|숨김 제외 후 limit|rank1 정상·rank2 삭제 상품·rank3 삭제 브랜드 상품·rank4 정상(재고 0)에서 limit=2 → rank 1·4. score `123456789012345678901234567890`·`1` 문자열, inputFixedAt `2026-11-09T01:23:45.123456Z`, 현재 상품명|
|2026-11-09|limit 기본/최대|공개 가능 12개에서 생략 → rank 1~10, limit=100 → 12개|
|2026-11-16·11-23|빈 공개본·미공개|11-16은 PUBLISHED·snapshotId·빈 items. 11-23은 NOT_PUBLISHED·기간·빈 items이고 JSON에 snapshotId·inputFixedAt 키 없음|
|2026-11-30·12-07|깨진 공개 상태|없는 snapshot을 가리키는 pointer, READY snapshot을 가리키는 pointer 모두 500 `Internal Server Error`|
|-|입력 오류 10건|날짜 누락/빈 값·형식·실제 날짜 아님·월요일 아님은 400 INVALID_REQUEST, limit 빈 값·문자·int 초과는 400 `Bad Request`, 0·101은 400 INVALID_REQUEST. 메시지는 §7.10 문구|
|-|고객 식별 우선|헤더 없음 400 INVALID_REQUEST(날짜 오류 메시지 아님), 없는 고객 404 USER_NOT_FOUND|

### 한계

- 두 조회가 같은 readOnly TX와 snapshotId를 쓰는 것은 코드 경로로 보장했고, 조회 중간에 새 revision이 공개되는 동시성 상황은 재현하지 않았다.
- batch가 만든 실제 완성본을 API가 읽는 연결은 6단위에서 확인한다.

## 6단위 — 실제 연결 한 건 (2026-11-17)

### 범위

- 사용자 착수 승인(2026-11-17): Implementation Quest §7, [§11.4~11.6](../features/weekly-ranking/verification.md#114-실제-연결-한-건--결제-주문-ab)의 WeeklyRankingFlowTest, batch 자식 JVM 진입점·실패 설정·Gradle classpath, [§9.11](../features/weekly-ranking/input-storage.md#911-조회저장-sql에-맞춘-인덱스-구성)의 streamer `idx_ppa_confirmed_at`.
- 5단위 정리: WeeklyRankingApiTest의 limit 테스트 데이터 조립을 반복문으로 바꿨다(기대값 동일, 16:58 15건 통과).

### 구현

|위치|내용|
|---|---|
|streamer `ProductPurchaseActivityModel`|`@Index(name = "idx_ppa_confirmed_at", columnList = "confirmed_at")`. 새로 만드는 DB에만 반영되며 기존 DB에는 별도 적용이 필요하다|
|streamer `PurchaseActivitySchemaIntegrationTest`|인덱스 목록 단언에 승인된 `idx_ppa_confirmed_at` 한 항목 추가. 기존 unique·CHECK 기대값은 그대로|
|batch test `process/WeeklyRankingTestProcessMain`|`CommerceBatchApplication.createApplication(WeeklyRankingFaultSupport.class)` → `SpringApplication.exit` → `System.exit`|
|batch test `process/WeeklyRankingFaultSupport`|`test.weekly-ranking.fail-before-product-id`가 있을 때만 arm(식별 parameter 아님). 위임 상품·실패 지점을 stdout에 남김|
|api `build.gradle.kts`|`prepareWeeklyRankingBatchChildClasses`(Sync: batch testClasses의 `com/loopers/job/ranking/process/**`만 복사), test 작업에 batch `sourceSets.main.runtimeClasspath`+복사 디렉터리를 입력·`-Dweekly-ranking.batch-child.classpath`로 전달. API classpath에는 넣지 않음|
|api test `WeeklyRankingBatchChild`|batch 자식 한 번 실행(준비/new/restart). test 프로필·부모 DB/Redis 접속값 명시, 비밀번호는 환경 변수, 출력은 파일. 결과 로그에서 instance·execution·상태, 처리 상품 ID를 읽음|
|api test `WeeklyRankingFlowTest`|아래 흐름. 기존 W7의 `OrderActivityStreamerChild`·`OrderOutboxE2eLifecycleSupport`(소유권·정리)를 재사용|

### 실행 결과

코드 상태: HEAD `3c0d040`(5단위 커밋) + 6단위 미커밋 변경. JDK 21.0.12, 실제 MySQL 8.0·Redis testcontainers, 로컬 Docker Kafka(전용 topic·group), PgFixtureServer.

|검사|명령·시각|결과|
|---|---|---|
|실제 연결 단독|`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:test --tests '*WeeklyRankingFlowTest' --rerun-tasks` 17:05:59|1건 통과|
|streamer 전체|`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-streamer:checkstyleMain :apps:commerce-streamer:checkstyleTest :apps:commerce-streamer:test --rerun-tasks` 17:01:15|16클래스 88건 통과, Checkstyle 오류 0|
|관련 회귀|`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-batch:check :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*WeeklyRanking*' --tests '*ArchitectureTest' --tests '*OrderOutboxDeliveryEndToEndIntegrationTest' --tests '*OrderEventEndToEndIntegrationTest' :apps:commerce-api:w6OutboxRecoveryTest --rerun-tasks` 17:07:04~17:09:23|batch 17건, API: WeeklyRankingFlowTest 1·WeeklyRankingApiTest 15·ArchitectureTest 3·W7 실제 전달 1, O11 1건 통과. Checkstyle api/batch 오류 0. `OrderEventEndToEndIntegrationTest`는 독립 @Test가 없는 지원 코드로 O11 안에서 실행됨|

API·batch 전체 회귀는 실행하지 않았다.

### 관찰 (17:05:59 실행)

|단계|관찰|
|---|---|
|준비|batch 준비 child(`job.name=NONE`, update/always) 종료 코드 0, weeklyRanking instance 0개. 2026-10-19 API는 NOT_PUBLISHED|
|주문 A|상품 a=1·b=2·c=3, 실제 PG 확정(확정 Clock 2026-10-19T00:00Z) → relay PUBLISHED → streamer APPLIED, 원본 3행. 기간 끝 sentinel(주문 900000001, a 수량 99, 2026-10-26T00:00)은 별도 source 행|
|r1|instance 1 / execution 1 COMPLETED, 결과 `1:2:1, 3:2:2, 2:1:3`(a2·c2·b1), API 같은 항목·snapshotId r1|
|r2 실패|instance 2 / execution 2, 종료 코드 1·FAILED. 처리 a·b 뒤 c 직전 실패, READY·입력 3행·입력 checksum = r1, stage a2·b1, 영속 read.count 2, API는 r1 유지|
|A 재발행|저장된 key·payload를 별도 producer로 같은 topic에 보냄(partition 1 offset 1) → streamer ALREADY_PROCESSED, A 원본 3행 그대로|
|늦은 구매 B|a 수량 3, 2026-10-20T03:00Z 확정 → APPLIED. 주 원본 4행(sentinel 제외). r2 manifest·stage 불변|
|r2 재시작|새 JVM에서 execution 2 restart → 같은 instance 2의 execution 3 COMPLETED(종료 코드 0), 처리 c만. 결과·입력/결과 checksum이 r1과 같고 API는 r2·같은 항목|
|r3|instance 3 / execution 4 COMPLETED, 입력 4행, 결과 a5·c2·b1, API r3. r1·r2 결과 보존|
|삭제·limit|c 삭제 뒤 limit=2 → `1:5:1, 2:1:3`(rank 유지)|
|빈 주|2026-10-12 NOT_PUBLISHED → new(execution 5) → PUBLISHED·`weekly-20261012-r1`·빈 items|
|정리|streamer 자식 SIGTERM 정상 종료, W7 정리 `kept=[]`. 구매 테이블은 W7 규칙대로 삭제, 랭킹 다섯 테이블·BATCH_*는 보존(컨테이너 수명). batch 자식은 매번 종료 확인. 종료 뒤 테스트 컨테이너 0개|

4단위에서 남긴 "Boot가 Runner를 ApplicationRunner로 실행하고 종료 코드를 내는 경로"는 위 batch 자식의 종료 코드 0/1로 확인했다.

### 한계

- (검토 보완으로 대체) 이 실행에서는 부모가 영속 read.count를 BATCH_STEP_EXECUTION_CONTEXT의 기본 직렬화를 직접 풀어 확인했다. 보완 뒤에는 batch 자식이 JobExplorer로 다시 읽은 값을 출력하고 부모가 그 출력을 확인한다(아래 검토 보완 절).
- A 재발행은 Outbox relay가 아니라 테스트 producer로 같은 key·payload를 보냈다. relay 중단·재발행 자체는 기존 W7 테스트 범위다.
- 기존 운영 DB에 `idx_ppa_confirmed_at`을 적용하는 절차는 만들지 않았다(§9.11).

## 전체 회귀 (2026-11-17)

코드 상태: HEAD `3c0d040` + 6단위 staged 변경(1~5단위는 커밋됨). JDK 21.0.12, 실제 MySQL·Redis testcontainers, 로컬 Docker Kafka.

`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" check --rerun-tasks --no-build-cache --continue`, 17:13:04~17:24:24, `BUILD SUCCESSFUL`(53개 작업 모두 실행).

|모듈|테스트|Checkstyle|
|---|---|---|
|commerce-api `test`|284개 결과 파일, 1,402건 중 1,394건 통과·failure 0·error 0·skip 8|main/test 오류 0|
|commerce-api `w6OutboxRecoveryTest`(O11)|1건 통과|-|
|commerce-streamer|16개 결과 파일, 88건 통과|main/test 오류 0|
|commerce-batch|5개 결과 파일, 17건 통과|main/test 오류 0|
|pg-simulator|9개 결과 파일, 79건 통과|main/test 오류 0|

skip 8건은 기존 W8 쿠폰의 로컬 Redis 장애 opt-in 테스트(`CouponLocalRedisFaultIntegrationTest`의 Q9·Q10, `CouponCacheLocalRedisFaultIntegrationTest`의 R4·R5 6건)로, 명시 실행 조건이 없으면 실행하지 않는 기존 설계다. 이번 통과로 세지 않는다. ArchUnit은 commerce-api·commerce-streamer의 기존 ArchitectureTest가 위 실행에 포함됐고 batch에는 없다. 종료 뒤 테스트 컨테이너 0개.

## Codex 종합 검토 보완 (2026-11-17)

Codex의 읽기 전용 종합 검토(HEAD `60b0e96` 기준) 지적 중 사용자가 승인한 범위를 반영했다. 기준 설계 문서는 바꾸지 않았다.

### 변경

|지적|반영|
|---|---|
|batch 자식 timeout·interrupt 때 종료 미확인, 정리 단계가 batch 자식을 모름|`WeeklyRankingBatchChild`가 띄운 프로세스를 모두 기록. timeout 메시지에 강제 종료 확인 여부 포함. Flow 정리의 `stopChild`가 남은 batch 자식을 먼저 강제 종료·대기하고, `childExited`는 streamer와 모든 batch 자식이 끝났을 때만 참이어서 W7 정리 규칙대로 API 데이터·구매 테이블·Kafka 정리를 막는다|
|snapshot 최대 revision 0 이하를 없음으로 보정|Runner가 저장값 0 이하이면 `CORRUPT_HISTORY`로 실행 등록 전에 거절(§4.8). `WeeklyRankingLaunchTest.nonPositiveStoredRevisionIsRejected`(2026-06-22) 추가|
|실제 연결의 소비 완료 증거 부족|relay probe로 받은 broker 위치(재발행은 producer의 RecordMetadata)에 대응하는 `ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic/partition/offset/eventId/result` 로그와 group committed offset ≥ 위치+1을 기다림|
|준비 child 확인이 전체 instance 0개|준비 전후 weeklyRanking instance 수 불변과 랭킹 다섯·Batch 핵심 테이블 존재로 확인|
|`queryForObject(..., LocalDateTime.class)`의 Timestamp fallback(§9.12)|`readDatabaseUtcNow`가 RowMapper에서 `rs.getObject(1, LocalDateTime.class)`를 직접 호출|
|최종 결과 로그에 공개 outcome 없음(§4.9)|이번 실행의 공개 Step context가 대상 snapshot과 맞을 때만 `publicationOutcome`을 결과·로그에 남김. 공개 Step을 실행하지 않으면 `null`|
|부모가 Batch 직렬화를 직접 해석(§5.7)|batch 자식 main이 종료 전 JobExplorer로 이번 실행의 계산 Step 영속 context를 다시 읽어 `WEEKLY_RANKING_CHECKPOINT … readCount=` 출력, 부모는 이를 확인. 직렬화 해석 코드 삭제|
|선택: 재공개 시각 불변|2026-09-28 시나리오가 snapshot 완료·수정 시각, 결과 행 수정 시각, pointer 공개·수정 시각 불변까지 단언|
|선택: UNKNOWN 영속 상태|2026-10-05 시나리오가 영속 Job `UNKNOWN`, 공개 Step `STARTED`를 단언|
|선택: 테스트 숫자 변환|Flow의 점수 읽기를 `toBigIntegerExact`로 변경|
|기록 오류|2단위 batch 결과 7건 → 6건 정정|

### 기존 DB의 `idx_ppa_confirmed_at` 적용 안내(§9.11)

새로 만드는 DB는 streamer 매핑으로 생긴다. 이미 있는 DB에는 운영자가 소유 DB에서 직접 확인·적용한다. 이번에 어떤 DB에도 적용하지 않았다.

```sql
-- 1) 존재 확인. 다음 중 하나면 적용하지 않는다.
--    a) idx_ppa_confirmed_at 이 NON_UNIQUE=1, 열 confirmed_at 하나로 이미 있음 → 이미 적용됨
--    b) 같은 이름이지만 유일성·열이 다름 → 멈추고 원인 확인
--    c) 다른 이름의 인덱스가 confirmed_at 하나로 이미 있음 → 중복 추가하지 말고 멈추고 확인
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_purchase_activity'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- 2) 없을 때만 추가. 기존 행·다른 인덱스를 지우거나 테이블을 다시 만들지 않는다.
ALTER TABLE product_purchase_activity
    ADD INDEX idx_ppa_confirmed_at (confirmed_at), ALGORITHM = INPLACE, LOCK = NONE;
```

MySQL 8.0 Reference Manual의 Online DDL Operations(Secondary Indexes)는 보조 인덱스 추가를 INPLACE·동시 DML 허용으로 설명한다. `LOCK=NONE`이어도 시작·종료 단계의 metadata lock은 잡으므로, 오래 열린 트랜잭션이 있으면 DDL과 뒤이은 쿼리가 대기할 수 있다. 실제 소요 시간·잠금 영향은 데이터 크기에 따라 다르며 측정하지 않았다. 지원하지 않는 환경이면 위 문장은 오류로 끝나므로 다른 방식으로 바꾸지 말고 확인한다.

### 실행 결과

코드 상태: HEAD `60b0e96` + 검토 보완 미커밋 변경. JDK 21.0.12.

|검사|명령·시각|결과|
|---|---|---|
|batch check|`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-batch:check --rerun-tasks` 18:31:03|5클래스 18건 통과(WeeklyRankingLaunchTest 5), Checkstyle 오류 0|
|API 관련|`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*WeeklyRanking*' --tests '*ArchitectureTest' --rerun-tasks` 18:31:28|WeeklyRankingFlowTest 1, WeeklyRankingApiTest 15, ArchitectureTest 3 통과, Checkstyle 오류 0|

실제 연결 관찰(18:31 실행): Kafka 처리·commit `A APPLIED@partition1:offset0`, `A 재발행 ALREADY_PROCESSED@1:1`, `B APPLIED@0:0`, 종료 시 committed offset partition1=2·partition0=1. r2 실패 실행의 JobExplorer read.count 2. r1·restart·r3·빈 주의 publicationOutcome은 PUBLISHED, 실패 실행은 null. W7 정리 `kept=[]`.

검토 보완 뒤 전체 회귀(`check`)는 사용자 결정으로 다시 실행하지 않았다. 18:3x에 시작한 실행은 중간에 중단했으며 그 결과를 통과로 쓰지 않는다. 중단 뒤 Gradle test worker·batch/streamer 자식 프로세스·테스트 컨테이너가 남지 않은 것을 확인했다. 직전 전체 회귀(17:13~17:24)는 보완 전 코드의 결과다.

## Codex 재검토 2차 보완 (2026-11-17)

Codex 재검토(보완 8개 파일 staged 상태)의 남은 지적 중 사용자가 승인한 범위를 반영했다. 기준 설계 문서는 바꾸지 않았다.

|지적|반영|
|---|---|
|준비 child 확인이 테이블 존재만 봄(§11.5)|API 테스트의 `WeeklyRankingSchemaSupport.assertDefinitions`(열 타입·null·ASCII collation, 명명 인덱스·열 순서·유일성, CHECK/FK 없음, InnoDB)를 공개해, 실제 연결 테스트가 batch 준비 child가 만든 다섯 랭킹 테이블에도 같은 기준을 적용. Batch metadata는 JOB_INSTANCE·JOB_EXECUTION·STEP_EXECUTION·STEP_EXECUTION_CONTEXT 존재 확인|
|공개 outcome이 이번 execution만 봄(§4.9)|같은 instance의 모든 execution에서 마지막 공개 Step(StepExecution ID 최대)의 context를 읽고, 대상 snapshot이 맞을 때만 outcome을 씀. 저장된 값이 없으면 null. `WeeklyRankingLaunchTest`에 실패 실행 null·재시작 PUBLISHED 단언 추가|
|자식 main의 관찰 실패 시 context 미종료(선택)|checkpoint 관찰이 예외로 끝나면 `SpringApplication.exit`로 context를 닫고 예외를 다시 던짐(부모는 0이 아닌 종료 코드를 봄)|
|인덱스 적용 전 판정 문구|`NON_UNIQUE=1`·열 일치, 같은 이름의 다른 정의, 다른 이름의 같은 열 인덱스를 구분해 적용하지 않을 조건을 명시. `LOCK=NONE`도 metadata lock은 잡는다는 한계 추가|
|HANDOFF의 2단위 7건|6건으로 정정(로컬 운영 문서)|

|검사|명령·시각|결과|
|---|---|---|
|batch·API 관련|`./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-batch:check :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*WeeklyRanking*' --tests '*ArchitectureTest' --rerun-tasks` 20:26:47~20:27:56|batch 5클래스 18건(WeeklyRankingLaunchTest 5), API: WeeklyRankingFlowTest 1·WeeklyRankingApiTest 15·ArchitectureTest 3 통과, failure/error/skip 0, Checkstyle api/batch 오류 0|

실제 연결 관찰: Kafka `A APPLIED@1:0`, `A 재발행 ALREADY_PROCESSED@1:1`, `B APPLIED@0:0`, r2 실패 JobExplorer read.count 2, W7 정리 `kept=[]`, 준비 직후 랭킹 다섯 테이블 정의 확인 통과. 종료 뒤 테스트 컨테이너 0개.

보완 뒤 전체 회귀는 실행하지 않았다(사용자 결정). batch 자식의 timeout·interrupt 정리 분기는 코드로 확인했고 강제 상황을 만들어 실행하지는 않았다.
