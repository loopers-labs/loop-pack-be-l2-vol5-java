# 랭킹 API 계약

이 문서는 [승인된 주간 랭킹 설계](../weekly-ranking.md)의 상세 문서다. 승인 상태와 전체 읽기 순서는 진입 문서를 따른다. 기존 절 번호는 유지하며 문서 사이에 분산된 절은 [절 번호 안내](../weekly-ranking.md#절-번호-안내)로 찾는다.

## 문서 간 읽기 경로

- [입력 고정·저장 구조·checksum](input-storage.md)
- [Job 실행·식별·재시작·복구](execution-recovery.md)
- [상품별 계산·완성본 공개](calculation-publication.md)
- [원문 대응·검증 구성](verification.md)

## 이 문서의 기준 절

[§7](#7-api-응답)

## 7. API 응답

### 7.1 미공개와 빈 공개 결과

사용자는 미공개와 공개 완료를 모두 HTTP 200·기존 ApiResponse 성공 형식으로 반환하고 data.status로 구분하는 방식을 선택했다. 날짜·limit 등의 잘못된 요청까지 HTTP 200으로 처리하는 합의는 아니다. 요청 검증·오류 계약은 [§7.7~7.10](#77-요청자-식별과-입력-값-객체)을 따른다.

|응답 필드|미공개|공개 완료|
|---|---|---|
|`status`|`NOT_PUBLISHED`|`PUBLISHED`|
|`periodStart`|요청한 UTC 업무 주의 시작 날짜|동일|
|`periodEnd`|다음 월요일 날짜, 구간에 미포함|동일|
|`snapshotId`|생략|공개 pointer가 가리키는 snapshotId|
|`inputFixedAt`|생략|해당 snapshot의 입력 고정 시점|
|`items`|빈 배열|공개 조건을 적용한 결과. 빈 배열도 가능|

미공개 응답 예시:

```json
{
  "meta": {"result": "SUCCESS"},
  "data": {
    "status": "NOT_PUBLISHED",
    "periodStart": "2026-08-17",
    "periodEnd": "2026-08-24",
    "items": []
  }
}
```

구매 0건인 완성본을 공개한 경우의 예시(시각은 합성 값):

```json
{
  "meta": {"result": "SUCCESS"},
  "data": {
    "status": "PUBLISHED",
    "periodStart": "2026-08-17",
    "periodEnd": "2026-08-24",
    "snapshotId": "weekly-20260817-r1",
    "inputFixedAt": "2026-08-24T01:00:00.000000Z",
    "items": []
  }
}
```

`PUBLISHED`는 구매 존재 여부가 아니라 완성본의 공개 여부를 뜻한다. 구매가 있었으나 현재 상품 공개 조건으로 모든 상품이 제외된 경우도 PUBLISHED·빈 items다. 구매가 없었던 경우와 숨김으로 비어 있는 경우를 구분하는 추가 응답 필드는 합의하지 않았다. 배열 위치로 rank를 다시 붙이지 않는 계약은 §2를 유지한다.

대안·선택 이유·비용: 공개본 부재를 HTTP 404로 처리할 수도 있지만 이번 API에서는 집계·공개 전 상태를 정상 조회 결과로 표현하기로 했다. 소비자는 HTTP 200만으로 공개 여부를 판단하지 않고 data.status를 확인해야 한다. snapshotId·inputFixedAt이 없는 미공개 표현은 기존 null 필드 생략 규칙과 맞춘다. 시각 직렬화·DTO 타입은 [§7.9](#79-클래스메서드응답-dto)를 따른다. inputFixedAt은 [§9.7](input-storage.md#97-입력-복사-sql과-읽기-시점--기본-격리-유지)에서 사용자 확인한 복사 직후 DB UTC 시각이며 계산 완료나 공개 시각으로 대신하지 않는다.

필드명 후속 합의: 입력 고정 시각은 `inputFixedAt`으로 표기하고 DB 열 초안도 `input_fixed_at`으로 맞춘다. 원문은 필드 이름을 지정한 것이 아니라 입력 고정 시점을 응답에 요구한다. 이 값은 해당 랭킹이 사용할 구매 내역을 고정한 기준 시각이며 계산 완료·공개 시각으로 대신하지 않는다. 입력 고정 뒤 늦은 구매는 새 revision으로 반영하므로 공개된 랭킹의 입력 기준을 설명하기 위해 제공한다. 구체적인 시계 기록 위치는 [§9.7](input-storage.md#97-입력-복사-sql과-읽기-시점--기본-격리-유지)의 제안과 구분한다.

### 7.2 월요일 날짜 입력과 요청 검증

사용자는 periodStart로 월요일 날짜를 받기로 선택했다. 원문 §1의 날짜·월요일 검증 요구를 따른다. 월요일이 아닌 날짜를 자동으로 그 주 월요일로 바꾸는 방식은 채택하지 않는다.

유효한 날짜와 월요일 여부를 랭킹 조회 전에 검증한다. 월요일이 아닌 날짜와 잘못된 날짜는 공통 API의 잘못된 입력 HTTP 400 원칙을 적용한다. DB에서 해당 날짜의 공개본을 찾지 못했다는 이유로 잘못된 입력을 NOT_PUBLISHED·빈 items로 바꾸지 않는다.

|예시 입력|처리|
|---|---|
|`2026-08-17` 월요일, 공개본 있음|HTTP 200·PUBLISHED|
|`2026-08-17` 월요일, 공개본 없음|HTTP 200·NOT_PUBLISHED·빈 items|
|`2026-08-18` 화요일|조회 전 HTTP 400 거절|
|존재하지 않는 날짜 또는 잘못된 날짜 형식|조회 전 HTTP 400 거절|

후속 대화에서 사용자는 날짜 필수·YYYY-MM-DD 형식·limit 기본10/1~100·미래 주 허용 제안에 동의했다. 오류 코드·메시지·요청자 식별과의 우선순위 및 DB 날짜 표현 범위는 [§7.7](#77-요청자-식별과-입력-값-객체)·[§7.10](#710-http-오류와-실패-우선순위)의 상세 초안을 따른다.

|입력|합의한 계약|
|---|---|
|`periodStart`|필수. YYYY-MM-DD 형식의 실제 월요일 날짜|
|날짜 생략|HTTP 400. 지난주·최신 공개 주를 자동 선택하지 않음|
|미래 월요일|허용. 공개본이 없으면 HTTP 200·NOT_PUBLISHED|
|`limit` 생략|10|
|`limit` 지정|정수 1~100. 범위 밖·정수로 해석할 수 없는 입력은 HTTP 400|

선택 이유·비용: 기간 필수는 어느 주를 조회했는지 명확히 하고 생략 시 기본 주 정책을 추가하지 않는 대신 호출 측이 날짜를 지정해야 한다. limit은 응답 크기를 제한하는 값이며 처리량을 측정해 고른 값은 아니다. 원문에 없는 과거 주 전용 제한은 추가하지 않는다.

### 7.3 조회 개수와 저장 범위 구분

원문 §4~5의 전체 상품 계산·완성 요구에 따라 snapshot 입력에 구매가 있는 모든 상품의 점수와 순위를 계산·저장한다. 상위 100개만 저장하는 방식이 아니다. 해당 주 구매가 없는 모든 카탈로그 상품을 0점으로 추가하는 기능도 아니다.

API의 limit 최대100은 한 요청의 반환 개수다. 현재 상품 공개 조건을 먼저 적용하고 저장된 rank 순서로 limit만큼 조회한다. 예를 들어 상위100개 중30개가 숨겨졌다면 101위 이하에서도 공개 가능한 상품을 찾아 최대100개를 반환한다. 공개 가능한 전체 상품 수가 부족하면 그보다 적게 반환한다. 반환한 rank는 재부여하지 않는다. 이는 §2의 원문 공개 조건 후 limit 적용 요구이며 새로운 상위100 저장 정책을 도입한 것이 아니다.

### 7.4 랭킹 항목의 필드

사용자는 items의 각 항목을 다음 네 필드로 구성하기로 선택했다.

|필드|정보의 기준|
|---|---|
|`productId`|완성 ranking에 저장된 상품 식별자|
|`productName`|조회 시점의 현재 상품명|
|`score`|해당 snapshot의 완성 ranking에 저장된 구매 수량 합계. JSON에서는 항상 문자열|
|`rank`|완성 ranking에 저장된 계산 순위. 현재 상품 제외 후에도 유지|

```json
{
  "productId": 7,
  "productName": "상품 A",
  "score": "5",
  "rank": 1
}
```

원문 §1의 현재 표시 정보 연결 요구에 따라 상품명은 계산 당시 이름을 복사해 보존하는 대신 조회 시점 값을 읽는다. 따라서 같은 snapshotId라도 이름 변경으로 productName이 달라질 수 있으며, 이것은 점수·순위 계산 결과의 변경이 아니다. 결과 checksum은 원문대로 productId·score·rank를 기준으로 하고 현재 상품명을 포함하지 않는다.

선택 이유·비용: 네 필드로 상품 식별·현재 표시 이름·구매 수량·저장 순위를 제공한다. 현재 이름과 노출 조건을 위해 상품/브랜드 조회가 필요하다. 상품명 snapshot 저장은 과거 이름 재현에는 유리하지만 이번 원문의 현재 이름 조회 요구와 달라 채택하지 않는다. 조회 SQL·숫자 필드 타입은 [§7.8~7.9](#78-두-조회-sql과-queryrepository-계약)를 따른다. 별도 추가 표시 필드는 합의하지 않았다.

### 7.5 Score의 JSON 표현

사용자는 score를 크기에 관계없이 항상 JSON 문자열로 전달하기로 선택했다. 앞선 숫자 score 예시는 이 합의로 대체한다. 값이5면 `"score": "5"`이며, 큰 값도 정수의 십진 자릿수를 그대로 문자열로 전달한다. 응답 DTO를 만들 때 BigInteger를 문자열로 변환하고 long·double을 거치지 않는다. 문자열 직렬화/스키마는 [§7.9](#79-클래스메서드응답-dto)를 따른다.

문제·대안·선택 이유: JSON 숫자로 보내고 큰 정수를 정확히 파싱하는 책임을 클라이언트에 맡길 수도 있다. 일반 JavaScript Number 등 제한된 정밀도의 숫자로 읽으면 큰 정수의 자릿수를 보존하지 못할 수 있으므로, 사용자는 문자열로 자릿수를 보존하는 쪽을 선택했다. 비용은 클라이언트에서 점수 계산이 필요할 때 적절한 정수 타입으로 변환해야 한다는 점이다. 작은 값은 숫자·큰 값은 문자열로 나누는 혼합 타입은 사용하지 않는다.

DB stage·완성 ranking의 score는 DECIMAL(65,0), Java 계산·조회 값은 BigInteger를 유지한다. 계산·점수 정렬은 숫자로 수행하고 HTTP 응답 표현만 문자열로 바꾼다. 문자열 사전식 순서로 점수를 정렬하지 않는다. 결과 checksum은 원문의 productId·score·rank를 정해진 규칙으로 직렬화하는 별도 계약이며 HTTP JSON 전체를 hash하는 방식으로 바꾸지 않는다. 원본 quantity·productId·rank의 API/저장 타입을 이 선택으로 자동 변경하지 않는다.

### 7.6 공개 스냅샷과 항목 조회

사용자는 두 SQL 조회를 하나의 읽기 전용 TX로 묶는 안에 동의했다. 단일 SQL 처리가 불가능해서 나누는 것은 아니다. 원문도 pointer join을 예시로 들며, 선택한 방식은 응답 상태와 항목 조회를 단순하게 나누기 위한 구현 선택이다.

```text
RankingFacade 조회 메서드: 기존 transactionManager의 readOnly TX
  격리는 기존 기본 REPEATABLE READ 유지
  1. periodStart의 공개 pointer와 연결 snapshot 정보 조회
     공개 행 없음: NOT_PUBLISHED·요청 주의 기간·빈 items 반환
     공개 행은 있으나 snapshot 누락/기간 불일치/미완성:
       미공개로 숨기지 않고 저장 정합성 오류 → INTERNAL_ERROR
     정상 공개 snapshotId·기간·inputFixedAt 확보
  2. 1에서 확보한 snapshotId의 ranking 조회
     현재 Product·Brand 모두 존재하고 미삭제인 상품만 포함
     calculated_rank 오름차순으로 정렬한 뒤 limit 적용
     저장된 productId·score·rank와 현재 productName 반환
  snapshot 정보와 items로 PUBLISHED 응답 구성
  items가 없어도 PUBLISHED 유지
```

두 읽기는 같은 thread·TX 연결에서 일반 SELECT로 수행하고 중간 REQUIRES_NEW·비동기 호출·별도 raw 연결을 추가하지 않는다. 기본 REPEATABLE READ의 같은 읽기 시점을 사용하며 두 번째 조회는 첫 번째에서 얻은 snapshotId로 고정한다. 중간에 새 revision이 공개돼도 해당 응답의 metadata와 ranking을 다른 snapshot으로 조합하지 않는다. 조회에 FOR UPDATE를 추가하지 않는다. 현재 정보는 이 조회 TX의 DB 읽기 시점 기준이며 commit된 상품 변경을 진행 중 조회에 실시간 반영한다는 보장은 하지 않는다.

한 SQL의 LEFT JOIN/결과 매핑으로 미공개·빈 공개본까지 표현하는 대안도 가능하며 DB 왕복을 줄일 수 있다. 이번 선택은 두 조회의 왕복 비용을 수용하고, metadata 부재와 항목 부재를 별도로 처리하는 단순함을 택한다. 테이블은 §9의 초안 명칭을 사용하며 SQL·domain 조회 결과·DTO·오류는 [§7.7~7.10](#77-요청자-식별과-입력-값-객체)에 명시한다. 테스트는 원문 API 동작 범위로 계획하고 별도 잠금/성능 비교 실험을 추가하지 않는다.

### 7.7 요청자 식별과 입력 값 객체

commerce-api의 기존 CustomerIdArgumentResolver를 유지한다. Controller의 첫 인자는 `@CustomerId Long userId`로 두어 기존 X-USER-ID의 존재·숫자 형식·User 존재를 확인한다. 랭킹은 사용자별 결과가 아니지만 기존 고객 API의 공통 식별 규칙을 생략하지 않는다. RankingFacade가 UserFacade를 다시 호출하거나 사용자별 snapshot을 만들지 않는다.

Controller는 periodStart와 limit을 모두 optional raw String으로 받아 domain의 WeeklyRankingQuery.of(periodStartRaw, limitRaw)에 전달한다. optional은 Spring binding을 위한 것이며 periodStart 생략을 업무적으로 허용한다는 뜻이 아니다. 원문 날짜/월요일·limit 계약의 검증 책임은 값 객체에 둔다. Controller에서 날짜를 월요일로 바꾸거나 빈 값을 임의 보정하지 않는다.

두 값을 String으로 받는 이유는 limit 생략과 명시 빈 값을 구분하기 위해서다. Integer optional binding은 빈 값을 null로 바꿀 수 있어 `limit=`이 기본10으로 처리될 여지가 있다. raw parsing은 공통 converter/Advice를 바꾸지 않고 이 endpoint 안에서 수행한다.

WeeklyRankingQuery는 LocalDate periodStart·periodEnd와 int limit을 가진 불변 값이다. 생성 순서는 다음과 같다.

```text
WeeklyRankingQuery.of(periodStartRaw, limitRaw):
  periodStartRaw null/빈 값: INVALID_REQUEST
  [0-9]{4}-[0-9]{2}-[0-9]{2} 형식과 엄격한 ISO 날짜 parsing
    실패: INVALID_REQUEST
    앞뒤 공백 제거·짧은 월/일 보정·잘못된 날짜의 다음 달 이월 금지
  UTC 업무 주 기준 월요일인지 확인; 아니면 INVALID_REQUEST
  periodEnd = periodStart + 7일
  limitRaw == null: limit=10
  그 밖: Integer.parseInt(limitRaw)
    빈 값/정수 아님/int 범위 밖: BAD_REQUEST
  limit < 1 또는 limit > 100: INVALID_REQUEST
  검증한 periodStart/periodEnd/limit으로 반환
```

형식의 YYYY는 구현 formatter에서 week-based-year인 YYYY로 쓰지 않는다. DateTimeFormatter.ISO_LOCAL_DATE의 엄격한 날짜 parsing을 사용하고 숫자4자리 형태를 앞에서 제한한다. system default zone이나 현재 날짜를 사용해 주간 범위를 바꾸지 않는다. 미래 주를 별도로 거절하지 않는다.

추가 날짜 범위 검사는 두지 않는다는 사용자 결정을 따른다. 날짜 형식/실제 날짜/월요일 여부만 검증하며, 시작/끝을 MySQL DATE의 최소·최대와 비교하는 별도 guard와 그에 대한400 분기는 구현하지 않는다. 특정 과거/미래 사업 연도로 제한하지 않는다. DB/JDBC/기본 날짜 연산에서 기술 오류가 실제 발생하면 기존 기술 오류 경계로 전파하고 빈 결과/다른 주로 보정하지 않는다. 별도 범위 검사가 없다는 선택을 DB의 저장 한계가 없다는 보장으로 설명하지 않는다. batch의 날짜 입력도 기존 형식/월요일·식별값 검증을 유지하며 별도 DB 범위 guard를 추가하지 않는다.

고객 식별 오류와 query 오류가 함께 있으면 먼저 배치한 기존 CustomerId resolver의 오류를 따른다. 고객 식별이 성공하면 periodStart → limit 순서로 오류를 판정한다. 기존 공통 User 조회는 발생할 수 있지만 랭킹 조회 SQL은 query 검증 성공 뒤에만 수행한다.

### 7.8 두 조회 SQL과 QueryRepository 계약

API의 WeeklyRankingQueryRepository는 다음 두 메서드만 제공한다. JDBC 구현은 같은 mySqlMainDataSource를 사용하는 JdbcTemplate을 주입받는다. SQL을 위해 API에 batch Entity를 중복 등록하거나 batch 앱 의존성을 추가하지 않는다.

|메서드|반환|책임|
|---|---|---|
|findPublishedSnapshot(periodStart)|Optional<WeeklyRankingQueryResult.Publication>|pointer와 연결 snapshot metadata. 행 부재와 dangling 연결을 구분|
|findVisibleItems(snapshotId, limit)|List<WeeklyRankingQueryResult.Item>|현재 Product·Brand 공개 조건과 저장 rank 순서의 상위 limit 항목|

첫 조회는 LEFT JOIN으로 연결 손상을 숨기지 않는다. pointer가 없을 때만 Optional.empty이며, pointer는 있으나 snapshot이 없으면 Publication의 snapshot 부분이 비어 있는 값으로 반환해 Facade가 오류를 판정한다.

```sql
SELECT publication.period_start AS pointer_period_start,
       publication.published_snapshot_id AS pointer_snapshot_id,
       publication.deleted_at AS pointer_deleted_at,
       snapshot.snapshot_id,
       snapshot.period_start,
       snapshot.period_end,
       snapshot.revision,
       snapshot.status,
       snapshot.input_fixed_at,
       snapshot.completed_at,
       snapshot.result_checksum,
       snapshot.deleted_at AS snapshot_deleted_at
FROM ranking_publication AS publication
LEFT JOIN ranking_snapshot AS snapshot
  ON snapshot.snapshot_id = publication.published_snapshot_id
WHERE publication.period_start = ?
```

Publication은 pointer의 기간/ID/삭제 상태와 nullable SnapshotSummary를 가진 불변 domain projection이다. SnapshotSummary는 위 snapshot 열을 보관한다. 정상적인 공개본은 pointer/snapshot ID가 같고, 요청 기간과 snapshot 기간이 맞고, status=COMPLETED·inputFixedAt/완료 시각/resultChecksum이 존재해야 한다. deleted_at이 있는 pointer/snapshot은 지원하는 삭제 기능이 없는 저장 손상이므로 미공개로 숨기지 않는다. 입력 checksum을 재계산하거나 전체 ranking 건수를 요청마다 COUNT하는 검사는 추가하지 않는다.

두 번째 조회는 첫 번째에서 확보한 snapshotId로 고정한다. product/brand 테이블명과 brand_id는 기존 Entity/카탈로그 조회 코드와 맞춘다.

```sql
SELECT ranking.product_id,
       product.name AS product_name,
       ranking.score,
       ranking.calculated_rank
FROM ranking_result AS ranking
JOIN product AS product
  ON product.id = ranking.product_id
 AND product.deleted_at IS NULL
JOIN brand AS brand
  ON brand.id = product.brand_id
 AND brand.deleted_at IS NULL
WHERE ranking.snapshot_id = ?
ORDER BY ranking.calculated_rank ASC
LIMIT ?
```

join의 공개 조건은 limit보다 먼저 적용된다. 재고 수량 조건은 추가하지 않는다. Product/Brand가 물리적으로 없어도 inner join에서 제외되고 과거 ranking 행은 삭제하지 않는다. ranking 내부의 계산 순위는 그대로 반환하고 현재 표시 순위를 새로 매기지 않는다. rank 유일 키로 같은 snapshot의 정렬 순서를 고정한다.

Item projection은 Long productId·String productName·BigInteger score·Long rank를 가진다. score는 JDBC BigDecimal의 toBigIntegerExact 등 손실 없는 변환을 사용하고 null을0으로 바꾸지 않는다. DB 기술 오류·변환 오류는 INTERNAL_ERROR 경계로 전파한다. ResultSet과 statement의 수명은 JDBC 구현이 소유하며 Entity·JPA proxy·JDBC 타입을 Controller에 반환하지 않는다. 상품별 ProductFacade 호출·추가 Redis cache·페이지 count 조회는 도입하지 않는다.

### 7.9 클래스·메서드·응답 DTO

아래 신규 타입은 commerce-api 내부에 둔다. batch의 동명 개념/SQL 계약을 Java 앱 의존성으로 공유하지 않는다.

|타입·위치|계약|
|---|---|
|interfaces/api/ranking/WeeklyRankingV1Controller·WeeklyRankingV1ApiSpec|GET /api/v1/rankings/weekly. CustomerId → WeeklyRankingQuery.of → RankingFacade → 응답 DTO|
|interfaces/api/ranking/WeeklyRankingV1Dto|WeeklyResponse·ItemResponse record와 from 변환. Entity를 직접 직렬화하지 않음|
|application/ranking/RankingFacade|getWeeklyRanking(WeeklyRankingQuery) → WeeklyRankingInfo. 기존 transactionManager의 @Transactional(readOnly=true), 기본 격리 유지|
|application/ranking/WeeklyRankingInfo|status·기간·nullable snapshotId/inputFixedAt·immutable Item 목록을 합성한 결과. Status=NOT_PUBLISHED/PUBLISHED|
|domain/ranking/WeeklyRankingQuery|raw 요청에서 업무 입력 검증. Clock·DB·API DTO 의존 없음|
|domain/ranking/WeeklyRankingQueryRepository·WeeklyRankingQueryResult|[§7.8](#78-두-조회-sql과-queryrepository-계약) 두 조회 포트와 Publication/SnapshotSummary/Item 불변 projection|
|infrastructure/ranking/WeeklyRankingQueryRepositoryImpl|두 SQL의 JdbcTemplate 구현과 JDBC 값 변환. application을 의존하지 않음|

```text
Controller.getWeeklyRanking(@CustomerId userId, periodStartRaw, limitRaw):
  query = WeeklyRankingQuery.of(periodStartRaw, limitRaw)
  info = rankingFacade.getWeeklyRanking(query)
  return ApiResponse.success(WeeklyResponse.from(info))

RankingFacade.getWeeklyRanking(query): // readOnly TX
  publication = repository.findPublishedSnapshot(query.periodStart)
  if pointer 행 없음:
    return NOT_PUBLISHED(요청 start/end, snapshot 없음, inputFixedAt 없음, 빈 목록)
  pointer와 연결 snapshot의 ID/기간/COMPLETED/필수 필드 확인
    깨진 저장 상태: CoreException(INTERNAL_ERROR)
  items = repository.findVisibleItems(snapshotId, query.limit)
  return PUBLISHED(같은 snapshot의 start/end/ID/inputFixedAt, List.copyOf(items))
```

두 조회 사이에 Facade TX를 끝내거나 새로운 TX/connection을 만들지 않는다. 같은 REPEATABLE READ의 일반 읽기 시점을 유지하고 snapshotId도 고정한다. 첫 조회의 상태에 따라 공개를 기다리는 polling·다른 주 대체·실시간 계산·배치 기동을 요청 경로에서 수행하지 않는다.

WeeklyResponse의 필드는 다음과 같다. ApiResponse 외에 PageResponse는 쓰지 않는다. 이 endpoint는 page/size/totalElements를 제공하는 페이지 조회가 아니라 합의된 기간별 top-limit 결과다. 기존 페이지 API의 PageResponse 계약을 변경하지 않는다.

|필드|DTO 타입·JSON|필수/생략|
|---|---|---|
|status|String enum 표현|항상 NOT_PUBLISHED/PUBLISHED|
|periodStart / periodEnd|String, ISO_LOCAL_DATE|항상 존재. 일반 표현은 YYYY-MM-DD, end는 다음 월요일·미포함|
|snapshotId|String|미공개일 때 null로 두어 기존 NON_NULL 규칙으로 생략|
|inputFixedAt|String, UTC ISO 시각|미공개일 때 생략. 공개본의 저장 입력 기준 시각|
|items|List<ItemResponse>|항상 배열. null 대신 빈 배열|

ItemResponse는 Long productId·String productName·String score·Long rank다. ID/rank는 기존 숫자 표현을 유지하고 score만 BigInteger.toString()의 십진 문자열로 변환한다. 원본 값이 크다고 숫자와 문자열을 혼용하지 않는다.

inputFixedAt은 UTC로 해석한 DB DATETIME(6)을 `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'`의 문자열로 변환한다. JDBC는 [§9.12](input-storage.md#912-native-jdbc의-날짜utc-시각-바인딩읽기)의 getObject(column, LocalDateTime.class)로 읽고 그 값을 UTC로 명시 해석하며 JVM 기본 Asia/Seoul로 재해석하지 않는다. DTO의 String 필드에서 format을 고정해 공통 JacksonConfig를 변경하지 않는다. 계산 완료/공개 시각이나 현재 시각을 대신 채우지 않는다. OpenAPI는 status 두 값, periodStart format=date와4자리 연도 입력, periodEnd의 ISO_LOCAL_DATE 문자열, inputFixedAt format=date-time, score type=string, items 배열과 미공개 snapshot 필드 생략을 명시한다. 추가 날짜 범위 guard를 두지 않는 결정에 맞춰 periodEnd는 Java 계산 결과를 ISO_LOCAL_DATE로 표현하며4자리 연도로 자르거나 보정하지 않는다. 확장 연도까지 이론적으로 가능하므로 periodEnd schema에4자리만 허용하는 제한을 추가하지 않는다.

### 7.10 HTTP 오류와 실패 우선순위

commerce-api의 기존 CoreException·ErrorType·ApiControllerAdvice를 사용한다. 신규 전역 예외 handler나 랭킹 전용 인증을 도입하지 않는다. 아래 코드는 기존 ErrorType의 실제 code 값이다.

|조건|HTTP·meta.errorCode|메시지/처리|
|---|---|---|
|X-USER-ID 누락/빈 값/숫자 해석 불가|400 INVALID_REQUEST|기존 CustomerIdArgumentResolver 메시지 유지|
|존재하지 않는 고객|404 USER_NOT_FOUND|기존 UserFacade/공통 응답|
|periodStart 누락/빈 값|400 INVALID_REQUEST|periodStart가 필요합니다.|
|날짜 형식/실제 날짜 오류|400 INVALID_REQUEST|periodStart는 YYYY-MM-DD 형식의 유효한 날짜여야 합니다.|
|월요일 아님|400 INVALID_REQUEST|periodStart는 UTC 기준 월요일이어야 합니다.|
|limit 명시 빈 값/정수 아님/int overflow|400 Bad Request|limit은 정수여야 합니다.|
|limit 정수이지만1~100 밖|400 INVALID_REQUEST|limit은 1~100이어야 합니다.|
|유효 요청의 pointer 행 없음|200 SUCCESS·NOT_PUBLISHED|오류가 아님|
|pointer의 연결 snapshot 없음/기간 불일치/미완성/필수 완료 정보 누락|500 Internal Server Error|기존 INTERNAL_ERROR 기본 메시지, 미공개로 숨기지 않음|
|DB·JDBC·숫자 변환 등 기술 오류|500 Internal Server Error|기존 공통 예외 응답. 빈 결과 fallback 없음|

periodStart/limit을 raw String으로 받으므로 endpoint의 값 객체가 parsing 오류를 기존 CoreException으로 변환한다. int parsing 오류에 ErrorType.BAD_REQUEST를 사용해 기존 숫자 query 변환 실패의 code인 Bad Request를 유지한다. limit 범위는 PageResponse/page-size 규칙을 그대로 끌어오지 않고 INVALID_REQUEST로 표현한다. query 오류에 원본 입력 문자열이나 SQL을 메시지로 복사하지 않는다. CoreException은 API 앱 소유 타입이며 batch의 실패 타입과 공유하려고 앱 간 의존을 추가하지 않는다.

사용자 식별 성공 이후에는 날짜 검증 → limit parsing/범위 → 랭킹 조회 순서다. 이 endpoint는 date/limit만 다른 과거 또는 미래 주에 정상 요청하면 같은 계약을 적용하며 날짜를 임의 보정하지 않는다. 전체 테스트 표는 뒤의 검증 설계에서 정한다. 이번에는 SQL 실행·HTTP 기동·MockMvc·테스트/실험을 수행하지 않았다.
