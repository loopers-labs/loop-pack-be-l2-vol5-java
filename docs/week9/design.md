# 9주차 설계 — 주간 구매 수량 랭킹 배치

W6·W7 consumer가 남긴 상품 구매 활동을 주간 snapshot으로 고정하고, 상품별 구매 수량을 계산해 완성된 결과만 공개한다. 계산이 중간에 멈추면 같은 입력·같은 작업을 이어 가고, 늦게 도착한 구매는 원래 주의 새 revision으로 다시 계산한다.

실행 식별과 늦은 구매 처리 결정은 [ADR 0079](../adr/0079-identify-weekly-snapshot-by-explicit-revision.md), 테이블 소유와 스키마는 [ADR 0080](../adr/0080-batch-owns-weekly-ranking-tables-by-sql-schema.md), 입력 고정의 revision·기존 snapshot 규칙은 [ADR 0081](../adr/0081-freeze-snapshot-once-in-revision-order.md), 공개 규칙은 [ADR 0082](../adr/0082-publish-verified-snapshot-atomically-and-converge.md), 틀린 계산의 복구는 [ADR 0083](../adr/0083-abandon-wrong-snapshot-and-recalculate-next-revision.md), API 계약은 [ADR 0084](../adr/0084-weekly-ranking-api-reads-published-snapshot-only.md), 입력 고정 방식은 [ADR 0085](../adr/0085-freeze-input-with-read-committed-insert-select.md), 늦은 구매 감지의 실행 방식은 [ADR 0086](../adr/0086-run-late-arrival-check-as-batch-app-runner.md)에 있다. 점수 계산과 재시작은 [ADR 0089](../adr/0089-read-product-ids-and-insert-weekly-scores.md), 잘못된 입력은 [ADR 0090](../adr/0090-reject-non-positive-quantity-in-frozen-input.md), 상품별 합계 조회의 인덱스는 [ADR 0091](../adr/0091-force-product-index-for-product-total.md), 멈춘 실행 정리는 [ADR 0092](../adr/0092-clear-stuck-execution-with-version-bump.md), Step 안의 재시도·건너뛰기는 [ADR 0093](../adr/0093-no-retry-or-skip-inside-steps.md), 순위 중복 방지는 [ADR 0094](../adr/0094-enforce-unique-rank-per-snapshot.md)를 따른다. 구현·검증은 [implementation-log.md](implementation-log.md)를 따른다.

## 1. 주간 결과 계약

과제가 정한 규칙이다.

| 항목 | 계약 |
| --- | --- |
| 기간 | UTC 월요일 00:00부터 다음 월요일 00:00 전까지 (`[periodStart, periodStart + 7일)`) |
| 주간 소속 | 구매 활동의 `confirmed_at` |
| 점수 | 상품별 구매 quantity 합, 정수 |
| 순서 | 점수 내림차순, 동점이면 productId 오름차순 |
| 입력 원본 | commerce-streamer의 `product_purchase_activity` (orderId·productId별 한 행) |
| 공개 | 기간별 하나의 완성된 snapshot |

입력 원본은 추가만 된다. `(order_id, product_id)`가 UNIQUE이고 모든 열이 `updatable = false`다. 같은 주문 이벤트의 여러 품목은 각각 한 행이다.

**한국 시간과의 경계.** 기간은 UTC로 자른다. 한국 시간(KST, UTC+9)으로는 **월요일 09:00**에 주가 바뀐다. 그래서 한국 시간 월요일 00:00~09:00에 확정된 구매는 지난주 랭킹에 들어간다. 정기 실행(4절)도 UTC 월요일 00:30, 한국 시간 월요일 09:30에 돈다. 다음 두 가지는 아직 정하지 않았다.

- 한국 시간 월요일 00:00~09:00의 구매를 지난주에 넣는 것이 서비스에 맞는가(기간을 `Asia/Seoul`로 자를지)
- 지난주 공개본이 생기기 전(한국 시간 월요일 00:00~09:30경) 메인 화면에 무엇을 보여 줄지. 지금 API는 그 주를 요청하면 `NOT_PUBLISHED`를 준다

## 2. 입력 snapshot과 revision

snapshot은 한 기간의 구매 활동을 **입력 고정 시점에 DB에 commit돼 있던 행**으로 복사해 둔 묶음이다. 계산과 재시작은 원본이 아니라 snapshot만 읽는다.

