# 9주차 설계: 같은 입력은 이어서 계산하고, 완성본만 공개한다

규칙 ID 는 `docs/week9/requirements.md` 다. 결정마다 근거와 **뒤집을 조건**을 같이 적는다.

## 1. 흐름

```
purchase_activity (W6 consumer 가 남긴 사실)
  │ ① 입력 고정 (Tasklet, TX 1)
  ▼
ranking_snapshot + ranking_snapshot_input      ← 바뀌지 않는 입력
  │ ② 점수 계산 (Chunk, chunk 마다 TX)
  ▼
점수 stage (snapshotId, productId, score)      ← 계산 중. API 가 읽지 않는다
  │ ③ 공개 (Tasklet, TX 1)
  ▼
완성 ranking + 기간별 공개 pointer              ← API 가 읽는 것
```

입력과 stage 는 계산하는 쪽(쓰기 모델)이고, 완성 ranking 과 pointer 는 보여 주는 쪽(읽기 모델)이다. 쓰기 모델은 batch 만, 읽기 모델은 commerce-api 만 읽는다. 공개 step 이 쓰기 모델을 읽기 모델로 옮기는 projection 이다. 이벤트마다 조금씩 고치는 projection(W6 의 구매 활동 consumer)과 달리 snapshot 마다 읽기 모델을 통째로 새로 만들고, 다 만든 뒤 pointer 하나로 바꾼다. 그래서 API 는 계산 중인 조각을 보지 않고(`PBL-003`), 다시 돌려도 결과가 같고(`PBL-004`), 옛 snapshot 의 결과가 남는다(`PBL-009`). 순위를 읽기 모델에 저장해 두므로 조회는 집계하지 않고, 공개 조건으로 상품을 빼도 순위가 당겨지지 않는다(`QRY-007`).

**뒤집을 조건**: 실시간 랭킹처럼 읽기 모델을 이벤트마다 고쳐야 할 때. 그때는 snapshot 을 통째로 만드는 대신 이벤트마다 고치는 projection 을 따로 둔다(12 장).

## 2. step 을 셋으로 나눈다 (`JOB-001`)

step 은 재시작의 단위다. Batch 는 COMPLETED step 을 다시 실행하지 않는다. 입력 고정을 따로 두면 계산이 실패해도 입력을 다시 복사하지 않고(`SNP-005`), 그 판단을 코드가 아니라 프레임워크가 한다.

| step | 방식 | 근거 |
|---|---|---|
| 입력 고정 | Tasklet | 입력 행과 snapshot 정보가 한 TX 로 commit 돼야 반쪽 입력이 없다(`SNP-003`, `SNP-004`) |
| 점수 계산 | Chunk | 상품마다 나눠 commit 하고 실패 지점부터 이어 간다(`JOB-004`) |
| 공개 | Tasklet | 완성 확인과 pointer 교체가 한 TX 여야 일부 순위가 보이지 않는다(`PBL-002`) |

Tasklet 은 메모리를 더 쓰는 방식이 아니다. 메모리는 행을 누가 옮기느냐로 정해진다. 입력 고정은 DB 안에서 옮기므로 애플리케이션이 받는 것은 행 수 하나다.

**뒤집을 조건**: 입력 고정 TX 가 너무 길다고 측정될 때. 6 장의 "한 번 읽고 나눠 쓰기" 로 바꾼다.

## 3. 실행 식별 (`JOB-002`)

| 값 | 식별하는 것 |
|---|---|
| job 이름 `weeklyRanking` | 처리 흐름 |
| `periodStart` | 업무 기간 |
| `revision` | 그 기간의 몇 번째 입력인지 |
| `snapshotId` | 고정 입력과 그 계산 결과의 묶음. `weekly-{yyyyMMdd}-r{revision}` |

`snapshotId` 는 기간과 revision 으로 정해지지만 parameter 로도 받아 고정 step 이 둘이 맞는지 확인한다(`SNP-008`). 같은 값이면 같은 JobInstance 다. 재시작은 그 instance 의 새 execution 이고, 늦은 구매를 포함한 재계산은 새 snapshotId 라 새 instance 다. `run.id` 나 시각을 식별 parameter 에 넣으면 실패 뒤 실행이 새 instance 가 되어 checkpoint 에서 이어 갔다는 증거가 사라진다. Demo 의 `RunIdIncrementer` 를 쓰지 않는 이유다.

## 4. 같은 TX (`JOB-003`)

chunk TX 가 시작되면 transaction manager 가 커넥션을 현재 스레드에 묶는다. writer 의 쿼리와 JobRepository 의 위치 저장이 그 커넥션을 꺼내 쓰므로 점수와 checkpoint 가 함께 commit 되거나 함께 rollback 된다.

이 저장소의 DataSource 는 `modules/jpa` 의 `@Primary mySqlMainDataSource` 하나다. Boot 가 만드는 `JpaTransactionManager` 를 step 에 넘기고, Boot 의 JDBC JobRepository 가 같은 DataSource 를 쓴다. `JOB-007` 의 연결 테스트가 이 사실을 확인한다.

같은 TX 가 깨지는 경우:

| 경우 | 결과 |
|---|---|
| `ResourcelessTransactionManager` | 커넥션을 스레드에 묶지 않는다. 쿼리마다 auto-commit 이다 |
| writer 와 transaction manager 의 DataSource 가 다름 | writer 가 새 커넥션을 연다 |
| 다른 스레드에서 쿼리 | 그 스레드에는 묶인 커넥션이 없다 |

step 에 넘기는 transaction manager 는 Boot 가 만든 `JpaTransactionManager` 다. batch 는 엔티티를 쓰지 않지만 DataSource 를 받으려고 의존하는 `modules:jpa` 가 JPA 를 데려와 Boot 가 `DataSourceTransactionManager` 대신 이것을 만든다. TX 를 시작하면 Hibernate 가 잡은 JDBC 커넥션을 같은 DataSource 로 스레드에 묶어, `JdbcTemplate` 과 JobRepository 가 그 커넥션을 쓴다. 대가는 TX 마다 쓰지 않는 Hibernate Session 을 하나 여는 것이다.

**뒤집을 조건**: Batch 전용 DataSource 를 두게 될 때. writer 와 JobRepository 가 같은 DataSource 를 쓰도록 먼저 맞추고 연결 테스트를 다시 돌린다. batch 가 JPA 없이 DataSource 만 받게 되면 transaction manager 가 `DataSourceTransactionManager` 로 바뀌므로 같은 TX 를 보는 테스트(`JOB-007`, `SNP-004`, `JOB-004`)를 다시 돌린다.

