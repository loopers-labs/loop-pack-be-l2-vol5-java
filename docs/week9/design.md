# 주간 구매 랭킹 배치 설계 — Week 9

> 중단된 배치를 같은 입력·같은 작업으로 이어서, 정상 실행과 같은 랭킹을 완성본으로만 공개한다.

## 0. 목표와 범위

- **입력:** W6·W7 consumer가 남긴 `product_purchase_activity`(주문·상품마다 1행, `quantity`, `confirmed_at`). eventId가 아니라 `(order_id, product_id)`가 업무 단위다.
- **점수:** 해당 주에 확정된 상품별 구매 수량의 합(정수). 조회·좋아요·매출과 섞지 않는다. W8 쿠폰은 건드리지 않는다.
- **순서:** `score DESC, product_id ASC`. 동점 규칙도 계산의 일부다.
- **기간:** UTC `[월요일 00:00, 다음 월요일 00:00)`. 주 소속은 `confirmed_at`으로 판단한다.
- **만드는 것:**
  - `commerce-batch`의 `weeklyRanking` job(입력 고정 → 점수 계산 → 완성본 공개)
  - `commerce-api`의 `GET /api/v1/rankings/weekly`
  - 둘이 함께 쓰는 랭킹 스키마 모듈 `modules:ranking`
- **범위 밖:** Redis 실시간 랭킹, 새 분석 이벤트, 처리량 튜닝(chunk 크기 2는 재시작 실험 값이다).

## 1. 흐름

```
PG 확정 주문 → Outbox → Kafka → streamer → product_purchase_activity
                                                      │ (그 주 행을 복사)
weeklyRanking(periodStart, snapshotId)                ▼
  ① freezeWeeklyInput   tasklet  ranking_snapshot(FROZEN) + ranking_snapshot_input
  ② calculateWeeklyScores chunk  item = productId → ranking_score_stage (절대값 upsert)
  ③ publishWeeklyRanking tasklet weekly_ranking + COMPLETED + ranking_publication(조건부)
                                                      │
GET /api/v1/rankings/weekly ── ranking_publication → 그 snapshot의 weekly_ranking만 읽음
```

## 2. 결정 사항

| # | 결정 | 이유·대안 |
|---|---|---|
| D0 | 이전 주차 docs 변경은 커밋하지 않고 작업 트리에 둔다 | 9주차 커밋과 섞지 않는다. 정리는 사용자가 한다 |
| D1 | 랭킹 테이블 5개는 `modules:ranking`의 `ranking-schema.sql` 하나로 정의한다. local·test 프로필에서 `spring.sql.init`이 `CREATE TABLE IF NOT EXISTS`로 만든다 | batch가 JDBC로 쓰고 api가 JDBC로 읽는다. 복합 PK·유일키를 SQL 그대로 표현하고, 정의가 한 곳에만 있다. JPA 엔티티로 만들면 DDL 생성만을 위한 복합키 클래스가 5벌 생기고, 앱마다 정의하면 두 벌이 어긋날 수 있다 |
| D2 | batch는 `product_purchase_activity`를 SQL로만 읽는다. batch 테스트는 그 테이블 DDL을 직접 만든다 | streamer 도메인에 의존하지 않는다. 실제 테이블과 맞는지는 E2E(§8)가 검증한다 |
| D3 | 식별 parameter는 `periodStart`(yyyy-MM-dd)와 `snapshotId`. snapshotId는 `weekly-{yyyyMMdd}-r{revision}` 형식이고, job이 형식과 periodStart 일치를 검증한다 | 재시작에는 같은 값을 다시 넘겨야 한다. job 안에서 만들면 재시작 때 같은 값을 알 수 없다. revision을 올리는 것은 명시적인 재계산 요청이다 |
| D4 | `ranking_snapshot (period_start, revision)` 유일키, 새 revision은 기존 최대값보다 커야 한다. pointer는 행 잠금 후 revision을 비교해 조건부로 바꾼다 | DB 제약만으로 중복 revision과 역행 공개를 막는다. 같은 기간 계산은 직렬로 실행하는 것을 전제로 한다 |
| D5 | API: limit 기본 10, 1~100, 범위 밖·월요일 아님·날짜 형식 오류는 400. 순위는 계산 당시 값 그대로(삭제 상품 때문에 1, 3처럼 빌 수 있음). 표시 정보는 기존 `ProductFacade.getProductsByIds` 규칙 | 과제 계약. 캐시는 이번 범위에서 쓰지 않는다 |
| D6 | 공개 commit 뒤 metadata만 FAILED이면 같은 instance를 재시작한다. publish는 이미 COMPLETED인 snapshot이면 ranking을 다시 넣지 않고 pointer만 확인해 같은 결과로 수렴한다 | 공개본을 되돌리지 않고, metadata를 실제 상태에 맞춘다 |
| D7 | E2E는 기존 `commerce-api` `e2eTest` 소스셋에 batch를 추가하고, job 구성만 명시적으로 불러와 `JobLauncher`로 실행한다 | W7에서 streamer를 붙인 방식과 같다 |