- snapshot에 들어가는 행: `confirmed_at`이 기간 안에 있고, 입력 고정 시점에 DB에 commit돼 있던 구매 활동.
- snapshotId 형식: `weekly-{periodStart yyyyMMdd}-r{revision}` (예: `weekly-20260817-r1`).
- 입력 고정 이후 도착한 같은 주의 구매는 그 snapshot에 추가하지 않는다. 원래 주의 다음 revision(r2, r3…)으로 다시 계산한다.
- 입력 고정은 "그 주의 모든 구매가 도착했다"는 증명이 아니다. API는 입력 고정 시점을 함께 응답한다.

## 3. 실행 식별

| 구분 | job parameter | JobInstance |
| --- | --- | --- |
| 처음 계산 | `periodStart=2026-08-17`, `snapshotId=weekly-20260817-r1` | 새 instance |
| 실패한 계산 이어 하기(재시작) | 위와 **같은 값** | 같은 instance, 새 execution |
| 늦은 구매 반영(재계산) | `snapshotId=weekly-20260817-r2` | 새 instance |

- 식별 parameter는 `periodStart`·`snapshotId`다. `run.id`·현재 시각은 붙이지 않는다.
- revision 번호는 배치 밖(스케줄러·감지기·운영자)이 정해 넘긴다. 배치는 받은 snapshotId로만 일한다.
- 배치는 `periodStart`와 snapshotId의 날짜가 맞는지 검사한다.

## 4. 운영 실행

### 정기 실행 (r1)

- 매주 **UTC 월요일 00:30**(KST 월요일 09:30)에 바깥 스케줄러가 직전 주 r1을 실행한다.
- 유예 30분의 근거는 정상 경로의 최대 지연이다. Outbox relay 10초 주기(`commerce.order-events.relay.fixed-delay-ms`), 발행 재시도 최대 60초 간격, consumer 저장 재시도 합계 5분(`max-elapsed-time: 5m`)이므로 정상 지연은 수 분 안이다.
- 장애로 늦어지는 경우는 길이에 상한이 없으므로 유예로 기다리지 않고 늦은 구매 감지(아래)로 보정한다. 예: consumer 서버 중단, DB 오류가 재시도 5분을 넘겨 listener가 멈춘 경우(offset을 넘기지 않아 사람이 다시 시작할 때까지 Kafka에 남는다), 메시지 오류로 DLT에 간 뒤 고쳐서 재처리하는 경우.
- 스케줄러 설정은 배포 환경의 일이며 이번 범위에서 만들지 않는다. 실패하면 같은 parameter로 다시 실행해 재시작한다.

### 늦은 구매 감지 (r2 이상)

랭킹은 메인의 "지난주 인기 상품" 노출용으로 본다. 노출되는 동안만 자동으로 고친다.

- **대상 기간**: 현재 UTC 주의 바로 앞 주 하나. 그 주의 snapshot이 하나 이상 있을 때만 동작한다(r1은 정기 실행의 몫이다).
- **마감**: 현재 UTC 주가 바뀌면 그 앞 주는 자동 감지 대상에서 빠진다. 마감 뒤 도착한 구매는 원본에 남고 과거 주 랭킹에는 자동 반영하지 않는다. 필요하면 운영자가 다음 revision을 직접 실행할 수 있다.
- **주기**: 매시.
- **감지**: 대상 기간의 현재 원본 건수와 마지막 snapshot 건수를 비교한다. 원본이 추가만 되므로 건수 차이로 새 행을 정확히 알 수 있다. `created_at > 입력 고정 시점`은 쓰지 않는다. `created_at`은 저장 전에 앱이 찍은 시각이라, 입력 고정 뒤 commit됐지만 시각은 그 전인 행을 놓친다.
- **안정화**: 대상 기간에 최근 30분 동안 새로 저장된 행이 없을 때만 새 revision을 실행한다. DLT 재처리처럼 늦은 구매가 나눠 들어올 때 revision이 연달아 생기는 것을 줄이기 위한 대기이며, 입력의 정확성은 건수 비교와 snapshot이 맡는다.
- **실패한 revision**: 마지막 revision이 `FROZEN`이면 새 revision을 만들지 않고 같은 parameter로 재시작한다. `ABANDONED`면 아무것도 하지 않는다(운영자가 처리).
- **실행**: 바깥 스케줄러가 매시 `--spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false --commerce.ranking.late-check.enabled=true`로 commerce-batch를 실행한다. `LateArrivalDetector`가 판단하고 `LateArrivalRevisionRunner`가 같은 프로세스에서 `weeklyRanking`을 실행한다. 실행 결과는 프로세스 종료 코드에 반영된다.

