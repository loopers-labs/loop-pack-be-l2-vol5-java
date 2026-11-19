# 9주차 설계 · 중단된 배치를 같은 지점에서 이어 가는 주간 구매 랭킹

- 과제: `docs/week9/reference/week9-practice.md` (실습) · 학습: `docs/week9/reference/week9-learning.md` (발제)
- 사전 조사: `docs/week9/reference/01-batch-module-survey.md` (기존 코드) · `docs/week9/reference/02-probe-result.md` (§3-1 probe 실험)
- 이전 설계: `docs/week7/outbox-recovery-design.md` (W7 Outbox·relay), `docs/week6/order-confirmed-event-design.md` (W6 구매 활동)
- 표기: 코드로 확인한 사실은 `파일:줄`. 확인 못 한 것은 `[확인 필요]`. 미정 사항은 2026-11-20 에 모두 확정했다(10절).

---

## 0. 전체 흐름

```
[W6·W7 · 이미 있는 것]                               [W9 · 이번 주 만드는 것]
 PG 확정 → Outbox → relay → Kafka                      commerce-batch  Job `weeklyRanking`
   → commerce-streamer consumer                           Step 1 freezeWeeklyInputStep   (Tasklet, 한 TX)
       · processed_order_confirmation (장부)                 product_purchase_activity 의 그 주 행을
       · product_purchase_activity (orderId·productId 당 1행)   weekly_ranking_inputs 로 복사 + 회차 행 INSERT
                                                           Step 2 calculateWeeklyScoresStep (Chunk 2)
                                                             상품 1개 = item 1개, 합계를 절대값 upsert
                                                           Step 3 openWeeklyRankingStep   (Tasklet, 한 TX)
                                                             순위 부여 → 회차 COMPLETED → 공개 pointer 조건부 갱신
                                                                   ▼
                                                        commerce-api  GET /api/v1/rankings/weekly
                                                             pointer → COMPLETED 회차 → 점수 행 (쿼리 2개)
```


입력은 W6·W7 consumer 가 저장한 `product_purchase_activity`(`apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/ProductPurchaseActivity.java:17-29`: orderId·productId·quantity·confirmedAt) 하나다. W8 쿠폰은 점수에 섞지 않는다.

**핵심 보장 3개**

| 번호 | 보장 | 지키는 장치 |
|---|---|---|
| 보장 1 | 같은 입력을 고정한다 | Step 1 이 한 TX 로 입력 복사 + 회차 행을 commit (D9). 이후 Step 은 복사본만 읽음 (D10) |
| 보장 2 | 같은 작업을 멈춘 자리부터 이어 간다 | 식별 파라미터 불변 (D1) + chunk commit 과 reader checkpoint 가 같은 TX (D10, probe 로 확인) |
| 보장 3 | 완성본만 공개한다 | 순위 부여·COMPLETED 전환·pointer 갱신이 한 TX (D11, R3) + API 는 pointer 경로로만 읽음 (R5) |

입력 원본: `apps/commerce-streamer/src/main/java/com/loopers/domain/purchase/ProductPurchaseActivity.java` (W6 consumer 가 저장, orderId·productId 당 1행).

**범위 밖 (한계로만 기록, 7-2절)**: 프로세스 kill 로 `STARTED` 에 남는 JobExecution 의 수동 복구, 배치 다중 인스턴스, 실패 알림·자동 재시작, `confirmed_at` 인덱스(측정 없이 추가하지 않음), 마이그레이션 도구, commerce-batch 의 ArchUnit.

---

## 1. 용어

처음 보는 사람을 위한 한 줄 설명.

| 용어 | 한 줄 설명 |
|---|---|
| Job / Step | Job 은 하나의 업무 흐름(`weeklyRanking`), Step 은 그 흐름의 한 단계. 이번엔 Step 3개 |
| Tasklet | 한 단계의 일을 메서드 하나로 직접 쓰는 Step 형태. 입력 고정·공개처럼 "한 번에 끝나는 일"에 쓴다 |
| Chunk | 항목을 N개 읽고 처리한 뒤 한 번에 commit 하는 단위. 이번엔 N=2 (과제 실험값) |
| ItemReader / Processor / Writer | chunk Step 의 세 역할: 다음 항목 읽기 / 항목 계산 / 결과 저장 |
| JobParameters · 식별 파라미터 | Job 에 넘기는 값. `identifying=true` 인 것만 "같은 작업인지" 판단에 쓰인다 |
| JobInstance | job 이름 + 식별 파라미터로 구분되는 논리적 작업 1개 |
| JobExecution / StepExecution | 그 작업을 실행한 한 번의 시도 / Step 한 번의 시도. 재시작하면 같은 JobInstance 에 새 JobExecution 이 붙는다 |
| JobRepository · Batch metadata | 위 실행 기록을 저장하는 저장소. 이 프로젝트에선 업무 DB 의 `BATCH_*` 테이블 (`01-survey` 3-3) |
| ExecutionContext · checkpoint | Step 이 재시작용 상태(reader 위치 등)를 저장하는 key-value. 마지막 commit 위치가 checkpoint |
| snapshot · revision | snapshot = 어느 시점에 고정한 입력 묶음과 그 계산 결과. revision = 같은 기간을 다시 계산한 회차 번호(r1, r2…) |
| 공개 pointer | 기간별로 "지금 고객에게 보여 줄 snapshot 은 이것"을 가리키는 행 |
| checksum | 정해진 규칙으로 직렬화한 내용의 SHA-256. 같은 입력·같은 결과인지 비교하는 도구 |
| 절대값 upsert | 있으면 UPDATE, 없으면 INSERT 하되 값은 "현재까지의 합계" 그 자체로 쓰는 방식. `+=` 와 반대 |

---

## 2. Job 식별 (D1)

<a id="d1"></a>
### D1. 식별 파라미터 → `periodStart` + `snapshotId`, 둘 다 identifying=true. run.id·현재 시각·실패 스위치 없음

- **선택지**: (a) `periodStart` + `snapshotId` (b) `periodStart` + `revision`(숫자) (c) `snapshotId` 하나 (d) 위 중 하나 + `run.id`(RunIdIncrementer)
- **선택**: (a).

| 파라미터 | 타입 | 형식 | 예 | identifying |
|---|---|---|---|---|
| `periodStart` | String | `YYYY-MM-DD`, UTC 월요일 | `2026-08-17` | true |
| `snapshotId` | String | `weekly-YYYYMMDD-rN` | `weekly-20260817-r1` | true |

- revision N 은 `snapshotId` 의 `-rN` 에서 파싱한다. `snapshotId` 안의 날짜(`YYYYMMDD`)와 `periodStart` 가 같은 날이 아니면 Step 1 에서 실패한다 (예외 → Job FAILED. 업무 오류 분류는 "잘못된 요청"에 해당. 배치는 HTTP 가 아니므로 `ErrorType` 을 쓰지 않고 `IllegalArgumentException` 에 `snapshotId`·`periodStart` 두 값을 담은 메시지로 던진다. 새 예외 클래스는 만들지 않는다).
- 재시작은 같은 두 값을 그대로 넘긴다. 실패 스위치(테스트용 hook)는 파라미터가 아니라 테스트 코드의 메서드 인자·빈 교체로 전달한다 (probe 와 같은 방식, `02-probe-result.md` 7절).
- **이유**:
  - 과제 예시가 그대로 이 조합이다(`weeklyRanking + periodStart + snapshotId` → 같은 JobInstance, 예시 값 `weekly-20260817-r1`).
  - probe 가 같은 방식(`snapshotId` 단일 식별 파라미터)으로 같은 JobInstance 의 재시작을 확인했다 (`02-probe-result.md` 2절: JobInstance 1 / JobExecution 1→2).
  - `snapshotId` 는 API 응답·로그·하위 테이블(D2~D5)의 공통 식별자다. 파라미터에 그대로 두면 "이 실행이 어느 snapshot 을 만들었나"를 metadata(`BATCH_JOB_EXECUTION_PARAMS`)만 보고 알 수 있다. (b) 는 revision 숫자에서 snapshotId 를 다시 조립해야 한다.
  - (d) 를 쓰지 않는 이유: `run.id` 나 현재 시각을 식별 파라미터에 넣으면 실패마다 새 JobInstance 가 되어, 성공해도 이전 checkpoint 에서 이어 갔다는 증거가 못 된다. 기존 `DemoJobConfig` 의 `RunIdIncrementer`(`apps/commerce-batch/src/main/java/com/loopers/batch/job/demo/DemoJobConfig.java:34`)는 "새 작업" 예시이므로 복사하지 않는다.
- **늦은 구매 재계산**은 새 `snapshotId`(rN+1) = 새 JobInstance 로 구분한다 (D15).
- **한계**: 두 파라미터가 중복 정보(날짜)를 가진다. 어긋나면 Step 1 에서만 잡히므로 Job 기동 자체는 된다. `periodStart` 를 빼고 (c) 로 가면 중복은 없지만 과제 예시·"업무 기간" 파라미터의 가독성을 잃는다.

---

## 3. 저장 구조 (D2 ~ D8) · 테이블 4개

과제의 저장 단위 5개 중 "점수 stage" 와 "완성 ranking" 을 한 테이블(D4)로 합쳤다. 합친 대가로 불변 규칙 R1~R7 을 명시한다.

| 과제 저장 단위 | 이 설계의 테이블 | 결정 |
|---|---|---|
| snapshot 입력 | `weekly_ranking_inputs` | D2 (이미 구현) |
| snapshot 정보 | `weekly_ranking_revisions` | D3 |
| 점수 stage + 완성 ranking | `weekly_ranking_scores` | D4 |
| 공개 pointer | `weekly_ranking_publications` | D5 |

