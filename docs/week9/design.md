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

**뒤집을 조건**: Batch 전용 DataSource 를 두게 될 때. writer 와 JobRepository 가 같은 DataSource 를 쓰도록 먼저 맞추고 연결 테스트를 다시 돌린다.

## 5. 데이터 접근: batch 는 JDBC, 테이블은 SQL DDL, 엔티티는 두지 않는다

batch 의 업무 코드는 `JdbcTemplate` 과 Spring Batch 의 JDBC reader 로 쓴다. batch 가 쓰는 테이블(`ranking_snapshot`, `ranking_snapshot_input`, `ranking_score_stage`)은 `schema/ranking.sql` 이 정의하고, local·test 프로필에서 `spring.sql.init` 이 `create table if not exists` 로 만든다. Batch metadata 를 `initialize-schema: always` 로 만드는 것과 같은 방식이다. commerce-api 의 랭킹 조회는 기존 코드와 같이 QueryDSL 이다.

엔티티는 행을 객체로 꺼내 상태를 바꾸거나 판단을 맡길 때 값을 한다. batch 는 행을 SQL 안에서 옮기고 합하므로 그런 일이 없다. 엔티티를 두면 테이블 자동 생성 하나를 얻는 대신 칸 순서를 Hibernate 에 맡기고(7 장), 원본 테이블의 정의를 batch 에 하나 더 두게 된다.

Java 로 넘어오는 값에만 타입을 둔다. 테이블마다 record 를 두지 않는다. 지금은 launch parameter 의 불변식을 가진 `WeeklySnapshot` 하나다. chunk item 처럼 코드 사이를 오가는 값이 생길 때 그 값의 record 를 만든다.

원본 `purchase_activity` 는 commerce-streamer 가 정의한다. batch 는 main 에 그 정의를 두지 않고, 필요한 테스트가 streamer 와 같은 모양의 DDL 을 테스트 자원으로 둔다. batch 를 띄워도 streamer 의 테이블을 다시 만들지 않는다.

운영 프로필은 `ddl-auto: none` 이고 `spring.sql.init` 을 켜지 않는다. 운영 DB 에는 `schema/ranking.sql` 을 따로 적용한다.

`create table if not exists` 는 없는 테이블만 만든다. 정의를 고쳐도 이미 있는 테이블에는 반영되지 않으므로 local 에서 칸을 바꾸면 그 테이블을 지우고 다시 띄운다. 테스트는 testcontainers 가 빈 DB 로 시작해 항상 파일 그대로다.

| 위치 | 구현 |
|---|---|
| 입력 고정 | `insert … select ?, … from purchase_activity where …` 한 문장 |
| reader | `JdbcPagingItemReader`(7 장) |
| processor | `select sum(quantity) …` |
| writer | `insert … as new on duplicate key update score = new.score`, chunk 를 `batchUpdate` 로(8 장) |
| 입력 checksum | fetchSize `Integer.MIN_VALUE` 로 드라이버가 행을 하나씩 받게 하고, `RowCallbackHandler` 로 한 행씩 해시에 넣는다(14 장) |

근거:

- batch 의 쿼리는 모두 정적이다. 조건이 붙었다 빠지는 쿼리가 없어 QueryDSL 의 가장 큰 이점(동적 조건)을 쓰지 않는다.
- JPQL 로 쓰면 MySQL 기능(`ON DUPLICATE KEY UPDATE`)을 쓸 수 없고, QueryDSL 은 insert-select 의 상수 칸을 빼고 직렬화하며 `sum()` 의 타입을 Integer 로 잡는다. 같은 일을 SQL 은 우회 없이 한 문장으로 한다.
- 이 저장소의 QueryDSL 은 `com.querydsl` 5.1.0 이다. 원 프로젝트는 업데이트가 거의 없고 관리되는 갈래는 OpenFeign 포크(`io.github.openfeign.querydsl`)다. 정적 쿼리만 있는 곳에 새로 의존을 늘리지 않는다.
- 엔티티가 없으므로 dirty checking 이 고정 입력을 바꾸는 일(`SNP-005`)이 구조적으로 없다.

