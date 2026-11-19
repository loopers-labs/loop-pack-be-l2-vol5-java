# 9주차 블로그 재료: 직접 재 본 것들

결정과 그 이유는 `design.md` 에 있다. 여기는 그 결정을 하며 잰 숫자, 잰 방법, 재다가 틀린 것을 모은다. 실험 코드는 `@Tag("experiment")`·`@Tag("speed-experiment")` 로 `check` 에서 빠져 있고 아래 명령으로 다시 돌릴 수 있다.

```bash
./gradlew --offline :apps:commerce-batch:experimentTest --tests '<클래스>'
./gradlew --offline :apps:commerce-batch:speedExperimentTest
```

## 후보 주제

| 주제 | 재료 |
|---|---|
| 한 행씩 읽는 코드가 한 행씩 읽지 않을 때: MySQL 드라이버와 배치 메모리 | EXP-029, EXP-031 |
| 재시작은 어디서부터 이어 가는가: paging 과 cursor | EXP-033, 재시작 테스트 |
| 배치의 "한 문장" 은 정말 한 문장인가: `rewriteBatchedStatements` | EXP-032 |
| group by 로 집계하면 page 마다 전부 다시 집계하는가 | EXP-034 |
| 큰 TX 가 실패하면: rollback 시간과 metadata lock | EXP-030 (측정 전) |

## EXP-029: 입력 checksum 이 붙잡는 heap

`FreezeSnapshotMemoryExperimentTest`. 고정 step 하나만 가진 job 을 돌린다.

- 조건: heap 512MB, young 16MB, 행 10 만·20 만·40 만, 각 3 회.
- 측정값: GC 알림으로 모은 "GC 직후 live heap" 의 최댓값 − 실행 전 기준선. young 을 작게 고정한 것은 결과를 받는 도중에 GC 가 일어나야 붙잡힌 행이 보이기 때문이다. 모든 실행에서 GC 가 4 회 이상 일어났다.

| 입력 행 | 결과 전체를 받음 (fetchSize 기본) | 행 단위로 받음 (`Integer.MIN_VALUE`) |
|---|---|---|
| 100,000 | 22MB | 0MB |
| 200,000 | 45MB | 0MB |
| 400,000 | 92MB | 0MB |

세 번 잰 값이 1MB 안에서 같았다. 행당 약 240B. fetchSize 하나만 바꾼 대조에서 증가가 사라져 원인이 드라이버의 결과 버퍼링이라고 말할 수 있다.

재다가 틀린 것:

- 첫 시도는 heap 256MB 에 200 만 행이었다. 고정 TX 가 200 만 행을 넣은 뒤 실패했고, 그 rollback 이 1 분에 약 2 만 행씩 줄어 끝나지 않았다. 그동안 다음 테스트의 `truncate table purchase_activity` 가 metadata lock 을 기다렸다. 실험이 실험을 망가뜨렸다.
- 그 시도는 heap pool 의 peak 를 더했다. peak 에는 아직 수거되지 않은 쓰레기가 섞이고 pool 마다 peak 시점이 다르다. GC 직후 값으로 바꿨다.
- OOM 이 났는지조차 확인하지 못한 채 멈췄다. 결과가 없는 실행은 결과가 없다고 적는다.

## EXP-031: 행 단위로 받으면 느려지는가

`ChecksumReadSpeedExperimentTest`. 기본 GC, heap 1GB. job 전체가 아니라 checksum 읽기만 잰다(job 전체는 INSERT…SELECT 가 대부분이라 차이가 묻힌다). 웜업 3 회를 버리고 두 방식을 번갈아 10 회. 두 방식의 checksum 이 같은지도 본다.

| 입력 행 | 결과 전체를 받음 (중앙값, 범위) | 행 단위로 받음 (중앙값, 범위) | 비율 |
|---|---|---|---|
| 100,000 | 92ms (89~95) | 91ms (89~98) | 0.99 |
| 400,000 | 392ms (381~409) | 376ms (361~388) | 0.96 |