공통 사항
- 네 테이블의 `@Entity` 는 **commerce-batch 에만** 둔다 (`com.loopers.domain.ranking`). test·local 의 `ddl-auto: create`(`modules/jpa/src/main/resources/jpa.yml:40,56`)가 `@EntityScan("com.loopers")`(`modules/jpa/src/main/java/com/loopers/config/jpa/JpaConfig.java:10`) 범위의 엔티티로 테이블을 만든다는 것은 probe 로 확인했다 (`02-probe-result.md` 6절). commerce-api 는 `@Entity` 를 두지 않고 JDBC 로 읽기만 한다 (D14).
- 운영(dev/qa/prd)은 `ddl-auto: none`·`initialize-schema: never` 라 DDL 을 외부에서 실행해야 한다 (`jpa.yml:7`, `apps/commerce-batch/src/main/resources/application.yml:17-18`). 아래 DDL 이 그 원본이다. 마이그레이션 도구는 범위 밖.
- `BaseEntity`(`modules/jpa/src/main/java/com/loopers/domain/BaseEntity.java:21-32`)의 컬럼: `id BIGINT` IDENTITY, `created_at` NOT NULL, `updated_at` NOT NULL, `deleted_at` NULL, 셋 다 `ZonedDateTime`. Hibernate 가 MySQL 에 만드는 실제 타입은 `DATETIME(6)` 으로 적는다 — §6 테스트에서 `information_schema.COLUMNS` 로 확인한다 (probe 에서 값은 확인했고 컬럼 타입은 조회하지 않음, `02-probe-result.md` 5절).

<a id="d2"></a>
### D2. `weekly_ranking_inputs` (이미 구현) → BaseEntity 상속, UNIQUE (snapshot_id, order_id, product_id)

- 구현: `apps/commerce-batch/src/main/java/com/loopers/domain/ranking/WeeklyRankingInput.java` — `@Table(name="weekly_ranking_inputs", uniqueConstraints=uk_wri_snapshot_order_product(snapshot_id, order_id, product_id))` (`:17-23`), 필드 `snapshotId @Column(length=64, nullable=false)`, `orderId`·`productId`·`confirmedAt` 은 `@Column(nullable=false)`, `quantity int` (`:26-38`).
- streamer 의 `ProductPurchaseActivity`(`...ProductPurchaseActivity.java:21-29`)와 같이 BaseEntity 를 상속하고 `@Column(name)` 없이 기본 snake_case 를 쓴다. probe 가 `snapshot_id`·`confirmed_at` 컬럼으로 JDBC insert/select 에 성공해 네이밍이 맞음을 확인했다 (`02-probe-result.md` 6절).

```sql
CREATE TABLE weekly_ranking_inputs (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  snapshot_id  VARCHAR(64)  NOT NULL,
  order_id     BIGINT       NOT NULL,
  product_id   BIGINT       NOT NULL,
  quantity     INT          NOT NULL,
  confirmed_at DATETIME(6)  NOT NULL,            -- UTC (D12)
  created_at   DATETIME(6)  NOT NULL,
  updated_at   DATETIME(6)  NOT NULL,
  deleted_at   DATETIME(6)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wri_snapshot_order_product (snapshot_id, order_id, product_id)
) ENGINE=InnoDB;
```
- 같은 주문의 여러 품목은 모두 포함한다 (orderId·productId 가 업무 단위).
- **한계**: 처음 구현한 엔티티에는 `nullable=false` 가 없어 Hibernate DDL 이 위 DDL 과 어긋났다(NULL 허용). 2026-11-20 에 `@Column(nullable=false)` 를 추가해 맞췼고 probe 테스트(`PurchaseInputProbeRestartTest`) 재실행으로 통과를 확인했다. 이 설계에서 허용한 유일한 코드 변경이다. UNIQUE 외 인덱스가 없다. Step 2 reader(`where snapshot_id = ? order by product_id`)와 processor(`where snapshot_id=? and product_id=?`)는 UNIQUE 의 선두 컬럼 `snapshot_id` 를 탈 수 있지만 `order by product_id` 는 정렬이 필요하다. 실습 데이터 규모에선 문제 없고, 측정 없이 인덱스를 늘리지 않는다.

<a id="d3"></a>
### D3. `weekly_ranking_revisions` (회차 = snapshot 정보) → PK `snapshot_id`(문자열 업무 식별자), status 는 CALCULATING / COMPLETED 두 값, BaseEntity 미상속

- **선택지**: PK 를 (a) BaseEntity `id` 로 두고 `snapshot_id` UNIQUE (b) `snapshot_id` 자체를 PK · status 에 (c) FAILED 포함 (d) CALCULATING/COMPLETED 만
- **선택**: (b) + (d).

```sql
CREATE TABLE weekly_ranking_revisions (
  snapshot_id            VARCHAR(64)  NOT NULL,
  period_start           DATE         NOT NULL,   -- UTC 월요일
  revision               INT          NOT NULL,
  status                 VARCHAR(20)  NOT NULL,   -- CALCULATING | COMPLETED
  frozen_at              DATETIME(6)  NOT NULL,   -- Step 1 의 Clock.now (UTC)
  input_count            INT          NOT NULL,   -- 복사된 입력 행 수
  expected_product_count INT          NOT NULL,   -- distinct product_id 수
  input_checksum         CHAR(64)     NOT NULL,   -- D13
  result_checksum        CHAR(64)     NULL,       -- Step 3 에서 채움
  created_at             DATETIME(6)  NOT NULL,
  updated_at             DATETIME(6)  NOT NULL,
  PRIMARY KEY (snapshot_id),
  UNIQUE KEY uk_wrr_period_revision (period_start, revision)
) ENGINE=InnoDB;
```

- **이유 (PK 가 문자열인 것)**: 과제 전체가 `snapshotId` 를 중심으로 설명하고, 하위 테이블(D2·D4)·pointer(D5)·API 응답(D14)·로그가 모두 이 값으로 참조한다. 숫자 id 를 따로 두면 참조마다 조인이 하나 늘고 "snapshot_id 가 곧 회차"라는 뜻이 흐려진다. 업무 키가 행의 정체인 두 테이블(회차 = `snapshot_id`, 공개 pointer = `period_start`, D5)은 BaseEntity 를 상속하지 않고 그 키를 PK 로 둔다. `created_at`·`updated_at` 은 직접 둔다(`deleted_at` 은 없음 — 회차는 지우지 않는다, R7).
- **이유 (FAILED 없음)**: 실행 실패는 Batch metadata(`BATCH_JOB_EXECUTION.STATUS`)가 이미 기록한다. 업무 테이블에 FAILED 를 중복 기록하면 "공개 commit 뒤 metadata 저장 실패" 같은 상황에서 둘이 어긋난다. 그래서 역할을 나눈다: **회차 status = 업무 사실(계산 중 / 완성)**, **JobExecution status = 실행 사실**. 둘을 함께 읽어 판단한다.
- **규칙**: 행이 존재한다 ⇔ Step 1 이 입력 복사와 **같은 TX** 로 commit 했다 (D9). 반쪽 snapshot 은 행이 없다.
- `expected_product_count` 는 Step 1 이 저장한다(고정 시점의 사실). Step 3 의 완성 검증(행 수 == 이 값)에 쓴다. 대안 "Step 3 에서 입력을 다시 세기"는 결과는 같지만 "입력을 고정했다"는 뜻과 덜 맞고, 검증 기준이 검증 대상과 같은 시점에 계산되는 꼴이 된다.
- **한계**: 회차 상태만 보면 "실패해서 멈춘 CALCULATING" 과 "지금 계산 중인 CALCULATING" 을 구분할 수 없다. 구분은 JobExecution 을 봐야 한다(의도한 역할 분리).

<a id="d4"></a>
### D4. `weekly_ranking_scores` (점수 stage + 완성 ranking 합침) → BaseEntity 상속, UNIQUE (snapshot_id, product_id), `ranking` NULL 허용, 불변 규칙 R1~R7

- **선택지**: (a) stage 테이블과 완성 ranking 테이블을 따로 (과제 표 그대로) (b) 한 테이블에 `score` 와 `ranking` 컬럼, ranking 은 Step 3 이 채움
- **선택**: (b).

```sql
CREATE TABLE weekly_ranking_scores (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  snapshot_id VARCHAR(64) NOT NULL,
  product_id  BIGINT      NOT NULL,
  score       BIGINT      NOT NULL,               -- 절대값 (quantity 합)
  ranking     INT         NULL,                   -- Step 3 이 한 TX 로 채움. `rank` 는 MySQL 8 예약어
  created_at  DATETIME(6) NOT NULL,
  updated_at  DATETIME(6) NOT NULL,
  deleted_at  DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wrs_snapshot_product (snapshot_id, product_id),   -- upsert 키
  KEY idx_wrs_snapshot_ranking (snapshot_id, ranking)             -- API 읽기용
) ENGINE=InnoDB;
```
- `score BIGINT`: 합계 컬럼은 입력(`quantity INT`)보다 넉넉히 둔다.
- **불변 규칙** (코드 주석·테스트 이름에 이 번호를 그대로 쓴다):

| 규칙 | 내용 | 지키는 곳 |
|---|---|---|
| R1 | 행 존재 ⇔ Step 2 가 그 상품 점수를 commit 했다. `score` 는 절대 NULL 이 아니다 | Writer upsert + NOT NULL |
| R2 | Step 2 는 `score` 만 쓴다. `ranking` 은 건드리지 않는다 | Writer SQL 에 ranking 없음 |
| R3 | `ranking` 은 Step 3 이 그 snapshot 의 **모든 행에 한 TX** 로 쓴다. 회차 COMPLETED 전환·pointer 갱신과 같은 TX | D11 |
| R4 | 회차가 COMPLETED 이면: 행 수 = `expected_product_count`, 모든 `ranking` NOT NULL, 1..N 연속, 순서는 `score DESC, product_id ASC` | D11 검증 + 테스트 |
| R5 | API 는 pointer → COMPLETED 회차 → 그 snapshot_id 행 경로로만 읽는다. snapshot_id 를 직접 받는 조회 경로는 없다 | D14 |
| R6 | Step 3 재실행은 같은 `ranking` 값을 다시 쓴다 (멱등) | D11, ROW_NUMBER 결정성 |
| R7 | 행은 지우지 않는다. 새 revision 은 새 snapshot_id 로 새 행 | D15 |