## 5. 저장 구성

과제가 개념으로 요구한 다섯 가지 데이터를 목적별 테이블로 나눈다. 스키마는 `apps/commerce-batch/src/main/resources/schema/weekly-ranking.sql`이다.

| 테이블 | 쓰는 때 | 읽는 쪽 | 키·유일성 |
| --- | --- | --- | --- |
| `ranking_snapshot` | 입력 고정 때 만들고, 공개 단계에서 완성 표시 | 배치, 감지기 | PK snapshot_id, UNIQUE(period_start, revision). 상태(`FROZEN` → `COMPLETED`, 운영자가 `FROZEN` → `ABANDONED`)·입력 고정 시각(UTC)·입력 건수·상품 수·입력 checksum·결과 checksum·완성 시각 |
| `ranking_snapshot_input` | 입력 고정 때 한 번, 이후 바꾸지 않음 | 배치 | PK(snapshot_id, order_id, product_id), 상품별 합계용 인덱스(snapshot_id, product_id, quantity) |
| `ranking_score_stage` | 계산 chunk마다 | 배치 | PK(snapshot_id, product_id). 계산 중 절대 점수 |
| `ranking_entry` | 공개 단계에서 한 번 | API | PK(snapshot_id, product_id), UNIQUE(snapshot_id, ranking). 점수·계산 순위 |
| `ranking_publication` | 공개 단계 | API | PK period_start. 공개 중인 snapshot_id·revision·공개 시각. 공개 여부는 이 표만 정한다 |

- **소유**: 다섯 테이블 모두 commerce-batch가 만들고 쓴다. commerce-api는 `ranking_entry`·`ranking_publication`을 SQL로 읽기만 하고 엔티티로 매핑하지 않는다. local의 `ddl-auto: create`는 앱이 뜰 때 자기 엔티티의 테이블을 지우고 다시 만들기 때문에, 두 앱이 같은 테이블을 엔티티로 가지면 api 재시작이 공개본을 지운다.
- **스키마 생성**: JPA 엔티티를 두지 않고 `CREATE TABLE IF NOT EXISTS` SQL을 `spring.sql.init`으로 실행한다(local·test만 `mode: always`, 그 밖에는 `never`). 이미 있으면 그대로 두므로 배치 실행·재시작 사이에 snapshot과 stage가 남는다. 배치의 업무 SQL은 모두 `JdbcTemplate`이다.
- **원본**: `product_purchase_activity`는 commerce-streamer가 소유한다. 배치는 읽기만 한다. batch 테스트는 같은 열·제약으로 직접 만든다(`PurchaseActivityFixture`).

## 6. weeklyRanking job

```text
weeklyRanking (periodStart, snapshotId)
 ├─ freezeSnapshotInput   tasklet, READ COMMITTED TX   원본 → snapshot 입력·snapshot 정보
 ├─ calculateWeeklyScores chunk, 기본 TX                snapshot 입력 → 상품별 절대 점수(stage)
 └─ publishWeeklyRanking  tasklet, 기본 TX              검증 → 완성 랭킹 → 완성 표시 → 공개 pointer
```

- 모든 Step은 기본 `PlatformTransactionManager`(JpaTransactionManager, `mySqlMainDataSource`)로 commit한다. JobRepository도 같은 관리자·DataSource를 쓰므로, chunk의 점수 쓰기와 reader 위치 저장이 한 TX에서 commit된다.
- incrementer를 두지 않는다. parameter 검사(`WeeklyRankingParametersValidator`)는 periodStart·snapshotId 두 개만 식별 String parameter로 받고, 그 밖의 parameter(`run.id` 등)가 있으면 거부한다.
- 실행: `--job.name=weeklyRanking periodStart=2026-08-17 snapshotId=weekly-20260817-r1`.

