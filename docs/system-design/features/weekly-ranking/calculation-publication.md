# 상품별 계산·완성본 공개

이 문서는 [승인된 주간 랭킹 설계](../weekly-ranking.md)의 상세 문서다. 승인 상태와 전체 읽기 순서는 진입 문서를 따른다. 기존 절 번호는 유지하며 문서 사이에 분산된 절은 [절 번호 안내](../weekly-ranking.md#절-번호-안내)로 찾는다.

## 문서 간 읽기 경로

- [입력 고정·저장 구조·checksum](input-storage.md)
- [Job 실행·식별·재시작·복구](execution-recovery.md)
- [랭킹 API 계약](api-contract.md)
- [원문 대응·검증 구성](verification.md)

## 이 문서의 기준 절

[§5](#5-상품별-점수-계산) · [§5.1](#51-chunk-크기와-실패-경계) · [§5.2](#52-점수의-숫자-타입과-db-정밀도) · [§5.3](#53-상품-id의-cursor-reader) · [§5.4](#54-db-트랜잭션-연결--기존-구성로컬-소스-대조) · [§6](#6-완성본과-공개-pointer) · [§8](#8-입력-고정계산의-처리-계약) · [§8.3](#83-상품별-계산과-실패-분기) · [§8.4](#84-stepreader-상태와-자원-수명) · [§8.5](#85-상품-합계와-절대-점수-저장-sql) · [§8.6](#86-클래스-배치포트와-호출-순서) · [§8.7](#87-이번-상세화의-검토-기준)

## 5. 상품별 점수 계산

사용자는 원문 §4의 기본 방식인 상품별 합계·절대값 저장을 우선 채택했다. 입력 고정 뒤 상품별 계산을 수행하며, 현재 구매 활동 원본을 다시 읽어 계산하지 않는다.

|역할|합의한 처리|
|---|---|
|Reader|해당 snapshot 입력의 서로 다른 productId를 오름차순으로 읽는다. 상품 하나가 item 하나다.|
|Processor|같은 snapshot·productId의 quantity 합을 계산한다. 상품 하나의 합계 SQL에는 GROUP BY가 필요하지 않다([§8.5](#85-상품-합계와-절대-점수-저장-sql)).|
|Writer|계산 중 점수 영역에 snapshotId·productId별 절대 점수를 upsert한다. 이 조합은 유일하다. 기존 값에 합계를 더하지 않는다.|

```text
snapshot 입력: (42,7,2), (42,8,1), (43,7,3), (44,9,5)
Reader: 7 → 8 → 9
Processor: 상품 7은 2+3=5, 상품 8은 1, 상품 9는 5
Writer: 상품 7을 재처리해도 5 → 5로 저장; 5 → 10으로 증가시키지 않음
```

계산 중 점수는 frozen 입력과 구분해 별도 점수 stage 테이블에 저장하고 고객 API에서 직접 읽지 않는다. 완성 ranking과의 분리는 §6을 따른다. 테이블/매핑은 §9, 상태 의미는 [§3.4](input-storage.md#34-snapshot-상태)를 따른다.

이 방식은 고정 입력과 같은 계산식에서 한 상품의 전체 합을 다시 써도 같은 값으로 수렴한다. 원문이 허용한 활동별 증분 합산은 적용 기록과 점수 증가의 원자성이 추가로 필요하므로 이번 선택에서는 채택하지 않는다. 전체 상품을 한 번에 GROUP BY로 집계하는 방식은 조회 횟수를 줄일 여지가 있지만 상품별 item·Processor·재시작 구조에 연결하는 검토가 필요하다. 현재 선택에는 상품별 합계 쿼리 횟수 비용이 있으며 성능 우열은 측정하지 않았다.

절대값 저장은 누락 상품까지 복구하지 않는다. Reader 순서/영속 처리 위치는 [§8.4](#84-stepreader-상태와-자원-수명), 결과와 checkpoint의 실제 TX 연결 설계는 [§5.4](#54-db-트랜잭션-연결--기존-구성로컬-소스-대조)를 따른다. 점수의 Java 타입은 [§5.2](#52-점수의-숫자-타입과-db-정밀도)의 BigInteger 합의를 따른다. Reader 구현체는 [§5.3](#53-상품-id의-cursor-reader)의 JdbcCursorItemReader 선택을 따른다. SUM 변환·인덱스/SQL·Reader 상태·메서드/실패 처리는 §8~§9, 같은 TX 연결은 [§5.4](#54-db-트랜잭션-연결--기존-구성로컬-소스-대조)를 따른다. 실제 연결 검증은 남아 있다.

### 5.1 Chunk 크기와 실패 경계

사용자는 원문 예시와 같은 초기 chunk 크기 2를 선택했다. item은 구매 활동 한 행이 아니라 상품 하나이므로 상품 두 개의 계산 결과를 한 묶음으로 저장한다. 검증하기 쉬운 초기값이며 최적 처리량을 측정해 정한 값은 아니다. 큰 데이터에서는 commit 횟수와 재처리량 등의 비용을 확인한 뒤 변경을 검토한다.

원문 요구대로 점수와 영속 Reader 처리 위치(checkpoint)를 같은 실제 DB commit으로 묶는다. 다음은 동작 계약이며 Reader 상태 key는 [§8.4](#84-stepreader-상태와-자원-수명), TX manager/connection 참여 설계는 [§5.4](#54-db-트랜잭션-연결--기존-구성로컬-소스-대조)를 따른다. 실제 연결 검증은 별도다.

```text
상품 7·8: 점수 5·1과 두 상품을 처리한 위치를 함께 commit
상품 9 직전 실패: 마지막 commit의 점수·위치는 보존, 새 완성본은 공개하지 않음
같은 식별값으로 재시작: 같은 JobInstance의 새 JobExecution에서 상품 9부터 처리

첫 묶음 commit 전 확정 rollback: 상품 7·8 점수와 처리 위치 모두 반영하지 않음
재시작: 상품 7부터 해당 묶음을 다시 처리
```

DB commit 결과가 불명확한 오류는 확정 rollback과 구분한다. 이 경우의 확인/재시작 가능 조건과 중단 절차는 [§5.6~5.8](execution-recovery.md#56-재시작-전-확인-순서와-지원-범위)을 따른다. 실행 사이 입력·stage·Batch metadata를 초기화하거나 새 run.id로 다른 작업을 만들지 않는다. 완료한 입력 고정 Step을 재시작 때 다시 수행하지 않는다.

### 5.2 점수의 숫자 타입과 DB 정밀도

사용자는 상품별 구매 수량 합계인 score의 Java 타입을 BigInteger로 선택했다. 기존 원본 활동·복사 입력의 개별 quantity는 양수 long이라는 원본 계약을 유지한다. 이 선택을 quantity·식별자·revision·rank의 타입 변경으로 확대하지 않는다.

대안·선택 이유·비용: long·BIGINT를 유지하고 합계가 범위를 넘으면 실패시키는 안도 가능하다. 사용자는 합계가 long 범위를 넘는 경우까지 더 큰 정수로 처리하는 안을 선택했다. 소수점 없는 수량 합계이므로 계산 값은 BigDecimal보다 BigInteger로 표현한다. 비용은 DB의 정확한 정수 저장 자릿수·집계 결과의 손실 없는 변환·API 표현을 함께 정해야 하는 것이다. 성능 개선을 주장하는 선택은 아니다.

공식 규칙 확인: MySQL 8.0 Reference Manual의 Fixed-Point Types (Exact Value) - DECIMAL, NUMERIC 및 DECIMAL Data Type Characteristics에 따르면 DECIMAL은 정확한 고정소수점 타입이며 정밀도는 최대65자리다. DECIMAL(p, 0)처럼 소수 자릿수를0으로 지정하면 정수 값을 저장할 수 있다. DECIMAL이라는 SQL 이름이 Java 도메인 값까지 BigDecimal로 정해야 한다는 뜻은 아니다. 이 확인은 공식 문서 확인이며 현재 DB DDL·JDBC·ORM 왕복 실행 검증이 아니다.

후속 사용자 동의로 stage와 완성 ranking의 score는 DECIMAL(65, 0)을 사용하기로 했다. 사용자는 별도 범위 초과 처리가 필요한지, DB에서 자동 실패하는지 질문했다. 별도 애플리케이션 범위 검사나 복구 기능을 승인한 것으로 해석하지 않는다. BigInteger도 DB에 저장할 때는 선택한 정밀도 한계를 따른다. SUM 결과는 [§8.5](#85-상품-합계와-절대-점수-저장-sql)의 JDBC BigDecimal→BigInteger 정확 변환을 사용하며 long/double을 거치지 않는다. API에서는 [§7.5](api-contract.md#75-score의-json-표현) 합의에 따라 score를 항상 문자열로 반환한다.

공식 규칙 추가 확인: MySQL 8.0 Reference Manual의 Out-of-Range and Overflow Handling은 컬럼 범위 초과에 strict SQL mode가 켜져 있으면 오류로 insert가 실패하고, 꺼져 있으면 경계값으로 보정하고 warning을 남길 수 있음을 설명한다. Server SQL Modes의 MySQL 8.0 기본값에는 STRICT_TRANS_TABLES가 포함되지만 현재 실행 연결의 유효 설정까지 확인한 것은 아니다. 저장소의 기존 PgPaymentMigrationIntegrationTest에는 세션 strict mode를 확인하는 테스트와 별도 연결에서 일시 해제·복원하는 준비 코드가 있다. 이번에는 읽기만 했고 실행하지 않았다.

구현 검토 방향은 오류를 삼키거나 skip하지 않고 Batch 실패로 전파하는 기존 원칙을 유지하는 것이다. “초과 시 실패”라는 동작 계약과 별도 수작업 범위 검사 구현은 구분한다. 현재 설계는 별도 수작업 자릿수 검사 없이 DB strict mode의 오류와 정확 변환에 맡긴다. 최초 DB 검증에서 유효 strict mode·집계/변환/저장 경로를 확인하며, 보장 전제가 다르면 통과로 처리하지 않고 환경/설계 수정 필요성을 사용자에게 보고한다. 자동 보정·반올림으로 잘못된 점수를 정상 결과로 사용하는 것은 원문의 정확한 합계 요구를 충족하지 않는다. 별도 범위 초과 복구·재시도 기능은 추가하지 않는다.

### 5.3 상품 ID의 Cursor Reader

사용자는 상품 ID Reader로 Spring Batch의 JdbcCursorItemReader를 선택했다. 원문 §3의 작은 DB 재시작 연결 예시와 같은 구현체를 사용하되, 그 예시가 제품 Reader 구현체를 강제한 것으로 해석하지 않는다.

```sql
SELECT DISTINCT product_id
FROM ranking_snapshot_input
WHERE snapshot_id = ?
ORDER BY product_id
```

위 SQL은 현재 제안 테이블명 기준이다. 이번 실행의 snapshotId를 바인딩하고, 서로 다른 productId를 안정된 오름차순으로 읽는다. saveState를 사용하며 실행별 Reader 객체가 달라져도 같은 논리 이름·동일 입력·영속 checkpoint로 재시작한다. bean 이름·scope·상태 key·connection 설정은 [§8.4](#84-stepreader-상태와-자원-수명)를 따른다. 메모리 위치나 로그만으로 영속 처리를 증명하지 않는다.

대안·선택 이유·비용: paging은 여러 쿼리의 페이지 경계·정렬·checkpoint를 함께 관리해야 한다. 사용자는 고정된 입력을 순서대로 읽으며 원문의 작은 연결 예시를 활용할 수 있는 cursor 방식을 선택했다. 비용은 읽는 동안 DB 연결/결과 집합을 유지한다는 점이다. 드라이버의 fetch 설정과 결과 버퍼링에 따라 메모리 사용이 달라질 수 있으므로 cursor 선택만으로 낮은 메모리나 빠른 재시작을 보장하지 않는다. 전체 상품 ID를 애플리케이션 목록으로 미리 복사하는 구현으로 바꾸지 않는다.

Reader용 읽기 연결과 chunk의 점수/checkpoint 쓰기 TX는 책임이 다르다. 실제 TX manager·DataSource·JobRepository·writer의 연결 참여 설계는 [§5.4](#54-db-트랜잭션-연결--기존-구성로컬-소스-대조)이고 실행 검증은 [§11.3](verification.md#113-미리-준비할-fixture와-batch-검증의-책임)을 따른다. cursor 자체가 결과/checkpoint 원자성을 보장하는 것은 아니다. 자원 종료·실패·재개에서 실제 reader 위치와 최초 재처리 상품을 확인하는 기준은 유지하며 테스트는 아직 실행하지 않았다.

### 5.4 DB 트랜잭션 연결 — 기존 구성·로컬 소스 대조

이 절은 사용자가 요청한 같은 commit 경계의 상세화다. 새로운 DataSource/트랜잭션 관리자나 제품 기능을 추가하지 않고 기존 Boot 자동 구성의 단일 관리자를 사용한다. 런타임 bean·실제 DB connection 참여는 아직 실행 검증하지 않았다.

#### 기존 설정과 근거 버전

- gradle.properties의 Boot 선언은3.4.4다. 로컬에 있는 Boot3.4.4 BOM POM은 Batch5.2.2·Framework6.2.5를 지정한다. 이 버전의 로컬 sources.jar를 읽었다. 현재 runtimeClasspath를 새로 해석해 확인한 결과는 아니며 dependencyInsight는 실행하지 않았다.
- modules/jpa의 DataSourceConfig는 primary bean `mySqlMainDataSource` 하나를 생성한다. batch는 같은 모듈의 JPA·DataSource 설정을 가져오며 별도 업무용/Batch용 DataSource·트랜잭션 관리자 선언은 현재 main 소스에 없다.
- Boot3.4.4 JpaBaseConfiguration.transactionManager는 별도 TransactionManager가 없으면 JpaTransactionManager를 구성한다. BatchAutoConfiguration.SpringBootBatchConfiguration은 별도 BatchDataSource/BatchTransactionManager 지정이 없으면 주입된 DataSource·PlatformTransactionManager를 사용한다. DefaultBatchConfiguration.jobRepository는 이 둘로 JDBC JobRepository를 만든다.
- Framework6.2.5 JpaTransactionManager.doBegin은 HibernateJpaDialect가 제공하는 JPA JDBC 연결을 같은 DataSource key의 thread-bound ConnectionHolder로 노출한다. JdbcTemplate은 DataSourceUtils.getConnection으로 그 연결을 사용한다. 따라서 동일 DataSource와 기존 JpaTransactionManager 아래 JPA/JDBC 참여가 가능하다. 이는 기술 구성의 근거이며 이름이나 같은 JDBC URL만으로 참여를 증명한 것은 아니다.

#### 사용할 bean과 호출 경계

|대상|설계할 연결|
|---|---|
|JobRepository/JobExplorer|Boot의 영속 JDBC 기본 구성 유지. mySqlMainDataSource와 기존 transactionManager 사용|
|세 Step|기존 PlatformTransactionManager bean을 명시 주입해 builder에 전달|
|점수 Processor/Writer의 JDBC 접근|같은 mySqlMainDataSource로 생성한 JdbcTemplate 사용. infrastructure의 저장 구현이 SQL을 담당|
|커서 Reader|같은 DataSource를 주입하되 기본 별도 읽기 연결 사용. useSharedExtendedConnection을 추가하지 않음|
|실행 진입|JobLauncher 호출 전체를 상위 @Transactional/TransactionTemplate로 감싸지 않음|

별도 @EnableBatchProcessing, DefaultBatchConfiguration 상속, 메모리 JobRepository, Batch 전용 DataSource/관리자, ResourcelessTransactionManager를 새 job에 추가하지 않는다. 기존 Demo 자체는 이번 설계 때문에 수정하지 않는다. JdbcTemplate 저장을 위해 별도 DataSourceTransactionManager를 하나 더 추가하면 기존 JPA 관리자와 경계가 갈라질 수 있으므로 사용하지 않는다. raw dataSource.getConnection을 writer에서 직접 얻어 독립 commit하는 구현도 사용하지 않는다.

대안은 JDBC 전용 관리자를 명시하고 JPA/Batch 자동 조립을 재구성하는 방식이다. JDBC 중심 앱에는 선택할 수 있지만 현재 starter의 기존 단일 관리자와 원문 작은 연결 예시를 유지하는 목적에는 추가 조립·회귀 비용이 생긴다. 기존 관리자의 JDBC 참여 지원을 사용하고 실제 참여를 검증하는 방향을 택한다.

```text
입력 고정 tasklet: tasklet(freezeTasklet, 기존 transactionManager)
  tasklet 한 번의 TX 안에서 정보·입력·manifest·READY 저장
  정상 완료 때 FINISHED 반환
  격리 수준은 기존 기본 REPEATABLE READ 유지; 정확한 읽기 SQL은 상세화

점수 계산 step: chunk(2, 기존 transactionManager)
  동일 실행 thread에서 상품 두 개 읽기·계산
  JdbcTemplate writer가 점수 upsert
  Batch가 Reader의 ExecutionContext 갱신
  JobRepository가 ExecutionContext·StepExecution 저장
  하나의 실제 DB TX commit
  commit 전 확정 실패: 이 chunk의 점수·checkpoint rollback

완성/공개 tasklet: tasklet(publishTasklet, 기존 transactionManager)
  tasklet 한 번의 TX 안에서 완성 확인·순위·완성 결과·COMPLETED 저장
  같은 TX에서 최신 revision 판단·pointer 변경 또는 공개 생략
  정상 완료 때 FINISHED 반환
```

Step을 다시 감싸는 REQUIRES_NEW 저장 서비스·afterCommit 점수 저장·@Async/병렬 chunk를 도입하지 않는다. tasklet/processor/writer가 하위 저장을 호출할 때 실제 Step TX에 참여하게 한다. 서비스 프록시·전파 방식/클래스 배치는 [§6.8](#68-클래스메서드tx-처리-순서)·[§8.6](#86-클래스-배치포트와-호출-순서)을 따른다. 기존 입력을 재사용하는 경로에서도 원본 재복사나 저장 위치 초기화를 하지 않는다.

#### Checkpoint와 최종 실행 상태의 차이

Batch5.2.2 TaskletStep.doExecute/ChunkTransactionCallback은 TransactionTemplate 안에서 tasklet 처리 뒤 stream.update, JobRepository.updateExecutionContext, update를 호출한다. AbstractJobRepositoryFactoryBean의 기본 속성은 일반 update에 REQUIRED를 사용한다. 같은 관리자·연결 참여 조건 아래 이 갱신이 chunk 점수와 같은 commit에 묶인다. create 계열 metadata는 별도 REQUIRES_NEW·create용 격리를 사용하므로 모든 Batch metadata가 전체 job 한 TX라는 뜻은 아니다.

AbstractStep.execute는 Step 처리 이후 최종 ExecutionContext·StepExecution도 저장한다. 이 마지막 기록은 앞선 업무 commit과 구분해야 한다. 공개가 commit된 뒤 최종 metadata 갱신만 실패하면 COMPLETED snapshot/pointer가 존재할 수 있다. 이때 공개 결과를 삭제하거나 pointer를 되돌리지 않는다.

로컬 소스에서는 최종 metadata 저장 실패 시 BatchStatus.UNKNOWN으로 바꾸고 재시작 주의 로그를 남기는 분기도 확인했다. 따라서 단순 실패 후 같은 parameter를 다시 실행하는 정상 재시작과 UNKNOWN·commit 결과 불명 복구를 동일하게 취급하지 않는다. 자동 metadata 수정·강제 재시작·새 run.id 우회는 추가하지 않는다. 운영자 확인 대상·지원 재시작/중단 절차는 [§5.6~5.8](execution-recovery.md#56-재시작-전-확인-순서와-지원-범위)을 따른다. 통제 검증 구성은 [§11.3](verification.md#113-미리-준비할-fixture와-batch-검증의-책임)·[§11.5](verification.md#115-앱-간-실행-연결과-테스트-전용-실패-주입)를 따른다.

#### 후속 실행 검증에 필요한 근거

현재 bean 종류는 설정과 소스에서 예상한 것이다. 후속 승인된 구현/검증에서 실제 transactionManager가 JpaTransactionManager이고 해당 EntityManagerFactory·JDBC·JobRepository가 같은 DataSource를 쓰는지 확인한다. Step TX의 thread-bound JDBC 연결과 writer·metadata 쓰기의 참여를 확인하고, commit 전 실패에서 점수/checkpoint의 동시 rollback·commit 뒤 실패에서 이전 chunk 보존을 새 DB 조회와 영속 JobExplorer로 대조한다. 커서 읽기 연결이 별도라는 이유만으로 쓰기 TX 원자성이 깨졌다고 판단하지 않는다.

기존 test/local의 JPA create와 Batch schema always는 최초 준비와 데이터 보존 재시작을 구분해야 한다. 별도 프로세스 재기동 구성과 초기화 옵션은 검증 자원 설계에서 정하며, 실행 사이 schema/metadata/stage를 초기화하지 않는 원문 계약을 유지한다. 이번에는 코드 수정·의존성 해석·앱/DB 기동·테스트를 수행하지 않았다.

## 6. 완성본과 공개 pointer

사용자는 계산 중 점수, 순위를 갖춘 완성 ranking, 기간별 공개 pointer를 구분하는 구조를 선택했다.

|저장 영역|역할|
|---|---|
|점수 stage 테이블|snapshot·상품별 계산 중 절대 점수. API는 읽지 않음|
|완성 ranking 테이블|snapshot·상품별 점수와 저장된 계산 순위|
|공개 pointer 테이블|기간별 현재 공개 snapshotId 하나|

테이블명·열·타입·PK/제약은 [§9.1~9.10](input-storage.md#91-다섯-테이블과-공통-열)을 따른다. 사용자 후속 합의로 stage·완성 결과·공개 pointer와 snapshot 정보 사이에도 물리 FK를 두지 않는다. 적용 범위와 한계는 [§3.3](input-storage.md#33-물리-fk를-두지-않기)을 따른다.

```text
기간의 공개 pointer는 revision 1을 가리킴
revision 2 계산 중: stage가 일부만 있어도 API는 revision 1을 반환
revision 2 계산 완료 뒤, 한 공개 DB 트랜잭션:
  모든 상품의 점수가 준비됐는지 확인
  score 내림차순·productId 오름차순으로 순위 생성
  revision 2의 완성 ranking 저장·완성 기록
  기간의 공개 pointer를 revision 2로 변경
commit 성공: API는 revision 2의 완성 결과를 조회
확정 rollback: 기존 revision 1의 pointer·완성 결과 유지
```

완성 조건과 공개 pointer 변경은 원문대로 같은 DB TX에서 보호한다. API는 pointer가 가리키는 완성본을 일관되게 읽어야 한다. 원자적 pointer 변경만으로 여러 조회 SQL의 일관성까지 자동 보장되는 것은 아니다. API는 [§7.6](api-contract.md#76-공개-스냅샷과-항목-조회)의 합의에 따라 같은 기본 REPEATABLE READ의 읽기 전용 TX에서 두 조회를 수행한다.

문제·선택 이유: 계산 실패로 일부 상품만 저장된 stage가 고객 결과가 되지 않게 한다. 완성 결과를 별도로 보존하면 고객 조회와 계산 진행을 분리할 수 있다. 비용은 stage와 완성 결과의 점수 중복 저장 및 공개 단계의 순위 생성·저장이다. 완성 결과를 따로 두지 않고 상태로 조회를 제한하는 대안도 있으나 이번에는 저장 영역을 분리하는 구조를 선택했다. 저장 공간·공개 TX 크기·성능은 아직 측정하지 않았다.

같은 snapshot 공개 재실행·오래된 revision 역전 방지·완성 확인·빈 완성본의 SQL과 메서드 분기는 [§6.5~6.9](#65-공개-전-완성-여부-확인-sql)에 상세화한다. 공개 commit 뒤 Batch metadata 실패와 commit 결과 불명의 운영 복구는 [§5.5](execution-recovery.md#55-명시-재시작과-상태-확인-후-수동-복구)를 유지하며 수동 확인/중단 절차는 [§5.6~5.8](execution-recovery.md#56-재시작-전-확인-순서와-지원-범위)을 따른다.

### 6.1 더 최신본이 공개된 경우

사용자는 공개하려는 계산본보다 같은 기간의 더 높은 revision이 이미 공개돼 있으면, 기존 pointer를 유지하고 “최신본 존재로 공개 생략”으로 정상 종료하기로 선택했다. 실제 공개 성공과 공개 생략은 실행 결과에서 구분한다. 결과 코드/Step context는 [§6.8](#68-클래스메서드tx-처리-순서), 최종 프로세스 종료는 [§4.9](execution-recovery.md#49-실행-결과와-프로세스-종료)를 따른다.

문제·대안·이유: revision 3이 이미 공개됐는데 revision 2의 공개 단계를 재실행해도, 같은 입력의 재시작으로는 revision의 신구가 바뀌지 않는다. 이를 실패로 끝내 운영자가 확인하게 하는 대안도 있으나, 사용자는 재시도로 해결할 문제가 아니므로 의도된 생략으로 끝내는 쪽을 선택했다. 비용은 정상 종료만 보고 해당 snapshot이 공개됐다고 오해할 수 있다는 점이므로 공개 여부와 생략 사유를 구분해 남겨야 한다.

```text
현재 공개 revision 1, 대상 revision 2: 완성 검증 뒤 정상 공개 절차
현재 공개 revision 3, 대상 revision 2: 대상의 완성 결과 저장, 공개 pointer 유지, 최신본 존재로 공개 생략
```

원문대로 revision 비교와 pointer 변경 사이의 역전도 막아야 한다. 조건부 갱신은 [§6.3](#63-snapshot-확인과-조건부-공개-갱신)의 후속 합의와 [§6.7](#67-현재-pointer의-조건부-update와0행-분류)의 SQL을 따른다. “최신본 존재”는 입력 손상이나 계산 실패를 무시하는 포괄적 정상 종료 조건이 아니다. 완성 저장 뒤 공개 여부를 정하는 순서·결과 표시는 [§6.8](#68-클래스메서드tx-처리-순서)을 따른다.

후속 합의: 더 최신본이 이미 공개됐어도 대상 snapshot의 모든 상품 점수가 준비됐는지 완성 여부를 확인하고 순위까지 갖춘 완성 결과를 저장한다. 계산 완료를 기록하되 기간의 공개 pointer 변경만 생략한다. 완성 결과 저장·완성 기록은 기존 한 공개 TX 안에서 보호하고, 확정 rollback 시 대상은 완료로 남지 않으며 기존 공개본은 유지한다. 이 저장이 실패한 경우를 공개 생략 정상 종료로 숨기지 않는다.

대안·선택 이유·비용: 완성 ranking을 만들지 않고 stage만 남겨 종료하면 저장·순위 생성 비용을 줄일 수 있다. 사용자는 계산 완료와 공개 여부를 구분하고 해당 revision의 점수·순위·결과 checksum을 확인할 수 있게 완성 결과도 저장하는 쪽을 선택했다. 이 선택은 모든 snapshot의 API 조회 기능이나 자동 보존/삭제 정책 추가를 뜻하지 않는다. API는 여전히 현재 공개 pointer의 결과만 읽는다. checksum 규칙은 [§3.5](input-storage.md#35-입력결과-checksum-규칙), 기존 완성 결과 재사용·공개 결과 코드는 [§6.6~6.8](#66-순위완성-결과manifest-저장-sql)을 따른다.

같은 snapshot이 이미 공개된 경우에는 원문대로 재실행이 안전해야 한다. 기존 공개 상태·완성 manifest·결과 건수를 확인하고 완성 결과/감사 시각·pointer를 중복 변경하지 않는 [§6.6~6.8](#66-순위완성-결과manifest-저장-sql)을 따른다. 공개 commit 뒤 metadata 실패의 실제 운영 재시작 허용 여부는 [§5.5](execution-recovery.md#55-명시-재시작과-상태-확인-후-수동-복구)와 구분한다.

### 6.2 공개 전 완성 확인과 테스트의 구분

사용자는 운영 배치의 공개 단계에는 원문 §5의 완성 여부 확인만 넣기로 했다. 계산 Step 완료 후 snapshot의 모든 상품 점수가 준비됐는지와 예상 상품 수를 확인하고, 그 조건을 충족해야 순위 생성·완성 저장·공개를 수행한다. 완료하지 못한 점수 묶음을 정상 공개하지 않으며, 불일치가 드러나면 실패로 처리하고 기존 공개본을 유지한다. 빈 주는 대상 상품0개·점수0개로 정상 완성할 수 있다.

점수의 합계를 공개 단계에서 원본 또는 snapshot으로 다시 계산해 전부 대조하는 검증은 추가하지 않는다. §6의 수작업 기대 점수/순위, 정상 실행 대 실패·재시작 결과/checksum 비교는 테스트 책임이다. 이 구분은 공개 생략 대상의 완성 결과 저장에도 동일하게 적용한다.

완성 확인은 [§6.5](#65-공개-전-완성-여부-확인-sql)에서 입력의 대상 상품에 대응하는 stage 행 수와 전체 stage 행 수를 예상 상품 수와 비교하는 SQL로 구체화한다. 이는 원문의 모든 대상 상품 점수 준비 확인이며 합계 재계산·추가 점수 양수 검사·별도 운영 점검 기능을 추가하는 것이 아니다. 입력/숫자 변환 오류를 조용히 무시하거나 유효한 기존 테스트를 완화하는 합의는 아니다.

### 6.3 Snapshot 확인과 조건부 공개 갱신

사용자는 snapshotId·기간·완성 상태를 확인하고 같은 기간의 revision을 비교하는 공개 처리와, 새 revision일 때만 UPDATE하는 조건부 갱신 방향으로 진행하도록 했다. revision 숫자만 단독으로 비교하거나 snapshotId 문자열을 정렬해 신구를 정하지 않는다.

공개 pointer에서 현재 snapshotId를 얻고 해당 snapshot의 기간·숫자 revision·완성 상태를 확인한다. 대상 snapshot도 요청의 ID·기간·revision과 맞아야 하고 완성 결과가 준비돼야 한다. 완료 확인은 [§6.2](#62-공개-전-완성-확인과-테스트의-구분) 범위이며 점수 합계를 다시 계산하는 검사를 추가하지 않는다.

|상황|처리|
|---|---|
|현재 pointer 없음|완성 결과·COMPLETED 저장과 같은 TX에서 정상 대상의 공개 pointer INSERT|
|현재와 대상 snapshotId가 같고 정상 완성본|이미 공개됨. 결과/pointer 중복 변경하지 않음|
|같은 기간의 다른 snapshot, 대상 revision이 더 높음|조건부 UPDATE로 대상 snapshotId 공개|
|같은 기간의 다른 snapshot, 현재 revision이 더 높음|대상의 완성 저장은 유지하고 공개 생략으로 정상 종료|
|기간 불일치 또는 같은 기간·revision인데 snapshotId가 다름|정합성 오류. 공개본 변경 없이 실패|

같은 기간·revision의 다른 snapshot은 유일 키·canonical ID 생성상 정상 경로에서는 생기면 안 된다. 검사 기준은 손상/연결 불일치를 정상 최신본 생략으로 숨기지 않기 위한 것이다. 내부 예외/상태 표시는 [§6.8](#68-클래스메서드tx-처리-순서)을 따른다.

UPDATE 시점에도 현재 pointer의 snapshot과 대상 snapshot이 같은 기간의 정상 완성본이고 대상 revision이 더 높은 경우에만 교체되도록 조건을 둔다. 사전 SELECT 결과만 믿고 무조건 UPDATE하지 않는다. UPDATE 영향0행을 모두 “이미 공개됨”으로 처리하지 않고 현재 공개 상태를 기준으로 같은 snapshot·더 최신본·정합성 오류·초기 행 부재를 구분한다. 기본 REPEATABLE READ의 과거 일반 SELECT 결과를 최신 현재 상태로 오인하지 않도록 최종 SQL/호출 순서를 상세화한다. 최초 행은 [§6.4](#64-최초-공개-행-생성)의 INSERT 계약을 따르고 경쟁 실패/UPDATE0행 SQL 분류는 [§6.7~6.8](#67-현재-pointer의-조건부-update와0행-분류)을 따른다.

대안·선택 이유·비용: 먼저 기간 행을 잠근 뒤 revision을 비교하는 방식도 가능하다. 이번 대화에서는 갱신 SQL 자체에 새 revision 조건을 넣어 읽기와 변경 사이의 역전을 막는 방향을 선택했다. 비용은 UPDATE0행의 정확한 분류와 최초 공개 처리를 함께 설계해야 한다는 점이다. 원문의 같은 기간 직렬 계산 전제도 유지하며, 다중 배치 동시 실행 기능이나 별도 잠금 성능 실험을 추가한 선택은 아니다. 완성 결과/상태 저장·pointer 변경은 기존 한 공개 TX 안에서 처리한다.

### 6.4 최초 공개 행 생성

사용자는 첫 공개에 성공하는 TX에서만 ranking_publication 행을 INSERT하고, 이후 새 공개본은 조건부 UPDATE하기로 선택했다. 공개 전 snapshotId가 비어 있는 기간 행을 미리 만들지 않는다. 따라서 공개 snapshotId·publishedAt은 NOT NULL이며, 해당 주의 공개 행 부재가 NOT_PUBLISHED를 뜻한다. 구매0건의 완성본은 snapshot/완성 기록과 유효 pointer 행이 있으므로 PUBLISHED·빈 items로 구분된다.

```text
최초 공개 TX:
  대상 완성 확인·순위/완성 결과·COMPLETED 기록
  periodStart·publishedSnapshotId·publishedAt을 가진 pointer INSERT
  함께 commit; 확정 실패면 함께 rollback
후속 공개 TX:
  완성 결과·COMPLETED 기록
  현재 snapshot과 같은 기간의 숫자 revision 조건으로 pointer UPDATE
```

periodStart의 유일 키를 유지한다. 원문 기본 범위는 같은 기간의 직렬 계산이다. 예상 밖 최초 공개 unique 경쟁이 발생해도 INSERT IGNORE나 무조건 upsert로 오류를 숨기거나 기존 최신본을 덮지 않는다. 기본 기술 오류 전파/rollback·수동 상태 확인 원칙을 적용하며 오류/재시작·중단 절차는 [§5.6~5.8](execution-recovery.md#56-재시작-전-확인-순서와-지원-범위)을 따른다. 경쟁을 정상 최초 공개 두 번으로 간주하지 않는다. 별도 자동 재시도나 동시 실행 기능은 추가하지 않는다.

대안·선택 이유·비용: nullable snapshotId를 가진 기간 행을 미리 만들면 최초 공개 전 잠금 대상으로 쓸 수 있지만, 미공개를 행 부재/빈 pointer 두 가지로 나타내게 된다. 사용자는 공개 성공 때 INSERT하는 단순한 표현을 선택했다. 최초 INSERT와 후속 UPDATE의 경로는 구분해야 한다.

### 6.5 공개 전 완성 여부 확인 SQL

SQL은 §9의 테이블명/열 매핑을 사용한다. 대상 snapshot의 ID·기간·revision·READY/COMPLETED·manifest 필수값을 먼저 확인한다. 공개는 계산 Step 완료 뒤에만 연결하고 상품 계산 중의 stage를 직접 공개하지 않는다. 현재 상품 삭제/노출 조건은 이 단계에 적용하지 않는다.

READY일 때 product_count는 입력 고정 TX에서 복사본의 DISTINCT product_id로 정한 값이다. 다음 SQL은 실제 대상 상품의 점수가 준비됐는지 건수로 확인한다.

```sql
SELECT COUNT(*) AS stage_count,
       COUNT(expected.product_id) AS prepared_product_count
FROM ranking_score_stage AS stage
LEFT JOIN (
    SELECT DISTINCT product_id
    FROM ranking_snapshot_input
    WHERE snapshot_id = ?
) AS expected ON expected.product_id = stage.product_id
WHERE stage.snapshot_id = ?
```

두 parameter는 같은 대상 snapshotId다. `stage_count == prepared_product_count == manifest.product_count`를 요구한다. stage의 snapshot·상품 유일 키와 입력 DISTINCT 때문에 상품 하나가 여러 번 준비됐다고 세지 않는다. 예컨대 입력7·8·9에 stage7·8·10이면 총3행이어도 준비 상품은2개여서 완성이 아니다. 비교를 위해 quantity SUM을 다시 수행하거나 점수 값을 재계산하지 않는다. 기대값은 테스트 코드에서 검증한다. 빈 입력/빈 stage는 두 count와 예상 상품 수가0이므로 정상이다.

이 불일치는 공개 전에 실패로 전파한다. 부족한 상품을0점으로 만들거나 임의 순위를 채우지 않는다. READY인데 ranking_result가 이미 존재하는 경우도 이전 정상 공개 TX와 맞지 않는 저장 상태이므로 실패시키며, 결과를 먼저 DELETE하고 다시 생성하지 않는다.

### 6.6 순위·완성 결과·manifest 저장 SQL

READY이고 완성 확인에 성공한 경우에만 다음 INSERT SELECT를 한 번 수행한다. 이미 COMPLETED인 대상은 이 SQL을 다시 실행하지 않는다.

```sql
INSERT INTO ranking_result
    (snapshot_id, product_id, score, calculated_rank,
     created_at, updated_at, deleted_at)
SELECT stage.snapshot_id, stage.product_id, stage.score,
       ROW_NUMBER() OVER (ORDER BY stage.score DESC, stage.product_id ASC),
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL
FROM ranking_score_stage AS stage
WHERE stage.snapshot_id = ?
```

MySQL 8.0 Reference Manual의 Window Function Descriptions에 따라 ROW_NUMBER는 정렬한 행에1부터 순번을 준다. product_id까지 정렬하므로 점수 동점도 결정된다. 공동 순위를 주는 RANK/DENSE_RANK를 선택하지 않는다. DB DECIMAL score의 숫자 순서로 계산하며 API 문자열 순서로 정렬하지 않는다. SQL이 저장한 행 수는 product_count와 같아야 한다.0상품이면0행 insert를 정상으로 본다.

새로 저장한 완성 결과를 다음 순서로 읽어 [§3.5](input-storage.md#35-입력결과-checksum-규칙)의 SHA-256 결과 checksum을 만든다. 읽은 행 수도 product_count와 맞춰 manifest의 대상을 확인한다. 점수를 재계산하는 대조가 아니라 저장한 결과의 checksum 생성이다.

```sql
SELECT product_id, score, calculated_rank
FROM ranking_result
WHERE snapshot_id = ?
ORDER BY calculated_rank
```

score는 JDBC의 정확한 decimal 결과에서 BigInteger로 손실 없이 변환한다. long/double 경유·반올림·소수 절삭은 하지 않는다. 행 콜백은 정수/순위 직렬화와 digest 누적만 수행하며 같은 열린 ResultSet 안에서 추가 DB 쿼리를 실행하지 않는다. 모든 결과를 하나의 문자열로 합치지 않고, ResultSet을 닫은 뒤 다음 UPDATE로 진행한다. 실제 driver fetch 버퍼링까지 일정 메모리라고 주장하지 않는다.

```sql
UPDATE ranking_snapshot
SET status = 'COMPLETED',
    result_checksum = ?,
    completed_at = UTC_TIMESTAMP(6),
    updated_at = UTC_TIMESTAMP(6)
WHERE snapshot_id = ?
  AND period_start = ?
  AND revision = ?
  AND status = 'READY'
  AND deleted_at IS NULL
```

영향 행 수는1이어야 한다.0/복수이면 성공으로 숨기지 않고 TX를 실패시킨다. 결과 insert·checksum·COMPLETED와 다음 공개 pointer 처리는 같은 Step TX다. native JDBC는 BaseEntity callback을 실행하지 않으므로 감사 열도 명시한다.

COMPLETED 재진입에서는 기간/revision/ID·완료 시각·필수 result_checksum·결과 건수가 manifest와 맞는지만 확인하고 기존 결과를 사용한다. stage에서 순위를 다시 만들거나 기존 result_checksum·completed_at·감사 시각을 갱신하지 않는다. 공개마다 입력 SUM이나 기존 결과 SHA를 다시 계산하는 운영 검사를 추가하지 않는다. 정상 완료 데이터는 유일 키·한 TX 완성과 수정/삭제 경로 미제공으로 보존한다. 직접 SQL 손상 자동 탐지/수리를 보장하지 않는다.

### 6.7 현재 pointer의 조건부 UPDATE와0행 분류

완성 저장 전 현재 공개 행과 연결 snapshot이 관찰되면 같은 기간의 정상 COMPLETED인지 먼저 확인한다. pointer가 이미 READY 대상이나 없는 snapshot을 가리킨 저장 손상을 이번 완성 저장으로 조용히 수리하지 않는다. 이 사전 읽기는 구조 확인이며 최신 공개본 판정은 아래 갱신 조건과0행의 current read로 수행한다.

```sql
UPDATE ranking_publication AS publication
JOIN ranking_snapshot AS current_snapshot
  ON current_snapshot.snapshot_id = publication.published_snapshot_id
JOIN ranking_snapshot AS target_snapshot
  ON target_snapshot.snapshot_id = ?
SET publication.published_snapshot_id = target_snapshot.snapshot_id,
    publication.published_at = UTC_TIMESTAMP(6),
    publication.updated_at = UTC_TIMESTAMP(6)
WHERE publication.period_start = ?
  AND current_snapshot.period_start = publication.period_start
  AND target_snapshot.period_start = publication.period_start
  AND current_snapshot.status = 'COMPLETED'
  AND target_snapshot.status = 'COMPLETED'
  AND publication.deleted_at IS NULL
  AND current_snapshot.deleted_at IS NULL
  AND target_snapshot.deleted_at IS NULL
  AND target_snapshot.revision > current_snapshot.revision
```

대상 ID와 기간은 검증된 request에서 전달한다. 이 SQL은 대상이 실제로 더 최신인 경우만 pointer를 교체한다. MySQL 8.0 Reference Manual의 UPDATE Statement가 허용하는 join 형식을 사용한다. 같은 snapshot이나 더 높은 기존 revision은 UPDATE하지 않는다.1행이면 PUBLISHED, 복수행이면 기간 유일성 위반으로 실패다. 정상 갱신에서는 snapshotId가 반드시 바뀌므로 matched/changed rows 설정 차이를 같은 값 UPDATE 성공 판정에 사용하지 않는다.

0행이면 같은 TX에서 다음 current read로 분류한다. REPEATABLE READ의 기존 일반 SELECT 결과만 다시 읽어 최신 pointer라고 판단하지 않는다.

```sql
SELECT publication.period_start,
       publication.published_snapshot_id,
       publication.published_at,
       publication.deleted_at AS pointer_deleted_at,
       snapshot.snapshot_id AS current_snapshot_id,
       snapshot.period_start AS current_period_start,
       snapshot.period_end AS current_period_end,
       snapshot.revision AS current_revision,
       snapshot.status AS current_status,
       snapshot.result_checksum AS current_result_checksum,
       snapshot.completed_at AS current_completed_at,
       snapshot.deleted_at AS snapshot_deleted_at
FROM ranking_publication AS publication
LEFT JOIN ranking_snapshot AS snapshot
  ON snapshot.snapshot_id = publication.published_snapshot_id
WHERE publication.period_start = ?
FOR UPDATE
```

MySQL 8.0 Reference Manual의 Locking Reads에 따르면 FOR UPDATE는 최신 사용 가능한 값을 읽는다. 여기서는 UPDATE0행의 현재 상태 판정과 최초 행 생성 경계를 위해 공개 관련 행에만 사용한다. 입력 원본에 FOR UPDATE를 추가하거나 격리 수준을 변경하는 안이 아니다. 조건부 UPDATE의 revision 보호는 그대로 유지한다. 이 기술 조립을 위해 별도 잠금 성능/대기 실험을 필수 범위에 추가하지 않는다.

- 행 없음: 최초 공개 INSERT 경로.
- 행 있으나 연결 snapshot 없음·삭제됨·기간 불일치·미완성/완료 manifest 누락: 저장 정합성 오류. 미공개나 공개 생략으로 숨기지 않음.
- 정상 현재 snapshotId == 대상 ID: ALREADY_PUBLISHED. 결과/pointer 시각·감사 열 변경 없음.
- 정상 현재 revision > 대상 revision: SKIPPED_NEWER. 대상 COMPLETED 결과 보존, 최신 pointer 유지.
- 같은 기간·같은 revision인데 다른 snapshotId: 저장 정합성 오류.
- 정상 현재 revision < 대상인데 첫 UPDATE가0행: 정상 갱신 조건과 모순된 결과이므로 오류/rollback. 자동 UPDATE 재시도 loop를 만들지 않음.

최초 공개는 빈 기간 행을 만들지 않고 완성 대상 ID로 바로 INSERT한다.

```sql
INSERT INTO ranking_publication
    (period_start, published_snapshot_id, published_at,
     created_at, updated_at, deleted_at)
VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)
```

성공 행 수1을 요구한다. 기간 unique/기술 오류는 전파하고 이번 공개 TX를 rollback한다. 기존 상태를 먼저 지우거나 INSERT IGNORE·무조건 upsert로 오류를 정상화하지 않는다. 같은 기간 직렬 실행 기본 범위를 유지하며 다중 프로세스의 병행 계산을 지원하는 별도 기능은 아니다.

### 6.8 클래스·메서드·TX 처리 순서

|타입·위치|계약|
|---|---|
|batch/job/weeklyranking/WeeklyRankingJobConfig|publishWeeklyRanking Step을 기존 transactionManager의 tasklet로 연결. calculateWeeklyScores 완료 뒤 실행|
|batch/job/weeklyranking/step/PublishWeeklyRankingTasklet|execute에서 식별 parameter를 검증한 요청으로 구성, service.publish 호출, Step ExecutionContext에 결과 저장, FINISHED 반환|
|application/ranking/RankingPublicationService|publish(request) → PublicationResult. MANDATORY로 기존 Step TX에 참여. 완성 확인/생성/재사용/pointer 분기 조율|
|domain/ranking/RankingPublicationRepository|manifest·사전 공개 view 조회, 완성 count, 결과 count/insert/순서 읽기, 완료 전이, pointer 조건부 UPDATE/current read/INSERT 포트|
|infrastructure/ranking/JdbcRankingPublicationRepository|동일 mySqlMainDataSource의 JdbcTemplate로 [§6.5~6.7](#65-공개-전-완성-여부-확인-sql) SQL 수행. 읽기는 불변 domain projection으로 반환|
|domain/ranking/RankingChecksum|[§3.5](input-storage.md#35-입력결과-checksum-규칙)의 필드 인코딩·SHA-256 누적/소문자hex·처리 행 수 관리. DB 접근 없음|
|domain/ranking/PublicationOutcome·PublicationResult|outcome은 PUBLISHED/ALREADY_PUBLISHED/SKIPPED_NEWER. result는 대상 snapshotId·실제 공개 snapshotId와 outcome을 반환|
|domain/ranking/RankingPublicationException|RuntimeException 기반의 batch 내부 실패. 정적 reason과 안전한 메시지로 불변식 위반을 표현하고 HTTP 타입/API 앱을 참조하지 않음|

Repository 포트는 JDBC/ResultSet·Spring Data 타입을 노출하지 않는다. 순서 읽기는 typed RankedProduct 행 콜백을 받아 닫기까지 구현이 소유하며 외부 SQL을 콜백에서 호출하지 않는다. target/current snapshot의 JDBC projection과 native UPDATE를 사용하고, 같은 행의 managed JPA Entity를 수정해 flush가 과거 READY 값으로 덮는 혼합 경로를 만들지 않는다.

주요 메서드:

```text
publish(request): // public, MANDATORY; tasklet의 기존 DB TX
  target = repository.findSnapshot(request.snapshotId)
  target의 ID/기간/revision·기본 manifest/삭제 아님 확인
  사전 공개 view가 있으면 연결 snapshot의 정상 완성/기간 확인

  if target.status == READY:
    기존 ranking_result 행이0인지 확인; 있으면 손상 오류
    counts = repository.countPreparedProducts(snapshotId)
    stage_count == prepared_product_count == target.product_count 아니면 실패
    inserted = repository.insertRankedResults(snapshotId)
    inserted == target.product_count 아니면 실패
    accumulator = 새 결과 checksum 누적기
    repository.forEachRankedResult(snapshotId, accumulator.accept)
    accumulator.rowCount == target.product_count 아니면 실패
    digest = accumulator.finishHex()
    repository.markCompleted(snapshotId, 기간, revision, digest) == 1 아니면 실패
    target의 논리 상태를 COMPLETED로 취급 // DB에는 자기 TX 쓰기가 보임
  else if target.status == COMPLETED:
    필수 완료 시각/result_checksum·결과 건수 == product_count 확인
    기존 ranking·checksum·시각은 그대로 사용
  else:
    상태/저장 정합성 오류

  changed = repository.publishIfNewer(snapshotId, periodStart)
  if changed == 1:
    return PUBLISHED(target ID, target ID)
  if changed != 0:
    저장 정합성 오류
  current = repository.findCurrentPublicationForUpdate(periodStart)
  if current 행 없음:
    repository.insertPublication(periodStart, target ID) == 1 아니면 실패
    return PUBLISHED(target ID, target ID)
  current의 정상 연결/기간/완료 manifest 확인
  if current.snapshotId == target.snapshotId:
    return ALREADY_PUBLISHED(target ID, current ID)
  if current.revision > target.revision:
    return SKIPPED_NEWER(target ID, current ID)
  같은 revision 다른 ID 또는 UPDATE 조건과 모순:
    저장 정합성 오류
```

명확한 내부 불변식 위반은 RankingPublicationException으로 전파한다. reason은 SNAPSHOT_CONTRACT_MISMATCH, SNAPSHOT_NOT_READY, INCOMPLETE_SCORES, INCONSISTENT_RESULT, INVALID_PUBLICATION, UNEXPECTED_WRITE_COUNT로 구분한다. 메시지에는 안전한 단계/사유만 담고 JDBC 기술 예외를 이 정상 분기로 바꾸지 않는다. 기존 CoreException/ErrorType은 commerce-api 앱에 있으므로 batch가 그 앱을 의존하거나 HTTP 오류 체계를 가져오지 않는다. 이번에는 공개 상태를 복구하는 별도 예외별 자동 재시도·삭제 기능을 만들지 않는다.

Tasklet은 세 정상 outcome 모두 정상 Step 완료로 처리하고, 별도 실패 ExitStatus로 만들지 않는다. execution context key는 `weeklyRanking.publicationOutcome`, `weeklyRanking.targetSnapshotId`, `weeklyRanking.publishedSnapshotId`로 고정한다. Batch가 업무 변경과 같은 TX에서 context를 저장하게 하고, tasklet 안의 정상 반환만으로 commit 성공을 로그/증거로 확정하지 않는다. commit 뒤의 afterChunk 관찰 또는 영속 상태 조회를 통해 결과를 보고한다. Step/Job 최종 metadata 실패는 [§5.4~5.5](../weekly-ranking.md#절-번호-안내)의 별도 경계다.

### 6.9 실패·재실행 기준과 이번 상세화의 한계

|상황|저장 결과/처리|
|---|---|
|완성 확인 실패|신규 ranking·COMPLETED·pointer 미반영, 기존 공개본 유지|
|ranking INSERT/checksum/완료 UPDATE 중 확정 실패|이번 TX의 결과·완료 전이 모두 rollback|
|최초 pointer INSERT/조건부 UPDATE/metadata chunk 저장 중 확정 실패|이번 공개 TX 전체 rollback, 이전 pointer/완성 결과 보존|
|대상이 COMPLETED인 재실행|기존 완성 결과·checksum·완료 시각을 덮지 않고 공개 상태만 판정|
|최신본이 있어 공개 생략|대상 완성 저장은 commit, 현재 최신 pointer 유지, SKIPPED_NEWER 기록|
|공개 commit 뒤 최종 Batch 상태 저장 실패|업무 결과를 삭제/역전하지 않음. 상태 확인 후 지원 가능한 수동 복구 절차 적용|
|commit 결과 불명|확정 rollback으로 단정하지 않음. 자동 재실행/상태 수리 없이 수동 확인|

이미 COMPLETED인 정상 결과를 확인하고 되풀이해도 바뀌지 않는 publish 메서드 계약과, UNKNOWN 실행에 JobOperator.restart가 바로 허용되는지는 다른 문제다. 이 메서드의 멱등성만으로 framework 재시작 제한을 우회하지 않는다.

이번에 완료한 것은 공개 단계의 SQL 초안·클래스/메서드 책임·주요 분기·TX/오류/멱등 처리 순서다. MySQL 8.0 Reference Manual의 Window Function Descriptions·UPDATE Statement·Locking Reads와 기존 Batch 로컬 소스/기합의를 정적으로 대조했다. SQL 실행·DDL/driver affected rows·예외 타입·앱 기동·테스트/실험은 하지 않았다. 검증은 원문 계산/재시작·공개 rollback/역전·최종 metadata 공백 기준 안에서 별도로 구성하며, 별도 잠금/성능 비교 테스트를 추가하지 않는다. Entity/매핑은 [§9.10](input-storage.md#910-entity-매핑쓰기-소유권과-최초-스키마-준비), 입력 검증은 [§7.7](api-contract.md#77-요청자-식별과-입력-값-객체)·[§8.1](input-storage.md#81-메서드의-입력과-책임), 수동 절차는 [§5.6~5.8](execution-recovery.md#56-재시작-전-확인-순서와-지원-범위), 검증 구성은 §11에 작성했다. 정적 검토/사용자 승인 상태는 §1을 따르며 실행 검증은 아직 전이다.

## 8. 입력 고정·계산의 처리 계약

합의한 동작을 commerce-batch의 구현 계약으로 구체화한 상세설계다. 전체 사용자 승인 설계에 포함되며, 실행·복구는 §4~5, 검증 자원 준비는 §11을 따른다. 아래 타입의 경로는 `com.loopers` 아래이며 코드 생성·실행 결과가 아니다.

입력 요청/manifest와 freeze의 계약은 [§8.1](input-storage.md#81-메서드의-입력과-책임)·[§8.2](input-storage.md#82-입력-고정과-재사용의-분기)에 있다. 아래는 상품 계산·쓰기 계약의 이어지는 부분이다.

### 8.3 상품별 계산과 실패 분기

```text
calculateProductScore(snapshotId, productId):
  maybeScore = repository.sumQuantity(snapshotId, productId) // Optional<BigInteger>
  score = maybeScore.orElseThrow(INCONSISTENT_INPUT) // Reader 상품의 입력 없음
  RankingProductScore(productId, score) 반환; null 반환으로 필터링하지 않음
  조회/변환 오류: 원인 보존, skip 없이 실패

writeScores(snapshotId, chunk):
  빈 chunk이면 쓰기 없음
  각 상품을 §8.5 절대값 upsert로 저장 // 가산 금지
  하나라도 실패하면 예외를 전파; 이미 쓴 앞 상품도 chunk TX rollback
  Batch가 같은 TX에서 Reader 상태와 repository checkpoint를 저장
  commit 전 확정 rollback: 점수·위치 모두 미반영
  commit 성공 뒤 다음 chunk 실패: 이전 chunk 유지
  재시작 시 같은 snapshot과 저장 위치 사용
```

예상 검증: 고정 후 늦은 구매와 상품 이름/삭제 변경에도 계산 입력 불변, 같은 상품 재처리 시 점수 불변, 빈 입력의 완료 구분, 다품목 보존, 오류에서 무단 skip 없음, 첫 chunk 전/후 실패의 DB 결과·영속 위치 대조. 테스트 구성과 실행은 아직 하지 않았다.

### 8.4 Step·Reader 상태와 자원 수명

Job 이름은 weeklyRanking, Step 이름/순서는 freezeWeeklyRankingInput → calculateWeeklyScores → publishWeeklyRanking으로 고정한다. 기존 JobRepository·transactionManager를 StepBuilder에 명시한다. 세 Step의 allowStartIfComplete는 기본 false, Job에 RunIdIncrementer를 붙이지 않는다. 병렬 Step·멀티스레드 Reader·skip/retry 설정·새 checkpoint 테이블은 추가하지 않는다.

상품 Reader bean/논리 이름은 `weeklyRankingProductReader`로 고정하고 @StepScope의 구체 반환 타입을 JdbcCursorItemReader<Long>로 선언해 ItemStream lifecycle이 Step에 등록되게 한다. 같은 snapshotId로 [§5.3](#53-상품-id의-cursor-reader) SQL을 바인딩하고 saveState=true다. currentItemCount를 코드에서 재설정하지 않으며 프레임워크가 전달한 영속 StepExecutionContext에서 복원한다. 상태 key는 `weeklyRankingProductReader.read.count`다. snapshotId를 Reader 이름에 붙이거나 실행마다 다른 이름을 만들지 않는다.

Batch5.2.2 로컬 소스 AbstractItemCountingItemStreamItemReader.open/update와 ExecutionContextUserSupport.getKey에서 `[name].read.count`의 int 저장/복원을 확인했다. 상품7·8의 첫 chunk commit 뒤 기대 checkpoint는2이며 다음 미처리 상품은9다. EOF의 추가 read까지 포함될 수 있는 최종 카운트를 단순 상품 총수라고 단정하지 않는다. Reader의 내장 카운트 한계로 무제한 상품 수 처리를 보장하지 않으며 별도 대용량 Reader 확장은 이번에 추가하지 않는다.

useSharedExtendedConnection=false·driverSupportsAbsolute=false·verifyCursorPosition=true의 기본값을 유지한다. cursor는 별도 읽기 연결이고 불변 snapshot 입력을 읽는다. 재시작 시 같은 SQL을 다시 열고 저장 건수만큼 cursor를 이동하므로 복원 비용이0이거나 인덱스 seek 한 번이라고 주장하지 않는다. 별도 ExtendedConnectionDataSourceProxy·드라이버 streaming 옵션·fetch-size 튜닝은 추가하지 않는다. 드라이버의 기본 버퍼링은 남는 비용이다.

Reader는 Step이 open/update/close를 소유한다. singleton 필드에 ResultSet·Connection·digest를 보관하지 않고 실패 때도 Spring의 종료 경로로 자원을 반환한다. chunk TX 연결과 Reader 연결이 동시에 필요하므로 풀이 최소 두 연결을 제공해야 한다. 기존 테스트 풀 최대10이라는 설정은 확인했지만 실제 자원 경쟁을 실행 확인한 것은 아니다.

### 8.5 상품 합계와 절대 점수 저장 SQL

Processor가 호출하는 RankingScoreRepository.sumQuantity(snapshotId, productId)의 SQL은 다음과 같다.

```sql
SELECT SUM(quantity) AS score
FROM ranking_snapshot_input
WHERE snapshot_id = ? AND product_id = ?
```

상품 하나로 제한했으므로 GROUP BY를 넣지 않는다. MySQL 8.0 Reference Manual의 Aggregate Function Descriptions는 정수/DECIMAL 인자의 SUM이 DECIMAL을 반환하고 대상 행이 없으면 NULL임을 설명한다. JDBC에서 getBigDecimal → toBigIntegerExact로 변환하며 long/double을 거치지 않는다. Reader가 읽은 상품에 합계가 없으면0으로 대체하지 않는다. DB 집계/저장의 유한 정밀도 한계와 strict mode 확인은 [§5.2](#52-점수의-숫자-타입과-db-정밀도)를 따른다. 별도 수작업 자릿수 검사·초과 시 보정·복구 기능은 추가하지 않는다.

Writer의 JdbcRankingScoreRepository.upsertScores(snapshotId, scores)는 같은 JdbcTemplate의 batchUpdate를 사용한다. 각 행의 parameter 순서는 snapshotId·productId·score·같은 score다. BigInteger는 new BigDecimal(score)로 정확히 바인딩한다.

```sql
INSERT INTO ranking_score_stage
    (snapshot_id, product_id, score, created_at, updated_at, deleted_at)
VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)
ON DUPLICATE KEY UPDATE score = ?, updated_at = UTC_TIMESTAMP(6)
```

같은 점수 재기록 시 영향 행 수가0/1/2 또는 JDBC batch의 SUCCESS_NO_INFO일 수 있으므로 모든 반환 count가1이어야 한다는 검사를 두지 않는다. SQL/JDBC batch 실패는 전파한다. 기존 created_at은 보존하고 점수에 이전 값을 더하지 않는다. stage soft delete/복구는 지원하는 기능이 아니므로 deleted_at을 초기화하는 자동 수선 경로를 넣지 않는다. 전체 점수/완성 여부의 업무 검사는 [§6.5](#65-공개-전-완성-여부-확인-sql)를 따른다.

### 8.6 클래스 배치·포트와 호출 순서

|타입·위치|메서드·책임|
|---|---|
|domain/ranking/WeeklyRankingRequest|of(String periodStart, Long revision, String snapshotId)·canonical ID 검증. Spring Batch 의존 없음|
|domain/ranking/RankingInputManifest·RankingProductScore·RankingInputException|[§8.1](input-storage.md#81-메서드의-입력과-책임)의 값·실패 원인. HTTP/다른 앱 클래스 참조 없음|
|domain/ranking/RankingInputRepository|findSnapshot(id)·findSnapshotByPeriodAndRevision·hasOrphanData·copyInput·readDatabaseUtcNow·countInput·forEachInputOrdered·insertReadySnapshot 포트|
|domain/ranking/RankingScoreRepository|findSnapshot(id) → Optional<RankingInputManifest>, sumQuantity(id, productId) → Optional<BigInteger>, upsertScores(id, List<RankingProductScore>) → void. JDBC 타입 대신 도메인 값 사용|
|infrastructure/ranking/JdbcRankingInputRepository|[§8.2](input-storage.md#82-입력-고정과-재사용의-분기)/[§9.7](input-storage.md#97-입력-복사-sql과-읽기-시점--기본-격리-유지) SQL, UTC LocalDateTime 매핑, RowCallbackHandler 내부 자원 소유|
|infrastructure/ranking/JdbcRankingScoreRepository|[§8.5](#85-상품-합계와-절대-점수-저장-sql) SQL, BigDecimal↔BigInteger 정확 변환, 같은 DataSource의 JdbcTemplate|
|application/ranking/RankingInputService|freezeOrReuseInput(request) → manifest. public MANDATORY, Step TX 참여, [§8.2](input-storage.md#82-입력-고정과-재사용의-분기) 순서 조율|
|batch/job/weeklyranking/step/FreezeWeeklyRankingInputTasklet|식별 parameter 변환 → inputService → Step context에 snapshotId/inputChecksum 기록 → FINISHED. Step context를 metadata 대신 원본 manifest로 사용하지 않음|
|batch/job/weeklyranking/step/WeeklyRankingScoreProcessor|bean 이름 weeklyRankingScoreProcessor, step scope, process(Long) → RankingProductScore. 요청 snapshot 고정, [§8.3](#83-상품별-계산과-실패-분기) 계산|
|batch/job/weeklyranking/step/WeeklyRankingScoreWriter|step scope, write(Chunk)에서 score 포트 호출. 새 TX/commit/예외 catch 없음|
|batch/job/weeklyranking/WeeklyRankingJobConfig|위 세 Step, Reader bean, 기존 영속 repository와 TX manager 조립. 계산 chunk=2|

Job parameter 추출 어댑터는 batch/job/weeklyranking/WeeklyRankingParameters에 둔다. `parse(JobParameters) → WeeklyRankingRequest`로 이름/타입/identifying을 검증하고 domain의 of(String periodStart, Long revision, String snapshotId)에 전달한다. JobParametersValidator와 각 Step이 이 어댑터를 같이 사용한다.

계산 Step의 beforeStep에서는 DB manifest의 요청 일치/READY/필수 manifest 존재를 확인한다. 정상 계산 재시작은 READY다. COMPLETED에 계산을 다시 쓰거나 입력 checksum을 매 chunk 재계산하지 않는다. 계산 Step까지 강제로 다시 실행하려는 모순된 저장 상태는 실패/수동 확인으로 보내고 stage/result를 초기화하지 않는다. 정상적인 공개 metadata 실패 재시작에서는 완료 계산 Step이 생략되고 공개 Step의 재진입 처리가 동작한다.

처리 흐름은 tasklet → application → domain 포트 → JDBC 구현, 계산은 chunk 엔진 → processor/writer → domain 포트 → JDBC 구현이다. Processor/Writer의 모든 포트 호출은 Step의 기존 TX 안에서 실행하며 직접 new connection·REQUIRES_NEW·수동 commit을 사용하지 않는다. JPA 관리 Entity와 native UPDATE를 같은 행에 섞지 않는다. 입력/결과의 checksum helper는 [§3.5](input-storage.md#35-입력결과-checksum-규칙)/[§6.8](#68-클래스메서드tx-처리-순서)의 RankingChecksum 하나를 사용하고 입력 시각 formatter는 UTC `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'`로 고정한다.

### 8.7 이번 상세화의 검토 기준

빈 입력은 READY → 계산0item → 공개0행·COMPLETED·pointer 생성으로 이어져야 한다. 상품7·8 commit 뒤9 실패는 기존 입력/7·8 stage/위치2를 유지해야 한다. 같은 상품 재기록은 점수가 증가하지 않아야 하며 writer 중간 오류는 해당 chunk 전체를 rollback해야 한다. 기존 ID의 다른 기간/revision·손상 입력은 덮어쓰지 않고, 늦은 원본 추가만으로 기존 snapshot을 손상 취급하지 않아야 한다. 이는 설계의 수용 기준이며 SQL·앱·테스트를 실행한 결과가 아니다. 구체적인 테스트/준비·정리 표는 §11을 따른다.