## 5. 데이터 접근: batch 는 JDBC, 테이블은 SQL DDL, 엔티티는 두지 않는다

batch 의 업무 코드는 `JdbcTemplate` 과 Spring Batch 의 JDBC reader 로 쓴다. batch 가 쓰는 테이블(`ranking_snapshot`, `ranking_snapshot_input`, `ranking_score_stage`, `weekly_ranking`, `weekly_ranking_publication`)은 `schema/ranking.sql` 이 정의하고, local·test 프로필에서 `spring.sql.init` 이 `create table if not exists` 로 만든다. Batch metadata 를 `initialize-schema: always` 로 만드는 것과 같은 방식이다.

commerce-api 는 공개 ranking 을 엔티티 없이 `JdbcTemplate` 과 record 로 읽는다. 조회는 pointer 와 ranking 을 잇는 정적 쿼리 하나다(`QRY-009`). 읽기 엔티티를 두면 같은 테이블의 정의가 batch 의 SQL 과 api 의 엔티티 두 곳에 생긴다. 테이블의 주인은 쓰는 쪽인 batch 하나다. **뒤집을 조건**: 랭킹 조회에 검색 조건처럼 붙었다 빠지는 조건이 생길 때 api 에 읽기 엔티티와 QueryDSL 을 둔다.

엔티티는 행을 객체로 꺼내 상태를 바꾸거나 판단을 맡길 때 값을 한다. batch 는 행을 SQL 안에서 옮기고 합하므로 그런 일이 없다. 엔티티를 두면 테이블 자동 생성 하나를 얻는 대신 칸 순서를 Hibernate 에 맡기고(7 장), 원본 테이블의 정의를 batch 에 하나 더 두게 된다.

Java 로 넘어오는 값에만 타입을 둔다. 테이블마다 record 를 두지 않는다. 지금은 launch parameter 의 불변식을 가진 `WeeklySnapshot` 하나다. chunk item 처럼 코드 사이를 오가는 값이 생길 때 그 값의 record 를 만든다.

원본 `purchase_activity` 는 commerce-streamer 가 정의한다. batch 는 main 에 그 정의를 두지 않고, 필요한 테스트가 streamer 와 같은 모양의 DDL 을 테스트 자원으로 둔다. batch 를 띄워도 streamer 의 테이블을 다시 만들지 않는다.

운영 프로필은 `ddl-auto: none` 이고 `spring.sql.init` 을 켜지 않는다. 운영 DB 에는 `schema/ranking.sql` 을 따로 적용한다.

`create table if not exists` 는 없는 테이블만 만든다. 정의를 고쳐도 이미 있는 테이블에는 반영되지 않으므로 local 에서 칸을 바꾸면 그 테이블을 지우고 다시 띄운다. 테스트는 testcontainers 가 빈 DB 로 시작해 항상 파일 그대로다.

| 위치 | 구현 |
|---|---|
| 입력 고정 | `insert … select ?, … from purchase_activity where …` 한 문장 |
| reader | `JdbcPagingItemReader`(7 장) |
| writer | chunk 의 상품 ID 로 `insert … select … sum(quantity) … group by … on duplicate key update` 한 문장(8 장) |
| 입력 checksum | fetchSize `Integer.MIN_VALUE` 로 드라이버가 행을 하나씩 받게 하고, `RowCallbackHandler` 로 한 행씩 해시에 넣는다(14 장) |

근거:

- batch 의 쿼리는 모두 정적이다. 조건이 붙었다 빠지는 쿼리가 없어 QueryDSL 의 가장 큰 이점(동적 조건)을 쓰지 않는다.
- JPQL 로 쓰면 MySQL 기능(`ON DUPLICATE KEY UPDATE`)을 쓸 수 없고, QueryDSL 은 insert-select 의 상수 칸을 빼고 직렬화하며 `sum()` 의 타입을 Integer 로 잡는다. 같은 일을 SQL 은 우회 없이 한 문장으로 한다.
- 이 저장소의 QueryDSL 은 `com.querydsl` 5.1.0 이다. 원 프로젝트는 업데이트가 거의 없고 관리되는 갈래는 OpenFeign 포크(`io.github.openfeign.querydsl`)다. 정적 쿼리만 있는 곳에 새로 의존을 늘리지 않는다.
- 엔티티가 없으므로 dirty checking 이 고정 입력을 바꾸는 일(`SNP-005`)이 구조적으로 없다.

대가: SQL 이 문자열이라 칸 이름을 바꾸면 컴파일이 아니라 실행에서 실패한다. 테스트가 실제 MySQL 로 돌아 거기서 잡는다. 테스트 데이터 정리도 엔티티 목록을 보는 `DatabaseCleanUp` 이 아니라 테스트가 자기 행을 지운다.

시간 칸은 UTC `LocalDateTime` 으로 주고받는다. 테스트 JVM 의 시간대는 `Asia/Seoul` 이고 DB 의 `confirmed_at` 은 UTC 다. `Timestamp` 를 넘기면 드라이버가 JVM 시간대로 바꿔 경계가 9 시간 어긋날 수 있다.

batch 모듈의 QueryDSL annotation processor 는 두지 않는다. QueryDSL 의 Q 클래스는 엔티티에서 만들어지므로 QueryDSL 을 쓰는 것은 엔티티를 다시 두는 것과 같은 선택이다.

**뒤집을 조건**: batch 에 조건이 바뀌는 쿼리(검색 필터, 선택적 조건)가 생길 때 QueryDSL 과 엔티티를 함께 둔다. 그때는 OpenFeign 포크로 옮길지 함께 정한다. 점수 계산에 반품 차감, 가중치 같은 도메인 규칙이 붙어 DB 없이 판단해야 할 때는 읽은 값을 도메인 객체로 바꾸어 넘긴다. 운영 스키마를 migration 도구로 관리하게 되면 `schema/ranking.sql` 을 그 도구의 첫 migration 으로 옮긴다. 칸 이름을 컴파일에서 잡고 싶어지면 jOOQ 를 둔다. `ranking.sql` 에서 DB 없이 타입을 만들 수 있어 테이블 정의가 한 곳으로 남고, insert…select·on duplicate key update·window 함수를 표현한다. 그때 같은 TX 테스트(`JOB-007`, `SNP-004`, `JOB-004`)를 다시 돌린다.

