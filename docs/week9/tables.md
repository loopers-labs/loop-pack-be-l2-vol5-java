# 9주차 · 주간 구매 랭킹 테이블 구성 (표로 보기)

테이블은 모두 6개다. 입력 원본 1개(W6에서 만든 것), 이번에 새로 만든 4개, 상품 이름을 가져오는 기존 1개.
새 테이블 4개의 정의(엔티티)는 공유 모듈 `modules/ranking-schema` 에 있어 batch·api 어느 쪽을 띄워도 생기고, **쓰기는 commerce-batch 만**, commerce-api 는 **읽기만** 한다. 테이블 사이에 FK(외래 키)는 두지 않고 값(`snapshot_id` 등)으로만 연결한다.
자세한 DDL·결정 이유는 [weekly-ranking-design.md](weekly-ranking-design.md) 3절.

## 먼저 알아야 할 것 — 배치 Job 의 Step 3개

주간 랭킹 Job(`weeklyRanking`)은 아래 세 단계를 순서대로 실행한다. 이 문서에서 "Step 1·2·3"은 항상 이것을 뜻한다.

| 단계 | 이름 | 하는 일 | 건드리는 테이블 |
|---|---|---|---|
| **Step 1 입력 고정** | `freezeWeeklyInputStep` | 그 주의 구매 기록을 복사해 두고, 회차 명세서를 만든다 (한 트랜잭션) | 구매 기록 → **고정 입력**, **회차 명세서** |
| **Step 2 점수 계산** | `calculateWeeklyScoresStep` | 고정 입력에서 상품별 수량 합을 계산해 점수로 저장한다 (2개씩 끊어 commit) | **점수·순위** 의 점수 칸 |
| **Step 3 공개** | `openWeeklyRankingStep` | 순위를 매기고, 회차를 완성으로 바꾸고, 공개 표지판을 갱신한다 (한 트랜잭션) | **점수·순위** 의 순위 칸, **회차 명세서**, **공개 표지판** |

## 테이블 6개 한눈에 보기

| 별명 | 실제 테이블 | 한 줄 설명 | 쓰는 쪽 | 읽는 쪽 |
|---|---|---|---|---|
| 구매 기록 | `product_purchase_activity` | 확정된 주문의 "어떤 상품을 몇 개 샀다" 기록 (W6 consumer 가 저장) | streamer consumer | Step 1 |
| 고정 입력 | `weekly_ranking_inputs` | 구매 기록에서 그 주의 행만 **복사해 고정**한 것 | Step 1 | Step 2 |
| 회차 명세서 | `weekly_ranking_revisions` | 계산 1회(회차)의 명세: 어느 주, 몇 번째 계산, 몇 행, 완성됐나 | Step 1 (생성), Step 3 (완성 처리) | Step 3, API |
| 점수·순위 | `weekly_ranking_scores` | 상품별 점수(Step 2)와 순위(Step 3) | Step 2, Step 3 | Step 3, API |
| 공개 표지판 | `weekly_ranking_publications` | 주마다 "지금 고객에게 보여 줄 회차는 이것"을 가리킴 | Step 3 | API |
| 상품 | `products` | 상품 이름·삭제 여부 (기존 테이블) | commerce-api | API |

아래 글에서는 왼쪽 **별명**으로 부른다.

**흐름을 말로 풀면**
1. Step 1 이 **구매 기록**에서 그 주 행을 **고정 입력**으로 복사하고 **회차 명세서**를 만든다.
2. Step 2 가 **고정 입력**을 상품별로 합쳐 **점수·순위**의 점수 칸을 채운다.
3. Step 3 가 **점수·순위**의 순위 칸을 채우고, **회차 명세서**를 완성으로 바꾸고, **공개 표지판**을 이 회차로 돌린다.
4. API 는 **공개 표지판** → **회차 명세서**(완성인지 확인) → **점수·순위** 순서로 읽고 **상품**에서 이름을 붙인다.

## 테이블끼리 어떻게 이어지나

| 연결 | 이어 주는 값 | 뜻 |
|---|---|---|
| 구매 기록 → 고정 입력 | `order_id`·`product_id`·`quantity`·`confirmed_at` 를 복사 | 주간 기간에 들어오는 행만 `snapshot_id` 를 붙여 옮긴다 |
| 회차 명세서 ↔ 고정 입력 | `snapshot_id` | 한 회차의 입력 행들. 명세서 행이 있으면 입력도 같은 트랜잭션으로 들어간 것 |
| 회차 명세서 ↔ 점수·순위 | `snapshot_id` | 한 회차의 점수·순위 행들 |
| 공개 표지판 → 회차 명세서 | `snapshot_id` | 표지판이 가리키는 회차. `status = COMPLETED` 인 회차만 API 가 인정 |
| 점수·순위 → 상품 | `product_id` | API 가 상품 이름을 붙이고, 삭제된 상품(`deleted_at` 있음)은 빼고 보여 준다 |

`snapshot_id` 가 거의 모든 연결의 키다. 형식은 `weekly-YYYYMMDD-rN` 이고, 예를 들어 `weekly-20260817-r1` 은 "2026-08-17 주의 1번째 계산"이다.

## 테이블별 컬럼

### 고정 입력 (`weekly_ranking_inputs`)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | 자동 증가 |
| `snapshot_id` | VARCHAR(64) | 어느 회차의 입력인지 |
| `order_id` | BIGINT | 원본 주문 번호 |
| `product_id` | BIGINT | 상품 번호 |
| `quantity` | INT | 구매 수량 |
| `confirmed_at` | DATETIME(6) | 주문 확정 시각 (UTC). 원본 값 그대로 |
| `created_at` / `updated_at` | DATETIME(6) | Step 1 이 고정한 시각과 같음 |
| `deleted_at` | DATETIME(6) NULL | 공통 컬럼. 항상 NULL |