### 입력 고정 (`FreezeSnapshotTasklet`)

한 TX 안에서 순서대로 한다.

1. 같은 snapshotId의 snapshot 정보가 이미 있으면 다시 복사하지 않는다. 저장된 기간·revision이 같고, 저장된 snapshot 입력으로 다시 계산한 건수·상품 수·checksum이 snapshot 정보와 같으면 그대로 쓰고 끝낸다. 하나라도 다르면 충돌로 실패한다.
2. 처음 보는 snapshotId면 revision이 그 기간의 마지막 revision + 1(없으면 1)이어야 한다. 건너뛰면 실패한다. 그 기간에 아직 `FROZEN`인 revision이 있으면 실패한다(같은 기간의 계산은 하나씩). `ABANDONED`는 막지 않는다.
3. 입력 고정 시각을 DB의 `UTC_TIMESTAMP(6)`로 읽는다.
4. `INSERT INTO ranking_snapshot_input … SELECT … FROM product_purchase_activity WHERE confirmed_at >= 시작 AND < 끝` 한 문장으로 복사한다.
5. 복사한 snapshot 입력에 수량이 0 이하인 행이 있으면 건너뛰지 않고 실패한다. 같은 TX라 복사본과 snapshot 정보가 함께 rollback된다([ADR 0090](../adr/0090-reject-non-positive-quantity-in-frozen-input.md)).
6. 복사한 snapshot 입력에서 건수·상품 수·checksum을 계산한다. 원본을 다시 읽지 않는다.
7. snapshot 정보를 상태 `FROZEN`으로 저장한다.

- 이 Step의 TX만 READ COMMITTED다. 원본을 읽는 문장이 4의 한 문장뿐이라 그 문장 시점의 commit된 행만 들어가고, 원본을 잠그지 않는다. [실험 1·1-2](experiments.md)의 C이며 ADR 0085로 확정했다. 문장 도중 commit된 행이 들어가지 않는 것도 실험 1-2에서 확인했다. 복사 SQL 직후 커밋하는 실험 1-3에서 다른 연결의 INSERT가 원본 읽기 잠금을 기다리는 현상은 관찰되지 않았다. INSERT를 쉬지 않고 넣은 실험 1-4에서도 행 잠금 대기는 0회였다. 실험 시간에는 이 Step의 입력 요약·manifest 저장이 포함되지 않는다. 원본에 `confirmed_at` 인덱스는 두지 않는다. READ COMMITTED는 행 기반 binlog가 필요하다(MySQL 8.0 기본값 `ROW`, `binlog_format`은 8.0.34부터 deprecated).
- 입력 고정 시각(3)은 복사 문장 직전의 DB 시각이다. 그 사이 아주 짧은 틈에 commit된 행이 들어갈 수 있다.
- 실패하면 TX 전체가 rollback되어 반쪽 snapshot이 남지 않는다.
- 1의 "이미 있음"은 정상 재시작에서는 생기지 않는다. 완료된 입력 고정 Step은 Spring Batch가 다시 실행하지 않기 때문이다. 입력 고정 TX는 commit됐는데 Step 완료 기록 저장만 실패한 경우에 같은 instance의 재시작이 이 Step을 다시 실행하며, 그때 같은 입력으로 이어 가기 위한 처리다.
- checksum: SHA-256. 행은 orderId·productId 오름차순으로 `orderId TAB productId TAB quantity TAB confirmedAt LF`이고 마지막 행도 LF로 끝난다. confirmedAt은 UTC `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`다. 실행 ID·실행 시각은 넣지 않는다.
- 나중에 고려할 것: 입력 checksum은 입력을 한 행씩 해시에 넣지만, 조회에 fetchSize를 지정하지 않았다. MySQL Connector/J는 이때 결과 전체를 받은 뒤 첫 행을 넘기는 것으로 알려져 있어(직접 재지는 않았다), 한 주의 입력이 커지면 배치 메모리가 입력 크기만큼 늘 수 있다. 그때는 이 조회만 한 행씩 받도록 드라이버 설정을 바꿀 수 있다.

### 점수 계산 (`calculateWeeklyScores`)

