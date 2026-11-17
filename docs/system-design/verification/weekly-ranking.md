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
|batch check(정리 후 최종 코드)|`./gradlew :apps:commerce-batch:check --rerun-tasks` 15:58:05|checkstyleMain 28파일·checkstyleTest 5파일 오류 0. 테스트 3클래스 7건 통과(CommerceBatchApplicationTest 1, DemoJobE2ETest 2, RankingJobRestartTest 3), failure/error/skip 0|

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