느려지지 않는다. 쿼리는 한 번이고 서버가 결과를 이어 보낸다. 행마다 왕복하는 것은 `useCursorFetch` 쪽이다. 40 만 행에서 checksum 읽기는 0.4 초, 고정 step 이 20 초 넘게 걸린 것은 대부분 INSERT…SELECT 였다.

## EXP-032: `rewriteBatchedStatements`

지금 writer 는 chunk 마다 `insert … select` 한 문장이라 이 옵션과 상관없다. 아래는 writer 가 `batchUpdate` 로 값 묶음을 보내던 때 잰 것이다.

`WriterBatchRewriteExperimentTest`. 상품 5, chunk 5. root 로 `general_log` 를 테이블로 켜고 서버가 받은 writer 문장을 센다. 옵션을 켠 context 와 끈 context 를 비교한다.

| 옵션 | 서버가 받은 문장 |
|---|---|
| 켬 | `insert … values ('…r1',1,1),('…r1',2,1),…,('…r1',5,1) as new on duplicate key update score = new.score` 1 문장 |
| 끔 | 같은 문장의 한 행짜리 5 문장 |

행 별칭(`as new`)이 붙은 upsert 도 값 묶음만 이어 붙인다. 다시 쓴 문장은 영향 행 수 대신 `SUCCESS_NO_INFO` 를 돌려줄 수 있다.

## EXP-033: paging 과 cursor 의 커넥션과 재시작

`ReaderRestartExperimentTest`. 고정 step, processor, writer 는 운영 bean 그대로, reader 만 바꾼다. 상품 10,000, chunk 100. 다른 커넥션이 상품 9,001 의 점수 행을 잡아 90% 지점에서 실제 잠금 대기로 실패시키고 같은 parameter 로 재실행한다. 재실행 동안 item 을 읽을 때마다 Hikari 사용 중 커넥션을 표본으로 잡고, `performance_schema.events_statements_summary_by_digest` 로 reader 쿼리가 DB 에서 보낸 행 수를 센다. Batch 의 read count 에는 cursor 가 건너뛰려고 읽어 버린 행이 없다.

| | 재실행 중 커넥션 최대 | 재시작 때 reader 쿼리 | DB 가 보낸 행 | 재시작 시간 |
|---|---|---|---|---|
| `JdbcPagingItemReader` (keyset) | 1 | 11 번 | 1,000 | 255ms |
| `JdbcCursorItemReader` (행 단위 fetch) | 2 | 1 번 | 10,000 (9,000 행을 버림) | 385ms |

재다가 틀린 것:

- cursor 를 행 단위 fetch 로 두자 첫 read 에서 `Operation not allowed for a result set of type ResultSet.TYPE_FORWARD_ONLY` 로 실패했다. `JdbcCursorItemReader` 가 기본으로 `rs.getRow()` 로 위치를 검증하는데 MySQL 스트리밍 결과는 이를 지원하지 않는다. `verifyCursorPosition(false)` 가 필요하다.
- paging 의 "DB 가 보낸 행" 이 처음에 0 이었다. MySQL 이 digest 를 기록할 때 `DISTINCT` 를 `DISTINCTROW` 로 바꿔 적어 거르기 조건이 맞지 않았다. 기록된 문장을 출력해 조건을 고쳤다.
- 한 번 끝까지 읽는 처리량은 재지 않았다. cursor 가 유리할 수 있다.

## EXP-034: group by 로 집계하면 page 마다 전부 다시 집계하는가

`GroupByReaderExperimentTest`. 상품 10,000, 상품마다 주문 5 건(입력 50,000 행), chunk 100. 계산 step 만 비교한다. 웜업 1 회 뒤 번갈아 3 회, 세 번 모두 같았다. 두 방식의 점수는 상품 10,000 개 모두 같았다.