## 6. 입력 고정 (`SNP-003`)

```sql
insert into ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at)
select :snapshotId, product_id, order_id, quantity, confirmed_at
from purchase_activity
where confirmed_at >= :periodStart and confirmed_at < :periodEnd
```

SELECT 한 문장은 하나의 읽기 시점이다. 재시작에 걸쳐 같은 입력을 읽으려면 그 결과를 테이블에 남겨야 한다. 한 TX 의 일관된 읽기는 실행이 바뀌면 이어지지 않고, `max(id)` 는 먼저 번호를 받은 TX 가 나중에 commit 하면 경계가 틀린다.

복사로 고정하는 또 다른 이유는 W7 의 지연에 상한이 없다는 것이다. relay 는 실패한 outbox 를 24 시간까지 재시도한 뒤 `BLOCKED` 로 두고, `BLOCKED` 재개와 확정 이벤트 복구 API 는 지난 주의 확정을 언제든 다시 보낸다. 그래서 "주가 끝나고 유예 시간이 지나면 더 늦은 구매는 없다" 는 경계를 정할 수 없다. 고정 시점까지 도착한 것을 그 revision 의 입력으로 명시적으로 고정하고, 늦게 도착한 구매는 새 revision 으로 반영한다(`REV-002`). 언제 고정하고 언제 새 revision 을 만들지는 운영자가 정한다(Q-3). 고정 시점에 아직 발행되지 않은 outbox 의 수를 함께 남기는 방법은 batch 가 api 의 테이블을 읽게 되고(Q-6 의 계약 경계와 어긋난다), 발행된 뒤 streamer 가 아직 처리하지 않은 것은 세지 못해 완전성을 보장하지도 않아 두지 않는다.

| 부담 | 이 저장소에서 |
|---|---|
| 원본 잠금 | 로컬(`docker/infra-compose.yml`)과 테스트 MySQL 은 READ COMMITTED 다. 이 격리 수준에서 INSERT…SELECT 는 원본을 잠그지 않고 읽는다. REPEATABLE READ 에서는 읽은 원본 행과 `confirmed_at` 인덱스 범위에 공유 next-key 잠금을 걸어, 고정하는 동안 같은 주에 늦게 도착한 구매의 저장이 기다린다 |
| 원본 스캔 | streamer 의 `idx_activity_confirmed_at` 로 그 주의 범위만 읽는다(Q-7). 행마다 `order_id`, `product_id`, `quantity` 를 읽으러 기본 키로 한 번 더 간다. streamer 는 활동을 쓸 때마다 이 인덱스를 함께 갱신한다 |
| TX 길이 | 복사가 끝날 때까지 TX 가 열린다. 측정 전 |

지금 고르지 않은 선택지:

| 선택지 | 고르지 않은 이유 |
|---|---|
| 한 번 읽고 나눠 쓰기. SELECT cursor 하나로 읽고 chunk 로 쓴 뒤 마지막에 `FROZEN` | TX 는 작아지지만 실패하면 이어 할 수 없다. 재시작하면 SELECT 가 새 시점이 되어 앞서 복사한 행과 섞이므로, 고정 중인 행을 지우고 처음부터 다시 복사해야 한다 |
| 상품별 합계만 고정 | 주문·상품별 입력과 입력 checksum(`SNP-007`)이 사라진다 |
| 복사하지 않고 `(기간, cutoff)` 경계와 checksum 만 고정 | W7 의 재시도·재개·복구 때문에 cutoff 뒤에도 cutoff 전에 확정된 구매가 도착한다. 원본이 수정·삭제되지 않는다는 보장과 DB 가 찍는 기록 시각도 없다 |

**뒤집을 조건**: 고정 TX 시간이나 undo 증가가 측정으로 문제가 될 때 "한 번 읽고 나눠 쓰기" 로 바꾼다. 격리 수준은 DB 서버 설정(READ COMMITTED)이 전제다. api 와 streamer 의 잠금 검증도 같은 전제 위에 있으므로, 이 전제를 보장할 수 없는 DB 에 배포할 때는 고정 step 만이 아니라 모든 앱의 연결 설정에서 READ COMMITTED 를 명시한다. 고정 시간이 측정으로 문제가 되면 인덱스를 `(confirmed_at, order_id, product_id, quantity)` 로 넓혀 기본 키를 다시 찾지 않게 한다. 운영 DB 는 `ddl-auto` 를 쓰지 않으므로 이 인덱스를 `create index` 로 따로 적용한다. W7 의 재시도와 복구에 기한이 생겨 지연의 상한이 정해지고, 원본이 append-only 로 보장되며 DB 가 기록 시각을 찍게 되면, 입력을 복사하지 않고 `(기간, cutoff)` 경계와 checksum 만 고정한다.

### 이미 고정한 snapshot (`SNP-006`)

Batch 는 COMPLETED step 을 다시 실행하지 않지만 고정 step 이 다시 도는 경우가 있다. tasklet TX 가 commit 된 뒤 step 을 COMPLETED 로 기록하는 JobRepository 의 TX 가 실패하면 그 step 은 COMPLETED 가 아니고, 재시작이 고정 step 을 다시 실행한다. metadata 를 지우고 같은 parameter 로 다시 launch 해도 같다.

고정 step 은 먼저 `snapshotId` 의 행을 찾는다. 있으면 기간과 revision 만 비교한다. 같으면 원본을 읽지 않고 끝내고, 다르면 실패한다. 입력은 비교하지 않는다. 다시 계산하면 고정 뒤 도착한 구매 때문에 다르게 나오는 것이 정상이고(`REV-001`), 고정한 입력이 그 snapshot 의 정의다(`SNP-005`). 늦은 구매를 넣으려면 새 revision 이다(`REV-002`).

`snapshotId` 는 기간과 revision 으로 정해지고(`SNP-008`) 기본 키라, 고정 step 을 거친 행끼리는 기간이나 revision 이 다를 수 없다. "다르면 실패" 는 손으로 넣은 행이나 형식이 바뀐 옛 행을 덮지 않으려는 것이다.

**뒤집을 조건**: 같은 snapshotId 로 입력을 다시 만들어야 하는 운영 절차가 생길 때. 그때도 덮지 않고, 무엇이 바뀌었는지 비교하려면 저장된 checksum 과 다시 계산한 checksum 을 함께 남긴다.