대가: SQL 이 문자열이라 칸 이름을 바꾸면 컴파일이 아니라 실행에서 실패한다. 테스트가 실제 MySQL 로 돌아 거기서 잡는다. 테스트 데이터 정리도 엔티티 목록을 보는 `DatabaseCleanUp` 이 아니라 테스트가 자기 행을 지운다.

시간 칸은 UTC `LocalDateTime` 으로 주고받는다. 테스트 JVM 의 시간대는 `Asia/Seoul` 이고 DB 의 `confirmed_at` 은 UTC 다. `Timestamp` 를 넘기면 드라이버가 JVM 시간대로 바꿔 경계가 9 시간 어긋날 수 있다.

batch 모듈의 QueryDSL annotation processor 는 두지 않는다. QueryDSL 의 Q 클래스는 엔티티에서 만들어지므로 QueryDSL 을 쓰는 것은 엔티티를 다시 두는 것과 같은 선택이다.

**뒤집을 조건**: batch 에 조건이 바뀌는 쿼리(검색 필터, 선택적 조건)가 생길 때 QueryDSL 과 엔티티를 함께 둔다. 그때는 OpenFeign 포크로 옮길지 함께 정한다. 점수 계산에 반품 차감, 가중치 같은 도메인 규칙이 붙어 DB 없이 판단해야 할 때는 읽은 값을 도메인 객체로 바꾸어 넘긴다. 운영 스키마를 migration 도구로 관리하게 되면 `schema/ranking.sql` 을 그 도구의 첫 migration 으로 옮긴다.

## 6. 입력 고정 (`SNP-003`)

```sql
insert into ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at)
select :snapshotId, product_id, order_id, quantity, confirmed_at
from purchase_activity
where confirmed_at >= :periodStart and confirmed_at < :periodEnd
```

SELECT 한 문장은 하나의 읽기 시점이다. 재시작에 걸쳐 같은 입력을 읽으려면 그 결과를 테이블에 남겨야 한다. 한 TX 의 일관된 읽기는 실행이 바뀌면 이어지지 않고, `max(id)` 는 먼저 번호를 받은 TX 가 나중에 commit 하면 경계가 틀린다.

| 부담 | 이 저장소에서 |
|---|---|
| 원본 잠금 | 로컬(`docker/infra-compose.yml`)과 테스트 MySQL 은 READ COMMITTED 다. 이 격리 수준에서 INSERT…SELECT 는 원본을 잠그지 않고 읽는다. REPEATABLE READ 에서는 원본 행에 공유 잠금을 건다 |
| 원본 스캔 | `confirmed_at` 인덱스가 없어 고정마다 쌓인 활동 전체를 훑는다(Q-7) |
| TX 길이 | 복사가 끝날 때까지 TX 가 열린다. 측정 전 |

지금 고르지 않은 선택지:

| 선택지 | 고르지 않은 이유 |
|---|---|
| 한 번 읽고 나눠 쓰기. SELECT cursor 하나로 읽고 chunk 로 쓴 뒤 마지막에 `FROZEN` | TX 는 작아지지만 실패하면 이어 할 수 없다. 재시작하면 SELECT 가 새 시점이 되어 앞서 복사한 행과 섞이므로, 고정 중인 행을 지우고 처음부터 다시 복사해야 한다 |
| 상품별 합계만 고정 | 주문·상품별 입력과 입력 checksum(`SNP-007`)이 사라진다 |

**뒤집을 조건**: 고정 TX 시간이나 undo 증가가 측정으로 문제가 될 때 "한 번 읽고 나눠 쓰기" 로 바꾼다. 서버 격리 수준이 REPEATABLE READ 인 환경에서 실행할 때 고정 TX 의 격리 수준을 READ COMMITTED 로 명시한다.

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

