# 9주차 · 주간 구매 랭킹 그림 3장

상세 결정은 [weekly-ranking-design.md](weekly-ranking-design.md), 개요는 [README.md](README.md), 테이블 구성도는 [tables.md](tables.md). 그림은 2026-11-20 구현 기준이다.

## 1. 요약도 — 무엇이 어디로 흐르나

```mermaid
flowchart LR
    subgraph W67["W6·W7 (이미 있음)"]
        PG[PG 확정] --> OB[Outbox relay] --> K[Kafka] --> C[streamer consumer]
        C --> PPA[(product_purchase_activity)]
    end

    subgraph W9B["W9 commerce-batch · Job weeklyRanking"]
        S1["Step 1 freezeWeeklyInputStep<br/>입력 복사 + 회차 행 (한 TX)"]
        S2["Step 2 calculateWeeklyScoresStep<br/>상품별 합계 절대값 upsert (chunk 2)"]
        S3["Step 3 openWeeklyRankingStep<br/>순위 → COMPLETED → pointer (한 TX)"]
        S1 --> S2 --> S3
    end

    subgraph DB["weekly_ranking_* (정의: 공유 모듈 ranking-schema · 쓰기: batch 만)"]
        IN[(inputs)]
        REV[(revisions)]
        SC[(scores)]
        PUB[(publications)]
    end

    subgraph W9A["W9 commerce-api"]
        API["GET /api/v1/rankings/weekly<br/>pointer → COMPLETED 회차 → 점수<br/>R4 재검증, 삭제 상품 제외 뒤 limit"]
    end

    PPA -.읽기.-> S1
    S1 --> IN & REV
    S2 --> SC
    S3 --> SC & REV & PUB
    PUB & REV & SC -.읽기.-> API

    G1[/"보장 1 같은 입력 고정"/] -.- S1
    G2[/"보장 2 멈춘 자리부터 이어 감"/] -.- S2
    G3[/"보장 3 완성본만 공개"/] -.- S3
```

## 2. 구체 흐름도 — 한 번의 실행과 재시작·재계산

```mermaid
sequenceDiagram
    autonumber
    participant L as JobLauncher
    participant V as JobParametersValidator
    participant S1 as Step 1 Freeze (TX, READ_COMMITTED)
    participant S2 as Step 2 Score (chunk TX ×N)
    participant S3 as Step 3 Open (TX)
    participant DB as MySQL (업무 + BATCH_ 같은 DataSource)
    participant API as Ranking API

    L->>V: periodStart·snapshotId (identifying)
    V-->>L: 형식·날짜 일치·월요일·revision≥1 아니면 거절 → JobInstance 미생성

    L->>S1: 시작
    S1->>DB: 회차 중복 조회 (snapshot_id | period+revision) → 있으면 conflict 실패
    S1->>DB: INSERT inputs SELECT FROM product_purchase_activity<br/>WHERE confirmed_at ∈ [월 00:00Z, +7일) AND deleted_at IS NULL
    S1->>DB: 복사본 재조회 → input_count·expected_product_count·input_checksum
    S1->>DB: INSERT revisions (CALCULATING, frozen_at=Clock)
    Note over S1,DB: 실패 시 전부 rollback → 반쪽 snapshot 없음

    loop chunk 2개씩 (reader: DISTINCT product_id ORDER BY product_id)
        S2->>DB: processor SUM(quantity) FROM inputs WHERE snapshot_id, product_id
        S2->>DB: writer INSERT scores … ON DUPLICATE KEY UPDATE score=VALUES(score)
        S2->>DB: 같은 TX 에 reader checkpoint (weeklyProductIdReader.read.count)
    end
    Note over S2,DB: 중간 실패 → 같은 파라미터 재실행 = 같은 JobInstance·새 JobExecution<br/>Step 1 재실행 없음, checkpoint 다음 상품부터

    S3->>DB: ① revisions FOR UPDATE
    S3->>DB: ② COUNT(scores) == expected_product_count
    S3->>DB: ③ UPDATE scores SET ranking = ROW_NUMBER() OVER (score DESC, product_id ASC)
    S3->>DB: ④ 1..N 연속 검증, ⑤ result_checksum·status=COMPLETED
    S3->>DB: ⑥ pointer: SELECT revision FOR SHARE → UPDATE … WHERE revision<=? → 0행이면 INSERT 또는 거절
    Note over S3,DB: 거절(더 새 revision 공개됨) → pointer 불변, Job COMPLETED + exitDescription NOT_PUBLISHED<br/>어느 단계 예외든 ranking·status·pointer 전부 rollback

    API->>DB: ① publications JOIN revisions(COMPLETED) WHERE period_start
    API->>DB: ② scores WHERE snapshot_id(①) ORDER BY ranking
    API->>API: R4 재검증(행 수·NOT NULL·1..N) 위반 → 500 / 삭제 상품 제외 → limit
    Note over API: 늦은 구매는 현재 공개본에 없음. 반영은 새 snapshotId(rN+1) 로 재실행 = 새 JobInstance
```

## 3. 클래스 다이어그램 — 누가 누구를 쓰나

