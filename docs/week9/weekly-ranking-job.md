# 주간 구매 랭킹 Job 설계

`weeklyRankingJob`은 특정 UTC 주간의 구매 활동을 고정하고, 상품별 구매 수량 합계를 계산해 완성된 랭킹만 공개한다. 입력은 [구매 활동 테이블](../../db/migrations/week6/002-create-purchase-activity-tables.sql)의 행을 사용한다.

## Job 식별


| 파라미터          | 규칙                                                                           |
| ------------- | ---------------------------------------------------------------------------- |
| `periodStart` | 주간 시작일. `YYYY-MM-DD` 형식의 UTC 월요일                                             |
| `snapshotId`  | 이번 입력 snapshot 식별자. 같은 작업의 재시작에서는 그대로 사용한다. |




## Step 흐름


| 순서  | Step                        | 방식      | 책임                                                                                    |
| ---: | --------------------------- | ------- | ------------------------------------------------------------------------------------- |
| 1   | `fixPurchaseSnapshotStep`   | Tasklet | `confirmed_at`이 `[periodStart, 다음 월요일)`인 구매 활동을 읽어 snapshot 입력과 snapshot 메타데이터를 고정한다. |
| 2   | `calculateWeeklyScoresStep` | Chunk   | 고정 입력에서 상품별 수량 합계를 계산해 점수 stage에 기록한다.                                                |
| 3   | `publishWeeklyRankingStep`  | Tasklet | 계산 완료를 확인하고 순위를 확정해 공개 snapshot을 지정한다.                                                |

## 재시작 흐름

1. Snapshot Step이 성공하면 입력과 메타데이터가 저장되고, 이후 재시작에서 이 Step은 다시 실행하지 않는다.
2. 점수 계산 중 실패하면 이미 commit된 chunk와 reader checkpoint를 보존한다.
3. 같은 `periodStart`, `snapshotId`와 영속 JobRepository로 다시 실행하면 같은 JobInstance의 새 JobExecution이 checkpoint부터 계산을 잇는다.
4. 계산 완료 뒤 공개 Step을 수행한다. 공개가 commit되기 전까지 기존 공개 snapshot을 유지한다.

Snapshot 입력·점수·Batch metadata는 재시작 사이에 초기화하지 않는다.