입력의 기본 키를 `(snapshot_id, product_id, order_id)` 로 둔다(`SNP-002`). reader 의 `product_id > ?` 와 processor 의 `product_id = ?` 가 기본 키 범위에서 시작한다. `(snapshot_id, order_id, product_id)` 이면 둘 다 snapshot 전체를 훑는다. 유일성은 세 칸의 조합이고 칸 순서는 정해져 있지 않다. InnoDB 는 기본 키 순서로 행을 저장하므로 한 snapshot 의 한 상품 입력이 붙어 있다. 대리 키를 두지 않는다. 테이블을 SQL 로 정의하므로 칸 순서를 직접 적을 수 있다(5 장).

고르지 않은 것:

| reader | 고르지 않은 이유 |
|---|---|
| 직접 만든 keyset `ItemStreamReader` | 같은 일을 `JdbcPagingItemReader` 가 검증된 구현으로 한다. 직접 만들면 재시작 동작을 우리가 검증해야 한다 |
| `JpaPagingItemReader` | 값 projection 이면 엔티티를 올리지 않지만 offset 이다 |
| jojoldu `QuerydslNoOffsetPagingItemReader` | `javax` 이고 마지막 key 를 메모리에만 두어 재시작 때 복원하지 않는다 |
| `AbstractPagingItemReader` 를 상속해 keyset 으로 | 재시작 때 `read.count` 로 페이지 안 위치를 계산해, keyset 으로 읽은 페이지의 앞부분을 건너뛴다. page 크기와 chunk 크기가 다르면 상품이 빠진다 |
| `JdbcCursorItemReader` | step 내내 커넥션을 따로 잡고, MySQL 은 fetchSize 를 정하지 않으면 결과 전체를 메모리에 올린다 |

**뒤집을 조건**: 정렬 key 가 `product_id` 하나로 유일하지 않게 될 때(예: 상품과 옵션 단위로 item 을 나눌 때). `sortKeys` 에 칸을 더한다.

### chunk 크기

chunk 크기는 설정값 `ranking.chunk-size` 이고 기본값은 100 이다. 재시작 테스트는 2 로 준다(`CAL-004`). job parameter 로 두지 않는다. 식별 parameter 면 값을 바꿀 때마다 새 JobInstance 가 되어 재시작이 이어지지 않고(`JOB-002`), 비식별 parameter 면 재시작 때 다른 값을 줄 수 있어 저장된 위치와 page 경계가 어긋나 보인다. 100 은 처리량을 잰 값이 아니다. 한 chunk 의 TX 가 상품 100 개의 합계 쿼리와 upsert 한 번을 담는 크기로 시작한다.

**뒤집을 조건**: 계산 step 의 처리량이나 chunk TX 시간을 측정해 문제가 될 때 그 측정으로 값을 정한다.

## 8. writer: 절대값 (`CAL-003`)

```sql
insert into ranking_score_stage (snapshot_id, product_id, score) values (?, ?, ?) as new
on duplicate key update score = new.score
```

chunk 의 점수를 `batchUpdate` 로 보낸다. 같은 chunk 를 다시 써도 값이 같다. `score = score + ?` 처럼 더하는 방식은 섞지 않는다. 행 별칭(`as new`)은 MySQL 8.0.19 부터이고, `VALUES()` 함수는 8.0.20 부터 deprecated 다.

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

main 과 test 모두 `com.loopers.batch.job.ranking` 이다(Q-8). commerce-api 의 `interfaces → application → domain ← infrastructure` 를 따르지 않는다. 그 계층은 TX 경계를 application 에 모으고 판단을 DB 없는 애그리거트에 두려는 것인데, batch 에서는 step 이 TX 경계이고 판단은 SQL 안의 합계와 정렬뿐이다. `WeeklySnapshot` 과 step 구성 요소를 package-private 으로 두므로 테스트가 같은 패키지에 있어야 한다. 연결 테스트도 여기에 남긴다.