## 7. reader: `JdbcPagingItemReader`, keyset (`CAL-001`)

| 기준 | offset | keyset |
|---|---|---|
| 다음 페이지 | `offset(page × size)` | `product_id > 마지막 값` |
| 전체 비용 | 상품 P, 페이지 s 이면 건너뛰는 행이 약 P²/(2s). 상품 10 만, 페이지 100 이면 49,950,000 | 건너뛰지 않는다 |
| 저장하는 위치 | 읽은 개수 | 마지막 정렬 key 값 |

`JdbcPagingItemReader` 는 `sortKey` 로 keyset 조회를 하고 마지막 값을 ExecutionContext 의 `snapshotProductReader.start.after` 에 저장한다. 재시작하면 그 값 다음부터 읽는다. `select distinct product_id … where snapshot_id = :snapshotId`, `sortKey = product_id` 이고 page 크기는 chunk 크기와 같다. 결과 안에서 `product_id` 가 유일하므로 정렬 key 의 유일성 조건을 만족한다. 페이지 조회는 chunk TX 의 커넥션을 쓴다. reader 는 `@StepScope` bean 이고 반환 타입을 `ItemStreamReader` 로 둔다. step scope 프록시는 반환 타입의 인터페이스만 구현하므로 `ItemReader` 로 두면 Batch 가 `open`·`update` 를 부르지 않아 위치가 저장되지 않는다.

입력의 기본 키를 `(snapshot_id, product_id, order_id)` 로 둔다(`SNP-002`). reader 의 `product_id > ?` 와 writer 의 `product_id in (…)` 가 기본 키 범위에서 시작한다. `(snapshot_id, order_id, product_id)` 이면 둘 다 snapshot 전체를 훑는다. 유일성은 세 칸의 조합이고 칸 순서는 정해져 있지 않다. InnoDB 는 기본 키 순서로 행을 저장하므로 한 snapshot 의 한 상품 입력이 붙어 있다. 대리 키를 두지 않는다. 테이블을 SQL 로 정의하므로 칸 순서를 직접 적을 수 있다(5 장).

고르지 않은 것:

| reader | 고르지 않은 이유 |
|---|---|
| 직접 만든 keyset `ItemStreamReader` | 같은 일을 `JdbcPagingItemReader` 가 검증된 구현으로 한다. 직접 만들면 재시작 동작을 우리가 검증해야 한다 |
| `JpaPagingItemReader` | 값 projection 이면 엔티티를 올리지 않지만 offset 이다 |
| jojoldu `QuerydslNoOffsetPagingItemReader` | `javax` 이고 마지막 key 를 메모리에만 두어 재시작 때 복원하지 않는다 |
| `AbstractPagingItemReader` 를 상속해 keyset 으로 | 재시작 때 `read.count` 로 페이지 안 위치를 계산해, keyset 으로 읽은 페이지의 앞부분을 건너뛴다. page 크기와 chunk 크기가 다르면 상품이 빠진다 |
| `JdbcCursorItemReader` | step 내내 커넥션을 하나 더 잡고 읽기가 chunk TX 밖에서 일어난다. 재시작하면 쿼리를 처음부터 다시 실행해 처리한 행을 읽어 버린다. MySQL 은 fetchSize 를 정하지 않으면 결과 전체를 메모리에 올리고(14 장), 행 단위 fetch 에서는 `rs.getRow()` 를 지원하지 않아 `verifyCursorPosition(false)` 가 필요하다 |
| `RepositoryItemReader`(Spring Data 저장소) | `Pageable` 의 page 번호로 읽어 offset 이다. 재시작도 읽은 개수로 page 를 계산한다. 엔티티와 저장소가 필요해 5 장의 결정과 맞지 않는다 |

상품 10,000 개를 90% 지점에서 실패시켜 재시작해 보면, paging 은 커넥션 1 개로 남은 1,000 행만 다시 읽고 cursor 는 커넥션 2 개로 10,000 행을 다시 받아 9,000 행을 버린다. cursor 의 재시작 비용은 남은 양이 아니라 전체 양에 비례한다.

**뒤집을 조건**: 정렬 key 가 `product_id` 하나로 유일하지 않게 될 때(예: 상품과 옵션 단위로 item 을 나눌 때). `sortKeys` 에 칸을 더한다.

### 합계는 writer 가 chunk 마다 구한다

계산 step 에 processor 가 없다. reader 는 상품 ID 를 읽고, writer 가 chunk 에 든 상품들의 `sum(quantity)` 를 고정 입력에서 group by 로 구해 그대로 upsert 한다. 점수가 단순 합이라 Java 에서 할 일이 없다. 상품마다 sum 쿼리를 보내는 processor 방식에 비해 쿼리 수는 상품 수에서 chunk 수로 줄고, DB 가 더하는 입력 행은 같다(상품 10,000 개에서 쿼리 10,101 → 201, 검사한 행 100,099 → 60,099, 시간 약 1.9 초 → 0.5 초).

| 선택지 | 판단 |
|---|---|
| processor 가 상품마다 sum | 지금은 두지 않는다. 읽기 · 계산 · 저장의 책임이 코드 모양에 드러나지만 계산이 SQL 의 합 하나라 processor 에 Java 로 할 일이 없다 |
| `JdbcPagingItemReader` 에 `groupClause` 를 준다 | 쓰지 않는다. provider 가 `SELECT * FROM (… GROUP BY …) AS MAIN_QRY WHERE key > ? … LIMIT n` 을 만든다. MySQL 은 key 조건을 집계 안으로 밀어 넣지만 LIMIT 은 넣지 못해 page 마다 남은 상품 전부를 집계한다. 비용이 상품 수의 제곱으로 는다 |
| group by·keyset·LIMIT 을 한 층에 둔 쿼리를 만드는 provider 를 직접 둔다 | 두지 않는다. writer 방식과 쿼리 수가 비슷하면서 `JdbcPagingItemReader` 의 내부 규칙(마지막 key 를 받는 `:_product_id`)에 기대는 코드를 우리가 갖게 된다 |

writer 가 같은 chunk TX 안에서 입력을 읽는다. READ COMMITTED 에서 INSERT…SELECT 는 읽는 행을 잠그지 않고, REPEATABLE READ 에서 거는 공유 잠금도 고정 입력은 아무도 쓰지 않아 기다릴 상대가 없다. 한 상품에 주문이 몰리면 그 상품을 맡은 chunk 의 TX 가 길어지는 것은 방식과 무관하게 남는다.

