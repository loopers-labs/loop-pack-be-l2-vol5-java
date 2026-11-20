# 주간 구매 랭킹 배치 — 구현 상세 (W9, AI용)

사람용 요약은 `weekly-ranking-design.md`. 근거는 `docs/note/week9/실습 과제.md`(§n), `docs/note/week9/발제.md`.

## 1. 테이블

모두 `BaseEntity`를 상속하고 `@Table(name)`을 명시한다(`DatabaseCleanUp`이 이름을 읽음).

| 테이블 | 소유 | 컬럼 (NOT NULL 기본) | 제약 |
|---|---|---|---|
| `product_purchase_activity` | streamer | order_id, product_id, quantity int, confirmed_at datetime(6) | UK (order_id, product_id). batch는 `@Immutable` 읽기 엔티티로 같은 정의를 매핑 |
| `weekly_ranking_publication` | batch | period_start date, last_revision int, published_snapshot_id varchar(64) NULL, published_revision int NULL | UK period_start |
| `weekly_ranking_snapshot` | batch | snapshot_id varchar(64), period_start date, revision int, status varchar(20), frozen_at datetime(6), input_count int, product_count int, input_checksum char(64), result_checksum char(64) NULL, published_at datetime(6) NULL, job_execution_id bigint | UK snapshot_id, UK (period_start, revision) |
| `weekly_ranking_snapshot_input` | batch | snapshot_id, order_id, product_id, quantity int, confirmed_at datetime(6) | UK (snapshot_id, order_id, product_id), IDX (snapshot_id, product_id, quantity) |
| `weekly_ranking_score_stage` | batch | snapshot_id, product_id, score bigint | UK (snapshot_id, product_id) |
| `weekly_ranking` | batch (api는 읽기 매핑) | snapshot_id, product_id, score bigint, ranking int | UK (snapshot_id, product_id), IDX (snapshot_id, ranking) |

- `rank`는 MySQL 예약어라 컬럼명은 `ranking`으로 쓴다.
- api는 `weekly_ranking_publication`, `weekly_ranking_snapshot`, `weekly_ranking`을 읽기 전용 엔티티로 매핑한다. 컬럼 정의는 batch와 같게 맞춘다.
- `job_execution_id`는 소속 증거다. 결과 checksum에는 넣지 않는다.

## 2. 실행 식별과 설정

- job 이름: `weeklyRanking`. `@ConditionalOnProperty(name="spring.batch.job.name", havingValue="weeklyRanking")`.
- 식별 parameter: `periodStart`(LocalDate), `revision`(Long). incrementer 없음. 실패 스위치·chunk 크기는 parameter가 아니다.
- snapshotId: `weekly-` + `yyyyMMdd`(periodStart) + `-r` + revision. job 시작 시 계산해 job ExecutionContext가 아니라 매번 parameter에서 다시 만든다(결정적).
- chunk 크기: property `ranking.weekly.chunk-size`(기본 100, 테스트 2).
- transaction manager: Boot의 `transactionManager`(JpaTransactionManager) 하나. `@EnableBatchProcessing`·`ResourcelessTransactionManager`·별도 DataSource 금지. JdbcTemplate을 쓰면 같은 DataSource.
- local: `spring.jpa.hibernate.ddl-auto=update`(batch만). test: `create` 유지, Batch schema `initialize-schema: always`(Boot가 `continueOnError=true`로 실행).
- 테스트: `spring.batch.job.enabled=false`, `JobLauncher`로 명시 실행. 테스트 전체 자동 rollback 금지.
- Clock 빈(UTC)을 batch에 추가하고 frozen_at·published_at에 쓴다.

## 3. 상태 전이

```
(없음) ──freeze──▶ FROZEN ──publish──▶ PUBLISHED
                     │
                     └──publish(더 새 revision 공개됨)──▶ SUPERSEDED
```

| Step 결과 | snapshot | publication | Job ExitStatus |
|---|---|---|---|
| freeze 성공 | FROZEN 생성 | last_revision = revision | — |
| publish 성공 | PUBLISHED, result_checksum, published_at | published_snapshot_id·published_revision 갱신 | COMPLETED |
| publish 생략(역행) | SUPERSEDED | 변경 없음 | COMPLETED / exit code `SUPERSEDED` |
| 계산 실패 | FROZEN 유지 | 변경 없음 | FAILED |

## 4. Step 분기

### ① freezeInput (tasklet)

TX 속성: isolation READ_COMMITTED. 순서:

1. periodStart가 월요일인지 검증. 아니면 실패.
2. `weekly_ranking_publication`를 `PESSIMISTIC_WRITE`로 조회. 없으면 `last_revision=0`으로 insert 후 다시 잠금(동시 insert는 UK 위반 → 실패로 드러냄, 재실행으로 해소).
3. 분기
   | 조건 | 처리 |
   |---|---|
   | snapshot (period, revision) 존재 | 복사하지 않음. 저장된 입력으로 건수·checksum을 다시 계산해 manifest와 다르면 실패 |
   | revision == last_revision + 1 | 4로 |
   | 그 외 | 충돌 예외(`revision은 {last+1}이어야 함`) |
4. HQL `insert into SnapshotInput(snapshotId, orderId, productId, quantity, confirmedAt) select :sid, a.orderId, a.productId, a.quantity, a.confirmedAt from PurchaseActivity a where a.confirmedAt >= :start and a.confirmedAt < :end` (BaseEntity 컬럼 created_at 등 NOT NULL이면 select에 상수로 채운다. 안 되면 네이티브 SQL).
5. 복사본을 (orderId, productId) 순으로 읽어 input_count·product_count(서로 다른 productId 수)·input_checksum 계산 → snapshot FROZEN insert, publication.last_revision = revision.

### ② calculateWeeklyScores (chunk)

- reader: `JpaCursorItemReader<Long>`, name `weeklyRankingProductReader`, `saveState(true)`, `@StepScope`.
  JPQL `select distinct i.productId from SnapshotInput i where i.snapshotId = :sid order by i.productId`.
  저장 key는 `weeklyRankingProductReader.read.count`(구현 후 실제 key를 테스트에서 관찰해 확정).
  MySQL 드라이버 결과 버퍼링: 구현 때 확인. 필요하면 fetch size hint 또는 `useCursorFetch`.
- processor: `SnapshotInputRepository.sumQuantity(sid, productId)` → `ProductScore(productId, total)`. total이 0 이하이면 계약 위반으로 예외(skip 금지).
- writer: 각 item마다 stage (sid, productId) 조회 → 있으면 `score = total`, 없으면 persist. `+=` 금지.
- skip·retry 정책 없음.

### ③ publish (tasklet)

1. publication `PESSIMISTIC_WRITE`, snapshot 조회.
2. 분기 (위에서부터 첫 일치)
   | 조건 | 처리 |
   |---|---|
   | snapshot PUBLISHED | no-op, COMPLETED (metadata만 실패했던 재실행이 수렴) |
   | snapshot SUPERSEDED | no-op, exit `SUPERSEDED` |
   | stage 행 수 ≠ product_count | 예외 (기존 pointer 유지) |
   | publication.published_revision > revision | snapshot SUPERSEDED, exit `SUPERSEDED` |
   | 그 외 | 3으로 |
3. stage를 `score DESC, productId ASC`로 정렬해 1부터 순번 → `weekly_ranking` insert → result_checksum → snapshot PUBLISHED·published_at → publication pointer = (sid, revision). 모두 같은 TX.
- 공개 TX 실패 시 ranking·snapshot·pointer 모두 rollback. 기존 공개본을 먼저 지우는 단계는 없다.

## 5. Checksum

- SHA-256, 소문자 hex. 행마다 필드를 TAB으로 잇고 LF로 끝낸다(마지막 행 포함). 빈 입력은 빈 문자열의 해시.
- 입력: (orderId, productId) 오름차순, `orderId\tproductId\tquantity\tconfirmedAt\n`, confirmedAt은 UTC `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`.
- 결과: ranking 오름차순, `productId\tscore\tranking\n`.
- 실행 ID·실행 시각·snapshotId는 넣지 않는다.

## 6. API (commerce-api)

- `GET /api/v1/rankings/weekly?periodStart&limit` → `WeeklyRankingController` → `WeeklyRankingFacade`(readOnly).
- 검증: periodStart 파싱 실패·월요일 아님 → `INVALID_INPUT`. limit 기본 10, 1~100 밖이면 `INVALID_INPUT`.
- 조회 순서(한 readOnly TX):
  1. publication 조회. 없거나 published_snapshot_id null → NOT_PUBLISHED.
  2. 한 JPQL: `weekly_ranking r join weekly_ranking_snapshot s on s.snapshotId = r.snapshotId join Product p on p.id = r.productId and p.deletedAt is null where r.snapshotId = :publishedSid order by r.ranking` + limit. snapshot 메타(frozen_at·revision)는 같은 sid로 조회.
  3. 상품 이름·가격은 2의 결과, 브랜드는 `BrandService.getAllByIds`로 일괄 조회.