**뒤집을 조건**: 반품 차감, 가중치처럼 DB 없이 테스트할 판단이 생길 때. 그 판단을 domain 패키지의 객체로 꺼내고 step 은 그것을 부른다. 설정만 보는 테스트라 깨지면 원인이 랭킹 로직이 아니라 TX·저장소 설정이라는 것이 바로 드러난다.

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

## 14. 입력 checksum 의 메모리 (`EXP-029`)

MySQL Connector/J 는 fetchSize 를 정하지 않으면 결과 전체를 클라이언트 메모리에 받은 뒤 첫 행을 넘긴다. `RowCallbackHandler` 가 한 행씩 처리해도 메모리에는 한 주의 입력이 모두 올라간다. checksum 쿼리에 fetchSize `Integer.MIN_VALUE` 를 주어 드라이버가 소켓에서 한 행씩 읽게 한다.

측정: heap 512MB, young 16MB(결과를 받는 도중에 GC 가 일어나 붙잡힌 행이 보이도록), 각 3 회. GC 직후 live heap 최댓값에서 실행 전 기준선을 뺀 값의 중앙값이다. 모든 실행에서 GC 가 4 회 이상 일어났다.

| 입력 행 | 결과 전체를 받음 | 행 단위로 받음 |
|---|---|---|
| 100,000 | 22MB | 0MB |
| 200,000 | 45MB | 0MB |
| 400,000 | 92MB | 0MB |

결과 전체를 받으면 행당 약 240B 가 붙잡힌다. fetchSize 하나만 바꾼 대조에서 증가가 사라지므로 원인은 드라이버의 결과 버퍼링이다.

속도는 따로 쟀다(`EXP-031`). 기본 GC, heap 1GB 에서 checksum 읽기만 두 방식으로, 웜업 3 회를 버리고 10 회를 번갈아 실행했다. 두 방식의 checksum 은 같았다.

| 입력 행 | 결과 전체를 받음 (중앙값, 범위) | 행 단위로 받음 (중앙값, 범위) | 비율 |
|---|---|---|---|
| 100,000 | 92ms (89~95) | 91ms (89~98) | 0.99 |
| 400,000 | 392ms (381~409) | 376ms (361~388) | 0.96 |

행 단위로 받아도 느려지지 않는다. 쿼리는 한 번이고 서버가 결과를 이어 보내므로 행마다 왕복하지 않는다. 40 만 행에서 checksum 읽기는 0.4 초이고, `EXP-029` 에서 고정 step 이 20 초 넘게 걸린 것은 대부분 INSERT…SELECT 다.

대가: 결과를 다 읽기 전에는 그 커넥션으로 다른 쿼리를 보낼 수 없다. checksum 은 고정 step 의 마지막 읽기라 그 사이에 다른 쿼리가 없다. 클라이언트가 늦게 읽으면 서버가 쓰기를 기다리고, Connector/J 는 이때 `net_write_timeout` 을 `netTimeoutForStreamingResults`(기본 600 초)로 올린다.

고르지 않은 선택지:

| 선택지 | 고르지 않은 이유 |
|---|---|
| `useCursorFetch=true` 와 fetchSize N | N 행마다 서버 cursor 에 왕복하고 서버에 임시 테이블이 생긴다. 커넥션 URL 에 두면 모든 쿼리에 걸린다 |
| DB 에서 checksum 계산(`sha2(group_concat(...))`) | `group_concat_max_len` 을 넘으면 결과가 잘린다. 행 단위 해시가 아니라 이어 붙인 문자열 전체를 서버 메모리에 만든다 |

**뒤집을 조건**: checksum 읽기 도중 같은 커넥션으로 다른 쿼리를 보내야 할 때 `useCursorFetch` 로 바꾼다. 실험의 heap 수치는 `-Xmn16m` 조건의 값이므로 운영 heap 을 정할 때는 운영 GC 설정으로 다시 잰다.