**뒤집을 조건**: 점수에 가중치, 반품 차감처럼 DB 없이 테스트할 규칙이 붙을 때 processor 를 다시 두고 그 규칙을 Java 로 옮긴다. 한 상품의 입력이 커서 chunk TX 가 길어지는 것이 측정되면 고정 때 상품별 합계를 함께 만들지 따진다.

### chunk 크기

chunk 크기는 설정값 `ranking.chunk-size` 이고 기본값은 100 이다. 재시작 테스트는 2 로 준다(`CAL-004`). job parameter 로 두지 않는다. 식별 parameter 면 값을 바꿀 때마다 새 JobInstance 가 되어 재시작이 이어지지 않고(`JOB-002`), 비식별 parameter 면 재시작 때 다른 값을 줄 수 있어 저장된 위치와 page 경계가 어긋나 보인다. 100 은 처리량을 잰 값이 아니다. 한 chunk 의 TX 가 page 조회 한 번과 상품 100 개의 집계 upsert 한 번을 담는 크기로 시작한다.

**뒤집을 조건**: 계산 step 의 처리량이나 chunk TX 시간을 측정해 문제가 될 때 그 측정으로 값을 정한다.

## 8. writer: 절대값 (`CAL-003`)

```sql
insert into ranking_score_stage (snapshot_id, product_id, score)
select * from (
  select snapshot_id, product_id, sum(quantity) as total
  from ranking_snapshot_input
  where snapshot_id = :snapshotId and product_id in (:productIds)
  group by snapshot_id, product_id
) as scored
on duplicate key update score = scored.total
```

`ScoreStageWriter` 가 이 문장을 맡는다. 점수 규칙(`CAL-002`, `CAL-003`)이 있는 곳이라 조립만 하는 config 에서 떼어 이름을 붙였다. chunk 하나가 문장 하나다. 같은 chunk 를 다시 써도 고정 입력의 합으로 덮으므로 값이 같다. `score = score + ?` 처럼 더하는 방식은 섞지 않는다. 집계는 chunk 의 상품 최대 chunk 크기만큼에 대해서만 일어나고 PK `(snapshot_id, product_id, …)` 범위로 읽는다.

stage 점수가 다시 써도 같다는 것을 지키는 테스트는 `CAL-003` 하나다. writer 를 더하는 방식으로 바꾸면 재공개(`PBL-004`)는 이미 쓴 순위 행을 건너뛰어, 재시작(`JOB-004`)은 다시 쓰는 상품이 처음 쓰는 행이어서 둘 다 통과한다.

**뒤집을 조건**: 운영 DB 가 MySQL 이 아니게 될 때. 그 DB 의 upsert(`MERGE` 등)로 바꾼다.

## 9. 단일 스레드 (`JOB-005`)

멀티스레드 step 은 reader 하나를 여러 스레드가 함께 쓴다. chunk 가 순서 없이 commit 되어 저장한 위치가 처리 완료 지점을 뜻하지 않으므로 위치 저장을 꺼야 하고, 재시작 요구(`JOB-004`)와 충돌한다. 점수 행은 상품마다 달라 행 충돌은 없지만 인접 key 의 upsert 가 next-key lock 으로 deadlock 을 낼 수 있다.

**뒤집을 조건**: 계산 step 의 처리 시간이 측정으로 문제가 될 때. 멀티스레드 step 이 아니라 partitioning 을 쓴다. partition 마다 reader 와 위치가 따로 있어 재시작을 지킨다.

## 10. 테스트

실제 MySQL(testcontainers, READ COMMITTED)로 본다. upsert, 잠금, 시간 타입, Batch metadata 스키마가 H2 와 다르다.

| 도구 | 쓰는 곳 | 쓰지 않는 곳 |
|---|---|---|
| `JobLauncher` + `JobExplorer` | 연결 테스트(Job 객체를 매번 새로 만든다) | |
| `JobLauncherTestUtils.launchJob(같은 params)` | 랭킹 재시작 테스트 | 인자 없는 `launchJob()`. 무작위 식별 parameter 로 매번 새 instance 다 |
| `JobRepositoryTestUtils.removeJobExecutions(자기 실행)` | 두 실행이 끝난 뒤 정리 | 두 실행 사이. checkpoint 가 사라진다 |
| `StepScopeTestUtils` | reader 하나를 job 없이 확인 | |

저장된 위치는 `JobExplorer` 로 DB 에서 다시 읽은 ExecutionContext 로 확인한다. 메모리의 StepExecution 은 저장의 증거가 아니다. writer 의 결과는 Batch 의 write count 가 아니라 업무 테이블을 따로 조회해서 확인한다. write count 는 writer 의 SQL 이 같은 TX 로 commit 됐는지 말해 주지 않는다.

`@SpringBatchTest` 는 테스트 클래스에서 `JobExecution` 이나 `StepExecution` 을 돌려주는 메서드를 찾아 step scope 용 실행을 만드는 데 쓴다. 테스트 helper 가 `JobExecution` 을 돌려주면 그 메서드를 인자 없이 부르려다 모든 테스트가 `No matching arguments found for method` 로 실패한다. helper 는 `BatchStatus` 같은 다른 타입을 돌려준다.

`StepExecution` 을 돌려주는 helper 도 같다. 인자 없이 `JobExecution` 을 돌려주는 helper 는 더 위험하다. 리스너가 테스트마다 그것을 먼저 불러 job 을 한 번 더 실행한다. 그래서 helper 는 실행 ID(`long`)를 돌려주고, 상태는 `JobExplorer` 로 DB 에서 다시 읽는다. 저장된 값이 증거이기도 하다.

재시작 테스트의 실패는 테스트 더블 없이 실제 DB 잠금으로 만든다. 다른 커넥션이 상품 9 의 점수 행을 commit 하지 않은 채 insert 해 두면, writer 의 insert 가 유일 키에서 기다리다 `innodb_lock_wait_timeout`(3 초, `jpa.yml`)에 걸려 `Lock wait timeout exceeded` 로 실패한다. 실패 스위치를 job parameter 로 두지 않는다(`JOB-002`). 잠금을 풀고 같은 parameter 로 다시 실행한다.

테스트 클래스와 메서드에 `@Transactional` 을 두지 않는다. Batch 가 이미 열린 TX 를 거부하고, 재시작 사이에 commit 된 상태를 봐야 한다.