- **이유 (합친 것)**: API 가 stage 행을 읽을 경로가 구조적으로 없다(R5: pointer 가 가리키는 COMPLETED 회차의 snapshot_id 만 읽음). 중간 결과 노출을 막는 핵심은 테이블 분리가 아니라 "순위 + COMPLETED + pointer 가 한 TX"(R3)다. 분리하면 Step 3 가 stage → 완성 테이블로 행을 복사해야 하고(쓰기 2배), 두 테이블의 (snapshot_id, product_id) 정합을 또 검증해야 한다.
- **한계**: `ranking NULL` 인 행이 "계산 중" 과 "완성" 테이블에 섞여 있다. R4 가 깨지는 버그(예: Step 3 가 일부 행만 갱신)를 테이블 구조가 막아 주지 않으므로 R4 테스트가 필수다 (8절).

<a id="d5"></a>
### D5. `weekly_ranking_publications` (공개 pointer) → 별도 테이블, PK `period_start`, `revision <= 새 revision` 조건부 갱신

- **선택지**: (a) 상태 기반 — 공개본 = `max(revision) where status = COMPLETED` (테이블 없음) (b) 회차 행(D3)에 `published_at` 컬럼 (c) 별도 pointer 테이블
- **선택**: (c).

```sql
CREATE TABLE weekly_ranking_publications (
  period_start DATE        NOT NULL,
  snapshot_id  VARCHAR(64) NOT NULL,
  revision     INT         NOT NULL,
  published_at DATETIME(6) NOT NULL,   -- Step 3 의 Clock.now (UTC)
  updated_at   DATETIME(6) NOT NULL,
  PRIMARY KEY (period_start)
) ENGINE=InnoDB;
```
- **이유**:
  - 과제 어휘(공개 pointer · 조건부 갱신 · 현재 pointer 검증)와 코드가 1:1 로 대응한다.
  - "COMPLETED 인데 공개되지 않은 회차"(D8 의 거절 결말)를 표현할 수 있다. (a) 는 COMPLETED 면 곧 공개라서 이 상태가 없고, 오래된 실행이 COMPLETED 로 끝나는 순간 max 가 바뀌지 않도록 하려면 결국 조건이 필요하다.
  - 운영에서 이전 revision 으로 되돌리기(pointer 만 UPDATE)가 가능하다. (b) 도 가능하지만 "기간당 공개본은 하나"라는 제약을 (b) 는 UNIQUE 로 표현하기 어렵다(`published_at IS NOT NULL` 조건부 UNIQUE 는 MySQL 에 없음). (c) 는 PK 로 그대로 표현된다. 그래서 이 테이블도 회차(D3)와 같이 업무 키 `period_start` 가 행의 정체이므로 BaseEntity 를 상속하지 않는다.
- **비용**: 테이블 1개 추가, 공개 TX 가 두 테이블(D3·D5)을 쓴다.
- **갱신 규칙** (Step 3 의 (5) 단계, D11. 두 단계 모두 Step 3 의 **같은 TX** 안):
  1. `UPDATE weekly_ranking_publications SET snapshot_id=?, revision=?, published_at=?, updated_at=? WHERE period_start=? AND revision <= ?` 를 실행하고 affected rows 를 본다. 1건이면 끝(갱신 또는 같은 revision 재기록).
  2. 0건이면 `SELECT revision FROM weekly_ranking_publications WHERE period_start=?` 로 존재를 확인한다. 행이 **있으면** 현재 revision 이 더 크다는 뜻이므로 **거절**(D8). 행이 **없으면** `INSERT` 한다.
  - `<=` 로 **같은 revision 의 재실행을 통과**시킨다. Step 3 가 두 번 실행돼도 같은 pointer 로 수렴(R6).
  - 같은 revision 재실행에서는 `published_at` 을 유지한다(`CASE WHEN revision = 새 revision THEN published_at ELSE now END`). 공개 시각은 처음 공개된 때를 뜻해야 하므로 수렴 재실행이 바꾸지 않는다.
  - 한 문장 `INSERT ... ON DUPLICATE KEY UPDATE` 로 쓰지 않는 이유: MySQL 은 그 UPDATE 절에 WHERE 조건을 둘 수 없어 `IF(revision <= VALUES(revision), ...)` 꼴이 되고, 거절 여부를 affected rows 로 읽기 어렵다.
- **한계**: pointer 와 회차 두 곳에 `revision` 이 있다. 어긋남은 테스트(R4·D8)로만 잡는다. 또 같은 기간의 **첫 공개가 동시에** 두 실행에서 일어나면 둘 다 UPDATE 0건 → SELECT 없음 → INSERT 로 가고, PK(`period_start`) 위반으로 한쪽이 실패한다. 과제가 같은 기간의 계산을 직렬 실행으로 전제하므로 이 실패는 그대로 수용한다(7-2절 "배치 다중 인스턴스").

<a id="d6"></a>
### D6. 충돌 규칙 → Step 1 시작 시 같은 `snapshot_id` 또는 같은 `(period_start, revision)` 행이 이미 있으면 무조건 실패 (Job FAILED)

- **선택지**: (a) 무조건 CONFLICT 실패 (b) 기존 행의 입력 checksum 이 지금 복사할 입력과 같으면 통과(멱등 재실행 허용)
- **선택**: (a).
- 정상 재시작에서는 Step 1 이 COMPLETED 라 재실행되지 않는다(`allowStartIfComplete` 기본 false). 따라서 이 충돌이 나는 경우는 둘뿐이다: ① revision 을 재사용한 운영 실수 ② Step 1 의 TX commit 직후 Batch metadata 저장이 실패해 Step 1 이 "미완료"로 남은 경우. ② 는 운영자가 metadata 와 회차 행을 확인하고 새 revision 으로 다시 실행한다 — 한계로 기록 (7-2절).
- **(b) 를 쓰지 않은 이유**: "같은 입력인지" 를 확인하려면 원본을 다시 읽어야 하고, 그 사이 늦은 구매가 오면 checksum 이 달라져 어차피 실패한다. 즉 완전한 멱등이 되지 못하면서 코드만 늘어난다. "실패를 드러내고 원인을 고친다" 원칙에 맞춰 단순히 실패한다.
- DB 제약(D3 의 PK·UNIQUE)이 최후 방어이고, 조회 후 INSERT 가 먼저 실패를 드러낸다 (W6 D11 "조회 먼저 + UNIQUE 는 최후 방어" 와 같은 태도).

<a id="d7"></a>
### D7. FK → 두지 않는다 (기존 관례: 집합 밖 참조는 평문 ID)

- 코드 확인 결과: `@JoinColumn`·`@OneToMany` 는 `apps/commerce-api/src/main/java/com/loopers/domain/order/Order.java:49-51` (`Order` → `OrderItem`, 같은 집합 안)에만 있다. 집합 밖 참조는 모두 평문 ID 다: `Product.brandId`(`apps/commerce-api/src/main/java/com/loopers/domain/product/Product.java:27`), `ProductPurchaseActivity.orderId/productId`(`...ProductPurchaseActivity.java:23-25`), `OrderConfirmedOutbox.orderId`. `FOREIGN KEY` DDL 문자열은 저장소에 없음(찾아본 위치: `grep -rn "JoinColumn\|ManyToOne\|OneToMany\|OneToOne\|foreignKey\|FOREIGN KEY" apps/*/src/main modules/*/src/main`).
- 9주차 테이블은 모두 다른 집합(상품은 commerce-api 소유, 입력 원본은 streamer 소유)을 참조하므로 관례대로 FK 없이 평문 ID 만 둔다. `scores.snapshot_id → revisions.snapshot_id` 도 FK 없이 둔다(네 테이블은 같은 집합이지만 Step 2 의 chunk 쓰기가 회차 행에 락을 걸지 않도록).
- **한계**: 고아 행(회차 없는 점수 행)을 DB 가 막지 않는다. Step 1 이 회차 행을 먼저 commit 한 뒤에만 Step 2 가 돌므로 정상 흐름에선 생기지 않는다.

<a id="d8"></a>
### D8. 오래된 실행이 뒤늦게 공개를 시도할 때 → 회차는 COMPLETED, pointer 는 그대로, WARN 로그, Job 은 COMPLETED

- **선택지**: 조건부 갱신이 거절됐을 때 Job 을 (a) FAILED 로 끝냄 (b) COMPLETED 로 끝내고 pointer 만 건너뜀 (c) 별도 ExitStatus(예: `COMPLETED_NOT_PUBLISHED`)
- **선택**: (b). ExitStatus 는 기본 `COMPLETED` 그대로 두고, `exitDescription` 에 `NOT_PUBLISHED: newer revision rN already published` 형식으로 남긴다(N = 현재 공개된 revision). 같은 내용을 WARN 로그로도 남긴다. 커스텀 ExitStatus 는 만들지 않는다.
- **이유**: 계산은 정상이고 pointer 경쟁에서 밀린 것뿐이다. FAILED 는 "고쳐서 재시작하라"는 신호인데 재시작할 이유가 없다(재시작해도 같은 거절). D5-(c) 덕분에 "COMPLETED but not published" 상태가 데이터로 남아 사후 확인이 된다.
- 순서: Step 3 TX 안에서 (4) 회차 COMPLETED 는 수행하고, (5) 갱신이 거절되면 WARN 로그 후 정상 commit. 회차 status 와 pointer 가 한 TX 인 것은 변하지 않는다(R3).
- **한계**: 운영자가 로그를 보지 않으면 "r1 이 COMPLETED 인데 공개는 r2" 를 알아채기 어렵다. 알림은 범위 밖.

---

## 4. Step 설계 (D9 ~ D11)