| 경계 | 구현 |
| --- | --- |
| Reader | `snapshotProductReader`는 `SnapshotProductPagingReader`(`JdbcPagingItemReader<Long>`)다. 고정 입력의 `DISTINCT product_id`를 상품 ID 오름차순으로 페이지 조회한다 |
| Processor | `SnapshotScoreProcessor`가 스냅샷과 상품 ID로 `SUM(quantity)`를 조회한다. 조회는 Reader와 같은 `idx_ranking_snapshot_input_product`를 지정해, 입력 고정 직후 통계가 비어 있어도 그 상품의 행만 읽는다([ADR 0091](../adr/0091-force-product-index-for-product-total.md), 실험 15). 상품 하나의 ID와 전체 점수를 담은 `ProductScore`를 만들며 ID와 점수가 양수인지 검증한다 |
| Writer | `ScoreStageWriter`가 `WeeklyRankingStore.insertScores`에 Chunk의 점수를 전달한다. 저장소는 `JdbcTemplate.batchUpdate`로 `(snapshot_id, product_id)`에 전체 점수를 INSERT한다 |
| Chunk · Page | `commerce.ranking.weekly.chunk-size`로 둘 다 설정한다. 기본은 100이다 |

- 첫 페이지는 지정한 snapshot의 고유 상품 ID를 읽는다. 다음 페이지는 `WHERE snapshot_id = ? AND product_id > ?`를 적용하고, 기존 `(snapshot_id, product_id, quantity)` 인덱스를 지정한다. 페이지 SELECT와 별개로 상품당 합계 SELECT가 한 번 실행된다.
- 페이지 조회와 합계 계산, 점수 쓰기는 같은 Chunk의 DB 연결을 사용한다. 점수 INSERT와 `snapshotProductReader.read.count`, `start.after`, `page.size`는 같은 쓰기 TX에서 저장한다. 실패한 Chunk의 점수와 위치는 함께 되돌아간다.
- 같은 스냅샷에 이미 커밋한 상품 점수가 다시 INSERT되면 기본 키로 거부한다. 원인을 확인할 수 있도록 오류로 남긴다. 다른 revision은 별도 snapshotId에 쓴다.
- 같은 JobInstance를 재시작하면 저장한 키로 다음 페이지를 읽는다. 저장된 페이지 크기와 지금 chunk 크기가 다르면 읽기 전에 실패한다. 읽은 수가 새 페이지 크기의 배수가 아니면 페이지 안에서 건너뛸 위치가 틀어져 상품을 빠뜨린다(검사가 없는 기본 Reader로 확인: 크기 2로 4개를 읽은 뒤 크기 3으로 열면 상품 5를 건너뜀). 원래 chunk 크기로 다시 실행한다([ADR 0095](../adr/0095-reject-restart-with-different-chunk-size.md)).

### 공개 (`PublishRankingTasklet`)

한 TX(기본 격리 수준) 안에서 순서대로 한다. 어느 단계에서 실패해도 전부 rollback되어 이전 공개본과 API 결과가 그대로다.

1. snapshot이 `FROZEN`이면 완성 조건을 확인한다. 하나라도 다르면 공개하지 않고 실패한다.
   - stage 행 수 = snapshot 정보의 상품 수
   - stage 점수 합 = snapshot 입력의 quantity 합
   - snapshot 입력으로 다시 계산한 건수·상품 수·입력 checksum = snapshot 정보
   - 상품별 점수는 대조하지 않는다. 상품 수와 점수 합이 같은 채 상품끼리 점수가 뒤바뀐 계산은 막지 못하며, 이는 계산 코드의 테스트로 확인한다.
2. stage 점수에 순위를 매겨 `ranking_entry`에 저장한다. 점수 내림차순, 동점이면 productId 오름차순이며 `ROW_NUMBER()`라 순위에 빈칸·공동 순위가 없다(상품 7·9가 5점이면 7이 1위, 9가 2위). 같은 snapshot 안에서 순위는 UNIQUE라 겹치면 저장이 실패한다([ADR 0094](../adr/0094-enforce-unique-rank-per-snapshot.md)).
3. 결과 checksum을 계산해 snapshot을 `COMPLETED`로 표시한다(`FROZEN`일 때만 바뀐다).
4. 공개 pointer 행을 `SELECT … FOR UPDATE`로 잠그고 판단한다.