- 응답: status, periodStart, periodEnd, snapshotId, revision, frozenAt, items[rank, productId, name, price, brand{id,name}, score]. 미공개는 status·periodStart·periodEnd·items만.

## 7. 테스트 시나리오

§6 fixture(UTC): (42,7,2,08-17T01) (42,8,1,08-17T01) (43,7,3,08-18T01) (44,9,5,08-19T01). 기대: 7=5·1위, 9=5·2위, 8=1·3위. 원본 4행, reader item 3개.

| 테스트 | 준비 | 검증 |
|---|---|---|
| probe (§3) | probe-1 입력 고정, `batch_restart_probe` 테스트 DDL | 1차 FAILED, ExecutionContext `purchaseProductProbe.read.count=2`, 행 7·8 / 2차 같은 instance·새 execution·COMPLETED, 행 7·8·9 |
| `RankingJobRestartTest` | fixture, `@MockitoSpyBean` 합계 조회가 상품 9 첫 호출만 예외 | 1차 FAILED·같은 instance·read.count=2·stage 2행(5,1)·pointer 없음(또는 기존 유지) / 2차 COMPLETED·processor 호출 상품은 9뿐(stage 결과로 확인)·순위·checksum = 정상 실행 기대값 |
| `WeeklyRankingFlowTest` | fixture | 고정 뒤 활동 추가 + 재시작 → 입력 checksum 불변 / r2 → 늦은 구매 포함, pointer r1→r2 / r2 공개 뒤 r1 공개 재실행 → SUPERSEDED·pointer r2 / 공개 TX 중 예외(spy) → pointer·ranking 불변 / 경계 시각(시작 포함·끝 제외) / 빈 주 PUBLISHED |
| `WeeklyRankingApiE2ETest` | ranking 테이블 직접 seed | 삭제 상품 제외 후 limit·저장 순위(간격) / NOT_PUBLISHED / 빈 PUBLISHED / periodStart·limit 오류 400 (`@ParameterizedTest`) |
| domain 단위 | — | 월요일 검증·구간, 순위 정렬, revision 허용 규칙, 공개 분기, checksum 직렬화 |

- 정상 실행 비교는 별도 snapshot(다른 테스트 메서드, 정리된 DB)에서 만든 기대 checksum 상수와 비교한다. 재시작 대상의 stage·metadata를 지우지 않는다.
- 정리: 테스트 클래스 사이에서만 `DatabaseCleanUp`. 한 시나리오의 실행 사이에는 지우지 않는다.

## 8. 로컬 인수 시나리오

1. `docker compose -f docker/infra-compose.yml up -d`, api·streamer bootRun(local).
2. 상품 3개(7·8·9 역할)로 주문을 확정해 이번 주 활동 4행을 만든다. periodStart = 이번 주 월요일(UTC).
3. mysql 세션 A: `SET GLOBAL innodb_lock_wait_timeout=5; BEGIN; INSERT INTO weekly_ranking_score_stage(...) VALUES ('weekly-…-r1', <상품9>, 0, …);` commit하지 않음.
4. batch r1 실행 → FAILED. 확인: `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`(read.count=2), stage 2행, API 응답(NOT_PUBLISHED).
5. streamer 정지 → 주문 하나 더 확정 → streamer 재기동 → 활동 도착 확인.
6. 세션 A `ROLLBACK`.
7. batch r1 재실행 → 같은 JOB_INSTANCE_ID, 새 JOB_EXECUTION_ID, COMPLETED, 입력 checksum 불변, 늦은 구매 미포함, API r1.
8. batch r2 → 늦은 구매 포함, API r2.
9. 각 단계의 조회 결과를 PR·기술 글에 기록. `innodb_lock_wait_timeout`은 원래 값으로 되돌린다.

## 9. 태스크

| # | 내용 | 완료 기준 |
|---|---|---|
| 1 | batch 기반: Checkstyle·ArchUnit, local ddl update, Clock, job.enabled 처리, probe 테스트 | probe 통과, `:apps:commerce-batch:check` 통과 |
| 2 | domain 규칙 + 단위 테스트 | 단위 테스트 통과 |
| 3 | 엔티티·repository | 컨텍스트 기동, 스키마 생성 |
| 4 | freezeInput + job 골격 | 고정 결과·manifest 확인 |
| 5 | 계산 Step + 재시작 테스트 | `RankingJobRestartTest` |
| 6 | publish + 흐름 테스트 | `WeeklyRankingFlowTest` |
| 7 | api | `WeeklyRankingApiE2ETest`, api check |
| 8 | 인수 시나리오·PR | 기록 |

각 태스크는 골격 제안 → 테스트 검토 → 운영 코드 순서.