제약: UNIQUE (`snapshot_id`, `order_id`, `product_id`) — 같은 회차에 같은 주문·상품이 두 번 들어가지 않는다.

### 회차 명세서 (`weekly_ranking_revisions`)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `snapshot_id` | VARCHAR(64) **PK** | 회차 이름 자체가 키. 예 `weekly-20260817-r1` |
| `period_start` | DATE | 주의 시작일 (UTC 월요일) |
| `revision` | INT | 같은 주의 몇 번째 계산인지 (1부터) |
| `status` | VARCHAR(20) | `CALCULATING`(계산 중) 또는 `COMPLETED`(완성). FAILED 는 없다 — 실행 실패는 Spring Batch 메타 테이블이 기록한다 |
| `frozen_at` | DATETIME(6) | Step 1 이 입력을 고정한 시각 (UTC). API 응답의 `frozenAt` |
| `input_count` | INT | 복사된 입력 행 수 |
| `expected_product_count` | INT | 서로 다른 상품 수. Step 3 가 "점수 행 수가 이것과 같은가"로 완성을 검증한다 |
| `input_checksum` | CHAR(64) | 입력 행 전체의 SHA-256. 같은 입력이면 항상 같은 값 |
| `result_checksum` | CHAR(64) NULL | 순위 결과의 SHA-256. Step 3 가 채운다 |
| `created_at` / `updated_at` | DATETIME(6) | |

제약: UNIQUE (`period_start`, `revision`) — 같은 주의 같은 번호 계산은 하나만.

### 점수·순위 (`weekly_ranking_scores`)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | 자동 증가 |
| `snapshot_id` | VARCHAR(64) | 어느 회차의 점수인지 |
| `product_id` | BIGINT | 상품 번호 |
| `score` | BIGINT NOT NULL | 그 주 구매 수량 합. Step 2 가 **최종값**으로 덮어쓴다(누적 더하기가 아님) |
| `ranking` | INT **NULL 허용** | 순위. Step 2 는 비워 두고, Step 3 가 모든 행에 한 번에 채운다. `rank` 는 MySQL 예약어라 `ranking` |
| `created_at` / `updated_at` / `deleted_at` | DATETIME(6) | 공통 컬럼. `deleted_at` 은 항상 NULL |

제약: UNIQUE (`snapshot_id`, `product_id`) — 상품 하나에 점수 행 하나. INDEX (`snapshot_id`, `ranking`) — API 가 순위 순으로 읽을 때 사용.

### 공개 표지판 (`weekly_ranking_publications`)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `period_start` | DATE **PK** | 주의 시작일. 주마다 행이 **하나만** 있다 |
| `snapshot_id` | VARCHAR(64) | 지금 공개 중인 회차 |
| `revision` | INT | 그 회차 번호. 더 작은 번호로는 덮어쓰지 않는다 |
| `published_at` | DATETIME(6) | 처음 공개한 시각. 같은 회차를 다시 공개해도 바뀌지 않는다 |
| `updated_at` | DATETIME(6) | |

### 구매 기록 (`product_purchase_activity`, W6 기존 테이블)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | |
| `order_id` / `product_id` | BIGINT | UNIQUE 쌍. 같은 주문의 같은 상품은 한 번만 |
| `quantity` | INT | 구매 수량 |
| `confirmed_at` | DATETIME(6) | 주문 확정 시각 (UTC). **어느 주에 속하는지** 이 값으로 정한다 |
| `created_at` / `updated_at` / `deleted_at` | DATETIME(6) | 공통 컬럼. `deleted_at` 이 NULL 인 행만 복사한다 |

## 테이블을 보고 상태 읽는 법

| 이렇게 보이면 | 이런 상태다 |
|---|---|
| 회차 명세서에 행이 없다 | Step 1 이 아직 안 돌았거나 실패해 되돌아갔다 (고정 입력도 함께 없다) |
| 회차 명세서 `status = CALCULATING` | 계산 중이거나 중간에 실패해 멈춤. 어느 쪽인지는 `BATCH_JOB_EXECUTION` 을 본다 |
| 점수·순위에 `ranking` 이 NULL 인 행이 있다 | Step 2 까지만 끝난 회차. 완성(COMPLETED) 회차라면 있어선 안 되고, 있으면 API 가 500 으로 알린다 |
| 회차 명세서는 COMPLETED 인데 공개 표지판은 다른 회차를 가리킨다 | 더 새 번호의 계산이 먼저 공개되어 이 회차는 공개되지 않았다 (정상, 거절 처리) |
| 공개 표지판에 그 주 행이 없다 | API 가 `NOT_PUBLISHED` 를 돌려준다 |
| 공개 표지판은 있는데 점수·순위가 0행 | 그 주에 구매가 없었던 완성본. API 가 `PUBLISHED` + 빈 목록을 돌려준다 |
| 같은 주에 `snapshot_id` 가 여러 개 (r1, r2…) | 다시 계산한 이력. 옛 회차 행은 지우지 않는다 |

## 함께 쓰이는 Spring Batch 메타 테이블

`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT` 등 9개. "어떤 작업을, 몇 번째로, 어디까지 실행했나"를 기록한다. 위 업무 테이블과 **같은 DB·같은 트랜잭션 매니저**를 쓰므로, Step 2 의 점수 저장과 "어디까지 읽었나"(checkpoint)가 함께 commit 된다. 테스트에서도 지우지 않는다.