공통
- Job 설정 클래스 위치는 기존 관례(`com.loopers.batch.job.demo.DemoJobConfig`)를 따라 `com.loopers.batch.job.ranking.WeeklyRankingJobConfig` 1개 + Tasklet 2개(`batch/job/ranking/step/`) 로 둔다. 구현 직전에 파일 목록을 먼저 제시한다(`docs/week9/reference/03-implementation-plan.md`). `@ConditionalOnProperty(name="spring.batch.job.name", havingValue="weeklyRanking")` 로 Demo 와 공존한다 (`DemoJobConfig.java:19`, `application.yml:16` `${job.name:NONE}`).
- TX 매니저는 컨텍스트의 `PlatformTransactionManager` 빈(런타임 클래스 `JpaTransactionManager`, `02-probe-result.md` 3절)을 세 Step 모두에 지정한다. `ResourcelessTransactionManager`(`DemoJobConfig.java:44`)는 쓰지 않는다.
- JobRepository 는 Boot 자동 구성 그대로. 같은 DataSource(`mySqlMainDataSource`)임을 probe 로 확인했다 (`02-probe-result.md` 4절).
- 순수 계산(기간 경계, snapshotId 파싱, checksum 직렬화)은 `com.loopers.domain.ranking` 에 두어 단위 테스트한다. 클래스는 1~2개("없으면 무엇이 깨지는가" 기준).

<a id="d9"></a>
### D9. Step 1 입력 고정 → `freezeWeeklyInputStep` (Tasklet, 한 TX, READ COMMITTED, `INSERT ... SELECT` 한 문장)

- **선택지**: 복사 방법 (a) `INSERT ... SELECT` 한 문장 (b) JPA 로 읽어 엔티티로 저장 (c) 페이지 단위 복사 · 범위 고정 (d) `confirmed_at` 범위 (e) `max(id)` 저장
- **선택**: (a) + (d).
- 한 TX 안에서 순서대로:
  1. D1 검증(`snapshotId` 날짜 == `periodStart`), D6 충돌 검사(회차 행 조회).
  2. `INSERT INTO weekly_ranking_inputs (snapshot_id, order_id, product_id, quantity, confirmed_at, created_at, updated_at) SELECT :snapshotId, order_id, product_id, quantity, confirmed_at, :now, :now FROM product_purchase_activity WHERE confirmed_at >= :start AND confirmed_at < :end AND deleted_at IS NULL` — 시작 포함·끝 제외. `deleted_at IS NULL` 은 저장소 관례(소프트 삭제 행은 없는 행으로 취급)를 따른 것이며, W6 consumer 에 삭제 경로가 없어 현재는 항상 NULL 이다. `:start`/`:end` 는 UTC `LocalDateTime` 바인딩 (D12).
  3. 복사된 행을 **같은 TX 에서** 다시 읽어 `input_count`, `expected_product_count`(distinct product_id), `input_checksum`(D13) 계산.
  4. 회차 행 INSERT: status `CALCULATING`, `frozen_at` = Clock.now (D12).
- 실패하면 전체 rollback → 반쪽 snapshot 도, 회차 행도 없다. 완료 뒤 재시작에서는 재실행하지 않는다(`allowStartIfComplete` 기본 false 유지).
- **격리 수준 READ COMMITTED (이 Step 만)**: Step 의 `transactionAttribute` 로 지정한다. MySQL 기본 REPEATABLE READ 에서 `INSERT ... SELECT` 는 원본 행에 공유(next-key) 락을 걸어 그 사이 consumer 의 구매 활동 INSERT 를 막을 수 있다. READ COMMITTED 면 SELECT 부분이 일관된 읽기(락 없음)로 동작한다 (한계 참고). 한 문장 SELECT 라 "읽기 시점" 은 그 문장 하나의 snapshot 으로 고정된다.
- **(e) `max(id)` 를 쓰지 않는 이유**: 번호를 먼저 할당받은 TX 가 늦게 commit 할 수 있어, `max(id)` 를 저장해도 "그 시점까지 commit 된 행" 이 고정되지 않는다.
- **(b) 를 쓰지 않는 이유**: streamer 의 엔티티는 batch classpath 에 없다(`01-survey` 6-4). 테이블 이름으로 직접 읽는 SQL 이 모듈 의존을 늘리지 않는다. 그 대신 batch 단독 테스트에서는 `product_purchase_activity` 테이블이 생성되지 않으므로 테스트가 DDL 을 직접 실행해야 한다 (8절).
- **한계**: READ COMMITTED 에서 `INSERT ... SELECT` 가 원본에 락을 걸지 않는 것은 `binlog_format=ROW` 일 때다. Testcontainers `mysql:8.0` 기본값은 ROW 이지만 운영 MySQL 설정은 모른다 [확인 필요]. 데이터가 커지면 한 문장 복사의 TX 가 길어진다. 페이지 복사(c) 로 바꾸려면 "같은 일관된 읽기 시점" 을 따로 보장해야 하는데 이번 범위에서는 하지 않는다. `confirmed_at` 에 인덱스가 없어 원본 테이블을 풀스캔한다 — 측정 없이 주장하지 않고 인덱스도 추가하지 않는다.

<a id="d10"></a>
### D10. Step 2 점수 계산 → `calculateWeeklyScoresStep` (Chunk 2, `JdbcCursorItemReader<Long>`, 합계 Processor, 절대값 upsert Writer)

- **선택지**: Reader (a) `JdbcCursorItemReader` (b) `JdbcPagingItemReader` (c) 메모리 리스트 · Writer (d) 절대값 upsert (e) 증분 `+=`
- **선택**: (a) + (d). chunk size 2 는 과제 실험값이며 처리량 측정값이 아니다.

| 역할 | 구성 | 근거 |
|---|---|---|
| Reader | `JdbcCursorItemReaderBuilder<Long>`: name `weeklyProductIdReader`, `saveState(true)`, `select distinct product_id from weekly_ranking_inputs where snapshot_id = ? order by product_id`, `@StepScope` 로 `#{jobParameters['snapshotId']}` 바인딩 | probe 와 같은 구성 (`PurchaseInputProbeRestartTest.java` helper) |
| Processor | `select sum(quantity) from weekly_ranking_inputs where snapshot_id = ? and product_id = ?` → `(productId, score)` | **frozen 입력에서만** 계산. 현재 활동 DB 를 읽지 않는다 |
| Writer | `INSERT INTO weekly_ranking_scores (snapshot_id, product_id, score, created_at, updated_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE score = VALUES(score), updated_at = VALUES(updated_at)` | 절대값 upsert, `ranking` 미접촉 (R2). 같은 입력을 다시 처리해도 `score = total` 로 수렴 |

- **chunk commit 과 reader checkpoint 가 같은 TX** — probe 로 확인한 사실: 첫 실행 readCount 3 / writeCount 2 / commitCount 1 / rollbackCount 1, 영속 ExecutionContext `purchaseProductProbe.read.count = 2`, 재실행은 상품 9 부터 readCount 1 (`02-probe-result.md` 1~2절). 실제 reader 상태 키는 `<reader name>.read.count` 이고 그 값은 "항목 수"가 아니라 "reader 호출 위치"(끝 확인 read 포함, 재실행 후 4)임을 코드 주석에 남긴다.
- **(b) paging reader 를 쓰지 않는 이유**: 데이터가 작아 메모리 이점이 없고, paging 은 정렬·query·checkpoint 검증 비용이 따로 든다. cursor 는 `order by product_id` 하나로 유일·안정 순서가 보장된다.
- **(e) 증분을 쓰지 않는 이유**: `score += delta` 는 같은 항목을 재처리하면 두 번 증가하지만, 고정 입력의 합을 `score = total` 로 저장하면 같은 값으로 수렴한다. chunk commit 직전 실패로 같은 상품이 재처리돼도 (d) 는 `5 → 5` 다.
- 잘못된 수량(합계 ≤ 0 등)은 skip 하지 않고 예외로 Step 을 실패시킨다.
- **한계**: Step 이름 `calculateWeeklyScoresStep` 의 접미사 `Step` 은 과제 예시(`calculateWeeklyScores`)·기존 Demo(Step 이름 `demoSimpleTask`, `DemoJobConfig.java:24`) 어느 쪽과도 같지 않다. 결정값을 따르되 관례 근거는 약하다. processor 가 상품마다 `SUM` SQL 1회를 실행한다(쿼리 수가 상품 수에 비례). 상품 수가 수천이면 느리지만 측정 전이라 바꾸지 않는다. 대안은 reader 가 `group by product_id` 로 합계까지 읽는 것인데, 그러면 "상품 1개 = item 1개" 는 유지되지만 processor 가 비어 과제의 역할 분리 설명과 멀어진다. 측정 뒤 재검토한다.

<a id="d11"></a>
### D11. Step 3 공개 → `openWeeklyRankingStep` (Tasklet, 한 TX, 순서 (1)~(5))

- 한 TX 안에서 순서대로:
  0. 회차 행을 `SELECT ... FROM weekly_ranking_revisions WHERE snapshot_id = ? FOR UPDATE` 로 잠가 읽는다. 같은 snapshot 의 공개 단계가 겹치지 않게 직렬화하는 방어선이다(Batch 가 같은 JobInstance 의 동시 실행을 막지만 비용이 거의 없어 하나 더 둔다). 행이 없으면 예외.
  1. `select count(*) from weekly_ranking_scores where snapshot_id = ?` == 회차의 `expected_product_count` 검증. 다르면 예외(Job FAILED). 0 == 0 이면 통과(구매 0건 완성본).
  2. `UPDATE weekly_ranking_scores s JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC) rn FROM weekly_ranking_scores WHERE snapshot_id = ?) r ON s.id = r.id SET s.ranking = r.rn, s.updated_at = ?` — 모든 행을 한 문장으로 (R3). 정렬 키가 유일(product_id)하므로 재실행 시 같은 값 (R6).
  3. 결과 checksum 계산(D13) → 회차 `result_checksum` 저장. 재실행이라 `result_checksum` 이 이미 있는데 지금 값과 **다르면** 예외(Job FAILED) — 같은 snapshot 에서 다른 결과가 나왔다는 뜻으로 R6 위반이다. 순위 1..N 연속·유일성도 이 시점에 함께 검증한다(R4).
  4. 회차 `status = COMPLETED`, `updated_at` 갱신. 이미 COMPLETED 면 같은 값으로 다시 쓴다(재실행 수렴).
  5. pointer 조건부 갱신(D5). 거절이면 D8.