| 현재 pointer | 처리 |
| --- | --- |
| 없음 | 이 snapshot으로 만든다 |
| 더 낮은 revision | `revision = 현재값` 조건으로 이 snapshot으로 바꾼다 |
| 같은 snapshot | 바꾸지 않는다(이미 공개됨) |
| 더 높은 revision | 바꾸지 않는다. Step·Job은 `COMPLETED`, 종료 코드 `NOT_PUBLISHED_OLDER_REVISION` |

- snapshot이 이미 `COMPLETED`면(공개 TX는 commit됐는데 Batch 기록만 실패한 뒤의 재실행) 1~3을 하지 않는다. 저장된 결과 checksum과 `ranking_entry`로 다시 계산한 값이 같은지만 확인하고 4를 다시 판단한다. 같은 결과로 수렴하며 완성 랭킹·완성 시각을 다시 만들지 않는다.
- 결과 checksum: SHA-256. 순위 순서로 `productId TAB score TAB rank LF`이며 마지막 행도 LF로 끝난다.
- 구매가 없는 주는 랭킹 0건인 완성본으로 공개한다.

### 공개 commit 뒤 Batch 기록이 실패했을 때

공개 TX가 commit된 뒤 공개 Step의 최종 상태 저장이 실패하면 Spring Batch는 그 Step을 `UNKNOWN`으로 보고 Job도 `UNKNOWN`으로 끝낸다. DB의 공개 Step은 `STARTED`로 남고, 같은 parameter의 재실행은 `JobExecutionAlreadyRunningException`으로 거부된다(실제 재현: `WeeklyRankingPublishTest`). 운영 절차는 다음과 같다.

1. 실행이 정말 끝났는지 확인: `STARTED`·`UNKNOWN`은 아직 돌고 있는 실행일 수도 있다. 그 실행을 띄운 commerce-batch 프로세스가 남아 있지 않은지, 같은 JobInstance의 다른 실행이 진행 중이지 않은지 먼저 확인한다. 살아 있으면 끝날 때까지 기다리거나 멈춘 뒤 다시 확인한다.
2. 공개 상태 확인: `ranking_publication`이 그 snapshot을 가리키는지, `ranking_snapshot.status`가 `COMPLETED`인지, `ranking_entry`의 결과 checksum이 저장값과 같은지.
3. 멈춘 실행 표시: 1·2를 확인한 뒤에만, 그 JobExecution과 공개 StepExecution을 지우지 않고 `FAILED`로 표시한다. 확인 없이 `FAILED`로 바꾸는 일반 규칙으로 쓰지 않는다.
4. 같은 parameter로 재시작: 완료된 입력 고정·계산은 건너뛰고 공개 Step만 다시 실행되어 같은 결과로 수렴한다.

2에서 공개되지 않았다면(pointer가 다른 snapshot이고 상태가 `FROZEN`) 공개 TX가 rollback된 것이므로 3·4로 공개를 다시 시도한다. 이 절차는 [ADR 0082](../adr/0082-publish-verified-snapshot-atomically-and-converge.md)에 둔다.

### 프로세스가 강제 종료돼 실행 기록이 남았을 때

배포·OOM·서버 재시작으로 프로세스가 죽으면 아무도 `FAILED`를 쓰지 못해 실행 기록이 `STARTED`로 남는다. 같은 parameter의 재실행은 `JobExecutionAlreadyRunningException`(`UNKNOWN`이면 `JobRestartException`)으로 거부된다. 위 1·2를 확인한 뒤 3의 표시는 정리 SQL 파일로 한다.

- 파일: `apps/commerce-batch/src/main/resources/runbook/mark-execution-failed.sql` (`:jobExecutionId`). Step·Job 실행 기록의 상태를 `FAILED`로 바꾸고 `VERSION`을 1 올린다. 업무 테이블은 건드리지 않는다.
- `VERSION + 1`은 1의 판단이 틀렸을 때의 안전장치다. 끝난 줄 알았던 실행이 살아 있으면, 그 실행이 다음 chunk를 commit하면서 같은 TX에서 Step 기록을 저장할 때 version이 맞지 않아(`OptimisticLockingFailureException`, "JobRepository failure forcing rollback") 그 chunk 전체가 rollback된다. 두 실행이 같은 점수를 겹쳐 쓰지 않는다.
- 그렇게 멈춘 옛 실행은 기록을 `UNKNOWN`으로 남긴다. 이때는 정리 SQL을 한 번 더 실행한 뒤 재시작한다.
- 검증: `StuckExecutionRecoveryTest`([ADR 0092](../adr/0092-clear-stuck-execution-with-version-bump.md)).