## 11. 패키지

batch 는 템플릿의 demo job 과 같은 관례를 따른다(Q-8). job 하나가 `com.loopers.batch.job.ranking` 이고, step 구성 요소(tasklet, writer)는 그 아래 `step/` 에 둔다. commerce-api 의 `interfaces → application → domain ← infrastructure` 계층은 따르지 않는다. 그 계층은 TX 경계를 application 에 모으고 판단을 DB 없는 애그리거트에 두려는 것인데, batch 에서는 step 이 TX 경계다.

| 위치 | 클래스 | 하는 일 |
|---|---|---|
| `ranking/` | `WeeklyRankingJobConfig` | job·step·reader 조립 |
| `ranking/` | `WeeklyRankingParameters` | job parameter 를 한 번 읽어 타입 있는 값으로 |
| `ranking/` | `WeeklyRankingParametersValidator` | launch 할 때 parameter 의 이름·타입·식별 여부와 `SNP-008` 을 확인한다(`JOB-002`) |
| `ranking/` | `WeeklySnapshot` | 판단(`SNP-006`, `SNP-008`, `REV-002`). DB 없이 테스트 |
| `ranking/store/` | `SnapshotStore`, `PublishedRankingStore`, `SortedRowsChecksum` | 테이블 묶음마다 이름 있는 메서드로 SQL |
| `ranking/step/` | `FreezeSnapshotTasklet`, `PublishRankingTasklet` | 판단과 store 를 부르는 순서 |
| `ranking/step/` | `ScoreStageWriter` | chunk 의 점수를 쓴다(`CAL-002`, `CAL-003`) |

루트는 job 단위(조립, parameter, 판단)이고 `step/` 은 step 부품, `store/` 는 SQL 이다. validator 는 step 이 아니라 job 에 붙으므로 루트에 둔다. reader 는 Spring Batch 의 `JdbcPagingItemReader` 를 builder 로 설정할 뿐이고, `@StepScope` bean 정의와 반환 타입(`ItemStreamReader`)이 config 에 있어야 하므로 따로 클래스를 두지 않는다. `step/` 이 `store/` 와 루트의 `WeeklySnapshot` 을 쓰므로 store 와 `WeeklySnapshot` 은 public 이고, store 둘만 쓰는 `SortedRowsChecksum` 은 `store/` 안에서 package-private 이다. demo 의 `DemoTasklet` 도 public 이다. batch 에는 ArchUnit 이 없고 job 이 하나라, 다른 코드가 store 를 우회해 쓰는 것은 리뷰로 막는다. 테스트는 `ranking/` 에 둔다. 연결 테스트도 여기에 남긴다. 설정만 보는 테스트라 깨지면 원인이 랭킹 로직이 아니라 TX·저장소 설정이라는 것이 바로 드러난다.

parameter 검사는 step 이 아니라 job 의 validator 가 한다. launcher 는 JobInstance 를 만들기 전에 validator 를 부르므로, 잘못된 parameter 는 실패한 실행으로 metadata 에 남지 않는다. step 안에서 검사하면 그 parameter 로 instance 가 생기고, `run.id` 가 붙거나 `revision` 이 식별용이 아니면 아예 다른 instance 로 성공해 버린다(`JOB-002`). 받은 식별 parameter 의 이름·타입 표가 정해 둔 표와 같은지를 한 번에 비교하고, 형식은 `WeeklySnapshot` 에 맡긴다. 고정 step 은 snapshotId 를 다시 확인하지 않는다.

**뒤집을 조건**: 두 번째 job 이 생겨 store 를 함께 쓰게 될 때. 공유하는 것을 job 밖으로 꺼내고, batch 에도 ArchUnit 으로 job 사이 의존을 막는다. 반품 차감, 가중치처럼 DB 없이 테스트할 판단이 생기면 그 판단을 `WeeklySnapshot` 옆의 객체로 꺼낸다.

## 12. 공개 결과의 저장소: DB, Redis Sorted Set 은 쓰지 않는다

공개 랭킹은 배치가 한 주에 한 번 만들고 그 뒤로 바뀌지 않는다. 완성 ranking 테이블이 이미 상위 N 을 읽을 수 있는 저장소이고, 같은 결과를 Sorted Set 에 한 번 더 두면 저장소가 둘이 된다.

| 계약 | Sorted Set 으로 하면 |
|---|---|
| 동점이면 `productId` 오름차순(`RNK-004`) | 같은 점수는 member 의 문자열 사전순이다. `"10"` 이 `"9"` 앞에 온다. 점수에 tie-break 를 섞어야 한다 |
| 저장된 순위 유지(`QRY-007`) | 순위를 조회할 때 계산하므로 삭제 상품을 빼면 순위가 당겨진다 |
| 완성 확인과 pointer 교체가 한 TX(`PBL-002`) | Redis 쓰기는 DB TX 밖이라 하나만 성공하면 부분 결과가 보인다 |
| 삭제 상품 제외(`QRY-007`) | 상품 테이블을 어차피 읽는다 |

**뒤집을 조건**: 실시간·시간 단위 랭킹이 요구될 때. 구매 이벤트 consumer 가 `ZINCRBY` 로 쌓는 별도 기능으로 둔다. 랭킹 API 의 읽기 부하가 측정으로 문제가 될 때는 공개된 snapshot 결과를 `ranking:weekly:{snapshotId}` 로 캐시한다. snapshot 내용은 바뀌지 않으므로 무효화가 필요 없고, pointer 는 DB 가 정한다.

## 13. 같은 기간의 직렬 실행 (`PBL-008`)

같은 기간의 서로 다른 revision 은 snapshotId 가 달라 다른 JobInstance 다. Batch 는 같은 instance 의 동시 실행만 막는다. 이번 범위에서는 운영에서 한 기간에 한 번에 하나만 launch 한다(Q-3). 스케줄러와 자동 launch 가 범위 밖이라 동시에 launch 하는 주체가 없다.

DB 가 지키는 것은 둘이다. 같은 기간·revision 의 snapshot 은 `(period_start, revision)` 유일 키 때문에 하나만 생긴다. 공개 pointer 는 조건부 갱신이라 늦게 끝난 옛 revision 이 새 revision 을 덮지 않는다(`PBL-005`). 지키지 않는 것은 두 revision 의 계산이 동시에 도는 일이다. 결과는 섞이지 않지만 같은 원본을 두 번 훑는다.

