# 주간 랭킹 Job 설계

> 상태: 구현했다. 계약과 규칙은 [주간 구매 랭킹](../../../docs/system-design/use-cases/weekly-ranking.md)을 따르고, 이 문서는 그 규칙을 `commerce-batch`의 Spring Batch로 구현하는 방법을 다룬다. 결정 배경은 [결정 기록](../../../docs/system-design/use-cases/weekly-ranking-decisions.md), 검증은 [검증 기록](../../../docs/system-design/use-cases/weekly-ranking-verification.md)을 따른다.

`commerce-batch`의 `weeklyRanking` Job은 한 기간의 한 회차를 세 Step으로 계산한다. 점수를 한 문장으로 고정하고, 고정한 점수를 chunk로 읽어 순위를 쓰고, 순위를 확인해 공개한다. 실행 기록은 Spring Batch의 JobRepository가 남기고, 업무 결과는 랭킹 테이블 두 개에 남는다.

## 실행

```bash
java -jar commerce-batch.jar --job.name=weeklyRanking periodStart=2026-08-17 revision=1
```

| 항목 | 내용 |
|---|---|
| Job 선택 | `spring.batch.job.name`이 `weeklyRanking`일 때만 Job 설정을 등록한다. 데모 Job과 같은 방식이다. |
| 식별 파라미터 | `periodStart`, `revision`. 둘 다 문자열 식별 파라미터이고 타입을 붙이지 않는다. Spring Batch는 파라미터의 타입까지 JobInstance 식별에 쓰므로, 타입이 섞이면 같은 작업이 다른 JobInstance가 된다. |
| 파라미터 검증 | `WeeklyRankingParametersValidator`가 확인한다. `periodStart`는 `yyyy-MM-dd` 형식의 월요일, `revision`은 0으로 시작하지 않는 양의 정수, 둘 다 식별 파라미터여야 하고 모르는 키는 거절한다. 어기면 JobInstance를 만들지 않고 시작하지 않는다. |
| 다시 실행 | 같은 파라미터로 실행하면 실패한 JobInstance의 새 JobExecution이 이어서 실행한다. 완료한 Step은 건너뛰고, 순위 Step은 그 회차의 순위를 비운 뒤 처음부터 다시 매긴다. 이미 완료된 JobInstance면 Spring Batch가 실행을 거절한다. 마지막 실행이 실행 중(`STARTED`)이나 결과 불명(`UNKNOWN`)으로 남아 있어도 거절하므로 [실패와 다시 실행](#실패와-다시-실행)의 절차로 먼저 정리한다. |
| 설정 | `weekly-ranking.chunk-size`는 순위 Step의 chunk 크기이자 reader의 페이지 크기다. 기본 100이다. 잡 파라미터가 아니라서 JobInstance 식별에 영향이 없다. |
| 사용하지 않는 것 | `RunIdIncrementer`, 실행 시각 파라미터. 매번 새 JobInstance가 되어 다시 실행과 완료 보호가 사라진다. 완료한 Step을 다시 돌리는 `allowStartIfComplete`도 켜지 않는다. |
| 종료 | 앱은 Job 하나를 실행하고 종료한다. 실패하면 0이 아닌 종료 코드를 돌려준다. 정기 실행은 외부 스케줄러가 띄운다. |

## Step 구성

| 순서 | Step | 종류 | 트랜잭션 |
|---|---|---|---|
| 1 | `freezeWeeklyScores` | Tasklet | 한 번의 `execute()`, 한 트랜잭션. Step의 `transactionAttribute`로 격리 수준 `READ_COMMITTED`를 준다 |
| 2 | `rankWeeklyScores` | chunk (Reader → Processor → Writer) | 묶음마다 커밋. 시작 전 순위 비우기는 묶음 트랜잭션 밖의 한 문장 |
| 3 | `publishWeeklyRanking` | Tasklet | 한 번의 `execute()`, 한 트랜잭션 |

- 세 Step 모두 실제 DB 트랜잭션 관리자를 쓴다.
- 격리 수준은 Step 설정으로 줘야 적용된다. Tasklet 안에 `@Transactional(isolation = ...)`을 붙이면 이미 열린 Step 트랜잭션에 합류해 무시된다.
- 점수 고정 Step의 트랜잭션 속성은 격리 수준만 바꾸고, 롤백 규칙은 Tasklet Step 기본값처럼 모든 예외로 둔다. 일반 `DefaultTransactionAttribute`를 그대로 쓰면 RuntimeException에서만 롤백한다. 그러면 checked 예외가 났을 때 점수만 커밋되고 Tasklet이 다시 불려 Job이 완료로 끝난다.
- Step의 쓰기 건수는 점수 고정이 넣은 점수 행 수, 순위 Step이 쓴 순위 수, 공개가 완성한 회차 1이다. 이미 한 일이라 건너뛰면 0이다. `READ_COMMITTED`의 `INSERT ... SELECT`는 MySQL binlog 형식이 `ROW`여야 한다. MySQL 8.0의 기본값이다.
- 2·3번은 원본 구매 활동을 읽지 않으므로 기본 격리 수준으로 둔다.

## 저장소와 트랜잭션

- JobRepository는 Spring Boot가 기본 DataSource(`mySqlMainDataSource`)와 기본 트랜잭션 관리자로 만드는 JDBC JobRepository를 그대로 쓴다.
- `@EnableBatchProcessing`, 메모리 JobRepository, `ResourcelessTransactionManager`를 추가하지 않는다. 데모 Tasklet의 `ResourcelessTransactionManager`는 DB에 쓰지 않는 예시라서 랭킹 Step에 복사하지 않는다.
- 업무 SQL은 같은 DataSource를 받은 `JdbcTemplate`으로 실행한다. 그래서 업무 쓰기와 Step 실행 기록이 같은 DB 트랜잭션에 묶인다. 순위 Step에서는 묶음의 순위와 그 묶음의 실행 기록이 함께 커밋되거나 함께 되돌아간다.
- local·test의 기존 `initialize-schema: always` 설정은 앱이 시작할 때마다 Batch 스키마 초기화 스크립트를 실행한다. 이미 있는 메타 테이블과 Job 실행 기록은 지우지 않는다.

## 1. 점수 고정

```sql
-- 1. 이미 있는 회차인지 본다
SELECT status FROM weekly_ranking_revisions WHERE period_start = ? AND revision = ?;

-- 2. 없으면 회차 번호와 계산 중 회차를 확인한다. revision은 첫 값 + 1이어야 하고, 둘째 값은 0이어야 한다
SELECT COALESCE(MAX(revision), 0) FROM weekly_ranking_revisions WHERE period_start = ?;
SELECT COUNT(*) FROM weekly_ranking_revisions WHERE period_start = ? AND status = 'CALCULATING';

-- 3. 잘못된 원본이 있는지 본다. 0이어야 한다
SELECT COUNT(*) FROM product_purchase_activity
WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL AND quantity <= 0;

-- 4. 점수를 한 문장으로 넣는다. 순위는 비워 둔다
INSERT INTO weekly_ranking_scores (period_start, revision, product_id, score)
SELECT ?, ?, product_id, SUM(quantity)
FROM product_purchase_activity
WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL
GROUP BY product_id;

-- 5. 회차 정보를 계산 중으로 넣는다
INSERT INTO weekly_ranking_revisions (period_start, revision, status, calculated_at, job_execution_id)
VALUES (?, ?, 'CALCULATING', ?, ?);
```

규칙과 순서는 [규칙 문서의 점수 고정](../../../docs/system-design/use-cases/weekly-ranking.md#1-점수-고정)을 따른다. 구현에서 정하는 것은 다음과 같다.

- **이미 있는 회차:** 1이 `CALCULATING`이면 고정한 점수를 그대로 쓰고, `COMPLETED`면 아무것도 쓰지 않는다. 둘 다 로그를 남기고 `FINISHED`를 돌려준다. `CANCELLED`면 예외를 던진다.
- **확인:** 회차가 없으면 2·3을 같은 트랜잭션 안에서 확인하고, 어기면 예외를 던져 아무것도 쓰지 않고 실패한다.
- **점수:** 4의 한 문장으로 한다. 점수 CHECK 같은 DB 오류는 감싸지 않고 그대로 던져 트랜잭션 전체를 rollback한다.
- **계산 시각:** 4가 끝난 뒤 `Clock`을 읽는다. 4 전에 읽지 않는다.
- **회차 정보:** 5에서 현재 JobExecution ID를 넣는다. 두 실행이 같은 회차를 함께 끝까지 가면 기본키가 늦은 쪽의 INSERT를 막고, 늦은 쪽은 rollback한다.

## 2. 순위 매기기

```sql
-- 시작 전: 계산 중인 회차의 순위를 비운다
UPDATE weekly_ranking_scores s
JOIN weekly_ranking_revisions r ON r.period_start = s.period_start AND r.revision = s.revision
SET s.rank_no = NULL
WHERE s.period_start = ? AND s.revision = ? AND r.status = 'CALCULATING';

-- Reader: 계산 중인 회차의 점수를 정해진 순서로 페이지 단위로 읽는다
SELECT s.product_id, s.score
FROM weekly_ranking_scores s
JOIN weekly_ranking_revisions r ON r.period_start = s.period_start AND r.revision = s.revision
WHERE s.period_start = :periodStart AND s.revision = :revision AND r.status = 'CALCULATING'
ORDER BY s.score DESC, s.product_id ASC;

-- Writer: 계산 중인 회차에만 순위를 절대값으로 쓴다
UPDATE weekly_ranking_scores s
JOIN weekly_ranking_revisions r ON r.period_start = s.period_start AND r.revision = s.revision
SET s.rank_no = ?
WHERE s.period_start = ? AND s.revision = ? AND s.product_id = ? AND r.status = 'CALCULATING';
```

| 구성 | 내용 |
|---|---|
| 시작 전 비우기 | Step listener의 `beforeStep`이 위 비우기 SQL을 실행한다. 처음 실행할 때는 비울 순위가 없고, 다시 실행할 때는 앞 실행이 커밋한 순위를 비운다. 회차가 `CALCULATING`이 아니면 바뀌는 행이 없다. |
| Reader | `JdbcPagingItemReader`, 이름 `frozenScoreReader`. `step/FrozenScoreReader`가 만든다. 빌더의 `build()`는 초기화하지 않으므로 만든 뒤 `afterPropertiesSet()`을 부른다. sort key는 `score DESC, product_id ASC`이고, 둘을 합치면 회차 안에서 유일하다. 페이지 크기는 chunk 크기와 같은 설정 값을 쓴다. 위치를 저장하지 않는다(`saveState(false)`). 잡 파라미터를 받는 `@StepScope` 빈이고, Step이 `ItemStream`으로 열고 닫도록 반환 타입을 `JdbcPagingItemReader`로 둔다. |
| 아이템 | `FrozenScore`는 상품 ID와 점수를 담고 `ItemCountAware`를 구현한다. Reader가 읽을 때 몇 번째로 읽힌 아이템인지 넣어 준다. 위치를 저장하지 않으므로 실행마다 1부터 센다. |
| Processor | 읽은 순번을 계산 순위로 정해 `(상품 ID, 순위)`를 넘긴다. |
| Writer | 위 UPDATE를 `JdbcTemplate`의 batch update로 실행한다. 절대값이라 다시 매겨도 같은 값이 된다. 바뀐 행 수의 합이 묶음의 아이템 수와 다르면 예외를 던진다. 묶음을 읽은 뒤 쓰기 전에 운영자가 회차를 닫은 경우다. 그 묶음은 롤백되고, 닫힌 회차의 행은 더 바뀌지 않는다. 묶음 사이에 닫히면 Writer까지 오지 않는다. 다음 페이지 쿼리가 계산 중인 회차만 읽어 0행을 받고, 순위 Step은 그대로 끝난다. |
| 다시 실행 | 이어 가지 않는다. 앞 실행이 커밋한 1·2위가 있어도 시작 전에 비우고 첫 행부터 다시 읽는다. 비우지 않고 덮어쓰면 순위 규칙이 바뀐 경우 남아 있던 순위와 유일 제약이 부딪힌다. |

규칙은 [규칙 문서의 순위 매기기](../../../docs/system-design/use-cases/weekly-ranking.md#2-순위-매기기)를 따른다. 회차가 `CALCULATING`이 아니면 비울 순위도 읽을 행도 없어 아무것도 쓰지 않고 끝난다.

## 3. 공개

```sql
-- 1. 회차 상태를 본다
SELECT status FROM weekly_ranking_revisions WHERE period_start = ? AND revision = ?;

-- 2. 순위가 빠짐없이 매겨졌는지 본다
SELECT COUNT(*), COUNT(rank_no), MIN(rank_no), MAX(rank_no)
FROM weekly_ranking_scores WHERE period_start = ? AND revision = ?;

-- 3. 순위 순으로 흘려 읽어 결과 checksum을 만든다
SELECT product_id, score, rank_no FROM weekly_ranking_scores
WHERE period_start = ? AND revision = ? ORDER BY rank_no;

-- 4. 완성으로 바꾼다
UPDATE weekly_ranking_revisions SET status = 'COMPLETED', result_checksum = ?
WHERE period_start = ? AND revision = ? AND status = 'CALCULATING';
```

규칙은 [규칙 문서의 공개](../../../docs/system-design/use-cases/weekly-ranking.md#3-공개)를 따른다. 구현에서 정하는 것은 다음과 같다.

- **상태:** 1이 `COMPLETED`면 로그를 남기고 `FINISHED`를 돌려준다. `CANCELLED`면 예외를 던진다.
- **순위 확인:** 2에서 행 수와 순위가 있는 행 수가 같아야 한다. 행이 있으면 최솟값이 1이고 최댓값이 행 수여야 한다. 순위 유일 제약이 겹치는 순위를 막으므로, 이 세 조건이면 순위가 1부터 행 수까지 빠짐없이 이어진다. 어기면 예외를 던지고 회차는 `CALCULATING`으로 남는다.
- **checksum:** 3을 `rank_no` 순으로 흘려 읽으며 `MessageDigest`로 만든다. `GROUP_CONCAT`은 기본 1024바이트에서 오류 없이 잘리므로 쓰지 않는다.
- **완성:** 4에서 바뀐 행이 1이 아니면 예외를 던진다. 그사이 운영자가 회차를 닫은 경우다.
- **0건 기간:** 2는 행 수 0으로 통과하고, checksum은 빈 문자열의 SHA-256이며, 4는 그대로 진행한다.

## 실패와 다시 실행

Step 안에서는 재시도하지도 건너뛰지도 않는다(결정 기록 R-31). 실패하면 Job은 실패로 끝나고 0이 아닌 종료 코드를 돌려준다. 다시 시도는 같은 파라미터로 Job을 다시 실행하는 것이고, 자동으로 다시 띄울지와 그 횟수·간격은 스케줄러 설정이다.

| 실패 지점 | 남는 상태 | 같은 파라미터로 다시 실행하면 |
|---|---|---|
| 점수 고정 (잘못된 원본, 회차 번호, 계산 중 회차, 제약 위반, 연결 끊김) | 업무 테이블에 아무것도 없음 | 원인을 고친 뒤 그 순간의 구매 활동으로 처음부터 계산한다 |
| 순위 매기기 | `CALCULATING` 회차, 점수 전부, 커밋한 묶음까지의 순위 | 점수 고정은 건너뛴다. 순위는 비운 뒤 처음부터 다시 매기고, 그다음 공개한다 |
| 순위를 매기는 중 회차가 닫힘 | `CANCELLED` 회차, 닫기 전에 커밋한 묶음까지의 순위. 묶음 사이에 닫혔으면 순위 Step은 완료로 끝나고 공개에서 실패하며, 쓰기 직전에 닫혔으면 순위 Step에서 실패한다 | 순위 Step이 완료됐으면 건너뛰고, 실패했으면 비울 순위도 읽을 행도 없어 그대로 끝난다. 어느 쪽이든 공개에서 실패한다. 새로 계산하려면 다음 회차로 실행한다 |
| 공개 (순위 확인 실패 포함) | `CALCULATING` 회차, 점수와 순위 | 점수 고정과 순위 매기기는 건너뛰고 공개만 다시 한다. 순위 확인이 계속 실패하면 회차를 닫는다 |
| 어느 Step이든 커밋 뒤, 성공 기록 전 | 그 Step의 결과는 커밋됨. 실행 기록은 대개 `UNKNOWN`이나 `STARTED`로 남는다 | 아래 정리 절차 뒤 다시 실행하면 그 Step이 다시 돈다. 각 Step이 회차 상태를 보고 이미 한 일은 다시 하지 않으므로 같은 결과로 끝난다 |
| 실행 기록이 `STARTED`나 `UNKNOWN`으로 남음 (프로세스 강제 종료, 기록 저장 실패) | 커밋한 데까지 남음 | 같은 파라미터의 실행을 Spring Batch가 거절한다. 정리 절차를 거친 뒤 다시 실행한다 |
| `CANCELLED` 회차로 실행 | 그대로 | 점수 고정이나 공개에서 실패한다. 순위 Step은 읽을 행이 없다. 새로 계산하려면 다음 회차로 실행한다 |

정리 절차: 프로세스가 끝났는지와 회차 정보를 확인한 뒤, 메타 테이블에서 `STARTED`나 `UNKNOWN`인 실행만 `FAILED`로 바꾼다. 앱은 Job 하나를 실행하고 끝나는 구조라 `JobOperator`를 부를 곳이 없어 SQL로 한다.

```sql
UPDATE BATCH_STEP_EXECUTION
SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = ?, LAST_UPDATED = ?
WHERE JOB_EXECUTION_ID = ? AND STATUS IN ('STARTED', 'UNKNOWN');

UPDATE BATCH_JOB_EXECUTION
SET STATUS = 'FAILED', EXIT_CODE = 'FAILED', END_TIME = ?, LAST_UPDATED = ?
WHERE JOB_EXECUTION_ID = ? AND STATUS IN ('STARTED', 'UNKNOWN');
```

회차를 닫는 일([규칙 문서의 회차 닫기](../../../docs/system-design/use-cases/weekly-ranking.md#회차-닫기))도 상태 조건을 붙인 SQL로 한다.

```sql
UPDATE weekly_ranking_revisions
SET status = 'CANCELLED', cancelled_at = ?, cancel_reason = ?
WHERE period_start = ? AND revision = ? AND status IN ('CALCULATING', 'COMPLETED');
```

## 시각과 설정

| 항목 | 내용 |
|---|---|
| 현재 시각 | 주입한 `Clock`을 마이크로초로 잘라 UTC `LocalDateTime`으로 쓴다(`RankingTime`). `commerce-api`의 `ApplicationTime`과 같은 방식이다. 기본 `Clock`은 `Clock.systemUTC()`다. |
| JDBC 시각 값 | `JdbcTemplate`에는 Hibernate의 UTC 설정(`jdbc.time_zone: UTC`)이 적용되지 않는다. JDBC URL에 시간대 설정이 없어 드라이버가 `Instant`나 `Timestamp`를 JVM 기본 시간대(`Asia/Seoul`)로 바꿔 보낸다. 그래서 시각은 UTC 기준 `LocalDateTime`으로 넣고 읽는다. 기간 범위와 계산 시각 모두 같다. |
| 기간 계산 | `RankingPeriod`가 `periodStart`를 받아 월요일인지 확인하고, UTC 자정 기준의 시작과 끝(7일 뒤)을 준다. JVM 기본 시간대를 쓰지 않는다. |
| chunk 크기 | `weekly-ranking.chunk-size`, 기본 100. 순위 Step의 chunk와 reader 페이지 크기로 같이 쓴다. 측정한 값이 아니다. 테스트와 로컬 연결 확인은 2를 준다. |
| JPA 스키마 | local 프로필은 `ddl-auto: validate`다. 공통 설정의 `create`를 그대로 받으면 엔티티로 매핑한 테이블을 로컬 실행 때마다 지우고 다시 만든다. test 프로필은 `create`를 유지한다. |
| 엔티티 매핑 | 랭킹 테이블 두 개와 읽기 전용 `product_purchase_activity`를 매핑한다. 매핑은 로컬 스키마 확인과 테스트 스키마 생성에 쓰고, 읽기와 쓰기는 위 SQL로 한다. 순위 유일 제약과 점수 CHECK가 테스트 스키마에도 생기도록 매핑에 선언한다. 계산 순위와 결과 checksum은 계산 중에는 비어 있을 수 있다. |

## 패키지

데모 Job과 같은 구조로 `com.loopers.batch.job.ranking` 아래에 둔다.

| 위치 | 책임 |
|---|---|
| `WeeklyRankingJobConfig` | Job, 세 Step, 순위 Step의 reader·processor·writer, 파라미터 검증 등록 |
| `WeeklyRankingParametersValidator` | 식별 파라미터의 모양 검사 |
| `step/FreezeWeeklyScoresTasklet` | 점수 고정 |
| `step/ClearRanksListener`, `step/FrozenScoreReader`, `step/FrozenScore`, `step/ScoreRankProcessor`, `step/ScoreRankWriter` | 순위 매기기의 시작 전 비우기, Reader 만들기, 아이템, Processor, Writer |
| `step/PublishWeeklyRankingTasklet` | 순위 확인, checksum, 완성 |
| `ProductRank` | 상품 하나에 매긴 계산 순위. Processor가 만들고 Writer가 쓴다 |
| `RankStats` | 공개 전 순위 확인 값(행 수, 순위가 있는 행 수, 최솟값, 최댓값)과 빠짐없는지 판단 |
| `WeeklyRankingKey` | 잡 파라미터의 기간과 회차 |
| `WeeklyRankingRevisionStatus` | 회차 상태 `CALCULATING`, `COMPLETED`, `CANCELLED` |
| `RankingPeriod` | 월요일 검증과 UTC 기간 범위 |
| `RankingChecksum` | 결과 checksum 직렬화와 SHA-256 |
| `RankingTime` | 주입한 시계의 UTC `LocalDateTime` |
| `WeeklyRankingRepository` | 회차 정보, 점수, 원본 읽기 SQL |
| `schema/` | 랭킹 테이블 두 개와 읽기 전용 구매 활동의 JPA 매핑 |

현재 시각 빈은 `com.loopers.batch.config.ClockConfig`(UTC 시계)가 등록한다. 로컬 연결 확인은 실제 시계로 돌고, 실패는 도구가 격리 DB에 트리거를 걸어 만든다.

## 구현 체크리스트

- [x] `RunIdIncrementer`와 실행 시각 파라미터를 쓰지 않는다.
- [x] 랭킹 Step에 `ResourcelessTransactionManager`를 쓰지 않는다.
- [x] 메모리 JobRepository나 `@EnableBatchProcessing`을 추가하지 않는다.
- [x] 업무 SQL과 JobRepository가 같은 DataSource와 트랜잭션 관리자를 쓴다.
- [x] 점수 고정 Step의 격리 수준은 Step의 `transactionAttribute`로 주고, 롤백 규칙은 모든 예외로 둔다.
- [x] 완료한 Step을 다시 돌리도록(`allowStartIfComplete`) 설정하지 않는다.
- [x] Step에 `faultTolerant()`의 retry와 skip을 두지 않는다.
- [x] 순위 Step의 reader는 `@StepScope` 빈이고 반환 타입은 `JdbcPagingItemReader`다.
- [x] 순위 Step은 시작할 때 계산 중인 회차의 순위를 비운다.
- [x] reader는 위치를 저장하지 않는다(`saveState(false)`).
- [x] 순위 Writer는 계산 중인 회차에만 쓰고, 바뀐 행 수가 묶음의 아이템 수와 다르면 실패한다.
- [x] 순위는 읽은 순번으로 정하고 절대값으로 쓴다.
- [x] 테스트는 `spring.batch.job.enabled=false`로 자동 실행을 끄고 직접 launch한다.
- [x] 테스트 시작 때 업무 테이블과 Batch 실행 기록을 함께 비운다. `DatabaseCleanUp`은 엔티티 테이블만 비우므로 실행 기록은 `JobRepositoryTestUtils`로 지운다.
- [x] 실패와 다시 실행 테스트에서 두 실행 사이에 DB와 메타 테이블을 비우지 않는다.
- [x] 실패 스위치는 테스트 설정으로 두고 식별 파라미터로 만들지 않는다.
- [x] 잡 파라미터는 타입 없이 문자열로 넘기고, 검증기가 모양을 확인한다.
- [x] JDBC SQL의 시각은 UTC `LocalDateTime`으로 넣고 읽는다.
- [x] 계산 시각은 점수를 넣은 뒤에 읽는다.
- [x] 실행 전에 `./gradlew :apps:commerce-batch:dependencyInsight --dependency spring-batch-core --configuration runtimeClasspath`로 Spring Batch 버전을 확인한다.
