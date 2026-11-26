# 9주차 · 주간 구매 랭킹 (Spring Batch) 개요

상세 결정·근거·실측은 [weekly-ranking-design.md](weekly-ranking-design.md). 이 문서는 그 요약이다.

## 1. 한 줄 요약

W6·W7 consumer 가 저장한 `product_purchase_activity` 를 commerce-batch 의 `weeklyRanking` Job 이 주간 단위로 고정·계산·공개하고, commerce-api 가 `GET /api/v1/rankings/weekly` 로 공개본만 읽는다. 보장은 셋이다: 같은 입력을 고정한다, 멈춘 자리부터 같은 작업을 이어 간다, 완성본만 공개한다.

## 2. 흐름

```
product_purchase_activity (W6·W7)
  → Step 1 freezeWeeklyInputStep   : 그 주 행을 weekly_ranking_inputs 로 복사 + 회차 행 (한 TX)
  → Step 2 calculateWeeklyScoresStep: 상품 1개 = chunk item 1개, 합계를 절대값 upsert (chunk 2)
  → Step 3 openWeeklyRankingStep    : 순위 부여 → 회차 COMPLETED → 공개 pointer 조건부 갱신 (한 TX)
  → GET /api/v1/rankings/weekly     : pointer → COMPLETED 회차 → 점수 행 (쿼리 2개, R4 재검증)
```

## 3. 만든 것

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| Job `weeklyRanking` | batch `batch/job/ranking/WeeklyRankingJobConfig` | 식별 파라미터 `periodStart`·`snapshotId`, Validator, Step 3개 연결 |
| Step 1 | batch `step/FreezeWeeklyInputTasklet` | 입력 복사·건수·checksum·회차 생성 (READ_COMMITTED) |
| Step 2 | batch `step/WeeklyScoreStepComponents` | reader·processor·writer 빈, frozen 입력에서만 계산 |
| Step 3 | batch `step/OpenWeeklyRankingTasklet` | 순위·완성·pointer 갱신, 거절 시 exitDescription |
| 테이블 4개 | 공유 모듈 `modules/ranking-schema` `domain/ranking/WeeklyRanking{Input,Revision,Score,Publication}` (batch·api 공통 의존, 쓰기는 batch 만) | 입력 / 회차 / 점수+순위 / 공개 pointer |
| 순수 계산 | batch `domain/ranking/{WeeklySnapshotId,RankingChecksum}` | 기간·snapshotId 규칙, SHA-256 직렬화 |
| API | api `interfaces/api/ranking/RankingV1Controller` → `WeeklyRankingFacade` → `WeeklyRankingJdbcReader` | 공개본 조회(PUBLISHED / NOT_PUBLISHED 구분), 삭제 상품 제외 뒤 limit, 표시 순위 `rank` 와 계산 순위 `calcRank` 함께 반환 |
| batch 테스트 | `WeeklyRankingJobRestartTest`·`FreezeWeeklyInputStepTest`·`WeeklyScoreWriterTest`·`OpenWeeklyRankingConvergenceTest`·`WeeklyRankingRevisionRejectTest`·`WeeklyRankingConcurrentLaunchTest` | §6 재시작, 경계·충돌·0행, writer 멱등, 수렴·rollback, D8, 동시 실행 |
| api·연결 테스트 | `WeeklyRankingV1ApiTest`, crossAppTest `WeeklyRankingFlowE2ETest` | API 계약 15건, PG→Kafka→Job→API §7 흐름 |

## 4. 핵심 결정 7개

| 결정 | 선택 | 이유 | 상세 |
|---|---|---|---|
| 입력 고정 | `INSERT ... SELECT` 한 문장으로 복사 + 회차 행, 한 TX | 복사본만 읽으면 실행 중 도착한 구매가 결과를 바꾸지 못한다 | [D9](weekly-ranking-design.md#d9) |
| 점수 쓰기 | 절대값 upsert, chunk commit 과 reader checkpoint 같은 TX | 같은 상품을 다시 처리해도 `5 → 5` 로 수렴하고 재시작 위치가 commit 과 어긋나지 않는다 | [D10](weekly-ranking-design.md#d10) |
| 공개 경계 | 순위 + COMPLETED + pointer 를 한 TX | 어느 단계에서 실패해도 부분 결과가 고객에게 보이지 않는다 | [D11](weekly-ranking-design.md#d11) |
| 공개 pointer | 별도 테이블, `revision <=` 조건부 갱신 | 같은 revision 재실행은 수렴하고 더 새 revision 은 덮이지 않는다 | [D5](weekly-ranking-design.md#d5) |
| 갱신 거절 | 회차는 COMPLETED, pointer 불변, Job 도 COMPLETED | 계산은 정상이고 경쟁에서 밀린 것뿐이라 재시작할 이유가 없다 | [D8](weekly-ranking-design.md#d8) |
| 늦은 구매 | 새 `snapshotId`(rN+1) = 새 JobInstance 로 재계산 | 재시작(같은 입력)과 재계산(입력 변경)을 파라미터로 구분한다 | [D15](weekly-ranking-design.md#d15) |
| API 읽기 | pointer 경로만, R4 재검증 후 500, 표시 순위와 계산 순위를 함께 반환 | 완성본 불변식이 깨지면 드러내고, 발제대로 두 순위를 모두 계약에 명시한다 | [D14](weekly-ranking-design.md#d14) |

## 5. 검증 증거 (상세 9절)

- 재시작: 첫 실행 JobInstance 13 / JobExecution 15 FAILED(read 3·write 2·commit 1·rollback 1, `read.count=2`) → 늦은 구매 1행 도착 → 같은 파라미터 재실행 JobInstance **13** / JobExecution 16 COMPLETED(read 1·write 1), 입력 4행·checksum 불변. 늦은 행은 새 revision r3 에서만 반영(입력 5행).
- 재시작 vs 정상 1회(r2, 먼저 실행): input checksum `3e90276e…`, result checksum `745d50bb…` 모두 일치, 점수·순위 집합 동일. r3 은 두 checksum 모두 다름.
- §7 흐름: 주문 5건 → consumer → Job 6회(r1~r4, 다음 주 r1, 실패 1회) → API. 재발행 뒤 활동 4행 유지, 늦은 구매는 r3 에서만 반영, 경계 행은 다음 주로.
- 회귀(`:apps:commerce-batch:check :apps:commerce-api:check`, 최종): batch 24건, api 591건, crossAppTest 3건, 실패·skip 0. ArchUnit·Checkstyle 포함.

## 6. 범위 밖

프로세스 kill 뒤 `STARTED` 잔류 수동 복구 · 배치 다중 인스턴스 · 실패 알림 · `confirmed_at` 인덱스(측정 없이 추가하지 않음) · 마이그레이션 도구 · batch ArchUnit. 결정별 대가까지 포함한 한계 전체(25건, 한 것/남은 것 구분)는 [상세 7-2 한계 모음](weekly-ranking-design.md#7-2).

## 7. 더 읽기

- 상세 설계(D1~D15, R1~R7, 장애 표, 실측): [weekly-ranking-design.md](weekly-ranking-design.md)
- 사전 조사·probe 결과: [reference/01-batch-module-survey.md](reference/01-batch-module-survey.md), [reference/02-probe-result.md](reference/02-probe-result.md)