지금 고르지 않은 선택지:

| 선택지 | 고르지 않은 이유 |
|---|---|
| 기간마다 실행권 행을 두고 고정 step 이 조건부 갱신으로 얻는다 | 실행권 테이블, 해제 시점, 실패한 실행의 실행권 만료 규칙이 함께 생긴다. 동시에 launch 하는 주체가 없는데 그 규칙을 검증해야 한다 |

**뒤집을 조건**: 스케줄러의 자동 launch 나 실패 시 자동 재시도가 생길 때 실행권 행을 둔다.

## 14. 입력 checksum 의 메모리

MySQL Connector/J 는 fetchSize 를 정하지 않으면 결과 전체를 클라이언트 메모리에 받은 뒤 첫 행을 넘긴다. `RowCallbackHandler` 가 한 행씩 처리해도 메모리에는 한 주의 입력이 모두 올라간다. checksum 쿼리에 fetchSize `Integer.MIN_VALUE` 를 주어 드라이버가 소켓에서 한 행씩 읽게 한다. 입력과 결과 checksum 모두 `SortedRowsChecksum` 을 거친다.

결과 전체를 받으면 입력 행당 약 240B 가 heap 에 붙잡혀 행 수에 비례해 늘고, 행 단위로 받으면 늘지 않는다. 행 단위로 받아도 느려지지 않는다. 쿼리는 한 번이고 서버가 결과를 이어 보내므로 행마다 왕복하지 않는다.

대가: 결과를 다 읽기 전에는 그 커넥션으로 다른 쿼리를 보낼 수 없다. checksum 은 고정 step 의 마지막 읽기라 그 사이에 다른 쿼리가 없다. 클라이언트가 늦게 읽으면 서버가 쓰기를 기다리고, Connector/J 는 이때 `net_write_timeout` 을 `netTimeoutForStreamingResults`(기본 600 초)로 올린다.

고르지 않은 선택지:

| 선택지 | 고르지 않은 이유 |
|---|---|
| `useCursorFetch=true` 와 fetchSize N | N 행마다 서버 cursor 에 왕복하고 서버에 임시 테이블이 생긴다. 커넥션 URL 에 두면 모든 쿼리에 걸린다 |
| DB 에서 checksum 계산(`sha2(group_concat(...))`) | `group_concat_max_len` 을 넘으면 결과가 잘린다. 행 단위 해시가 아니라 이어 붙인 문자열 전체를 서버 메모리에 만든다 |

**뒤집을 조건**: checksum 읽기 도중 같은 커넥션으로 다른 쿼리를 보내야 할 때 `useCursorFetch` 로 바꾼다.

## 15. 공개 (`PBL-001` ~ `PBL-005`)

공개 step 은 Tasklet 하나이고, 한 TX 안에서 세 가지를 한다(`PBL-002`).

1. 완성 확인. stage 의 점수 행 수가 입력의 서로 다른 상품 수와 같아야 한다. 다르면 실패하고 아무것도 쓰지 않는다(`PBL-001`).
2. 순위 쓰기. `row_number() over (order by score desc, product_id)` 로 `weekly_ranking` 에 insert…select 한다(`RNK-004`). 그 snapshot 의 순위 행이 이미 있으면 쓰지 않는다. 순위 행은 pointer 와 같은 TX 로만 들어가므로 행이 있으면 완전한 결과다(`PBL-004`).
3. pointer 갱신. `weekly_ranking_publication` 은 기간마다 한 행이다. upsert 하되 새 revision 이 저장된 revision 보다 클 때만 snapshot 을 바꾼다(`PBL-005`). 같은 revision 을 다시 공개해도 `published_at` 이 바뀌지 않는다.

| 테이블 | 키 | 이유 |
|---|---|---|
| `weekly_ranking` | `(snapshot_id, rank_no)` | 조회가 snapshot 의 순위 앞부분부터 읽는다. 순위 칸을 `rank` 로 하지 않는 것은 MySQL 8 의 예약어(window 함수)라서다 |
| `weekly_ranking_publication` | `period_start` | 기간마다 공개본은 하나다. 조건부 갱신이 한 행에서 끝난다 |

**뒤집을 조건**: 한 기간에 공개본이 둘 이상 필요할 때(예: 점수 기준이 여럿) pointer 키에 그 축을 더한다.

완성 확인은 점수 행을 센 뒤 순위를 쓰는 "읽고 나서 쓰기" 다. 그 사이 stage 가 바뀌지 않는다. 같은 snapshot 의 계산은 같은 JobInstance 라 Batch 가 동시 실행을 막고, 다른 revision 은 다른 snapshot 의 행을 쓴다. pointer 는 비교와 갱신이 upsert 한 문장 안에 있어 읽고 나서 쓰는 틈이 없다. **뒤집을 조건**: 같은 snapshot 의 stage 를 job 밖에서 고치는 경로가 생길 때 완성 확인과 순위 쓰기 사이를 잠근다.

### 결과 checksum 은 저장하지 않는다 (`PBL-007`)

공개 step 은 공개한 순위로 결과 checksum 을 계산해 자기 StepExecution 의 ExecutionContext 에 `resultChecksum` 으로 남긴다. 공개 TX 가 commit 될 때 step 기록과 함께 저장된다. `ranking_snapshot` 에 칸을 두지 않는다.

입력 checksum 은 저장한다. 원본 `purchase_activity` 는 계속 바뀌므로 고정 시점의 값은 다시 만들 수 없고, `SNP-001` 이 snapshot 정보에 넣으라고 한다. 결과 checksum 은 한 번 쓰면 바뀌지 않는 `weekly_ranking` 에서 언제든 같은 값으로 다시 계산된다(`PBL-004`, `PBL-009`). 칸으로 두면 순위 행과 그 해시가 따로 저장되어 둘이 어긋날 수 있고, 공개 전의 null 이 "아직 공개 안 됨" 이라는 상태를 대신 말하게 된다. 지금 이 값을 읽는 것은 실험(`EXP-027`)과 운영자 확인뿐이다.

두 checksum 은 같은 방식이다. 정렬한 행을 한 줄씩 행 단위로 읽어(14 장) 해시에 넣는다. `SortedRowsChecksum` 이 이 일을 하고, 쿼리와 줄 형식만 다르다.