## 3. 테이블 (`modules/ranking/src/main/resources/ranking-schema.sql`)

| 테이블 | 키 | 내용 |
|---|---|---|
| `ranking_snapshot` | PK `snapshot_id`, UK `(period_start, revision)` | 기간, revision, status(FROZEN/COMPLETED), frozen_at, input_count, product_count, input_checksum, result_checksum, completed_at |
| `ranking_snapshot_input` | PK `(snapshot_id, order_id, product_id)` | quantity, confirmed_at. 고정 후 바뀌지 않는다 |
| `ranking_score_stage` | PK `(snapshot_id, product_id)` | 계산 중 절대 점수. API는 읽지 않는다 |
| `weekly_ranking` | PK `(snapshot_id, product_id)`, IDX `(snapshot_id, rank_no)` | score, rank_no(계산 순위). `rank`는 MySQL 예약어라 쓰지 않는다 |
| `ranking_publication` | PK `period_start` | 공개 snapshot_id, revision, published_at |

- 시각은 모두 UTC `DATETIME(6)`이다. 원본 활동도 Hibernate가 UTC로 저장한다(`NORMALIZE_UTC`). JDBC로는 `LocalDateTime`으로 읽고 써서 JVM 시간대(Asia/Seoul)가 끼어들지 않게 한다.
- 지난 snapshot·점수 행은 지우지 않는다.

## 4. 입력 고정 (`freezeWeeklyInput`, tasklet · 실제 DB 트랜잭션 하나)

1. snapshotId 형식과 periodStart(월요일) 검증
2. 같은 snapshotId의 manifest가 있으면
   - 기간·revision이 같고 FROZEN/COMPLETED → 아무것도 하지 않음(재시작)
   - 다르면 충돌로 실패. 덮어쓰지 않는다
3. 같은 기간의 기존 최대 revision 이상이면 충돌로 실패
4. manifest 생성 → `INSERT … SELECT`로 `confirmed_at ∈ [start, end)` 행 복사 → quantity ≤ 0이면 실패(skip하지 않음) → 건수·상품 수·입력 checksum 기록 → FROZEN

- 한 트랜잭션이라 중간에 실패하면 manifest와 입력이 함께 rollback된다. 반쪽 snapshot이 남지 않는다.
- `max(id)`로 범위를 정하지 않는다. InnoDB `INSERT … SELECT`는 원본 범위를 잠금 읽기로 읽기 때문에, 그 순간 commit된 행 집합이 그대로 복사된다. 늦게 commit되는 행은 이 snapshot에 들어오지 않고 다음 revision의 대상이 된다.
- frozen_at은 이 트랜잭션의 시각이다. 이것은 "그 주의 모든 사건이 도착했다"는 증명이 아니라, 결과의 기준 시점이다.

## 5. 점수 계산 (`calculateWeeklyScores`, chunk)

| 경계 | 구현 |
|---|---|
| Reader | `@StepScope` `JdbcCursorItemReader` `purchaseProductReader`, `select distinct product_id … where snapshot_id = ? order by product_id`, `saveState=true` |
| Processor | 같은 snapshot 입력의 `sum(quantity)`. 현재 활동 테이블은 읽지 않는다 |
| Writer | `ranking_score_stage`에 `INSERT … ON DUPLICATE KEY UPDATE score = VALUES(score)` |
| Chunk | 결과와 reader 위치가 같은 트랜잭션 매니저(Boot의 `transactionManager`)·같은 DataSource로 commit된다 |