### Step 안에서는 재시도하지도 건너뛰지도 않는다

Spring Batch의 chunk 재시도(`faultTolerant().retry()`)·건너뛰기(`skip`)를 쓰지 않는다. 실패하면 Job은 `FAILED`로 끝나고, 같은 parameter로 다시 실행하는 것이 재시도다. 이유와 대안은 [ADR 0093](../adr/0093-no-retry-or-skip-inside-steps.md)에 둔다.

### 완성 조건이 실패했을 때 (틀린 계산)

계산 Step은 이미 `COMPLETED`라 재시작은 공개만 다시 하고 같은 이유로 실패한다. 그 기간의 다음 revision도 `FROZEN`인 snapshot 때문에 시작할 수 없다.

1. 원인(계산 코드)을 고친다.
2. 틀린 snapshot을 지우지 않고 `ABANDONED`로 표시한다: `UPDATE ranking_snapshot SET status = 'ABANDONED' WHERE snapshot_id = ? AND status = 'FROZEN'`.
3. 다음 revision(마지막 + 1)을 실행한다. 입력은 그 시점의 원본으로 새로 고정된다.

`ABANDONED` snapshot은 공개 Step이 거부하므로, 그 JobInstance를 재시작해도 공개되지 않는다.

## 7. 주간 랭킹 API (commerce-api)

`GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10`

| 항목 | 계약 |
| --- | --- |
| `periodStart` | 필수, `yyyy-MM-dd`, 월요일(UTC 주간 시작). 형식이 틀리거나 월요일이 아니거나 없으면 400. UTC 기준이라 한국 시간으로는 그 월요일 09:00부터 다음 월요일 09:00 전까지의 구매다(1절) |
| `limit` | 기본 10, 1~100. 범위 밖이거나 숫자가 아니면 400 |
| 읽는 곳 | 기간의 `ranking_publication`이 가리키는 snapshot의 `ranking_entry`만. stage·다른 주·다른 revision은 읽지 않는다 |
| 일관성 | 한 읽기 TX(REPEATABLE READ)에서 pointer·snapshot 정보·완성 랭킹을 읽어, 도중에 pointer가 바뀌어도 섞지 않는다 |
| 공개 조건 | 기존 고객 상품 조회 규칙(`ProductQueryService.getActiveByIdsInOrder`: 상품·브랜드가 삭제되지 않음)을 적용한 **뒤** limit을 채운다. 순위 순서로 limit개씩 읽으며 보이는 상품을 모은다 |
| 순위 | 배치가 저장한 계산 순위를 그대로 준다. 제외된 상품 자리에 빈칸이 생길 수 있다(1, 4 …). 내부 랭킹 행은 지우지 않는다 |

응답 `data`:

```json
{
  "periodStart": "2026-08-17",
  "periodEnd": "2026-08-24",
  "status": "PUBLISHED",
  "snapshotId": "weekly-20260817-r1",
  "revision": 1,
  "inputFrozenAt": "2026-08-24T00:30:00Z",
  "items": [
    { "rank": 1, "score": 5, "productId": 7, "productName": "…", "price": 7000, "brandId": 1, "brandName": "…" }
  ]
}
```

- `periodEnd`는 포함하지 않는 다음 월요일이다.
- `inputFrozenAt`은 입력 고정 시점(UTC)이다. 이 랭킹이 그 시점까지 DB에 들어온 구매 기준임을 밝힌다.
- 공개본이 없으면 `status: NOT_PUBLISHED`, `snapshotId`·`revision`·`inputFrozenAt` 없음, `items: []`이다.
- 구매가 없던 주의 완성본은 `status: PUBLISHED`, snapshotId 있음, `items: []`이다.
- 상품 이름·가격·브랜드는 조회 시점의 현재 값이다.

구조: `RankingV1Controller` → `WeeklyRankingQueryService`(application) → `WeeklyRankingReader`(domain 인터페이스) / `JdbcWeeklyRankingReader`(infrastructure, SQL).