- **공개 commit 뒤 Batch metadata 저장 실패** → 재시작하면 Step 3 가 다시 실행된다. (2) 는 같은 값, (4) 는 이미 COMPLETED, (5) 는 `<=` 로 통과 → 결과 불변 (R6). 이 시나리오를 테스트로 고정한다 (8절).
- 기존 공개본을 먼저 지우고 나중에 넣는 두 TX 방식은 쓰지 않는다. 공개는 pointer 한 행의 UPDATE/INSERT 뿐이다.
- **한계**: (2) 의 "바뀐 행 수 == expected" 와 D5 6a 의 "affected rows 1" 검증은 Connector/J 기본값(`useAffectedRows=false`, 매치된 행 수 반환)에 의존한다. `jpa.yml`·테스트 fixture 의 jdbc-url 에 이 파라미터는 없다(확인 2026-11-20). 누군가 `useAffectedRows=true` 를 켜면 같은 값을 다시 쓰는 재실행에서 0 이 돌아와 3단계·6a 검증이 깨진다. (2) 의 `UPDATE ... JOIN (SELECT ... ROW_NUMBER())` 는 MySQL 8.0 윈도 함수에 의존한다(Testcontainers `mysql:8.0`, `modules/jpa/src/testFixtures/java/com/loopers/testcontainers/MySqlTestContainersConfig.java:13`). 상품 수가 많으면 한 UPDATE 가 길어진다.

---

## 5. 시간 규칙 (D12) · checksum 규칙 (D13)

<a id="d12"></a>
### D12. 시간 → 저장·계산은 UTC, 주간 = `[월요일 00:00Z, 다음 월요일 00:00Z)`, JDBC 바인딩은 UTC `LocalDateTime`, batch 에 `Clock` 빈 추가

- 주간 소속은 `confirmed_at`(UTC) 기준 `[periodStart 00:00Z, periodStart+7일 00:00Z)`. 시작 포함·끝 제외.
- DB 저장: Hibernate 가 `ZonedDateTime` 을 UTC 로 정규화해 저장한다 (`modules/jpa/src/main/resources/jpa.yml:11-12` `timezone.default_storage: NORMALIZE_UTC`, `jdbc.time_zone: UTC`). 따라서 `product_purchase_activity.confirmed_at` 의 컬럼 값은 UTC 벽시계다.
- JDBC(`JdbcTemplate`)로 시각을 **바인딩할 때는 UTC `LocalDateTime`** 으로 넘긴다. `Timestamp`/`Instant` 는 드라이버가 JVM 기본 시간대(Asia/Seoul, `apps/commerce-batch/src/main/java/com/loopers/CommerceBatchApplication.java:17`)로 변환할 수 있다. probe 에서 `LocalDateTime` 바인딩으로 컬럼 값이 UTC 그대로 저장됨을 확인했다 (`02-probe-result.md` 5절). Step 1 의 `:start`/`:end`, `frozen_at`, `published_at` 모두 이 규칙.
- **Clock 빈**: commerce-batch 에는 `Clock` 빈이 없다 (`01-survey` 1-4). streamer 의 `apps/commerce-streamer/src/main/java/com/loopers/config/ClockConfig.java:11-17`(`@Configuration` + `@Bean Clock clock() { return Clock.systemDefaultZone(); }`) 와 **같은 패턴으로, 이름만 다른 `com.loopers.config.BatchClockConfig` 를 batch 에 추가**한다. `frozen_at`·`published_at` 은 `ZonedDateTime.now(clock).withZoneSameInstant(UTC)` 에서, 테스트는 고정 Clock 으로 교체(`@MockitoBean` 또는 `@Primary` 빈, api 테스트 관례 `01-survey` 7-5).
  - 이름을 `ClockConfig` 로 두지 않는 이유: §7 연결 테스트(crossAppTest)는 streamer jar 와 batch jar 를 한 classpath 에 올린다. 같은 FQCN `com.loopers.config.ClockConfig` 가 둘이면 하나만 로드되어 어느 쪽이 쓰이는지 classpath 순서에 좌우된다. 이름을 달리 두면 두 빈이 모두 보이므로 `CrossAppTestConfig.java:43-49` 의 REGEX 제외 목록에 `com\.loopers\.config\.BatchClockConfig` 를 **추가해야** 한다 (8절 §7 연결 테스트의 build·설정 변경 목록에 포함).
- API 응답: `periodStart`·`periodEnd` 는 `YYYY-MM-DD`, `frozenAt` 은 ISO-8601 UTC(`Z`) (D14).
- **한계**: `created_at`·`updated_at` 은 `BaseEntity` 가 `ZonedDateTime.now()`(`BaseEntity.java:44,53`)로 찍어 Clock 을 쓰지 않는다. 기존 관례이므로 바꾸지 않는다.

<a id="d13"></a>
### D13. checksum → SHA-256 소문자 hex 64자, 입력은 `orderId TAB productId TAB quantity TAB confirmedAt` + LF, 결과는 `productId TAB score TAB ranking` + LF

| 종류 | 정렬 | 행 직렬화 | 저장 위치 |
|---|---|---|---|
| 입력 checksum | `order_id ASC, product_id ASC` | `orderId \t productId \t quantity \t confirmedAt(UTC, 고정 6자리 `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`, 예 2026-08-17T01:00:00.000000Z)` + `\n`. 마지막 행도 `\n`. 0행이면 빈 문자열의 SHA-256 | `revisions.input_checksum` (Step 1) |
| 결과 checksum | `ranking ASC` | `productId \t score \t ranking` + `\n`. 마지막 행도 `\n` | `revisions.result_checksum` (Step 3) |

- 실행 ID·실행 시각·`snapshotId` 는 checksum 에 넣지 않는다. 같은 입력을 다른 revision 으로 다시 계산하면 입력·결과 checksum 이 같아야 "같은 결과" 를 증명할 수 있다.
- `confirmedAt` 직렬화 형식은 **UTC 고정 6자리** `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'` 다. 이유: DB `DATETIME(6)` 과 정밀도가 1:1 이라 정보 손실이 없고, 길이가 고정이라 손으로 기대값을 계산하기 쉽다. §6 fixture 의 첫 행 직렬화 예: `42\t7\t2\t2026-08-17T01:00:00.000000Z\n`.
  - **한계(비교)**: `DateTimeFormatter.ISO_INSTANT` 는 소수부를 값마다 다른 길이(없음/3/6/9자리)로 붙여 같은 시각도 표현이 흔들릴 수 있어 채택하지 않았다. 고정 6자리는 나노초 정밀도 입력이 오면 절단하지만 DB 가 이미 마이크로초까지만 저장하므로 실제 손실은 없다.
- 테스트는 checksum 비교 **와** 기대 점수·순위 직접 assert 를 둘 다 한다.
- **§6 fixture 기대값**: 입력 4행 `(42,7,2)`, `(42,8,1)`, `(43,7,3)`, `(44,9,5)` → reader item 3개(7, 8, 9) → 상품 7 = 5점 1위, 상품 9 = 5점 2위, 상품 8 = 1점 3위. 결과 직렬화: `7\t5\t1\n9\t5\t2\n8\t1\t3\n`.

---

## 6. API 계약 (D14) · 늦은 구매와 revision (D15)

<a id="d14"></a>
### D14. `GET /api/v1/rankings/weekly?periodStart=YYYY-MM-DD&limit=10` → pointer 경로 쿼리 2개(①pointer+회차 → ②점수), JDBC 읽기 전용, 삭제 상품 제외 뒤 limit

#### 요청

| 항목 | 위치 | 필수 | 기본값 | 규칙 |
|---|---|---|---|---|
| `periodStart` | query | 예 | — | `YYYY-MM-DD`, UTC 월요일. 아니면 400 |
| `limit` | query | 아니오 | 10 | 1 ~ 100. 밖이면 400 |
| `X-USER-ID` | header | **아니오** | — | 상품 목록 API 와 같게 받지 않는다. `ProductV1Controller.getProducts`(`apps/commerce-api/src/main/java/com/loopers/interfaces/api/product/ProductV1Controller.java:24-37`)는 `@RequestParam` 네 개만 받고 헤더가 없다. 헤더를 쓰는 곳은 주문·좋아요·포인트·결제·쿠폰 컨트롤러다(`grep -rn "X-USER-ID" apps/commerce-api/src/main`) |


#### 응답 (`ApiResponse<T>` 포장, `apps/commerce-api/src/main/java/com/loopers/interfaces/api/ApiResponse.java:3`)

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | `PUBLISHED` \| `NOT_PUBLISHED` | pointer 행 존재 여부 |
| `periodStart` / `periodEnd` | `YYYY-MM-DD` | 요청 값 / +7일 (끝 제외) |
| `snapshotId` | String \| null | 공개된 snapshot. NOT_PUBLISHED 면 null |
| `frozenAt` | `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC, 초 단위) \| null | 그 회차의 `frozen_at`. NOT_PUBLISHED 면 null. D13 의 6자리 형식은 checksum 전용이고 API 표시는 초 단위다 |
| `items[]` | 배열 | 공개 조건을 통과한 상품, `ranking ASC`, 최대 `limit` 개 |
| `items[].productId` / `productName` / `score` / `ranking` | Long / String / Long / Integer | `ranking` 은 **저장된 계산 순위** 그대로. 삭제 상품을 제외한 **뒤** limit 을 적용하므로 1·3위처럼 간격이 생길 수 있다 |

성공 예시 (상품 8 이 삭제된 경우):
```json
{ "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
  "data": { "status": "PUBLISHED", "periodStart": "2026-08-17", "periodEnd": "2026-08-24",
            "snapshotId": "weekly-20260817-r1", "frozenAt": "2026-08-24T00:10:00Z",
            "items": [ { "productId": 7, "productName": "상품 7", "score": 5, "ranking": 1 },
                       { "productId": 9, "productName": "상품 9", "score": 5, "ranking": 2 } ] } }