`ranking_snapshot.status` 는 `FROZEN` 하나만 쓴다. 공개 여부는 pointer 와 `weekly_ranking` 행이 말한다. `PUBLISHED` 를 더하면 상태와 pointer 가 서로 다른 revision 을 말하는 조합이 생긴다.

**뒤집을 조건**: API 나 외부 소비자가 결과 checksum 을 읽어야 할 때, 또는 `weekly_ranking` 을 지우거나 옮기는 정책이 생길 때 `ranking_snapshot` 에 결과 checksum 칸을 둔다. 상태를 읽어 분기하는 규칙(예: 계산 중인 snapshot 의 재계산 금지)이 생길 때 상태 값을 더한다.

## 16. 조회 API (`QRY-001` ~ `QRY-009`)

commerce-api 는 상품 조회와 같은 경로다. controller → `RankingFacade` → domain 의 조회 포트 `WeeklyRankingQuery` → infrastructure 의 `WeeklyRankingQueryImpl`. 요청 값은 `WeekStart`(월요일)와 `RankingLimit`(1 ~ 100, 기본 10)이 converter 에서 만들어지고, 거부하면 400 이다. 조회 결과 record(`Published`, `Item`)는 상품 조회의 `View` 처럼 포트와 함께 domain 에 있다.

조회는 pointer 와 순위, 상품 표시 정보를 한 문장으로 읽는다(`QRY-009`). 삭제된 상품은 안쪽 join 에서 먼저 빼고 바깥에서 limit 을 건다(`QRY-008`). 순위는 저장된 `rank_no` 를 그대로 쓴다(`QRY-007`). pointer 가 있고 순위 행이 없으면 left join 의 한 행으로 `PUBLISHED` 와 빈 items 가 된다(`QRY-006`). 결과는 엔티티 없이 `NamedParameterJdbcTemplate` 의 `ResultSetExtractor` 가 칸 이름으로 읽어 domain record 를 바로 만든다. `frozen_at` 은 `LocalDateTime` 으로 읽어 JVM 시간대로 바뀌지 않는다.

읽기 쪽은 화면에 필요한 테이블을 한 문장에서 join 한다. 상품 목록 조회(`ProductViewQueryImpl`)가 상품·브랜드·좋아요 집계를 join 하는 것과 같다. 애그리거트 경계와 package-private 엔티티는 상태를 바꾸는 쪽에서 지킨다. 그래서 랭킹 조회는 QueryDSL 로 쓰지 않는다. 상품·브랜드 엔티티와 그 Q 클래스는 상품·브랜드 패키지 밖에서 쓰지 않는다. 결과 record 는 상품 조회의 `View` 처럼 포트의 `Published`, `Item` 이고, 응답 record 는 `RankingV1Dto` 다.

대가: "고객에게 보이는 상품" 조건(`PRODUCT-005`, `deleted_at IS NULL`)이 상품 조회와 랭킹 조회 두 SQL 에 있다. **뒤집을 조건**: 그 조건이 하나에서 늘어날 때(숨김 상품, 판매 중지 등) 한 곳(DB view 나 상품 조회 포트)으로 모으고, 랭킹은 상품 정보를 그 포트에 묻는다.

batch 는 그 주에 구매된 상품 전부의 순위를 쓰고, API 가 limit 만큼 읽는다. 상위 N 개만 저장하면 그중 삭제된 상품만큼 limit 보다 적게 돌아온다.

**읽기 모델은 batch 와 api 사이의 계약이다.** 두 앱은 코드나 모듈을 공유하지 않는다. 테이블 정의는 쓰는 쪽인 batch 의 `schema/ranking.sql` 한 곳에 있고, api 테스트는 사본 없이 그 파일을 직접 읽어(`@Sql("file:../commerce-batch/…/ranking.sql")`) 테이블을 만든다. batch 가 칸을 바꾸면 api 테스트가 깨진다. Gradle 이 이 의존을 모르면 api 테스트를 다시 돌리지 않으므로, api 의 test task 에 그 파일을 입력으로 선언한다. 입력 고정 시점도 pointer 에 함께 써서 api 가 쓰기 모델(`ranking_snapshot`)을 읽지 않는다.

**뒤집을 조건**: api 와 batch 가 다른 저장소로 나뉘어 파일을 읽을 수 없게 될 때 계약 테스트나 공유 스키마 모듈을 둔다. 상품 수가 커서 전부 저장하는 비용이 측정으로 문제가 되면 상위 K 개를 여유 있게 저장하고 limit 보다 적게 돌아올 수 있음을 받아들인다. 읽기 부하가 문제가 되면 공개된 snapshot 결과를 Redis 에 캐시한다(12 장).

## 17. 연결 검증 (`EXP-028`)

원문은 PG 확정부터 API 까지를 한 연결 테스트에서 확인하라고 한다. 한 실행으로 묶지 않고 구간마다 그 앱의 테스트로 확인한다(Q-6). W6 Q-4, W7 Q-3 과 같은 방식이다.

| 구간 | 확인하는 곳 |
|---|---|
| PG 확정 → Outbox → Kafka | commerce-api 의 W6·W7 테스트와 공유 계약 JSON |
| 재발행해도 구매 활동이 한 번(`REV-004`) | streamer 의 W6 테스트(`ACT-002`) |
| 구매 활동 → 고정 → 계산 → 공개, 늦은 구매와 revision, 경계 시각 | batch 테스트 |
| 공개본 → API, 빈 주·미공개·삭제 상품 | api 테스트 |

구간 사이의 계약은 두 개다. 이벤트 payload 는 W6 의 공유 계약 JSON 이 잇는다. 읽기 모델은 api 테스트가 batch 의 DDL 을 직접 읽어 잇는다(16 장). `purchase_activity` 는 streamer 가 엔티티로 정의해 batch 테스트가 사본 DDL 을 둔다. 이 사본이 어긋나는 것은 테스트로 잡지 못한다.

한 실행으로 묶으면 앱 테스트가 다른 앱의 실행물과 테이블을 알게 되어, 그 테스트의 주인이 모호해진다. 원문과 다르게 한 것이므로 PR 에 밝힌다.

**뒤집을 조건**: 구간 테스트가 모두 통과했는데 실제 연결에서 실패하는 일이 생길 때. 그 장면을 재현 스크립트(compose 위에 세 앱을 프로세스로)로 남긴다. `purchase_activity` 사본이 실제로 어긋날 때 batch 테스트가 streamer 엔티티로 만든 스키마를 쓰는 방법을 따진다.
