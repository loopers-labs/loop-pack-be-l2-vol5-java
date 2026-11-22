# ADR 0084: 주간 랭킹 API는 공개 pointer의 완성본만 읽고, 계산 순위를 그대로 둔 채 비노출 상품을 뺀 뒤 limit을 채운다

Status: Accepted
Related: [9주차 설계 7절](../week9/design.md), [ADR 0080](0080-batch-owns-weekly-ranking-tables-by-sql-schema.md), [ADR 0082](0082-publish-verified-snapshot-atomically-and-converge.md), [구현 기록](../week9/implementation-log.md)

## Context

과제가 정한 API 요구는 다음과 같다.

- `GET /api/v1/rankings/weekly?periodStart=…&limit=…`
- 날짜·월요일을 검증하고, limit은 양수·최대값 등 명시한 범위를 적용한다.
- 응답에는 기간·snapshotId·입력 고정 시점·상품별 점수·순위가 들어간다.
- 공개본이 없음(`NOT_PUBLISHED`)과 구매가 0건인 완성본(`PUBLISHED`, 빈 items)을 구분한다. stage나 다른 주를 대신 보여 주지 않는다.
- 삭제·비노출 상품은 기존 고객 공개 조건으로 뺀다. 저장된 계산 순위를 유지하므로 순위에 빈칸이 생길 수 있음을 계약에 밝힌다. 공개 조건을 적용한 뒤 limit을 적용한다. 과거 행은 지우지 않는다.

랭킹 테이블은 commerce-batch가 소유하고, commerce-api는 읽기만 한다(ADR 0080).

## Decision

- **입력 검증**
  - `periodStart`는 필수다. `yyyy-MM-dd` 형식의 월요일이어야 한다.
  - `limit`은 기본 10, 1~100이다.
  - 어긋나면 400(`BAD_REQUEST`)이다.
- **읽기**: 한 읽기 TX(REPEATABLE READ) 안에서 공개 pointer와 snapshot 정보를 읽고, 그 snapshot의 `ranking_entry`를 계산 순위 순서로 읽는다. SQL은 infrastructure의 `JdbcWeeklyRankingReader`이고, domain에는 `WeeklyRankingReader` 인터페이스만 둔다.
- **공개 조건**: 기존 `ProductQueryService.getActiveByIdsInOrder`를 그대로 쓴다. 상품·브랜드가 삭제되지 않은 것만 남는다. 순위 순서로 limit개씩 읽으며 보이는 상품이 limit개가 될 때까지 다음 페이지를 읽는다.
- **응답** (`data`):
  - `periodStart`, `periodEnd`(포함하지 않는 다음 월요일), `status`(`PUBLISHED`/`NOT_PUBLISHED`)
  - `snapshotId`, `revision`, `inputFrozenAt`(UTC)
  - `items[]`: `rank`(계산 순위), `score`, `productId`, `productName`, `price`, `brandId`, `brandName`
  - 표시 정보는 조회 시점의 현재 상품 값이다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 비노출 상품을 뺀 뒤 순위를 1부터 다시 매김 | 고객 화면에는 자연스럽지만 공개본의 계산 결과와 API 숫자가 달라진다. 과제는 계산 순위를 유지하는 쪽을 예로 든다 |
| 공개 조건을 랭킹 SQL에 product·brand join으로 직접 넣기 | 한 번의 쿼리로 끝나지만 고객 공개 규칙이 두 곳에 생긴다. 규칙이 바뀌면 랭킹만 어긋날 수 있다 |
| 공개본이 없으면 404 | "아직 공개 전"과 "없는 자원"이 섞인다. 과제가 상태 값으로 구분하라고 한다 |
| 가장 최근 공개된 주로 대체 응답 | 다른 주를 요청한 주의 결과로 보여 주게 된다 |
| ranking_entry를 엔티티로 매핑 | local의 `ddl-auto: create`가 배치가 공개한 행을 지운다(ADR 0080) |

## Consequences

- 비노출 상품이 많으면 limit을 채우기 위해 랭킹을 여러 페이지 읽는다. 페이지마다 상품 조회가 한 번씩 더 생긴다.
- 순위에 빈칸이 생긴다(1, 4 …). 고객 화면이 연속 번호를 원하면 화면에서 다시 매겨야 한다.
- commerce-api 테스트는 commerce-batch의 스키마 파일을 직접 실행해 랭킹 테이블을 만든다.

## 검증

`WeeklyRankingApiTest`(6개, MockMvc + 실제 MySQL)로 확인했다. 랭킹 행은 배치가 남기는 형태로 직접 넣었다.

| 경우 | 결과 |
| --- | --- |
| 앞 주에만 공개본이 있는 주를 요청 | `NOT_PUBLISHED`, snapshotId 없음, items 0 |
| 0건 완성본 | `PUBLISHED`, snapshotId, items 0 |
| 정상 공개본 | 7(5점, 1위), 9(5점, 2위), 8(1점, 3위) 순서, `periodEnd`, `inputFrozenAt=…T00:30:00Z`, 상품명·브랜드명 |
| 삭제 상품(2위)과 삭제 브랜드 상품(3위)이 있고 `limit=2` | 1위·4위가 순위 그대로 나온다. ranking_entry 4행은 남는다 |
| r2 완성 랭킹·stage가 있지만 pointer가 r1 | r1이 보인다. pointer를 r2로 바꾸면 r2가 보인다 |
| 잘못된 요청 | 파라미터 없음·월요일 아님·형식 오류·limit 0·101·문자는 400, limit 100은 200 |

`LayeredArchitectureTest`(ArchUnit)도 통과했다.