```
미공개 예시:
```json
{ "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
  "data": { "status": "NOT_PUBLISHED", "periodStart": "2026-08-10", "periodEnd": "2026-08-17",
            "snapshotId": null, "frozenAt": null, "items": [] } }
```
- `NOT_PUBLISHED`: 해당 `period_start` 에 pointer 행이 없을 때. HTTP 200 (404 가 아님 — "아직 없음" 은 정상 상태).
- `PUBLISHED` + 빈 `items`: 구매 0건 완성본. 두 상태를 구분한다.
- 새 revision 계산 중에는 이전 pointer 가 그대로 보인다 (D5·D15).

#### 오류 (코드는 `apps/commerce-api/src/main/java/com/loopers/support/error/ErrorType.java` 의 실제 상수)

| 상황 | HTTP | `errorCode` | 처리 위치 |
|---|---|---|---|
| `periodStart` 누락 | 400 | `BAD_REQUEST` (`ErrorType.java:12`) | `ApiControllerAdvice.handleBadRequest(MissingServletRequestParameterException)` (`.../interfaces/api/ApiControllerAdvice.java:42`) |
| `periodStart` 형식 오류·월요일 아님 | 400 | `BAD_REQUEST` | Facade/도메인에서 `CoreException(BAD_REQUEST)` → `ApiControllerAdvice.handle(CoreException)` (`:27-30`). 형식은 String 으로 받아 직접 파싱한다(기존 코드가 jakarta validation 을 쓰지 않고 도메인에서 `BAD_REQUEST` 를 던지는 관례와 일관. `LocalDate` 바인딩 + `MethodArgumentTypeMismatchException`(`:33`) 은 "월요일" 규칙을 같은 메시지로 담을 수 없어 쓰지 않음) |
| `limit` 1~100 밖 | 400 | `BAD_REQUEST` | 같은 방식 |
| 서버 오류 | 500 | `INTERNAL_ERROR` (`ErrorType.java:11`) | `ApiControllerAdvice` Throwable 처리 |

- `NOT_FOUND`(`ErrorType.java:14`)는 쓰지 않는다(미공개는 200 NOT_PUBLISHED). jakarta validation(`@Valid`)은 기존 코드가 쓰지 않으므로(`01-survey` 7-3) 수동 검증 + `CoreException` 관례를 따른다.

#### 읽기 경로

- **쿼리 2개, 같은 `readOnly` TX**:
  - ① `SELECT p.snapshot_id, p.revision, r.frozen_at FROM weekly_ranking_publications p JOIN weekly_ranking_revisions r ON r.snapshot_id = p.snapshot_id AND r.status = 'COMPLETED' WHERE p.period_start = ?` → 0행이면 `NOT_PUBLISHED`, 1행이면 `snapshotId`·`frozenAt` 확보.
  - ② `SELECT product_id, score, ranking FROM weekly_ranking_scores WHERE snapshot_id = ? ORDER BY ranking ASC` — ①에서 얻은 `snapshotId` 로 읽는다.
  - ②가 ①의 `snapshotId` 를 조건으로 쓰므로, ①과 ② 사이에 pointer 가 다른 revision 으로 바뀌어도 서로 다른 snapshot 의 행이 섞이지 않는다(그 응답은 ①시점의 공개본을 일관되게 보여 준다). 구매 0건 완성본은 ①이 1행·②가 0행이라 `PUBLISHED` + 빈 `items` 로 자연히 구분된다.
- **commerce-api 는 이 테이블들에 `@Entity` 를 두지 않는다.** 읽기는 `JdbcTemplate`(또는 네이티브 쿼리)만. 이유: api 의 test/local `ddl-auto: create` 가 batch 와 다른 정의로 테이블을 다시 만드는 사고를 막는다 — 테이블 정의는 batch 가 소유하고 api 는 읽기 전용. 비용: api 테스트가 이 테이블의 DDL 을 직접 실행해야 한다 (8절).
- **삭제 상품**: 점수 행의 productId 목록을 **기존 공개 조건**으로 거른 **뒤** limit 을 적용한다. 상품명 등 표시 정보는 `ProductService.findActiveProducts(Collection<Long>)`(`apps/commerce-api/src/main/java/com/loopers/domain/product/ProductService.java:103-107`: `findAllByIdIn` 후 `deletedAt == null` 필터) 를 재사용한다. 포트 `ProductRepository.findAllByIdIn`(`.../domain/product/ProductRepository.java:13`), JPA `ProductJpaRepository.findAllByIdIn`(`.../infrastructure/product/ProductJpaRepository.java:11`). 저장된 `ranking` 은 유지하므로 1·3위처럼 간격이 생길 수 있다.
  - 구현 순서: 점수 행을 `ranking ASC` 로 **전부** 읽고 → 활성 상품으로 거르고 → 앞에서 `limit` 개. 읽는 행 수에 상한을 두지 않는다. 삭제 상품을 제외한 **뒤** limit 을 적용해야 하므로 전부 읽어야 정확하다(상한을 두면 삭제가 많은 주에 limit 보다 적게 돌려줄 수 있다). 상품 수가 많을 때의 비용은 한계로 두고 측정 뒤 재검토한다.
- **계층** (brand 단건 GET 과 같게, `01-survey` 7-4. 2026-11-20 구현 이름): `interfaces/api/ranking/RankingV1Controller` + `RankingV1Dto` → `application/ranking/WeeklyRankingFacade`(`@Transactional(readOnly=true)`, limit 검증·삭제 제외·limit 적용) + `WeeklyRankingInfo` → `domain/ranking/WeeklyRankingReader`(포트, `findPublished`·`findRankedScores`) + `WeeklyPeriod`(월요일 규칙, batch 의 `WeeklySnapshotId` 와 같은 규칙을 작게 다시 쓴 것) + record `PublishedSnapshot`·`RankedScore` → `infrastructure/ranking/WeeklyRankingJdbcReader`(JdbcTemplate 구현). 도메인 서비스는 brand 처럼 두지 않는다. 클래스 수는 "없으면 무엇이 깨지는가" 기준으로 줄인다(ArchUnit 상 interfaces → domain 직접 의존은 허용되므로 Info 클래스는 두지 않음).
- **한계**: api 와 batch 가 같은 테이블을 서로 다른 코드(엔티티 vs SQL 문자열)로 알고 있어 컬럼 변경 시 두 곳을 고쳐야 한다. 이번엔 테이블 4개·컬럼 수가 적어 감수한다.

<a id="d15"></a>
### D15. 늦은 구매와 revision → 고정 뒤 도착한 구매는 현재 snapshot 에 넣지 않고, 새 `snapshotId`(rN+1) 로 명시 재계산

| 구분 | 재시작 | 재계산 |
|---|---|---|
| 뜻 | 같은 입력을 멈춘 자리부터 이어 간다 | 입력이 바뀌었을 수 있음을 명시하고 새로 계산한다 |
| JobParameters | **같음** (`periodStart`, `snapshotId` 동일) | `snapshotId` 의 revision 만 +1 |
| JobInstance | 같음 (새 JobExecution) | **새 JobInstance** |
| Step 1 | 재실행 안 함 (완료 상태) | 새 회차 행 + 새 입력 복사 |
| 입력·점수 행 | 그대로 | 새 snapshot_id 로 새 행 (R7) |
| 공개 | 이전 pointer 유지 → Step 3 에서 갱신 | 공개 전엔 이전 revision, 공개 후 새 revision |

- snapshot 고정 뒤 같은 주의 늦은 구매가 `product_purchase_activity` 에 들어와도 현재 입력(`weekly_ranking_inputs`)·재시작 결과는 바뀌지 않는다 (Step 2 는 복사본만 읽음, D10).
- 늦은 구매도 **원래 주**(confirmed_at 기준)에 속한다. 이번 주 점수에 더하지 않는다.
- revision 은 운영자가 명시적으로 올린다(자동 감지·자동 재계산 없음).
- W7 이벤트 재발행 시 구매 활동은 W6 UNIQUE(`uk_ppa_order_product`, `ProductPurchaseActivity.java:19`)로 늘지 않고, 따라서 점수도 늘지 않는다 — §7 테스트로 확인 (8절).
- **한계**: "지난주 모든 사건이 도착했다"는 증명은 없다. 공개된 결과의 기준 시점은 `frozenAt` 으로 응답에 드러낸다.

---

## 7. 장애 시나리오

### 7-1. 막는 장애

| # | 장애 | 막는 장치 | 결말 | 검증 테스트 (계획, 8절) |
|---|---|---|---|---|
| 1 | Step 1 도중 실패 | Step 1 전체가 한 TX (D9) | rollback → 입력 행·회차 행 모두 없음. 반쪽 snapshot 없음 | `WeeklyRankingJobRestartTest.freezeFailure_leavesNoSnapshot` |
| 2 | Step 2 chunk 사이 실패 | reader `saveState` + chunk commit 과 같은 TX (D10) | 마지막 commit 위치에서 재시작. 완료 상품 재계산 없음 | `WeeklyRankingJobRestartTest.restartsFromCheckpoint` (§6 시나리오) |
| 3 | chunk commit 직전 실패 → 같은 상품 재처리 | 절대값 upsert (D10, R1) | `5 → 5`. 행 수 불변 | `WeeklyScoreWriterTest.rewriteSameProduct_keepsScore` |
| 4 | Step 3 공개 TX 도중 실패 | 순위·COMPLETED·pointer 한 TX (D11, R3) | 전부 rollback. `ranking` 전부 NULL, 회차 CALCULATING, 이전 pointer 유지 | `WeeklyRankingPublishTest.publishFailure_keepsPreviousPointer` |
| 5 | 공개 commit 뒤 metadata 저장 실패 | Step 3 멱등 (R6) + pointer `<=` 조건 (D5) | 재시작 시 Step 3 재실행 → 변화 없음 | `WeeklyRankingPublishTest.republish_converges` |
| 6 | 더 새 revision 공개 뒤 오래된 실행 도착 | pointer 조건부 갱신 거절 (D5) → D8 | 오래된 회차 COMPLETED, pointer 는 새 revision 유지, Job COMPLETED + WARN | `WeeklyRankingPublishTest.staleRevision_doesNotOverridePointer` |
| 7 | 계산 중 늦은 구매 도착 | Step 2 는 frozen 입력만 읽음 (D10) | 점수 불변. 반영은 새 revision (D15) | `WeeklyRankingFlowTest.lateArrival_requiresNewRevision` (§7) |
| 8 | 같은 (기간, revision) 동시 실행 | Spring Batch 가 같은 JobInstance 의 동시 실행을 `JobExecutionAlreadyRunningException` 으로 거절 + `revisions` UNIQUE (D3·D6) | 두 번째 실행은 기동 실패 또는 Step 1 CONFLICT | `WeeklyRankingConcurrentLaunchTest.sameInstance_secondLaunchIsRejected` (결정적 방식, 8절) |
| 9 | 삭제 상품 | API 읽기 시 `deleted_at IS NULL` 필터 (D14) | 제외 뒤 limit. 저장 순위 유지(간격) | `WeeklyRankingApiTest.deletedProduct_isExcludedBeforeLimit` |

### 7-2. 막지 못하는 장애 (한계)

| 장애 | 왜 못 막나 | 지금 할 수 있는 것 |
|---|---|---|
| 프로세스 kill (OOM·강제 종료) | JobExecution 이 `STARTED` 로 남아 재시작하면 `JobExecutionAlreadyRunningException` | 운영자가 `BATCH_JOB_EXECUTION`·`BATCH_STEP_EXECUTION` 의 STATUS/END_TIME 을 수동으로 FAILED 로 바꾼 뒤 재시작. 자동화·도구는 범위 밖 |
| 배치 다중 인스턴스 | 같은 JobRepository 를 쓰면 Batch 가 막지만, 서로 다른 revision 을 동시에 돌리면 D5 의 UPDATE→INSERT 두 문장 사이 경쟁 가능 | 단일 인스턴스 전제(과제 기본 범위는 직렬 실행) |
| Step 1 commit 직후 metadata 저장 실패 | 회차 행은 있는데 Step 1 이 미완료 → 재시작 시 D6 CONFLICT | 운영자가 확인 후 새 revision 으로 (D6) |
| 실패 알림 없음 | D8 의 WARN·FAILED 를 보는 사람이 없으면 모른다 | 로그만. Slack 등은 범위 밖 (`StepMonitorListener.java:39` 주석과 같은 상태) |
| `confirmed_at` 풀스캔 | 인덱스 없음 (`ProductPurchaseActivity.java:17-20`) | 측정 없이 주장·추가하지 않는다 |
| 서버 시간대 | JVM 기본 Asia/Seoul 과 UTC 저장이 섞여 있음 | D12 의 바인딩 규칙 + 테스트의 UTC 값 assert 로만 방어 |

---

## 8. 검증 계획

- **probe 완료 (§3-1)**: `02-probe-result.md` — 같은 `snapshotId=probe-1` 로 첫 실행 FAILED(JobInstance 1 / JobExecution 1 / StepExecution 1, read 3 · write 2 · commit 1 · rollback 1, ExecutionContext `purchaseProductProbe.read.count=2`, probe 행 [7,8]) → 재실행 COMPLETED(JobInstance 1 / JobExecution 2 / StepExecution 2, read 1 · write 1, 행 [7,8,9]). TX 매니저 `JpaTransactionManager`, JobRepository 의 DataSource 는 `mySqlMainDataSource` 와 같은 인스턴스. 테스트 4건(Demo 2 + contextLoads 1 + probe 1) 통과, Checkstyle 통과.

- **batch 테스트 공통 전제**
  - `@SpringBootTest(properties = "spring.batch.job.enabled=false")`. `DatabaseCleanUp` 은 쓰지 않는다(BATCH_ 이력을 지우지도 못하고, 두 실행 사이 초기화 금지).
  - BATCH_ 이력은 JVM 내 모든 테스트가 공유한다(`01-survey` 5-3). 테스트마다 **다른 업무 파라미터**(`periodStart`/`snapshotId`)를 쓴다. `run.id` 금지.
  - `product_purchase_activity` 는 batch classpath 에 엔티티가 없어 생성되지 않는다 → 테스트 시작부에서 `CREATE TABLE IF NOT EXISTS`(streamer 엔티티와 같은 컬럼) 를 실행하고 fixture 를 JDBC 로 넣는다. DDL 은 **테스트 상수 문자열**로 둔다(파일 추가 없음). **한계**: streamer 의 `ProductPurchaseActivity` 엔티티가 바뀌어도 이 문자열은 자동으로 따라가지 않는다(드리프트). 후속으로 마이그레이션 도구가 테이블 정의의 단일 원본이 되면 그쪽을 참조한다.
  - 시각 바인딩은 UTC `LocalDateTime` (D12). Clock 은 고정값.
  - `PurchaseInputProbeRestartTest` 의 `batch_restart_probe`·`probe-1` 데이터와 BATCH_ 이력이 같은 JVM 에 남는다. Job 이름이 다르므로(`purchaseInputProbe` vs `weeklyRanking`) 충돌은 없지만 JobInstance ID 가 1 부터 시작하지 않을 수 있다 — 9절 기록에 ID 절대값을 기대값으로 쓰지 않는다.

- **§6 재시작 테스트** (`WeeklyRankingJobRestartTest`, commerce-batch): fixture 4행(`confirmed_at` 2026-08-17~19) → `periodStart=2026-08-17`, `snapshotId=weekly-20260817-r1` → Step 2 에서 상품 9 처리 직전 실패(테스트용 hook. 파라미터 아님. main 에 `domain/ranking/ScoreProcessingHook` seam 1개를 두고 processor 가 SUM 직전에 부른다. 제품은 no-op 빈, 테스트는 지정한 (snapshotId, productId) 에서 한 번만 예외를 던지는 `@Primary` 빈을 등록한다 — W6·W7 hook 예외 방식과 같음) → 확인: Job FAILED, 같은 JobInstance, `weekly_ranking_scores` 2행(7=5, 8=1, `ranking` NULL), 회차 CALCULATING, pointer 행 없음(이전 공개본 없음) 또는 이전 pointer 불변, reader ExecutionContext 위치 2 → 같은 파라미터 재실행 → COMPLETED, StepExecution read 1 / write 1, `ranking` [7→1, 9→2, 8→3], `result_checksum` == 손으로 계산한 기대값, pointer 가 r1 → **별도 snapshotId(예 `weekly-20260824-r1`, 다른 기간)로 정상 1회 실행**한 결과와 입력·결과 checksum 및 점수·순위 비교.

- **R1~R7 고정 테스트** (규칙당 1개, 이름에 규칙 번호):

| 규칙 | 테스트 (계획) | 확인 |
|---|---|---|
| R1 | `r1_scoreRowExistsOnlyAfterCommit` | chunk 1 commit 뒤 별도 연결로 2행, score NOT NULL |
| R2 | `r2_step2_neverTouchesRanking` | Step 2 만 실행 → 모든 `ranking` NULL |
| R3 | `r3_rankingAndPointer_sameTx` | 공개 TX 중간 실패 → ranking 전부 NULL, pointer 불변 (7절 #4) |
| R4 | `r4_completedRevision_hasContiguousRanking` | COMPLETED 회차: 행 수 == expected, 1..N 연속, 정렬 일치 |
| R5 | `r5_api_readsOnlyThroughPointer` | 회차 COMPLETED 인데 pointer 없음 → API NOT_PUBLISHED |
| R6 | `r6_republish_isIdempotent` | Step 3 재실행 → ranking·checksum·pointer 불변 (7절 #5) |
| R7 | `r7_newRevision_keepsOldRows` | r2 실행 뒤 r1 의 입력·점수 행 수 불변 |

- **Step 3 수렴 테스트** (`WeeklyRankingPublishTest`): 공개 완료 상태에서 Step 3 만 다시 실행 → 변화 없음. 공개 TX 중간 실패(테스트 hook) → `ranking` 전부 NULL·회차 CALCULATING·pointer 불변.
- **D8 테스트**: r2 공개 뒤 r1 을 실행 → r1 회차 COMPLETED, pointer 는 r2 유지, Job COMPLETED, exitDescription `NOT_PUBLISHED: newer revision r2 already published`.
- **동시 실행 거절 테스트** (`WeeklyRankingConcurrentLaunchTest`, 7절 #8): 타이밍에 의존하지 않는 **결정적** 방식으로 쓴다. ① 테스트 전용 Job(Step 하나가 `CountDownLatch.await()` 로 실행 중 상태에서 멈추는 Tasklet)을 **별도 스레드**에서 동기 `JobLauncher.run` 으로 시작한다(운영 `JobLauncher` 빈·설정은 바꾸지 않고, 비동기가 필요한 테스트 안에서만 스레드를 만든다). ② Step 이 latch 에 도달한 것을 확인한 뒤 **같은 JobParameters** 로 두 번째 `run` → `JobExecutionAlreadyRunningException` 단언. ③ latch 해제 → 첫 Job `COMPLETED` 단언. 스레드 두 개를 동시에 띄워 경쟁시키는 방식은 어느 쪽이 먼저 JobRepository 에 닿는지 실행마다 달라 flaky 하므로 쓰지 않는다. 테스트 전용 Job 이름은 `weeklyRanking` 과 다르게 둬 BATCH_ 이력이 섞이지 않게 한다.
- **§7 연결 테스트** (`WeeklyRankingFlowTest`, commerce-api crossAppTest): PG 확정 → Outbox relay → Kafka → 구매 활동(기존 `OutboxDeliveryE2ETest` 경로, `apps/commerce-api/src/crossAppTest/java/com/loopers/crossapp/OutboxDeliveryE2ETest.java:186`) → Job 실행 → API 조회. 추가 확인: 같은 이벤트 재발행 뒤 구매 활동·점수 무증가, 늦은 구매 → 현재 snapshot 불변 → 새 revision 실행 → 공개 전·후 API 응답 전환.
  - 필요한 build 변경 (`01-survey` 9-3): batch plain jar 활성화(`tasks.jar { enabled = true }`, streamer 와 같게 `apps/commerce-streamer/build.gradle.kts:27`), `crossAppTestImplementation(project(":apps:commerce-batch"))` 추가(`apps/commerce-api/build.gradle.kts:49` 옆), `spring.batch.jdbc.initialize-schema=always`·`spring.batch.job.enabled=false` 를 crossAppTest 속성에 추가, `CrossAppTestConfig.java:42` 의 `ASSIGNABLE_TYPE` 제외 목록에 `CommerceBatchApplication` 추가, `CrossAppTestConfig.java:43-49` 의 REGEX 제외 목록에 `com\.loopers\.config\.BatchClockConfig` 추가(D12. 제외하지 않으면 `@MockitoBean Clock` 과 별개로 실제 시계 빈이 하나 더 생긴다). 이 4건은 **구현 직전에 diff 로 확인받는다** (build 파일 변경).
- **API 테스트** (`WeeklyRankingApiTest`, commerce-api): 유효 요청 / `periodStart` 누락·형식 오류·월요일 아님 → 400 `BAD_REQUEST` / `limit` 0·101 → 400 / 미공개 → 200 NOT_PUBLISHED / 구매 0건 완성본 → PUBLISHED + 빈 items / 삭제 상품 제외 **뒤** limit(3위까지 있고 2위 삭제, limit 2 → 1위·3위). api 테스트는 테이블 4개의 DDL 을 직접 실행하고 JDBC 로 행을 넣는다.
- **회귀**: `./gradlew :apps:commerce-batch:check :apps:commerce-api:check :apps:commerce-api:crossAppTest`. 0건·skip·연결 실패는 통과가 아니다.

---

## 9. 실험 기록 (구현 후 채움)

### 9-1. §6 재시작 실행 (2026-11-20, `WeeklyRankingJobRestartTest`)

Testcontainers MySQL 8.0, period 2026-08-17, fixture orderId 42·43·44. 같은 JVM 의 다른 테스트가 먼저 돌아 ID 절대값은 1 부터 시작하지 않는다(의미는 상대 비교).

| 항목 | 첫 실행 (r1, 실패) | 재실행 (r1) | 정상 1회 실행 (r2, 비교용) |
|---|---|---|---|
| periodStart / snapshotId | 2026-08-17 / weekly-20260817-r1 | 같음 | 2026-08-17 / weekly-20260817-r2 |
| JobInstance ID / JobExecution ID | 12 / 14 | **12** / 15 | 13 / 16 |
| 실행된 Step (StepExecution ID) | freeze #22, calculate #23 | calculate #24, open #25 (**freeze 없음**) | freeze #26, calculate #27, open #28 |
| Step 2 status | FAILED | COMPLETED | COMPLETED |
| Step 2 read / write / commit / rollback | 3 / 2 / 1 / 1 | 1 / 1 / 1 / 0 | 3 / 3 / 2 / 0 |
| reader ExecutionContext 키 = 값 | `weeklyProductIdReader.read.count = 2` | `= 4` | `= 4` |
| 그 외 ExecutionContext 키 | `batch.version=5.2.2`, `batch.taskletType=ChunkOrientedTasklet`, `batch.stepType=TaskletStep` | 같음 | 같음 |
| `weekly_ranking_scores` 행 (product=score, ranking) | 7=5·8=1, ranking 모두 NULL | 7=5(1)·9=5(2)·8=1(3) | 같음 |
| 회차 status / input_count / expected_product_count | CALCULATING / 4 / 3 | COMPLETED / 4 / 3 | COMPLETED / 4 / 3 |
| input_checksum | `3e90276ec1f9…c357a298` | 같음 | 같음 (r1 과 동일) |
| result_checksum | NULL | `745d50bbd2f0…3b0a67d2` | 같음 (r1 과 동일) |
| pointer (period_start → snapshot_id, revision) | 없음 | 2026-08-17 → r1, 1 | 2026-08-17 → r2, 2 |

- 전체 값: input `3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298`, result `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`, 빈 목록 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- 재실행에서 Step 1 의 StepExecution 이 만들어지지 않았고(`allowStartIfComplete=false`), 입력 행은 4 그대로였다. r2 뒤에도 r1 의 입력 4행·점수 3행·회차 COMPLETED 는 그대로다(R7).
- Step 1 DB 동작(`FreezeWeeklyInputStepTest`, period 2026-07-27): `2026-07-27T00:00:00Z`(포함)·`2026-08-02T23:59:59.999999Z`(포함) 2행만 복사, `2026-08-03T00:00:00Z`·`2026-07-26T23:59:59Z`·삭제 행 제외. 같은 (기간, r2) 회차 행이 미리 있으면 Step 1 FAILED(`conflict`), 입력 0행. 활동 0건 주(period 2026-07-06): COMPLETED, input 0·expected 0·두 checksum 모두 빈 문자열 값, 점수 0행, pointer → r1.

### 9-2. 공개 단계 (R6 수렴 · R3 rollback · D8 거절, 2026-11-20)

| 장면 | 회차 status | ranking 상태 | pointer | Job status / ExitStatus | 근거 테스트 |
|---|---|---|---|---|---|
| 공개 뒤 Step 3 만 재실행 (probe Job `openOnlyProbe`, 같은 snapshotId) | COMPLETED 유지 | 동일 | r1 유지, `published_at` 동일 (CASE) | COMPLETED / `exitDescription=` (빈 값) | `OpenWeeklyRankingConvergenceTest` |
| 공개 TX 중간 실패 (r2: Step 2 상품 9 직전 hook 실패 → result_checksum 을 `00…00` 으로 심음 → 재시작 → Step 3 5단계 불일치) | CALCULATING 유지, 심은 checksum 그대로 | 3행 모두 NULL (3단계 UPDATE rollback) | r1 유지, `published_at` 동일 | FAILED, Step 3 FAILED, 예외 메시지에 `checksum` | `OpenWeeklyRankingConvergenceTest` |
| r2 공개 뒤 r1 실행 | r1 COMPLETED, result_checksum == r2 의 값 | r1 1..3 | r2 유지, `published_at` 동일 | COMPLETED / `exitDescription=NOT_PUBLISHED: newer revision r2 already published` | `WeeklyRankingRevisionRejectTest` |
| 실행 중 JobInstance 에 같은 파라미터 2차 launch | — | — | — | 2차: `JobExecutionAlreadyRunningException`, 1차: latch 해제 후 COMPLETED | `WeeklyRankingConcurrentLaunchTest` |

### 9-3. §7 연결 (PG → Kafka → 구매 활동 → Job → API)

| 항목 | 값 |
|---|---|
| 생성 주문 / 상품 / 수량 | |
| 구매 활동 행 수 (재발행 전 / 후) | |
| r1 점수·순위 / API 응답 | |
| 늦은 구매 뒤 r1 재시작 결과 (불변 확인) | |
| r2 점수·순위 / 공개 전·후 API 응답 | |

### 9-4. 회귀 결과

| 명령 | 건수 / 실패 / skip | 비고 |
|---|---|---|
| `:apps:commerce-batch:test` (2026-11-20, ⑥ 시점) | 10 클래스 22건 / 0 / 0 | Checkstyle main·test 통과. `check` 전체는 ⑦·⑧ 뒤 다시 |
| `:apps:commerce-api:test --tests '*WeeklyRanking*' --tests '*ArchitectureTest'` (2026-11-20, ⑦ 시점) | 16건 / 0 / 0 | ranking API 13 + ArchUnit 3. Checkstyle main·test 통과. `check` 전체는 ⑧ 뒤 |
| `:apps:commerce-api:crossAppTest` | | |

---

## 10. 검토 요청

**미정 없음 — 2026-11-20 확정.** 아래는 확정 요약이며 본문의 해당 결정에 반영돼 있다.

| 위치 | 확정 내용 |
|---|---|
| D1 | 불일치 예외는 `IllegalArgumentException` + 메시지에 snapshotId·periodStart 두 값. 새 클래스 없음 |
| D8 | ExitStatus 기본 `COMPLETED`, exitDescription `NOT_PUBLISHED: newer revision rN already published`, WARN 로그 |
| 4절 공통 | `WeeklyRankingJobConfig` 1개 + Tasklet 2개 + `domain/ranking` 순수 계산 1~2개. 구현 직전 파일 목록 제시(`03-implementation-plan.md`) |
| D10 | reader 이름 `weeklyProductIdReader`. 상품당 SUM 1회는 한계로 두고 측정 뒤 재검토 |
| D13 | `confirmedAt` 직렬화는 UTC 고정 6자리 `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`. ISO_INSTANT 미채택 이유는 D13 한계 |
| D14 | `periodStart` 는 String 직접 파싱(jakarta validation 미사용 관례와 일관) |
| D14 | 점수 행 읽기 상한 없음(삭제 제외 뒤 limit 이므로 전부 읽어야 정확) |
| D14 | api 는 brand 4계층, "없으면 깨지는가" 기준으로 축소 |
| 7절 #8 | 동시 실행 테스트 작성. CountDownLatch 로 멈춘 테스트 전용 Job + 같은 파라미터 2차 launch → `JobExecutionAlreadyRunningException`. 스레드 경쟁 방식은 flaky 라 미채택 |
| 8절 | 테스트용 `product_purchase_activity` DDL 은 테스트 상수 문자열. streamer 엔티티 드리프트는 한계(후속: 마이그레이션 도구) |
| 8절 | crossAppTest build·설정 변경 4건은 구현 직전 diff 로 확인 |