- 상품 ID는 snapshot 안에서 유일하고 정렬이 안정적이라 checkpoint 위치가 바뀌지 않는다.
- 같은 상품을 다시 처리해도 `score = total`로 수렴한다(5 → 5). 증분 합산과 섞지 않는다.
- MySQL 드라이버는 cursor 결과를 메모리에 받는다. 읽는 값이 상품 ID뿐이라 감당할 수 있다고 보고, 대량 데이터에서는 paging reader로 바꿔야 한다.

## 6. 완성본 공개 (`publishWeeklyRanking`, tasklet · 실제 DB 트랜잭션 하나)

1. manifest를 잠그고 읽는다. COMPLETED가 아니면 stage 행 수 = product_count인지 검증 → `ROW_NUMBER() OVER (ORDER BY score DESC, product_id ASC)`로 `weekly_ranking` 저장 → 결과 checksum → COMPLETED
2. `ranking_publication`을 잠그고 읽는다.
   - 없음 → insert
   - 기존 revision < 이번 → update
   - 같은 snapshot → 그대로(재실행 수렴)
   - 기존 revision > 이번 → 바꾸지 않고 step exit status `SUPERSEDED`
- 계산 중·실패 중에는 pointer를 건드리지 않으므로 API는 이전 공개본을 보여 준다. 공개 트랜잭션이 rollback되면 ranking·상태·pointer가 모두 이전 그대로다.

## 7. 조회 API (`GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10`)

- 응답: `status`(NOT_PUBLISHED/PUBLISHED), `periodStart`, `periodEnd`, `snapshotId`, `revision`, `frozenAt`, `items[rank, productId, score, name, brandName, price]`
- 공개본 없음 → NOT_PUBLISHED, snapshotId null, 빈 items. 구매 0건 완성본 → PUBLISHED, snapshotId 있음, 빈 items
- pointer에서 snapshotId를 먼저 읽고, 그 snapshotId의 ranking만 읽는다. ranking 행은 공개 후 바뀌지 않으므로, 도중에 pointer가 바뀌어도 서로 다른 snapshot의 행이 섞이지 않는다.
- 삭제 상품(`product.deleted_at`)은 SQL join으로 먼저 거르고 그다음 limit을 적용한다. 표시 정보는 기존 상품 조회 규칙으로 붙이고, 그 사이 삭제된 상품은 뺀다.

## 8. Checksum

- 입력: `(order_id, product_id)` 순으로 `orderId\tproductId\tquantity\tconfirmedAt\n`, 결과: rank 순으로 `productId\tscore\trank\n`, SHA-256 hex
- confirmedAt 형식: `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'`(UTC, 마이크로초 6자리 고정)
- 실행 ID·실행 시각은 넣지 않는다. 기대 점수·순위 assert와 함께 쓴다.

## 9. 검증 계획 (실제 MySQL·Kafka, mock 없음)

| 테스트 | 확인 |
|---|---|
| `PurchaseInputProbeTest` | 실패 chunk 다음부터 같은 instance로 이어짐(완료) |
| freeze 테스트 | 같은 주문 여러 품목, 경계(시작 포함·끝 제외), 실패 시 반쪽 없음, 고정 뒤 늦은 구매 미반영, 충돌 |
| `RankingJobRestartTest` | §6 fixture, chunk=2, 9 직전 실패 → 재시작. 기대 7(5,1위)·9(5,2위)·8(1,3위), 정상 실행과 checksum 동일, 입력 step 재실행 없음 |
| publish 테스트 | rollback 시 이전 공개본 유지, 재실행 수렴, 오래된 revision 차단 |
| `WeeklyRankingApiTest` | 미공개·빈 완성본 구분, 삭제 상품 제외와 순위 간격, 400 조건 |
| E2E | 주문 → Kafka → 활동 → job → API, 재발행 무변화, 늦은 구매는 r2에서만 |

## 10. 실행 환경 메모

- 루트 `build.gradle.kts`의 toolchain(21)은 루트 프로젝트에만 걸려 있어서, 서브모듈은 Gradle을 실행하는 JDK로 컴파일된다. JDK 17로 실행하면 commerce-api가 컴파일되지 않으므로 `JAVA_HOME`을 21로 맞춘다.
