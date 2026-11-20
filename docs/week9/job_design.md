### Job 구조

```
JobParameters : periodStart=2026-08-17, snapshotId=weekly-20260817-r1   (둘 다 식별, run.id 없음 — D1)
JobInstance   : (weeklyRanking, periodStart, snapshotId)
재시작        : 같은 파라미터로 다시 launch → 같은 JobInstance의 새 JobExecution
재계산        : snapshotId 의 revision 만 +1 (weekly-20260817-r2) → 새 JobInstance (D15)
Validator     : 시작 전에 형식·날짜 일치·월요일·revision≥1 검사 → 위반이면 JobInstance 자체가 생기지 않음 (D1)

weeklyRanking
 │
 ├─ 1. freezeWeeklyInputStep            Tasklet   TX: REQUIRED + READ_COMMITTED            (D9)
 │     product_purchase_activity ──INSERT…SELECT──▶ weekly_ranking_inputs
 │       조건: confirmed_at ∈ [periodStart 00:00Z, +7d 00:00Z), deleted_at IS NULL
 │     + weekly_ranking_revisions 행 INSERT
 │       status=CALCULATING, input_count, expected_product_count, input_checksum, frozen_at(Clock)
 │     실패 → 전부 rollback (반쪽 snapshot 없음)
 │     완료 후 재시작 → 다시 실행하지 않음 (allowStartIfComplete=false)
 │
 ├─ 2. calculateWeeklyScoresStep        chunk(2)  TX: 기본, JobRepository와 같은 JpaTransactionManager  (D10)
 │     reader    weeklyProductIdReader : SELECT DISTINCT product_id … ORDER BY product_id  (saveState)
 │     processor : SUM(quantity) — weekly_ranking_inputs(frozen)에서만
 │     writer    : INSERT … ON DUPLICATE KEY UPDATE score = VALUES(score)   (ranking 미접촉 — R2)
 │     chunk마다 [점수 2건 + reader 위치]가 같은 commit → 실패 시 마지막 commit 다음 상품부터
 │
 └─ 3. openWeeklyRankingStep            Tasklet   TX: 기본, 아래 전부 한 TX                (D11)
       ① 회차 FOR UPDATE
       ② 점수 행 수 == expected_product_count
       ③ ROW_NUMBER() OVER (score DESC, product_id ASC) → ranking
       ④ ranking 1..N 연속 검증
       ⑤ result_checksum 계산·저장, status=COMPLETED  (기존 값과 다르면 실패 — R6)
       ⑥ pointer: SELECT revision … FOR SHARE (현재 공개 revision 읽기)
                 → UPDATE … WHERE period_start=? AND revision <= ?
            1행 → 공개 (같은 revision 재실행이면 UNCHANGED, published_at 유지)
            0행 → 현재 행 있음: 거절(D8, exitDescription "NOT_PUBLISHED: newer revision rN …", Job은 COMPLETED)
                   현재 행 없음: INSERT (첫 공개)
       어느 단계 실패 → ranking·status·pointer 전부 rollback, 이전 공개본 유지 (R3)
```

| 테이블 | 쓰는 Step | 읽는 쪽 |
|---|---|---|
| weekly_ranking_inputs | 1 | 2 (reader·processor), 1 (checksum) |
| weekly_ranking_revisions | 1 (INSERT), 3 (COMPLETED) | 3, API |
| weekly_ranking_scores | 2 (score), 3 (ranking) | 3, API |
| weekly_ranking_publications | 3 | API |

엔티티 4개는 공유 모듈 `modules/ranking-schema` (batch·api 공통 의존). 쓰기는 batch 만, api 는 Repository 없이 JDBC 읽기만 (R5).

API(`GET /api/v1/rankings/weekly?periodStart=&limit=`)는 publications → revisions(COMPLETED) → scores 순서로만 읽고(쿼리 2개, ②는 ①의 snapshotId 로),
읽은 행에 R4(행 수 == expected, ranking NOT NULL, 1..N)를 재검증해 위반이면 500 으로 드러낸다. 삭제 상품 제외 → limit → `rank`(표시 순위 1..n)·`calcRank`(DB ranking, 간격 가능) 함께 응답 (D14).