| | 입력 테이블 쿼리 | DB 가 검사한 행 | 시간 |
|---|---|---|---|
| distinct reader + processor 가 상품마다 sum (지금) | 10,101 | 100,099 | 약 1.9 초 |
| `JdbcPagingItemReader` + `groupClause` | 101 | 505,401 | 약 1.2 초 |

Spring Batch 5.2.2 의 `SqlPagingQueryUtils` 가 만드는 다음 page 쿼리:

```sql
SELECT * FROM (SELECT product_id, sum(quantity) as score FROM ranking_snapshot_input
  WHERE snapshot_id = :snapshotId GROUP BY product_id) AS MAIN_QRY
WHERE ((product_id > ?)) ORDER BY product_id ASC LIMIT 100
```

`EXPLAIN FORMAT=TREE` 에서 `product_id > ?` 는 집계 아래의 PK 범위 scan 으로 내려간다(derived condition pushdown). 그런데 `LIMIT 100` 은 바깥에 남아, 각 page 가 남은 상품 전부를 집계해 임시 테이블로 만든 뒤 100 개를 고른다. 505,401 은 10,000 + 9,900 + … + 100 ≈ 505,000 과 맞는다. 지금 규모에서는 더 빠르지만 비용이 상품 수의 제곱으로 는다(상품 수를 늘려 재지는 않았다).

processor 를 없애고 writer 가 chunk 의 상품들을 `insert … select … group by` 한 문장으로 쓰게 바꾼 뒤 같은 조건으로 다시 쟀다.

| | 입력 테이블 쿼리 | DB 가 검사한 행 | 시간 |
|---|---|---|---|
| writer 가 chunk 마다 group by sum | 201 | 60,099 | 약 0.5 초 |

같은 입력을 읽으면서 쿼리는 page 100 번과 chunk 쓰기 100 번이 됐다. group by 가 무거운 것이 아니라 Spring Batch 가 group by 를 감싸는 모양이 무거웠다.

재다가 틀린 것:

- 판정 기준을 "검사한 행이 입력의 2 배 이하" 로 미리 적었다. 조건이 안으로 들어가면 충분하다고 봤는데 LIMIT 이 바깥에 남는 것을 놓쳤다.
- `EXPLAIN` 에 값을 넣다가 이 쿼리가 snapshot 을 `?` 가 아니라 `:snapshotId` 로 받는 것을 놓쳐 문법 오류가 났다.
- 실험에서 builder 로 만든 `JdbcPagingItemReader` 가 첫 read 에서 NPE 로 실패했다. `build()` 는 `afterPropertiesSet()` 을 부르지 않는다. 운영에서는 `@Bean` 이라 Spring 이 불러 줬다.

## 이름이 헷갈리는 것: cursor

| 방식 | API 페이지네이션에서 | Spring Batch |
|---|---|---|
| offset | 페이지 번호 방식 | `JpaPagingItemReader`, `RepositoryItemReader` |
| keyset | "커서 기반 페이지네이션"(책갈피 token) | `JdbcPagingItemReader`(sortKey) |
| DB 커서 | 없다 | `JdbcCursorItemReader` |

API 의 cursor 는 마지막 key 를 담은 책갈피이고 서버는 매번 새 쿼리를 보낸다. Spring Batch 의 cursor 는 DB 연결 위에 열어 둔 결과 집합이다. 같은 "paging" 이라도 `JdbcPagingItemReader` 는 keyset, `JpaPagingItemReader` 는 offset 이다.

## EXP-030: 실패한 큰 고정 TX 의 rollback (측정 전)

EXP-029 첫 시도에서 한 번 보았다. 200 만 행 insert 가 실패한 뒤 세션이 끊기자 서버가 뒤에서 rollback 했고, `information_schema.innodb_trx` 의 `trx_rows_modified` 가 1 분에 약 2 만 행씩 줄었다. 그동안 원본 테이블의 `truncate` 가 metadata lock 을 기다렸다. 수치로 적으려면 행 수를 바꿔 가며 재야 한다.