```mermaid
classDiagram
    direction LR

    namespace commerce_batch {
        class WeeklyRankingJobConfig {
            +JOB_NAME = "weeklyRanking"
            +weeklyRankingJob() Job
            +freezeWeeklyInputStep() Step
            +calculateWeeklyScoresStep() Step
            +openWeeklyRankingStep() Step
            ~weeklyRankingParametersValidator()
        }
        class FreezeWeeklyInputTasklet {
            <<StepScope>>
            -JdbcTemplate
            -Clock
            +execute()
        }
        class WeeklyScoreStepComponents {
            <<Configuration>>
            +weeklyProductIdReader(snapshotId) JdbcCursorItemReader~Long~
            +weeklyScoreProcessor(snapshotId, hook) ItemProcessor
            +weeklyScoreWriter(snapshotId) ItemWriter
            +noOpScoreProcessingHook()
        }
        class OpenWeeklyRankingTasklet {
            <<StepScope>>
            -lockRevision()
            -verifyScoreCount()
            -assignRanking()
            -verifyRankingShape()
            -completeRevision()
            -publishPointer()
        }
        class WeeklySnapshotId {
            <<record>>
            +parse(snapshotId)
            +of(snapshotId, periodStart)
            +periodStartUtc()
            +periodEndUtc()
        }
        class RankingChecksum {
            +serializeInputs() / inputChecksum()
            +serializeResults() / resultChecksum()
            +sha256Hex()
        }
        class ProductScore {
            <<record>>
            productId, score
        }
        class ScoreProcessingHook {
            <<interface>>
            +beforeProcess(snapshotId, productId)
        }
        class BatchClockConfig {
            +clock() Clock
        }
    }

    namespace ranking_schema {
        class WeeklyRankingInput {
            <<Entity>>
            snapshot_id, order_id, product_id, quantity, confirmed_at
        }
        class WeeklyRankingRevision {
            <<Entity, PK snapshot_id>>
            period_start, revision, status, frozen_at, counts, checksums
        }
        class WeeklyRankingScore {
            <<Entity>>
            snapshot_id, product_id, score, ranking?
        }
        class WeeklyRankingPublication {
            <<Entity, PK period_start>>
            snapshot_id, revision, published_at
        }
    }

    namespace commerce_api {
        class RankingV1Controller {
            +getWeeklyRanking(periodStart, limit)
        }
        class RankingV1Dto {
            WeeklyRankingResponse, Item
        }
        class WeeklyRankingFacade {
            +getWeeklyRanking(periodStart, limit) WeeklyRankingInfo
            -verifyPublishedShape()
        }
        class WeeklyRankingInfo {
            <<record>>
            status, periodStart, periodEnd, snapshotId, frozenAt, items
        }
        class WeeklyPeriod {
            <<record>>
            +parse(periodStart)
            +periodEnd()
        }
        class WeeklyRankingReader {
            <<interface, port>>
            +findPublished(periodStart) Optional~PublishedSnapshot~
            +findRankedScores(snapshotId) List~RankedScore~
        }
        class PublishedSnapshot {
            <<record>>
            snapshotId, revision, frozenAt, expectedProductCount
        }
        class RankedScore {
            <<record>>
            productId, score, ranking?
        }
        class WeeklyRankingJdbcReader {
            -JdbcTemplate
        }
        class ProductService {
            +findActiveProducts(ids)
        }
    }

    WeeklyRankingJobConfig --> FreezeWeeklyInputTasklet
    WeeklyRankingJobConfig --> WeeklyScoreStepComponents
    WeeklyRankingJobConfig --> OpenWeeklyRankingTasklet
    WeeklyRankingJobConfig ..> WeeklySnapshotId : validator
    FreezeWeeklyInputTasklet ..> WeeklySnapshotId
    FreezeWeeklyInputTasklet ..> RankingChecksum
    WeeklyScoreStepComponents ..> ProductScore
    WeeklyScoreStepComponents ..> ScoreProcessingHook
    OpenWeeklyRankingTasklet ..> RankingChecksum
    FreezeWeeklyInputTasklet ..> BatchClockConfig : Clock
    WeeklyScoreStepComponents ..> BatchClockConfig : Clock
    OpenWeeklyRankingTasklet ..> BatchClockConfig : Clock
    FreezeWeeklyInputTasklet ..> WeeklyRankingInput : INSERT
    FreezeWeeklyInputTasklet ..> WeeklyRankingRevision : INSERT
    WeeklyScoreStepComponents ..> WeeklyRankingScore : upsert
    OpenWeeklyRankingTasklet ..> WeeklyRankingScore : ranking
    OpenWeeklyRankingTasklet ..> WeeklyRankingRevision : COMPLETED
    OpenWeeklyRankingTasklet ..> WeeklyRankingPublication : pointer

    RankingV1Controller --> WeeklyRankingFacade
    RankingV1Controller ..> RankingV1Dto
    WeeklyRankingFacade --> WeeklyRankingReader
    WeeklyRankingFacade --> ProductService
    WeeklyRankingFacade ..> WeeklyPeriod
    WeeklyRankingFacade ..> WeeklyRankingInfo
    WeeklyRankingReader ..> PublishedSnapshot
    WeeklyRankingReader ..> RankedScore
    WeeklyRankingJdbcReader ..|> WeeklyRankingReader
    WeeklyRankingJdbcReader ..> WeeklyRankingPublication : SELECT (JDBC, Repository 없음)
    WeeklyRankingJdbcReader ..> WeeklyRankingRevision : SELECT
    WeeklyRankingJdbcReader ..> WeeklyRankingScore : SELECT
```

- 엔티티 4개는 공유 모듈 `modules/ranking-schema` 에 있고 테이블 정의(DDL)의 원본이다. batch·api 가 함께 의존해 어느 쪽 `ddl-auto` 로도 테이블이 생기지만, 쓰기는 batch 의 Tasklet·writer 만 JdbcTemplate 으로 하고 api 는 Repository 없이 JDBC 로 읽기만 한다(R5).
- `ScoreProcessingHook` 은 테스트가 Step 2 중간 실패를 만들기 위한 seam 이며 제품 기본 구현은 no-op 이다.
- 점선은 "SQL 로 접근" 또는 "값 객체 사용", 실선은 빈 주입이다.
