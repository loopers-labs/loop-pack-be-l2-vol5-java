# 9주차 실험 기록

점수 관련 실험 2~8은 비교를 수행할 당시의 구성과 결과를 기록한다. 현재 Job은 상품 ID를 읽고 Processor에서 상품별 합계를 구한 뒤 INSERT한다. [ADR 0089](../adr/0089-read-product-ids-and-insert-weekly-scores.md)

## 실험 1. 입력 고정 방식 비교

입력 고정은 원본 `product_purchase_activity`에서 한 기간의 구매 활동을 snapshot 입력으로 복사한다. 복사하는 동안에도 consumer는 원본에 저장한다. 세 방식을 비교했다.

- 복사 시간
- 복사 TX가 열린 동안 다른 연결의 INSERT가 기다리는지와 그때 걸린 잠금
- 나눠 읽는 도중 commit된 행이 snapshot에 들어가는지
- commit 전 실패의 rollback

도구: `SnapshotFreezeExperimentTest`(commerce-batch, `@Tag("experiment")`).

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*SnapshotFreezeExperimentTest'
```

### 조건

| 항목 | 값 |
| --- | --- |
| DB | Testcontainers `mysql:8.0` (8.0.46), 로컬 Mac의 colima VM |
| 설정 | `binlog_format=ROW`, `log_bin=1`, 기본 격리 수준 `REPEATABLE-READ` |
| 원본 | `exp_purchase_activity`: 실제 `product_purchase_activity`와 같은 열·인덱스(PK id AUTO_INCREMENT, UNIQUE(order_id, product_id), confirmed_at 인덱스 없음) |
| 데이터 | 지난주 100,000행(주문 50,000 × 상품 2), 이번 주 20,000행. confirmed_at은 7일에 고르게 퍼뜨림. 복사 대상이 전체의 약 83%다 |
| 복사 대상 | `confirmed_at >= 2026-08-17 00:00 AND < 2026-08-24 00:00` |
| 앱 연결 | Hikari, `rewriteBatchedStatements=true` |

| 방식 | TX 격리 수준 | 복사 방법 |
| --- | --- | --- |
| A | REPEATABLE READ | `(order_id, product_id)` 순으로 1,000건씩 SELECT → 앱에서 묶음 INSERT |
| B | REPEATABLE READ | `INSERT INTO snapshot SELECT … FROM 원본 WHERE confirmed_at 범위` 한 문장 |
| C | READ COMMITTED | B와 같은 한 문장 |

### 세 방식의 동작

**A. REPEATABLE READ TX 안에서 나눠 읽고 앱에서 INSERT**

```sql
-- TX 시작 (REPEATABLE READ, MySQL 기본)
-- 아래 SELECT를 마지막 (order_id, product_id) 다음부터 반복한다. 결과가 없으면 끝.
SELECT order_id, product_id, quantity, confirmed_at
FROM 원본
WHERE confirmed_at >= ? AND confirmed_at < ?
  AND (order_id > ? OR (order_id = ? AND product_id > ?))
ORDER BY order_id, product_id
LIMIT 1000;
-- 읽은 1,000건을 앱에서 묶음 INSERT
INSERT INTO snapshot VALUES (?, ?, ?, ?, ?), ...;
-- COMMIT
```

- 사진 시점: TX의 **첫 SELECT 순간**이다. REPEATABLE READ는 첫 읽기에서 만든 읽기 시점(read view)을 TX가 끝날 때까지 쓰므로, 몇 번을 나눠 읽어도 같은 시점을 본다.
- 원본 읽기: 일반 SELECT라 잠그지 않는 읽기(consistent read)다.
- 비용: 데이터가 DB → 앱 → DB로 오간다. 페이지 수만큼 SELECT·INSERT 왕복이 생긴다. 앱 메모리에는 한 페이지만 올린다.

**B. REPEATABLE READ TX에서 `INSERT … SELECT` 한 문장**

```sql
-- TX 시작 (REPEATABLE READ)
INSERT INTO snapshot (snapshot_id, order_id, product_id, quantity, confirmed_at)
SELECT ?, order_id, product_id, quantity, confirmed_at
FROM 원본
WHERE confirmed_at >= ? AND confirmed_at < ?;
-- COMMIT
```

- 사진 시점: 이 문장이 원본을 읽는 순간이다.
- 원본 읽기: MySQL 문서 기준으로 REPEATABLE READ의 `INSERT … SELECT`는 원본에서 읽은 행에 공유 next-key 잠금을 건다. 문서는 statement 기반 binlog로 복구(roll-forward)할 때 SQL이 원래와 똑같이 실행돼야 하기 때문이라고 설명한다. 이 잠금은 TX가 끝날 때까지 유지된다. 실험 DB는 `binlog_format=ROW`였는데도 잠금이 걸렸다.
- 비용: 데이터가 DB 안에서만 복사된다. 앱 왕복이 없다.

**C. READ COMMITTED TX에서 `INSERT … SELECT` 한 문장**

```sql
-- TX 시작 (이 TX만 READ COMMITTED. 실험에서는 TransactionTemplate.setIsolationLevel)
INSERT INTO snapshot (...) SELECT ... FROM 원본 WHERE confirmed_at 범위;  -- B와 같은 문장
-- COMMIT
```

- 사진 시점: 이 문장이 원본을 읽는 순간이다. READ COMMITTED는 **문장마다** 새 읽기 시점을 만든다. 원본을 읽는 문장이 하나뿐이라 시점도 하나다.
- 원본 읽기: MySQL 문서 기준으로 READ COMMITTED의 `INSERT … SELECT`는 원본을 잠그지 않는 읽기로 읽는다. (READ COMMITTED는 행 기반 binlog만 지원한다는 문서 내용과 이어 생각할 수 있지만, 문서가 둘을 직접 연결하지는 않는다.)
- 비용: B와 같다.
- 주의: 같은 TX 안에서 원본을 **다시** 읽으면 다른 시점을 본다. 건수·checksum은 원본이 아니라 방금 만든 snapshot에서 계산해야 한다.

측정 방법:

- **가시성**: A 방식으로 첫 페이지를 읽은 직후, 다른 연결이 지난주 늦은 구매 1행을 commit한다. 이 행은 order_id가 가장 커서 마지막 페이지에 읽힐 자리다. 복사가 끝난 snapshot이 100,000행이면 그 행을 보지 않은 것이고 100,001행이면 본 것이다. 같은 나눠 읽기를 REPEATABLE READ와 READ COMMITTED로 각각 실행했다.
- **rollback**: 복사 뒤 다른 연결이 지난주 늦은 구매 1행을 넣기 시작하고, 200ms 뒤 예외로 TX를 실패시킨다. 예외부터 rollback이 끝날 때까지의 시간, snapshot 행 수, 원본 행 수를 본다.
- **인덱스**: 마지막에 원본에 `confirmed_at` 인덱스를 만들고, 인덱스 전후 복사 문장의 `EXPLAIN`을 남긴다.

복사 도중 다른 연결의 INSERT가 기다리는지와 그때 원본에 걸린 잠금은 [실험 1-3](#실험-1-3-실제-복사-도중의-insert-대기)에서 쟀다.

### 결과

실험은 3번 실행했다.
- 1차: 시간
- 2차: 가시성을 고친 뒤
- 3차: rollback·인덱스·EXPLAIN을 더한 뒤 같은 테스트를 두 번 돌림

아래 표는 3차의 마지막 실행 값이다.

**복사 시간(ms)**

| 방식 | 3차 1·2·3회 | 이전 실행(참고) |
| --- | --- | --- |
| A 나눠 읽기 + 앱 INSERT | 1,793 · 1,479 · 1,488 | 1차 1,808 · 1,663 · 1,644 / 2차 4,960 · 4,947 · 1,628 |
| B `INSERT … SELECT`, REPEATABLE READ | 554 · 674 · 587 | 1차 676 · 627 · 648 / 2차 633 · 719 · 681 |
| C `INSERT … SELECT`, READ COMMITTED | 596 · 562 · 626 | 1차 580 · 589 · 567 / 2차 585 · 645 · 660 |

**복사 문장의 실행 계획**

| 원본 인덱스 | type | possible_keys | key | rows |
| --- | --- | --- | --- | --- |
| confirmed_at 인덱스 없음 | ALL | - | - | 116,915 |
| confirmed_at 인덱스 있음 | ALL | idx_exp_confirmed_at | - | 116,915 |

**나눠 읽는 도중 commit된 늦은 구매(2차·3차 같음)**

| 나눠 읽기의 격리 수준 | snapshot 행 |
| --- | --- |
| REPEATABLE READ | 100,000 (보지 않음) |
| READ COMMITTED | 100,001 (봄) |

1차 실행의 가시성 결과는 쓰지 않는다. 늦은 구매를 넣는 코드가 복사 TX와 같은 스레드에서 돌아 TX의 연결을 함께 썼고(자기 쓰기), 두 격리 수준 모두 100,001이 나왔다. 2차부터 다른 스레드에서 넣는다.

**commit 전 실패**

| 방식 | rollback 시간 | snapshot | 원본 |
| --- | --- | --- | --- |
| A | 431 ms | 0행 | 120,000 → 120,001 |
| B | 369 ms | 0행 | 120,000 → 120,001 |
| C | 544 ms | 0행 | 120,000 → 120,001 |

원본이 1행 늘어난 것은 다른 연결이 넣은 늦은 구매가 복사 TX의 rollback과 무관하게 남았다는 뜻이다. B에서는 그 INSERT가 rollback으로 잠금이 풀린 뒤에 들어갔다.

### 세 방식 비교 요약

| 기준 | A 나눠 읽기 (RR) | B `INSERT … SELECT` (RR) | C `INSERT … SELECT` (RC) |
| --- | --- | --- | --- |
| 읽는 시점 | TX 첫 일반 SELECT의 Read View | 잠금을 잡으며 스캔이 도달한 행을 읽는 시점 | 복사 문장의 원본 Read View |
| 고정된 시점의 입력인가 | 예. 나눠 읽는 도중 commit된 행은 보이지 않음(100,000행) | 아니오. 실험 1-3에서 스캔이 도달하기 전에 commit된 행이 포함됨 | 예. 실험 1-2에서 복사 도중 commit된 행이 들어가지 않음을 확인 |
| 원본 잠금 (실험 1-3) | 없음 | 읽은 PRIMARY 행에 S (복사 시작 약 0.3초 뒤 약 4만~7만 개) | 없음 |
| 복사 중 consumer INSERT (실험 1-3) | 잠금 대기 없음 | 전체 스캔이면 잠금 대기 관찰 안 됨. 범위 스캔이면 이미 읽은 범위의 늦은 구매가 commit까지 기다림 | 잠금 대기 없음 |
| 복사 도중 commit된 늦은 구매 (실험 1-3) | 안 들어감 | **아직 읽지 않은 자리면 들어감** | 안 들어감 |
| 복사 시간 (100,000행) | 약 1.5~1.8초 (2차 실행에서 약 5초 2번) | 약 0.55~0.72초 | 약 0.56~0.66초 |
| commit 전 실패 | snapshot 0행, rollback 431 ms | 0행, 369 ms | 0행, 544 ms |
| 앱 메모리·왕복 | 페이지 하나씩, 페이지 수만큼 왕복 | 없음 (DB 안에서 복사) | 없음 (DB 안에서 복사) |
| 설정 | 기본 격리 수준 그대로 | 기본 격리 수준 그대로 | 이 TX만 READ COMMITTED |
| 데이터가 커지면 | 복사 TX가 길어질 수 있다. 일반 SELECT는 원본 읽기 잠금을 사용하지 않는다 | 범위 스캔일 때 늦은 구매의 INSERT 대기가 길어질 수 있다 | 복사 TX가 길어질 수 있다. 원본은 비잠금 일관된 읽기로 조회한다 |
| 주의할 점 | 나눠 읽기를 READ COMMITTED로 바꾸면 섞인다(100,001행) | | 같은 TX에서 원본을 다시 읽으면 다른 시점을 본다 |

세 방식 모두 복사 TX가 길어지면 MySQL이 그 읽기 시점 이후의 옛 버전(undo)을 지우지 못하는 비용이 있다. 이번 실험에서는 재지 않았다.

### 관찰

- **시간**: 100,000행에서 B·C는 약 0.55~0.72초, A는 약 1.5~1.8초였다. 2차 실행의 A 두 번은 약 5초였고 원인은 확인하지 않았다.
- **인덱스 가설은 틀렸다**: 처음에는 confirmed_at 인덱스가 없어 전체를 훑는다고 해석했다. 인덱스를 만들어도 optimizer는 전체 스캔(type=ALL)을 골랐다. 복사 대상이 전체의 약 83%라 인덱스를 타지 않은 것으로 본다. 원본에 여러 주의 데이터가 쌓여 복사 대상 비율이 작아지면 실행 계획과 잠금 범위가 달라질 수 있는데, 그 경우는 재지 않았다.
- **잠금과 INSERT 대기**: 실험 1-3에서 쟀다.
- **한 순간의 사진**: 이 실험에서는 RR로 나눠 읽을 때 첫 Read View를 유지했다. RC로 나눠 읽으면 도중에 commit된 행이 뒤 페이지에 섞였다. RC 한 문장의 실행 도중 commit 실험은 1-2에서 이어서 수행했다. [MySQL 8.0의 일관된 읽기](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)
- **rollback**: 세 방식 모두 commit 전 실패에서 snapshot이 0행으로 돌아갔다. 반쪽 snapshot이 남지 않았다. READ COMMITTED는 무엇이 보이는지에만 영향을 주고, 실패하면 TX 전체가 되돌아가는 성질은 같았다. 100,000행 rollback은 약 0.4~0.5초 걸렸다.
- **한계**: 로컬 단일 MySQL, 100,000행, 한 주와 다음 주 데이터만 있는 원본이다. 운영 규모의 시간, 여러 주가 쌓인 원본, replica 지연은 말할 수 없다.

### 선택

실험 1·1-2·1-3 결과로 C를 골랐다([ADR 0085](../adr/0085-freeze-input-with-read-committed-insert-select.md)).

## 실험 1-2. 원본에 여러 주가 쌓였을 때

실험 1은 원본에 지난주와 이번 주만 있어 복사 대상이 83%였다. 실제 `product_purchase_activity`는 지우지 않고 쌓인다. 그래서 복사 대상이 전체의 작은 일부인 원본에서 다시 쟀다. 함께 실험 1에서 미검증으로 남긴 "C의 복사 문장이 도는 도중 commit된 행"도 확인했다.

도구: `SnapshotFreezeExperimentTest.compareWithAccumulatedHistory`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*SnapshotFreezeExperimentTest.compareWithAccumulatedHistory'
```

### 조건

- DB·설정·원본 열·인덱스는 실험 1과 같다.
- 원본은 92,000행이다.
  - 지난주(복사 대상) 10,000행
  - 앞선 8주 각 10,000행
  - 이번 주 2,000행
  - 복사 대상은 10.9%다.
- 로컬 PC 부담 때문에 전체를 10만 행 안으로 줄였다. 처음에는 주당 10만 행(약 92만 행)으로 시작했다가 중단했다.

**문장 도중 commit 측정**: C 방식(READ COMMITTED, `INSERT … SELECT`)으로 복사 TX를 시작하고 10·30·60ms 뒤 다른 연결이 지난주 늦은 구매 1행을 자동 commit으로 넣는다. 그 행은 AUTO_INCREMENT라 PRIMARY 맨 끝에 들어간다. 그다음 두 가지를 기록한다.

- commit이 복사 문장이 끝나기 전에 일어났는지(앱에서 잰 시각 비교)
- snapshot에 그 행이 들어갔는지

**나머지 측정**은 실험 1과 같은 방법이다.

1. 인덱스 없이 A·C 복사 시간(2회)과 EXPLAIN을 잰다.
2. `confirmed_at` 인덱스를 만들고 같은 것을 다시 잰다.

같은 원본에서 복사 도중 INSERT 대기는 실험 1-3에서 쟀다.

### 결과

**C의 복사 문장 도중 commit된 늦은 구매**

| commit 시점 | 복사 문장 길이 | commit이 문장보다 먼저 끝남 | snapshot |
| --- | --- | --- | --- |
| 시작 뒤 35ms | 173ms | 예 | 10,000행 (포함 안 됨) |
| 시작 뒤 33ms | 63ms | 예 | 10,000행 (포함 안 됨) |
| 시작 뒤 62ms | 64ms | 예 | 10,000행 (포함 안 됨) |

**복사 시간과 실행 계획**

| 원본 인덱스 | EXPLAIN (원본 쪽) | A 복사 1·2회 | C 복사 1·2회 |
| --- | --- | --- | --- |
| confirmed_at 인덱스 없음 | `type=ALL`, key 없음 | 381 · 252 ms | 75 · 75 ms |
| confirmed_at 인덱스 있음 | `type=range`, key `idx_exp_confirmed_at` | 278 · 240 ms | 60 · 60 ms |

EXPLAIN의 rows 추정값은 17·8로 실제 행 수와 크게 달랐다. 대량 INSERT 직후 통계가 갱신되지 않은 것으로 봤다. 실험 1-3에서 같은 원본을 `ANALYZE TABLE`로 통계를 갱신하자 EXPLAIN은 `type=ALL`(rows 91,785)이 됐다. 위 `range`는 통계 갱신 전 실행 계획이다.

### 관찰

- **C는 문장 도중 commit된 행을 넣지 않았다.** 세 번 모두 commit이 복사 문장보다 먼저 끝났지만 snapshot은 10,000행이었다. 실험 1에서 문서 규칙으로만 말했던 "READ COMMITTED의 한 문장은 시작 시점을 읽는다"를 이 조건에서 확인했다. 다만 늘어난 행이 PRIMARY 맨 끝에 있어 스캔이 늦게 도달하는 경우만 봤다.
- **복사 대상 비율이 작아지자 optimizer가 인덱스를 탔다**(`type=range`, 통계 갱신 전). 실험 1(83%)에서는 인덱스가 있어도 전체 스캔이었다. 이 규모에서는 인덱스 유무에 따른 시간 차이가 작았다(C 75 → 60ms). 원본이 더 쌓일 때의 차이는 재지 않았다.
- 한계: 92,000행, 각 1~2회, 로컬 단일 MySQL이다. 통계 갱신 여부와 운영 규모의 실행 계획은 확인하지 않았다.

## 실험 1-3. 실제 복사 도중의 INSERT 대기

실제 복사 SQL과 Consumer INSERT를 겹쳐 실행했다. 복사 TX는 SQL이 끝나면 바로 commit했다. 대기 설정은 별도 INSERT 연결의 호출 시점과 관측 주기를 조절하는 데만 사용했다. 업무 Step에서 복사 뒤 수행하는 입력 요약 및 manifest 저장은 이 실험에 포함하지 않았다.

도구: `SnapshotFreezeExperimentTest.compareInsertWaitDuringRealCopy`, `compareInsertWaitDuringRealCopyWithHistory`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*SnapshotFreezeExperimentTest.compareInsertWaitDuringRealCopy*'
```

### 조건

- DB·원본 열·인덱스는 실험 1과 같다(MySQL 8.0.46, `binlog_format=ROW`).
- 원본은 로컬 부담 때문에 10만 행 안으로 둔다.
  - **대상 83%**: 지난주 80,000행 + 이번 주 16,000행 = 96,000행. 실험 1과 비율이 같다.
  - **대상 10.9%**: 실험 1-2와 같은 92,000행(지난주 10,000행).
- 측정 전에 `ANALYZE TABLE`로 통계를 갱신한다. 갱신하지 않으면 대량 INSERT 직후 통계로 실행 계획이 정해진다(아래 참고 실행).
- 복사 문장 시작 기준으로 정해 둔 시점마다 INSERT 두 건을 넣는다. 각 INSERT는 미리 열어 둔 별도 연결로 자동 commit한다.
  - 지난주 늦은 구매 1건(`confirmed_at` = 지난주 중간)
  - 이번 주 구매 1건
  - 시점: 대상 83%는 0·100·200·300·400·500·700ms, 대상 10.9%는 0·10·20·30·40·60·90ms
- 기록하는 것
  - 각 INSERT의 시작 시점, 걸린 시간, 그 순간 단계(복사 문장 도중 / 문장 끝·commit 전 / commit 뒤)
  - 복사가 끝날 때까지 약 10ms마다 `performance_schema.data_lock_waits` 조회(누가 무엇을 기다리는지)
  - 시작 약 300ms(10.9%는 약 30ms) 뒤 원본의 행 잠금을 인덱스·모드별로 한 번 집계. 어느 인덱스에 잠금이 걸렸는지로 실제 읽은 경로를 본다.
  - snapshot 행 수: 대상 행 수보다 많으면 복사 도중 들어온 늦은 구매가 들어간 것이다.
- 복사 없이 INSERT 한 건은 중앙값 1~2ms였다.
- 방식마다 3번씩 반복했다.

### 결과 — 대상 83% (96,000행)

| 방식 | 실행 계획 | 복사 문장 | 원본 행 잠금 (약 0.3초 뒤) | 잠금 대기 관찰 | INSERT 걸린 시간 | snapshot |
| --- | --- | --- | --- | --- | --- | --- |
| A 나눠 읽기 (RR), 인덱스 없음 | - | 1,374~1,658 ms | 없음 | 없음 | 2~37 ms | 80,000 (3번 모두) |
| B (RR), 인덱스 없음 | ALL | 494~623 ms | PRIMARY `S` 40,819~70,823개 | **없음** | 1~38 ms | **80,005~80,006** |
| C (RC), 인덱스 없음 | ALL | 495~515 ms | 없음 | 없음 | 1~9 ms | 80,000 (3번 모두) |
| B (RR), 인덱스 있음 | ALL | 547~565 ms | PRIMARY `S` 56,340~59,416개 | **없음** | 2~107 ms | **80,006** |
| C (RC), 인덱스 있음 | ALL | 484~574 ms | 없음 | 없음 | 1~104 ms | 80,000 (3번 모두) |

- B의 snapshot이 80,000보다 많다. 복사 문장이 끝나기 전에 넣은 지난주 늦은 구매(문장 도중 6건)가 snapshot에 들어갔다. 1건이 적은 실행(80,005)은 그 INSERT가 문장 끝 뒤에 시작했다.
- 잠금 대기가 관찰되지 않은 INSERT도 가끔 수십~100ms 걸렸다. C에서도 비슷한 지연이 나왔다(최대 104ms). 응답 시간만으로 원본 잠금 대기라고 판단할 수 없으며, 이 지연의 원인은 확인하지 않았다.

**참고 실행 — 통계 갱신 전 (대상 83%, 인덱스 있음)**

`ANALYZE TABLE`을 넣기 전 첫 실행에서는 EXPLAIN 추정 rows가 7이었고 optimizer가 `confirmed_at` 범위 스캔(`type=range`)을 골랐다. 이때 B는 다르게 동작했다.

| 지난주 늦은 INSERT 시작 | 걸린 시간 (3번) | 그때 복사 문장 |
| --- | --- | --- |
| 0~200ms | 2~12 ms | 도중 |
| 약 305ms | 309 · 335 · 352 ms | 도중 |
| 약 405ms | 212 · 235 · 251 ms | 도중 |
| 약 505ms | 111 · 136 · 152 ms | 도중 |

- 약 305ms 이후 시작한 INSERT는 모두 복사 TX의 commit 시각(시작 뒤 614~656ms) 무렵에 끝났다. `data_lock_waits`에는 `idx_exp_confirmed_at`의 `X,GAP,INSERT_INTENTION` 요청을 같은 인덱스의 `S`가 막는 대기가 보였다.
- 이번 주 INSERT는 대부분 2~12ms였고, 400ms 무렵 3번 중 2번이 36·43ms였다.
- snapshot은 80,003행으로 0~200ms에 넣은 늦은 구매 3건이 들어갔다.
- C는 같은 조건에서 잠금 대기가 없었고 snapshot은 80,000행이었다.

### 결과 — 대상 10.9% (92,000행, 인덱스 있음)

`ANALYZE TABLE` 뒤 EXPLAIN은 `type=ALL`(rows 91,785)이었다. 그런데 실제 잠금이 걸린 인덱스를 보면 **같은 데이터에서도 시도마다 읽은 경로가 달랐다.**

| 방식·시도 | 원본 행 잠금 (약 30~40ms 뒤) | 복사 문장 | 잠금 대기 관찰 | 지난주 늦은 INSERT | 이번 주 INSERT | snapshot |
| --- | --- | --- | --- | --- | --- | --- |
| B #1 | PRIMARY `S` 3,178개 (전체 스캔) | 150 ms | 없음 | 3~14 ms | 3~44 ms | 10,007 |
| B #2 | `idx_exp_confirmed_at` `S` 4,727개 (범위 스캔) | 56 ms | 있음 | 2~6 ms, 약 33·44ms 시작은 **29·17 ms** | 2~10 ms | 10,003 |
| B #3 | `idx_exp_confirmed_at` `S` 7,704개 (범위 스캔) | 72 ms | 있음 | 2~8 ms, 약 35·43·65ms 시작은 **41·34·12 ms** | 2~7 ms | 10,003 |
| C 3번 | 없음 | 58~70 ms | 없음 | 1~13 ms | 1~10 ms | 10,000 |

- 범위 스캔일 때 막힌 대기는 `idx_exp_confirmed_at`의 `X,GAP,INSERT_INTENTION` ← `S`였다.

### 관찰

- **B의 영향은 읽는 경로에 따라 달랐다.**
  - **전체 스캔(PRIMARY)**: 복사 도중 INSERT의 잠금 대기를 관찰하지 못했다. 새 행은 AUTO_INCREMENT라 PRIMARY 맨 끝에 들어가는데, 스캔은 맨 끝에 가장 늦게 도달한다. 그래서 스캔이 맨 끝에 닿은 뒤부터 commit까지(이번 실험에서는 문장 끝 뒤 8~15ms)만 막힐 수 있는 것으로 본다. 그 사이에 시작한 INSERT는 한 번(B 인덱스 없음 3번째) 있었고 28·29ms였지만, 약 10ms 간격 조회로는 잠금 대기를 잡지 못해 잠금 때문인지는 확인하지 못했다.
  - **범위 스캔(`confirmed_at` 인덱스)**: 스캔이 이미 지나간 범위에 들어가는 늦은 구매는 commit까지 기다렸다. 80,000행 대상에서는 최대 약 0.35초, 10,000행 대상에서는 최대 41ms였다. 이번 주 구매는 대부분 막히지 않았다.
- **B의 snapshot은 "문장 시작 순간"이 아니었다.** B는 잠그면서 읽는 읽기(locking read)라 읽는 순간의 최신 commit 행을 본다. 그래서 스캔이 아직 닿지 않은 자리에 commit된 늦은 구매가 snapshot에 들어갔다(전체 스캔 5~7건, 범위 스캔 3건). 어떤 행이 들어가는지는 스캔 위치와 시점에 달려 있다.
- **A와 C는 복사 도중 들어온 행을 넣지 않았고, 원본에 행 잠금이 없었고, 잠금 대기도 없었다.** A는 TX 첫 읽기 시점, C는 문장 시작 시점을 읽는다.
- **실행 계획은 통계에 따라 바뀌었다.** 통계 갱신 전에는 83%에서도 범위 스캔이었고, 갱신 뒤에는 10.9%에서도 EXPLAIN이 전체 스캔이었다. 실제 실행은 EXPLAIN과 다른 경로를 고르기도 했다(10.9% B #2·#3).
- 한계
  - 로컬 단일 MySQL, 10만 행 이하다.
  - INSERT는 정해 둔 시점에 한 건씩만 넣었다. 계속 들어오는 consumer 부하는 아니다.
  - 잠금 대기 조회 간격이 약 10ms라 그보다 짧은 대기는 놓칠 수 있다.

### 같은 코드로 다시 실행

지연 없는 복사 테스트 두 건을 다시 실행해 통과했다. 원본 96,000행에서 B는 PRIMARY 공유 잠금이 확인됐지만 INSERT 대기 관계는 포착되지 않았다. B는 늦은 구매 5~6행을 포함했고, A와 C는 80,000행을 유지했다. 원본 92,000행에서는 B가 복사 대상 10,000행에 3~7행을 더 포함했고 C는 10,000행을 유지했다. 이번 회차의 `data_lock_waits` 조회에서는 범위 스캔 대기 관계를 포착하지 못했다. 앞 표의 범위 스캔 대기 수치와 이번 회차를 구분한다.

## 실험 1-4. 주문이 계속 들어오는 동안 복사하면

실험 1-3은 정해 둔 시점에 INSERT를 한 건씩만 넣었다. 실제 consumer는 복사하는 동안에도 구매를 계속 저장한다. 그래서 INSERT를 쉬지 않고 넣으면서 B·C로 복사했다.

도구: `SnapshotFreezeExperimentTest.compareContinuousInsertsDuringCopy`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*SnapshotFreezeExperimentTest.compareContinuousInsertsDuringCopy'
```

### 조건

- 실제 원본처럼 `confirmed_at` 인덱스는 두지 않는다. 측정 전 `ANALYZE TABLE`. 두 원본 모두 EXPLAIN은 `type=ALL`이었다.
  - 대상 83%: 지난주 80,000행 + 이번 주 16,000행
  - 누적 10.9%: 실험 1-2와 같은 92,000행(지난주 10,000행)
- 미리 연 연결 두 개가 쉬지 않고 한 건씩 자동 commit INSERT를 한다. 하나는 지난주 늦은 구매, 하나는 이번 주 구매다.
- 순서: INSERT 시작 → 300ms 뒤 복사 TX 시작(복사 문장 → 바로 commit) → commit 뒤 300ms → INSERT 중지.
- 기록하는 것
  - INSERT마다 시작 시각과 걸린 시간. 시작 시각이 복사 구간(문장 시작 ~ commit 끝)에 든 것과 그 밖의 것을 나눈다.
  - 서버의 행 잠금 대기 누적값 `Innodb_row_lock_waits`(횟수)·`Innodb_row_lock_time`(ms)의 복사 전후 차이. 실험 컨테이너에는 이 실험의 연결만 있다.
  - snapshot에 대상보다 더 들어간 행 수와, 그중 "복사 문장 시작 전에 commit된 늦은 구매" 수. 이 행들은 어느 방식이든 들어가야 하는 행이다.
- 방식마다 3번. 약 10ms마다 `data_lock_waits`도 조회했지만, B 복사 중에는 조회 한 번이 오래 걸려 복사 한 번에 2~3번밖에 조회하지 못했다. 그래서 잠금 대기는 누적 카운터로 판단한다.

### 결과

| 원본 | 방식 | 복사 문장 / commit까지 (ms) | snapshot에 더 들어간 행 = 복사 전 commit + 그 밖 | 복사 문장 도중 끝난 늦은 구매 | 행 잠금 대기 (횟수·합계) |
| --- | --- | --- | --- | --- | --- |
| 대상 83% | B | 649/662 · 591/602 · 545/552 | 381 = 203 + **178** · 344 = 151 + **193** · 320 = 174 + **146** | 178 · 193 · 146 | 5회·28ms · 36회·47ms · 5회·14ms |
| 대상 83% | C | 536/543 · 411/419 · 429/437 | 179 = 178 + 1 · 169 = 168 + 1 · 172 = 171 + 1 | 134 · 117 · 135 | 0 · 0 · 0 |
| 누적 10.9% | B | 196/201 · 160/162 · 166/169 | 274 = 176 + **98** · 300 = 210 + **90** · 264 = 169 + **95** | 98 · 90 · 95 | 105회·113ms · 106회·80ms · 67회·52ms |
| 누적 10.9% | C | 91/96 · 65/68 · 52/57 | 191 = 190 + 1 · 219 = 218 + 1 · 174 = 173 + 1 | 29 · 33 · 26 | 0 · 0 · 0 |

INSERT 걸린 시간(ms), 3번의 중앙값 · p95 · 최대 범위. 지난주 늦은 구매와 이번 주 구매는 거의 같아 지난주 늦은 구매만 적는다.

| 원본 | 방식 | 복사 구간에 시작 (건수) | 중앙값 | p95 | 최대 | 복사 전후에 시작: 중앙값 · p95 |
| --- | --- | --- | --- | --- | --- | --- |
| 대상 83% | B | 146~193건 | 1.9~2.2 | 5.8~9.5 | 85.7~133.0 | 1.6~1.7 · 3.4~3.6 |
| 대상 83% | C | 119~136건 | 2.0~2.6 | 6.1~8.5 | 26.2~110.7 | 1.6~1.8 · 3.0~3.3 |
| 누적 10.9% | B | 90~98건 | 1.5~1.7 | 2.6~3.1 | 8.4~31.5 | 1.5 · 2.5~3.1 |
| 누적 10.9% | C | 28~34건 | 1.7~3.0 | 3.4~5.7 | 3.8~8.5 | 1.4~1.5 · 2.2~2.7 |

### 관찰

- **B는 복사 문장 도중 끝난 늦은 구매를 모두 snapshot에 넣었다.** 6번 모두 "그 밖" 행 수가 복사 문장 도중 끝난 늦은 구매 수와 정확히 같았다. 주문이 계속 들어오면 B의 snapshot은 "복사가 끝날 때까지 들어온 구매"가 된다.
- **C는 복사 전에 commit된 늦은 구매만 넣었다.** 6번 모두 1행이 더 있었다. 앱이 기록한 복사 시작 시각과 실제 문장 시작 사이에 끝난 INSERT 하나로 보이지만, DB 쪽 시각으로 확인하지는 않았다.
- **계속 들어오면 B에서도 행 잠금 대기가 생겼다.** 대상 83%에서 5~36회(합계 14~47ms), 누적 10.9%에서 67~106회(합계 52~113ms)였다. 한 번에 평균 1~6ms 정도로 짧았다. C는 6번 모두 0회였다. 어느 잠금에서 기다렸는지는 `data_lock_waits` 조회가 따라가지 못해 확인하지 못했다.
- **복사 중 INSERT가 느려진 정도는 B·C가 비슷했다.** 복사 구간의 p95는 복사 밖보다 약 2배(대상 83%: 약 3ms → 6~9ms)였고, 최대값은 두 방식 모두 100ms 안팎까지 튀었다. C도 잠금 대기가 0인데 비슷하게 튀었으므로, 이 차이는 잠금보다 복사 자체가 DB 자원을 쓰는 영향으로 본다. 원인을 따로 나눠 재지는 않았다.
- 한계: 로컬 단일 MySQL, 연결 두 개의 직렬 INSERT(초당 약 수백 건)다. 실제 consumer의 동시 연결 수와 유입량은 아니다.

## 실험 2. 절대값 쓰기와 증분 쓰기

writer가 점수를 덮어쓰는 방식(`score = total`)과 더하는 방식(`score = score + delta`)을 같은 실패·복구 조건에서 비교했다. 목적은 "증분이면 두 배가 된다"가 **어떤 조건에서** 일어나는지 확인하는 것이다.

도구: `ScoreWriteModeExperimentTest`(commerce-batch, `@Tag("experiment")`).

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*ScoreWriteModeExperimentTest'
```

### 조건

- 과제 6절 입력(상품 7·8·9, 점수 5·1·5)을 chunk=2로 쓴다.
- reader는 상품별 합계를 읽고, writer는 실험용 테이블 `exp_score(run_key, product_id)`에 쓴다.
- 첫 chunk(7·8)는 commit된다. 두 번째 chunk(9)를 쓴 직후(`ItemWriteListener.afterWrite`), chunk TX가 commit되기 전에 한 번 실패시키고 다시 실행한다.

| 복구 조건 | 점수 쓰기 TX | 다시 실행 |
| --- | --- | --- |
| 같은 TX | chunk TX에 참여(현재 구조) | 같은 parameter → 같은 JobInstance 재시작 |
| 별도 TX | `REQUIRES_NEW`로 먼저 commit | 같은 parameter → 같은 JobInstance 재시작 |
| 새 instance | chunk TX에 참여 | `run.id`를 붙여 새 JobInstance로 처음부터 |

### 결과

| 복구 조건 | 쓰기 | 실패 직후 점수 | 다시 실행 뒤 점수 |
| --- | --- | --- | --- |
| 같은 TX | `=` | 7=5, 8=1 | 7=5, 8=1, 9=5 |
| 같은 TX | `+=` | 7=5, 8=1 | 7=5, 8=1, 9=5 |
| 별도 TX | `=` | 7=5, 8=1, 9=5 | 7=5, 8=1, 9=5 |
| 별도 TX | `+=` | 7=5, 8=1, 9=5 | 7=5, 8=1, **9=10** |
| 새 instance | `=` | 7=5, 8=1 | 7=5, 8=1, 9=5 |
| 새 instance | `+=` | 7=5, 8=1 | **7=10, 8=2**, 9=5 |

모든 경우 첫 실행은 FAILED, 다시 실행은 COMPLETED였다.

### 관찰

- 점수와 reader 위치가 같은 TX면 `+=`도 두 배가 되지 않았다. 실패한 chunk의 쓰기가 위치와 함께 rollback되고, 재시작은 commit된 위치 다음부터 읽기 때문이다.
- 점수 쓰기만 먼저 commit되면, 위치는 rollback되어 재시작이 상품 9를 다시 처리한다. `+=`는 9를 두 번 더했다.
- `run.id`로 새 instance를 만들면 처음부터 다시 처리한다. `+=`는 이미 commit된 7·8을 두 번 더했다.
- `=`는 세 조건 모두 같은 결과로 수렴했다. 절대값 쓰기가 막아 주는 것은 TX 경계가 어긋나거나 처음부터 다시 처리하는 경우다. 이 결과는 고정 입력과 계산식이 바뀌지 않는다는 전제(snapshot)에서 성립한다.
- 한계: 상품 3개, 실패 한 번의 작은 실험이다. 증분 방식에 "적용 기록"을 두는 보완은 구현하지 않았다.

## 실험 3. 점수 계산: chunk 크기와 GROUP BY tasklet

점수 계산 Step을 상품별 SUM 방식의 Chunk로 구성하고, chunk 크기에 따라 시간이 어떻게 달라지는지 봤다. 같은 입력을 GROUP BY 한 문장 tasklet으로 계산하는 것과도 비교했다. 함께 실패 뒤 다시 실행하는 비용도 봤다.

도구: `ScoreStepStrategyExperimentTest`(commerce-batch, `@Tag("experiment")`).

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*ScoreStepStrategyExperimentTest'
```

### 조건

- snapshot 입력 100,000행, 상품 10,000개(상품당 10행)다. 로컬 PC 부담으로 10만 행 안으로 뒀다.
- **chunk**: 실험 당시 점수 계산 Step과 같은 구성이다.
  - reader: `DISTINCT product_id` keyset paging, page 크기 = chunk 크기
  - processor: 상품마다 `SUM(quantity)` 조회
  - writer: 덮어쓰기
  - 실험용 테이블 `exp_stage`에 쓴다. chunk 10·100·1000을 비교했다.
- **GROUP BY tasklet**: `INSERT INTO exp_stage SELECT product_id, SUM(quantity) … GROUP BY product_id` 한 문장, 한 TX다.
- **끝까지 한 번에**: 각 방식을 2회 실행했다.
- **실패 뒤 다시 실행**
  - chunk는 9,000번째 상품을 처리하기 직전에 한 번 실패시키고 같은 parameter로 재시작했다.
  - tasklet은 집계를 마친 뒤 commit 전에 한 번 실패시키고 다시 실행했다.
- 결과는 상품 순서로 `productId, score`를 직렬화한 checksum으로 비교했다.

### 결과

**끝까지 한 번에**

| 방식 | 1·2회 | commit 수 | checksum |
| --- | --- | --- | --- |
| chunk 10 | 43,571 · 22,951 ms | 1,001 | 9f5dec36… |
| chunk 100 | 8,791 · 8,564 ms | 101 | 같음 |
| chunk 1000 | 7,270 · 7,202 ms | 11 | 같음 |
| GROUP BY tasklet | 173 · 179 ms | — | 같음 |

**9,000번째 상품 직전(tasklet은 집계 뒤 commit 전) 실패 → 다시 실행**

| 방식 | 첫 실행 | 실패 전 남은 점수 | 다시 실행 | 다시 실행이 읽은 상품 | checksum |
| --- | --- | --- | --- | --- | --- |
| chunk 10 | FAILED 21,544 ms | 8,990 | COMPLETED 1,477 ms | 1,010 | 같음 |
| chunk 100 | FAILED 7,813 ms | 8,900 | COMPLETED 957 ms | 1,100 | 같음 |
| chunk 1000 | FAILED 6,586 ms | 8,000 | COMPLETED 1,446 ms | 2,000 | 같음 |
| GROUP BY tasklet | FAILED 236 ms | 0 | COMPLETED 162 ms(처음부터) | 전부 | 같음 |

처음 실행은 chunk reader를 직접 만들면서 초기화(`afterPropertiesSet`)를 빠뜨려 chunk가 모두 실패했다. 실제 job은 reader가 빈이라 이 문제가 없다. 초기화를 넣고 다시 실행한 결과만 쓴다.

### 관찰

- **이 규모에서는 GROUP BY 한 문장이 chunk보다 약 40배 빨랐다**(약 0.17초 vs 약 7.2초). 실패 뒤 처음부터 다시 해도 0.16초라, 이어 가는 이점이 시간으로 드러나지 않았다.
- **chunk의 시간은 대부분 상품마다 하는 합계 조회로 보인다.** 상품 10,000개에 processor 조회가 10,000번이다. 이것을 따로 쟀지는 않았다.
- **chunk 크기**: 10은 commit 1,001번이라 가장 느렸고 실행마다 차이도 컸다(43.6 · 23.0초). 100과 1000의 차이는 작았다(8.6~8.8 vs 7.2~7.3초). 크기가 클수록 실패 때 다시 하는 양이 늘었다(1,010 → 1,100 → 2,000개).
- 다섯 방식 모두 결과 checksum이 같았다. 실패 뒤 다시 실행해도 같았다.
- **해석**: chunk의 장점(멈춘 곳부터 이어 가기)은 한 번의 계산이 오래 걸려 처음부터 다시 하는 비용이 클 때 의미가 있다. 10만 행에서는 GROUP BY 한 문장이 훨씬 싸다. 어느 규모부터 뒤집히는지는 재지 않았다.
- **한계**: 로컬 단일 MySQL, 10만 행, 각 1~2회. 과제는 chunk로 재시작을 검증하는 구조를 요구해 현재 구현은 chunk로 둔다. reader가 상품별 합계를 바로 읽게(`GROUP BY` + keyset paging) 바꾸면 processor 조회를 없앨 수 있는데, 구현하거나 재지는 않았다.


## 실험 4. 점수 계산 Reader가 실제로 보내는 SQL

`JdbcPagingItemReader`가 페이지를 넘길 때 OFFSET을 쓰는지, 마지막 정렬 키 다음부터 읽는지(keyset)를 DB가 실제로 받은 SQL로 확인했다.

당시 도구는 `ReaderSqlExperimentTest`였다. 아래는 Paging Reader에서 관찰한 실행 기록이다. 이후 같은 테스트를 사용해 채택한 Reader의 실제 SQL을 확인했다.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*ReaderSqlExperimentTest'
```

### 조건

- 상품 7·8·9·11의 구매 5행으로 `weeklyRanking`을 chunk=2(page 크기 2)로 실행했다.
- root 연결로 MySQL `general_log`(log_output=TABLE)를 켠 동안의 기록에서 Reader SQL만 골랐다.

### 결과

```sql
SELECT DISTINCT product_id FROM ranking_snapshot_input WHERE snapshot_id = 'weekly-20270906-r1' ORDER BY product_id ASC LIMIT 2
SELECT DISTINCT product_id FROM ranking_snapshot_input WHERE (snapshot_id = 'weekly-20270906-r1') AND ((product_id > 8)) ORDER BY product_id ASC LIMIT 2
SELECT DISTINCT product_id FROM ranking_snapshot_input WHERE (snapshot_id = 'weekly-20270906-r1') AND ((product_id > 11)) ORDER BY product_id ASC LIMIT 2
```

### 관찰

- OFFSET이 없다. 첫 페이지 뒤로는 마지막으로 읽은 `product_id` 다음부터 읽는다(keyset).
- 재시작 테스트에서 저장된 위치 `start.after={product_id=8}`과 같은 값이 조건에 들어간다.
- 처음에는 Gradle 명령의 로그 레벨 설정으로 SQL을 보려 했지만 테스트 JVM에 전달되지 않아 로그가 나오지 않았다. 그래서 DB의 general log로 바꿨다.
- 한계: 작은 데이터에서 SQL 모양만 확인했다. keyset과 cursor Reader의 속도는 비교하지 않았다.

## 실험 5. 상품별 합계를 읽는 세 가지 방식

상품 1만 개의 점수를 만들 때 상품마다 합계를 조회하는 비용과, 합계를 Reader에서 바로 읽을 때의 비용을 비교했다.

도구는 `RankingReaderStrategyExperimentTest`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*RankingReaderStrategyExperimentTest'
```

### 조건

- 로컬 Apple Silicon Mac, Testcontainers MySQL 8.0.46, Spring Batch 5.2.2, Connector/J 9.1.0.
- 상품 수는 10,000으로 고정했다. 상품마다 구매 10행 / 20행을 넣어 입력을 100,000 / 200,000행으로 만들었다. 같은 크기의 각 후보는 같은 snapshot 입력을 읽는다.
- 모두 정렬 키는 상품 ID, chunk 크기는 100이다. Paging의 pageSize는 100, Cursor는 `useCursorFetch=true`와 `fetchSize=100`을 사용했다. Writer는 세 방식 모두 당시의 절대값 UPSERT와 같은 JDBC batch 설정을 사용했다.
- 후보마다 워밍업 한 번 후 실행 순서를 회차마다 돌려 총 세 번 측정했다. 준비, 점수 조회 검증, 실행 계획 확인은 측정 구간 밖에서 수행했다.
- 전체 시간은 점수 계산 Step 하나를 실행하는 Job의 시작부터 반환까지다. Reader는 open+read 경과 시간, SUM은 개별 조회 경과 시간, Writer는 JDBC batch 호출 경과 시간이다. DB 엔진 CPU 시간이나 네트워크 왕복 횟수를 뜻하지 않는다. Paging의 Reader 쿼리 수에는 마지막 빈 페이지가 포함된다.

### 정상 완료

시간은 ms다. Reader·SUM·Writer 열은 3회 중앙값과 호출 수다. 모든 정상 실행에서 상품 10,000개의 점수가 기대값과 일치했고, Writer 호출 100번·커밋 101번을 기록했다.

| 구매 행 | 방식 | 전체 1·2·3회 | 전체 중앙값 | Reader 시간 / SELECT 실행 수 | 별도 SUM 시간 / 실행 수 | Writer 시간 / batch 호출 수 |
| ---: | --- | --- | ---: | --- | --- | --- |
| 100,000 | A. 상품 ID + 상품별 SUM | 8,663 · 9,586 · 8,981 | **8,981** | 1,144 / 101 | 6,947 / 10,000 | 251 / 100 |
| 100,000 | B. 집계 Paging | 4,986 · 6,990 · 4,839 | **4,986** | 3,882 / 101 | 0 / 0 | 398 / 100 |
| 100,000 | C. 집계 Cursor | 912 · 1,070 · 907 | **912** | 88 / 1 | 0 / 0 | 258 / 100 |
| 200,000 | A. 상품 ID + 상품별 SUM | 12,270 · 12,183 · 12,151 | **12,183** | 4,290 / 101 | 7,029 / 10,000 | 250 / 100 |
| 200,000 | B. 집계 Paging | 9,331 · 9,351 · 9,363 | **9,351** | 8,271 / 101 | 0 / 0 | 380 / 100 |
| 200,000 | C. 집계 Cursor | 909 · 902 · 855 | **902** | 129 / 1 | 0 / 0 | 239 / 100 |

- 상품 ID를 읽은 뒤 합계를 구하면 두 입력 크기 모두 Processor의 SELECT가 10,000번 실행됐다.
- 집계 Paging은 Processor 조회를 없앴다. 그러나 뒤쪽 페이지의 `EXPLAIN ANALYZE`에서 MySQL은 `product_id > 9000` 조건을 집계 안쪽으로 옮겨도 PRIMARY에서 해당 snapshot의 **100,000/200,000행을 읽고 필터링**했다. 10,000/20,000행이 필터를 통과했다. 이 계획에서는 keyset이 결과를 나눠도 DB가 읽는 양까지 그만큼 줄지 않았다.
- Cursor는 집계 SELECT 한 번으로 점수 10,000개를 읽었다. 상품 수와 Writer 작업량을 고정한 채 구매 행만 늘리자 Cursor의 open+read 시간은 늘었다. 전체 시간의 3회 중앙값은 약 0.9초로 비슷했다. 두 크기에서 관찰한 값이다.

### 실패 후 이어서 계산

chunk=100에서 9,000상품을 커밋한 뒤 9,001번째를 처리하는 도중 실패시켰다. 실패 직후 별도로 조회한 stage 9,000행과 DB의 `read.count=9000`이 일치했다. 집계 Paging에는 `start.after={product_id=9000}`도 기록됐다. 실패한 실행의 readCount에는 커밋 전 미리 읽은 100개가 더 포함됐다. 두 방식 모두 같은 JobInstance의 새 JobExecution에서 나머지 1,000개를 읽었다.

| 구매 행 | 방식 | 첫 실패까지(ms) | 재시작 후 완료(ms) | 합(ms) | 재개 때 Reader open(ms) |
| ---: | --- | ---: | ---: | ---: | ---: |
| 100,000 | B. 집계 Paging | 4,710 | 241 | 4,951 | 0 |
| 100,000 | C. 집계 Cursor | 803 | 262 | 1,065 | 105 |
| 200,000 | B. 집계 Paging | 8,740 | 620 | 9,360 | 0 |
| 200,000 | C. 집계 Cursor | 834 | 252 | 1,086 | 131 |

작은 입력에서도 세 방식 모두 상품 7·8을 커밋한 뒤 상품 9 직전에 실패시켰다. 같은 JobInstance에서 상품 9만 읽어 `7:5:1, 9:5:2, 8:1:3`을 확인했다. 대형 입력은 기대식을 독립적으로 적용해 상품 ID 순서의 `product_id TAB score LF` SHA-256을 검증했다. 10만 행은 `9f5dec368e24b27468b6afc4d4a6209d03fef48c3aedbe45e9f40f52446b6f1a`, 20만 행은 `d362dcf87d9e1401e4df706c46d01bd10ec1a293b75a450ac81908e8f7b6cb7e`이다.

### 판단

이 조건에서 점수 계산의 우선 후보는 집계 Cursor다. 쿼리를 다시 열어 이전 행 수만큼 이동하는 비용은 있었고, 10만 행에서 재시작 자체는 Paging이 21ms 빨랐다. 정상 실행과 실패 전 작업까지 합치면 Cursor가 두 크기 모두 짧았다. 이 비교는 점수 Step의 결과와 재시작만 측정했다. 상품 수가 증가할 때의 메모리, 장시간 연결 점유 및 기존 JobInstance의 checkpoint 호환성은 별도 확인이 필요하다. 운영 Reader 채택은 그 검증 뒤 결정한다.

### 집계 Cursor 채택 후 실제 Job 확인

성능 표는 후보 비교 당시의 실행 시간이다. 주간 랭킹에는 [ADR 0087](../adr/0087-read-weekly-product-totals-with-cursor.md)로 집계 Cursor를 채택했다. 실제 Job에서는 상품별 합계 SELECT 1건과 서버의 `Com_stmt_fetch` 증가를 확인했다(`ReaderSqlExperimentTest`). 기존 Paging에서 첫 chunk를 커밋한 뒤 Cursor로 재시작했을 때도 같은 JobInstance에서 마지막 상품 하나만 저장했다(`RankingJobRestartTest`). 실제 Job의 전체 실행 시간과 큰 상품 수에서의 메모리 비용은 이 확인에서 측정하지 않았다.

## 실험 6. 집계 Paging도 상품 범위만 읽을 수 있을까?

실험 5의 Paging에서는 뒤쪽 페이지도 기본 키로 snapshot 전체를 읽었다. `(snapshot_id, product_id, quantity)` 보조 인덱스는 이미 있으므로, 집계할 범위를 직접 지정하고 그 인덱스를 사용하면 실제로 읽는 행이 줄어드는지 확인했다.

도구는 `RankingReaderStrategyExperimentTest.compareRangePagingWithCursor`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*RankingReaderStrategyExperimentTest.compareRangePagingWithCursor'
```

### 같은 페이지의 실제 읽기 범위

MySQL 8.0.46의 동일한 snapshot(구매 100,000행·상품 10,000개)에서 `product_id > 9000` 다음의 상품 100개를 비교했다. 세 SQL 모두 상품 9,001~9,100의 기대 점수를 같은 순서로 반환했다.

| SQL | 실제 인덱스 접근 | 인덱스에서 읽은 구매 행 |
| --- | --- | ---: |
| 기본 Paging Provider의 집계 서브쿼리 | PRIMARY에서 해당 snapshot 조회 후 필터 | 100,000 |
| 평평한 SELECT에서 `WHERE snapshot_id = ? AND product_id > ?` | PRIMARY에서 해당 snapshot 조회 후 필터 | 100,000 |
| 같은 SELECT에 기존 보조 인덱스를 `FORCE INDEX`로 지정 | 해당 snapshot의 상품 ID 범위를 커버링 인덱스에서 읽고 순서대로 집계 | 1,001 |

```sql
SELECT product_id, SUM(quantity) AS score
FROM ranking_snapshot_input FORCE INDEX (idx_ranking_snapshot_input_product)
WHERE snapshot_id = ? AND product_id > ?
GROUP BY product_id
ORDER BY product_id
LIMIT 100;
```

이 실행에서 옵티마이저는 힌트 없이 PRIMARY를 선택했고 입력 행 수를 5행으로 추정했다. 이 수치만으로 선택 원인을 확정할 수 없다. 힌트를 적용했을 때에는 필요한 상품 100개의 구매 1,000행과 다음 그룹의 첫 행까지 읽고 종료하는 실행 계획이 관찰됐다. 이 비교에서 인덱스나 스키마는 추가하지 않았다.

### 최적화한 Paging과 Cursor의 점수 계산 비교

같은 snapshot, chunk 100, 같은 Writer로 워밍업 1회 뒤 순서를 교차해 각 3회 측정했다. 시간은 ms이며 점수 계산 Step만 포함한다.

| 방식 | 정상 1·2·3회 | 정상 중앙값 | 9,000개 커밋 후 실패까지 | 재시작 후 완료 | 실패+복구 |
| --- | --- | ---: | ---: | ---: | ---: |
| 기존 보조 인덱스를 사용한 집계 Paging | 1,305 · 1,234 · 1,198 | 1,234 | 1,048 | 256 | 1,304 |
| 집계 Cursor | 1,041 · 1,106 · 1,021 | 1,041 | 939 | 253 | 1,192 |

두 방식 모두 정상 점수가 독립 기대 checksum과 일치했다. 실패 직후 DB의 stage 9,000건과 영속 checkpoint를 확인했고, 같은 JobInstance의 새 Execution에서 나머지 1,000건만 처리해 같은 checksum을 얻었다.

집계 Paging의 큰 비용은 이 조건에서 인덱스 범위 읽기로 줄일 수 있었다. Cursor는 정상 중앙값이 약 193ms 짧았고 두 방식의 재시작 단독 시간은 3ms 차이였다. 같은 크기 한 종류의 로컬 측정이다. 입력 분포가 바뀌었을 때의 계획, 인덱스 힌트의 필요성, 동시 실행 시 연결 점유는 별도로 확인해야 한다. 이 비교 결과는 [ADR 0088](../adr/0088-page-weekly-product-totals-by-indexed-key.md)의 범위 Paging 선택 근거다.

## 실험 7. 첫 Chunk 커밋 후 연결과 재시작 SQL

실험 6과 같은 범위 Paging과 집계 Cursor를 상품 7·8·9, chunk=page=2에서 비교했다. 두 방식 모두 7·8을 커밋하고 9 처리 전에 실패시켰다. 다른 Job과 섞지 않고 같은 Hikari 풀에서 실행했다. 커밋 직후 `ChunkListener.afterChunk`에서 첫 실행 전보다 풀의 사용 중 연결이 몇 개 늘었는지 확인했다. 재시작 동안에는 MySQL general log로 실제 집계 SELECT를 확인했다. 연결을 일부러 잡아 두거나 커밋을 지연시키지 않았다.

| 방식 | 첫 Chunk 커밋 직후 사용 중 연결 증가 | 실패로 Step이 끝난 뒤 | 재시작의 첫 집계 SQL |
| --- | ---: | ---: | --- |
| 범위 Paging | 0 | 0 | `WHERE snapshot_id = … AND product_id > 8` |
| 집계 Cursor | 1 | 0 | `WHERE snapshot_id = …`로 집계한 결과에서 앞의 두 상품을 건너뜀 |

두 방식 모두 영속 checkpoint와 stage 2행을 확인했고 같은 JobInstance의 새 실행에서 상품 9만 저장했다. 실제 주간 랭킹 Job의 정상 실행 SQL에서도 보조 인덱스 지정과 후속 페이지의 `product_id > 8`, `product_id > 11`을 확인했다. 이 실험은 연결 반환 시점과 조회 시작점을 비교한다. 동시 작업이 연결을 빌리느라 기다린 시간은 재지 않았다.

## 실험 8. JDBC Batch UPSERT는 서버에 몇 문장으로 도착할까?

당시 `ScoreStageWriter`는 저장소의 `JdbcTemplate.batchUpdate`를 통해 상품별 전체 점수를 UPSERT했다. 공통 DataSource의 `rewriteBatchedStatements=true`가 이 SQL도 재작성하는지 확인했다. 재현 테스트는 당시 SQL을 사용한다.

도구는 `ScoreWriterBatchRewriteExperimentTest`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*ScoreWriterBatchRewriteExperimentTest'
```

- 로컬 Testcontainers MySQL 8.0.46에서 실제 저장소 SQL·테이블을 사용했다. 두 풀의 다른 드라이버 설정은 같고 rewrite 설정만 바꿨다.
- 먼저 MySQL general log에서 100개 점수의 batch 호출 뒤 실행된 DML을 셌다. rewrite를 끄면 단건 UPSERT 100문장, 켜면 100행을 담은 UPSERT 1문장이 도착했다. 켠 상태로 같은 batch를 한 번 더 실행해도 기대 점수의 100행만 남았고, 다른 batch는 COMMIT 직전에 실패하자 0행으로 rollback됐다.
- 시간을 잴 때는 general log를 끄고 100개씩 20회, 총 2,000개 점수를 새 Snapshot 키로 저장했다. 각 호출을 한 TX로 커밋하고, 워밍업 뒤 순서를 교차해 3회 비교했다. 시간은 Writer 저장과 커밋을 포함하며 Reader, JobRepository, 공개 단계는 제외했다.

| rewrite | 저장+커밋 1·2·3회 | 중앙값 |
| --- | --- | ---: |
| off | 1,200 · 980 · 926ms | 980ms |
| on | 120 · 124 · 116ms | 120ms |

기존 Writer와 설정에서 이미 여러 행 UPSERT가 실행됐다. Connector/J는 재작성된 UPSERT의 전체 영향 행 수를 개별 명령에 정확히 대응시킬 수 없다. 이 구현의 실행 결과는 DB에 커밋된 점수와 checkpoint로 확인한다. 측정 시간의 범위는 로컬의 점수 저장과 커밋이다. [Connector/J 성능 옵션](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)

### 실험 8-2. 현재 INSERT Writer에서 다시 확인

실험 8은 당시의 UPSERT로 쟀다. 현재 `ScoreStageWriter`가 호출하는 `WeeklyRankingStore.insertScores`(단순 INSERT)를 그대로 써서 같은 비교를 다시 했다.

도구는 `ScoreWriterBatchRewriteExperimentTest.compareCurrentInsertWriterWithAndWithoutRewrite`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*ScoreWriterBatchRewriteExperimentTest.compareCurrentInsertWriterWithAndWithoutRewrite'
```

- 조건은 실험 8과 같다. 두 풀은 rewrite 설정만 다르고, 시간을 잴 때는 general log를 끄고 100개씩 20회(2,000개)를 새 Snapshot 키로 저장했다. 각 호출을 한 TX로 커밋하고, 워밍업 뒤 순서를 교차해 3회 비교했다.
- general log: rewrite를 끄면 100개 batch 호출 한 번에 **INSERT 100문장**, 켜면 **100행을 담은 INSERT 1문장**이 도착했다. 커밋 직전에 실패시킨 batch는 0행으로 rollback됐다.

| rewrite | 저장+커밋 1·2·3회 | 중앙값 |
| --- | --- | ---: |
| off | 785 · 762 · 694ms | 762ms |
| on | 92 · 113 · 79ms | 92ms |

현재 공통 설정(`rewriteBatchedStatements=true`, `modules/jpa/src/main/resources/jpa.yml`)에서는 Chunk의 점수 INSERT가 서버에 여러 행 INSERT 한 문장으로 도착한다. 이 설정은 저장소 첫 커밋부터 있던 공통 DataSource 설정이다. 시간은 로컬의 점수 저장과 커밋만 포함한다.

## 실험 9. 2-2번 점수 Step의 SQL

`ReaderSqlExperimentTest`로 현재 Job의 MySQL general log를 확인했다. 구매 5행, 상품 7·8·9·11, Chunk와 페이지 크기 2에서 실행했다.

| 동작 | 관찰 |
| --- | --- |
| Reader | `DISTINCT product_id`로 조회했다. 기존 보조 인덱스와 상품 ID 오름차순을 사용했고 후속 조건은 각각 `product_id > 8`, `product_id > 11`이었다. 마지막 SELECT는 빈 페이지를 확인한다 |
| Processor | 상품 7·8·9·11의 `SUM(quantity)` SELECT가 각각 한 번 실행됐다. 구매 내역이 두 행인 상품 7도 합계 SELECT 한 번이다 |
| Writer | `INSERT INTO ranking_score_stage … VALUES (...), (...)` 두 문장이 기록됐다. 각 문장에 해당 Chunk의 점수 두 행이 들어갔다 |

이 검증은 현재 SQL 형태와 실행 횟수를 확인했다. 전체 Job 시간이나 1번 방식과의 처리량 차이는 측정 범위에 포함하지 않았다.

## 실험 10. 구매 10만 행을 구매 행과 상품 ID로 읽었을 때

이 절은 구매별 적용 기록을 쓰던 Writer의 측정값이다. 적용 기록 제거 후 결과는 실험 12에 기록한다.

`ScoreDistributionExperimentTest`에서 과제의 상품 ID 기준 점수 Step과 구매 행 기준 실험 Job에 같은 스냅샷을 주었다. 로컬 MySQL 8.0.46에서 입력을 10만 행, 페이지와 Chunk 크기를 100으로 맞췄다. 수량은 1~7을 반복한다. 1회차에는 구매 행 기준을 먼저, 2회차에는 상품 ID 기준을 먼저 실행했다. 시간은 점수 계산 Step의 시작부터 종료까지다. 입력 생성, 공개, 결과 검산은 측정 구간 밖이다.

| 입력 분포 | 처리 기준 | 읽은 항목 | 페이지 조회 | 상품별 SUM | 구매 적용 기록 INSERT | 점수 쓰기 SQL | 커밋 | 1회차(초) | 2회차(초) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10만 상품, 각 1행 | 구매 행 | 100,000 | 1,001 | 0 | 100,000 | 1,000 | 1,001 | 81.298 | 66.018 |
| 10만 상품, 각 1행 | 상품 ID | 100,000 | 1,001 | 100,000 | 0 | 1,000 | 1,001 | 81.942 | 80.156 |
| 1천 상품, 각 100행 | 구매 행 | 100,000 | 1,001 | 0 | 100,000 | 1,000 | 1,001 | 71.255 | 66.772 |
| 1천 상품, 각 100행 | 상품 ID | 1,000 | 11 | 1,000 | 0 | 10 | 11 | 0.932 | 0.870 |
| 1천 상품, 한 상품에 9만 행 | 구매 행 | 100,000 | 1,001 | 0 | 100,000 | 1,000 | 1,001 | 66.507 | 63.955 |
| 1천 상품, 한 상품에 9만 행 | 상품 ID | 1,000 | 11 | 1,000 | 0 | 10 | 11 | 0.852 | 0.816 |

SQL 횟수는 MySQL `performance_schema`의 Step 실행 전후 차이다. 페이지 조회에는 마지막 빈 페이지가 포함된다. 점수 쓰기는 서버가 실행한 SQL 문장 수로, 한 문장이 여러 상품 점수를 쓸 수 있다. 횟수는 두 회차에서 같았다.

12개 실행 모두 별도로 구한 상품별 수량 합계와 순위에 일치했다. 구매 행 기준에서는 세 분포 모두 구매 적용 기록을 10만 번 썼다. 상품 ID 기준에서는 상품 수가 10만 개에서 1천 개로 줄자 합계 조회도 10만 번에서 1천 번으로 줄었다. 1천 상품 중 한 상품에 9만 행을 몰아도 조회는 1천 번이었다. 개별 SUM의 소요 시간은 따로 재지 않았다. [실험 코드](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/ScoreDistributionExperimentTest.java)

## 실험 11. 구매 100행과 9만 행인 상품의 SUM 한 번

과제 Processor가 사용하는 `WeeklyRankingStore.productQuantityTotal`을 직접 호출했다. 테스트용 MySQL에 총 10만 행과 상품 1천 개를 넣었다. 한 상품은 9만 행, 다른 상품은 100행, 나머지 998개는 합쳐서 9,900행이다. 각 구매의 수량은 1~7을 반복하며 두 상품의 기대 합계 359,995와 401을 매 호출에서 확인했다.

같은 스냅샷·SQL·인덱스 구성으로 적재 직후와 `ANALYZE TABLE ranking_snapshot_input` 후를 측정했다. 각 조건에서 두 상품을 각각 5회 워밍업하고 조회 순서를 회차마다 바꿔 상품당 21회 측정했다. 시간은 같은 트랜잭션의 연결에서 JDBC 호출 직전부터 합계가 앱에 돌아올 때까지다. 인덱스 힌트는 사용하지 않았다. 각 조건의 시간 측정 직후 같은 SQL의 `EXPLAIN ANALYZE`를 별도로 실행해 인덱스 접근 단계가 반환한 행 수를 확인했다.

| 통계 상태 | 상품 구매 행 | 선택된 인덱스 | 인덱스에서 읽은 행 | 중앙값(ms) | 최소~최대(ms) |
| --- | ---: | --- | ---: | ---: | ---: |
| 적재 직후 | 100 | `PRIMARY` | 100,000 | 27.103 | 26.606~41.213 |
| 적재 직후 | 90,000 | `PRIMARY` | 100,000 | 29.027 | 28.533~38.448 |
| 통계 갱신 후 | 100 | `idx_ranking_snapshot_input_product` | 100 | 1.286 | 0.925~1.990 |
| 통계 갱신 후 | 90,000 | `PRIMARY` | 100,000 | 26.816 | 26.444~30.311 |

적재 직후에는 두 상품 모두 PRIMARY로 스냅샷 10만 행을 읽고 상품 ID를 필터링했다. 통계 갱신 뒤 100행 상품은 기존 커버링 인덱스에서 해당 상품의 100행만 읽었고, 9만 행 상품은 PRIMARY로 10만 행을 읽어 9만 행을 합산했다. 이때 두 중앙값은 약 21배 차이 났다. 이 비율에는 합산할 행 수와 MySQL이 선택한 접근 경로의 차이가 함께 들어 있다. 실험 10의 Step 시간과는 별도로 구성한 단일 쿼리 측정이다. CPU·디스크 I/O는 측정하지 않았다.

```sh
./gradlew :apps:commerce-batch:experimentTest --tests '*ScoreDistributionExperimentTest.measuresOneSumForSmallAndHotProducts' --rerun
```

## 실험 12. 구매별 적용 기록을 제거한 뒤의 DB 부하

고정 Snapshot에서 구매 행을 읽는 비교용 Writer는 Chunk 안에서 같은 상품의 수량을 모아 증가분만 UPSERT한다. 상품 ID를 읽는 과제 Step은 상품별 전체 SUM을 조회해 점수를 INSERT한다. Docker Desktop의 전용 MySQL 8.0.46에서 구매 10만 행, page·chunk 100, 수량 1~7 반복으로 세 분포를 만들었다. 분포·회차별로 별도 Snapshot을 적재했다. 1회차에는 구매 행을, 2회차에는 상품 ID를 먼저 실행했다. 각 실행 직후 별도 집계 SQL과 모든 상품의 점수·순위를 대조했고 12회 모두 일치했다.

| 구매 분포 | 단위 | 읽은 Item | 페이지 조회 | 상품별 SUM | 점수 쓰기 SQL | 커밋 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 10만 상품 × 1행 | 구매 행 | 100,000 | 1,001 | 0 | 1,000 | 1,001 |
| 10만 상품 × 1행 | 상품 ID | 100,000 | 1,001 | 100,000 | 1,000 | 1,001 |
| 1천 상품 × 100행 | 구매 행 | 100,000 | 1,001 | 0 | 1,000 | 1,001 |
| 1천 상품 × 100행 | 상품 ID | 1,000 | 11 | 1,000 | 10 | 11 |
| 1천 상품 중 하나에 9만 행 | 구매 행 | 100,000 | 1,001 | 0 | 1,000 | 1,001 |
| 1천 상품 중 하나에 9만 행 | 상품 ID | 1,000 | 11 | 1,000 | 10 | 11 |

두 회차 모두 구매별 적용 기록 INSERT는 0번이었다. 점수 쓰기는 MySQL에서 실행된 SQL 문장 수다. 한 문장에 여러 상품이 들어간다.

| 구매 10만 행의 분포 | 읽는 단위 | Step 시간(초) | 검사한 행 | 대상 SQL CPU(초) | 버퍼 풀 미스 읽기 | redo(MiB) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 10만 상품 × 1행 | 구매 행 | 31.008 / 6.023 | 86,100,000 / 100,000 | 22.613 / 0.952 | 9,449 / 3,823 | 12.15 / 12.29 |
| 10만 상품 × 1행 | 상품 ID | 42.429 / 42.157 | 200,999 / 200,999 | 13.101 / 13.284 | 6,859 / 6,578 | 12.16 / 12.23 |
| 1천 상품 × 100행 | 구매 행 | 6.651 / 6.205 | 100,000 / 100,000 | 1.287 / 1.267 | 2,743 / 2,995 | 15.08 / 14.77 |
| 1천 상품 × 100행 | 상품 ID | 0.494 / 0.503 | 200,009 / 200,009 | 0.185 / 0.183 | 391 / 239 | 0.29 / 0.24 |
| 1천 상품 중 하나에 9만 행 | 구매 행 | 5.461 / 5.245 | 100,000 / 100,000 | 0.601 / 0.579 | 3,021 / 2,353 | 5.18 / 5.14 |
| 1천 상품 중 하나에 9만 행 | 상품 ID | 0.540 / 0.468 | 210,009 / 210,009 | 0.189 / 0.168 | 376 / 184 | 0.15 / 0.15 |

표의 각 셀은 1회차 / 2회차 순이다. Step 시간은 Spring Batch가 기록한 점수 계산 시간이다. 나머지는 한 Step만 가진 Job을 실행하기 직전과 직후 MySQL 누적값의 차이다. 입력 적재·기대값 생성·결과 검산은 측정 구간 밖에 있다.

- 검사한 행과 SQL CPU는 Reader의 페이지 조회, Processor의 상품별 SUM, Writer의 점수 INSERT·UPSERT에 해당하는 digest의 `SUM_ROWS_EXAMINED`와 `SUM_CPU_TIME`을 합산했다. 한 행을 여러 번 검사하면 여러 번 센다. CPU는 해당 SQL을 처리한 스레드의 누적 시간이다.
- 버퍼 풀 미스 읽기는 `Innodb_buffer_pool_reads`, redo는 `Innodb_os_log_written`이다. 두 값은 배치 메타데이터 작업과 백그라운드 작업까지 포함하는 전용 서버 전체 수치다. OS 캐시와 물리 디스크 지연, 전체 DB의 CPU 사용률은 따로 계측하지 않았다. [MySQL의 상태 변수 정의](https://dev.mysql.com/doc/refman/8.0/en/server-status-variables.html) · [SQL CPU 시간 정의](https://dev.mysql.com/doc/refman/8.0/en/performance-schema-events-statements-current-table.html)
- 같은 DB에 스냅샷이 차례로 쌓이고 캐시·통계도 변할 수 있다. 수치는 2회 관찰값이다. 적용 기록을 쓰던 실험 10과는 실행 환경도 달라져 두 실험의 시간 차이 전체를 기록 INSERT 제거 효과로 환산할 수 없다.

상품이 10만 종류였을 때 구매 행 방식은 두 번 다 상품 ID 방식보다 먼저 끝났다. 그런데 구매 행 SQL이 검사한 행은 1회차 8,610만 행, 2회차 10만 행이었다. 이때 대상 SQL의 CPU 시간도 22.613초에서 0.952초로 달라졌다. 반환한 구매는 두 번 다 10만 줄이었다. 해당 회차의 페이지별 실행 계획을 수집하지 않아 이 편차의 원인은 확인하지 못했다.

상품이 1천 종류이고 각 상품이 100번 팔렸을 때는 상품 ID 방식이 두 번 다 약 0.5초에 끝났다. 구매 행 방식은 1,001번 커밋하고 점수 쓰기 SQL 1,000문장을 보냈고, 상품 ID 방식은 11번 커밋하고 10문장을 보냈다. 같은 실행 구간에 서버가 쓴 redo도 구매 행 방식은 약 15 MiB, 상품 ID 방식은 약 0.24~0.29 MiB였다. 구매가 한 상품에 9만 줄 몰린 경우에는 구매 행 방식의 커밋 수와 쓰기 문장 수가 그대로였지만 redo는 약 5 MiB였다. 한 문장이 처리하는 상품 수가 달라질 수 있어 SQL 횟수만으로 쓰기량을 알 수는 없었다.

구매 네 줄의 별도 테스트에서는 두 번째 Chunk가 점수를 쓴 직후 예외를 냈다. 첫 Chunk의 상품 7은 2점, 체크포인트는 주문 1702의 상품 8이었다. 같은 JobInstance가 남은 두 줄을 읽어 상품 7이 5점이 됐다. 같은 결과 영역을 그대로 둔 채 새 JobInstance로 네 줄을 다시 처리하자 7·9·8번의 점수가 각각 10·10·2가 됐다. 실패한 작업은 같은 식별 파라미터로 재시작하고, 전량 재계산은 새 결과 영역에 쌓아야 한다.


```sh
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 DOCKER_HOST="unix://$HOME/.docker/run/docker.sock" TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :apps:commerce-batch:experimentTest --tests '*PurchaseRowScoreExperimentTest' --tests '*ScoreDistributionExperimentTest.comparesBothScoreStepsOnThreePurchaseDistributions'
```

## 실험 13. 입력을 넣은 직후 계산하면 통계 상태에 따라 무엇이 달라질까?

실제 배치는 입력 고정에서 `ranking_snapshot_input`에 한 주 입력을 대량으로 넣고, **바로 다음 Step**에서 점수를 계산한다. 실험 12의 1회차에서 구매 행 방식이 검사한 행이 8,610만 행으로 크게 튄 것도, 입력을 넣은 직후 가장 먼저 실행된 측정이었다. 그래서 계산 직전의 테이블 통계 상태만 바꿔 같은 계산을 반복했다.

도구는 `StatsFreshnessExperimentTest`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*StatsFreshnessExperimentTest'
```

### 조건

- 로컬 Testcontainers MySQL 8.0.46, `innodb_stats_persistent=1`, `innodb_stats_auto_recalc=1`(기본값).
- 이번 주 입력은 **5만 행**이다. 로컬 부담 때문에 테이블 전체를 10만 행 안으로 둔다.
- 통계 상태 3가지. 측정마다 테이블을 비우고(TRUNCATE) 새로 넣는다.

| 통계 상태 | 계산 직전 테이블 |
| --- | --- |
| 빈 테이블에 적재 직후 | 이번 주 5만 행만 있고 통계는 거의 빈 테이블 기준이다(`n_rows` 8~20) |
| 적재 후 `ANALYZE` | 이번 주 5만 행, `ANALYZE TABLE`로 통계를 갱신했다 |
| 이전 주가 쌓인 테이블에 적재 직후 | 이전 주 5만 행을 넣고 `ANALYZE`한 뒤 이번 주 5만 행을 넣었다(전체 10만 행). 매주 입력이 쌓이는 실제 테이블과 같은 순서다 |

- 구매 분포 3가지(실험 12와 같은 규칙): 상품 5만 개 × 1행 / 상품 1천 개 × 50행 / 1천 상품 중 한 상품에 4.5만 행.
- 읽는 단위 2가지: 구매 행(실험 12의 비교용 Job) / 상품 ID(실제 점수 계산 Step). page·chunk 100.
- 조합마다 3회, 회차마다 먼저 실행하는 방식을 바꿨다.
- 계산 직전과 직후에 `mysql.innodb_table_stats`의 `n_rows`와, Step이 보내는 SQL과 같은 모양의 `EXPLAIN`을 남겼다.
  - 구매 행: 두 번째 페이지부터의 SQL(`(order_id > ?) OR (order_id = ? AND product_id > ?)`, `ORDER BY order_id, product_id LIMIT 100`)
  - 상품 ID: 상품 하나의 `SUM(quantity)`. 쏠린 분포에서는 쏠린 상품의 SUM도 함께
- 검사한 행은 점수 계산 SQL(페이지 조회와 상품별 SUM) digest의 `SUM_ROWS_EXAMINED` 차이다.
- 36번 모두 별도 `GROUP BY` 집계와 상품별 점수가 같았다.

### 결과

Step 시간(ms)과 검사한 행, 각 3회.

| 분포 | 단위 | 빈 테이블에 적재 직후 | 적재 후 ANALYZE | 이전 주가 쌓인 테이블에 적재 직후 |
| --- | --- | --- | --- | --- |
| 5만 상품 × 1행 | 구매 행 | 10,027 · 9,331 · 9,244 ms<br>약 2,450만~2,500만 행 | 2,933 · 3,366 · 3,777 ms<br>5만 행 | 2,942 · 3,337 · 3,178 ms<br>5만 행 |
| 5만 상품 × 1행 | 상품 ID | 23,218 · 23,056 · 26,797 ms<br>100,499행 | 24,872 · 24,200 · 24,429 ms<br>100,499행 | 23,882 · 24,187 · 23,623 ms<br>100,499행 |
| 1천 상품 × 50행 | 구매 행 | 9,601 · 9,531 · 9,750 ms<br>약 2,165만~2,235만 행 | 3,505 · 3,663 · 3,629 ms<br>5만 행 | 3,535 · 3,370 · 3,923 ms<br>5만 행 |
| 1천 상품 × 50행 | 상품 ID | 8,934 · 9,186 · 9,189 ms<br>약 4,825만~5,005만 행 | 575 · 488 · 434 ms<br>100,009행 | 433 · 514 · 540 ms<br>100,009행 |
| 한 상품에 4.5만 행 | 구매 행 | 9,225 · 8,870 · 9,401 ms<br>약 2,350만~2,500만 행 | 2,648 · 2,848 · 2,610 ms<br>5만 행 | 2,830 · 2,974 · 2,883 ms<br>5만 행 |
| 한 상품에 4.5만 행 | 상품 ID | 9,177 · **500** · 9,077 ms<br>약 4,674만·**34.8만**·4,759만 행 | 407 · 509 · 569 ms<br>105,009행 | 501 · 547 · 444 ms<br>105,009행 |

**계산 직전 실행 계획**

| 통계 상태 | 구매 행 다음 페이지 | 상품 하나의 SUM (1천 상품 분포) |
| --- | --- | --- |
| 빈 테이블에 적재 직후 | `ref`, `idx_ranking_snapshot_input_product`, **Using filesort** (9회 모두) | `ref`, **PRIMARY** (6회 중 5회). 쏠린 분포 2회차만 상품 인덱스 |
| 적재 후 ANALYZE | `range`, PRIMARY | `ref`, 상품 인덱스(Using index) |
| 이전 주가 쌓인 테이블에 적재 직후 | `range`, PRIMARY | `ref`, 상품 인덱스(Using index) |

- 빈 테이블에 적재 직후 `n_rows`는 8~20이었다(실제 5만 행). 이전 주가 쌓인 조건의 `n_rows`는 약 4.9만~5만으로, 이번 주를 넣기 전 값이었다(실제 10만 행).
- 쏠린 분포에서 쏠린 상품 하나의 SUM은 통계 상태와 관계없이 PRIMARY로 snapshot 범위를 읽었다. 그 상품의 행이 snapshot의 90%라 범위 스캔이 낫다고 본 것으로 보인다.
- 빈 테이블 조건에서는 Step 도중 자동 통계 갱신이 일어난 경우도 있었다(끝난 뒤 `n_rows`가 약 4.9만으로 바뀜). 그래도 검사한 행은 크게 줄지 않았다.

### 관찰

- **통계가 거의 빈 테이블 기준이면 두 방식 모두 검사하는 행이 수백 배로 늘었다.**
  - 구매 행: optimizer가 PK 대신 상품 인덱스로 snapshot 전체를 읽고 페이지마다 정렬(filesort)했다. 페이지 하나(100행)를 위해 약 5만 행을 읽어, 500페이지에서 약 2,500만 행이 됐다. 실험 12 1회차의 8,610만 행(1,001페이지 × 약 8.6만 행)과 같은 모양이다.
  - 상품 ID: 상품별 SUM이 상품 인덱스 대신 PRIMARY로 snapshot 전체(5만 행)를 읽었다. 1천 번 × 5만 행 ≈ 5,000만 행. 시간은 약 0.5초 → 약 9초였다. 실험 11에서 본 "적재 직후 PRIMARY" 현상과 같다.
- **이전 주 통계가 있는 테이블에 넣은 직후에는 18번 모두 정상 계획이었다.** 통계 `n_rows`는 실제의 절반이었지만 계획은 `ANALYZE` 뒤와 같았고 시간도 같은 범위였다. 매주 입력이 쌓이는 실제 테이블에서는, 이번 실험 조건(같은 분포가 이미 있는 경우)이라면 문제가 드러나지 않았다.
- 따라서 문제가 드러난 것은 **통계가 거의 비어 있는 테이블에 처음 대량으로 넣고 바로 계산할 때**였다. 예를 들어 배포 뒤 첫 실행이 이 조건에 가깝다. 이전 주와 분포가 크게 다를 때, 오래된 snapshot을 지운 뒤에도 같은지는 재지 않았다.
- **상품 5만 개 × 1행에서는 상품 ID 방식이 통계와 관계없이 약 23~27초였다.** 검사한 행은 정상(100,499)이었다. 시간은 상품별 SUM 5만 번 호출 수로 정해졌다. 같은 조건의 구매 행 방식(통계 정상 약 3초)보다 길었다.
- 같은 통계 조건에서 다시 재면, 조건마다 가장 긴 시간이 가장 짧은 시간의 1.4배 안이었다(쏠린 분포·빈 테이블·상품 ID의 500ms 한 번 제외). 실험 12의 회차 간 큰 차이는 통계 상태가 회차마다 달랐던 영향으로 본다. 실험 12의 각 회차 통계를 기록하지 않아 직접 확인하지는 못했다.
- 한계
  - 로컬 단일 MySQL, 이번 주 5만 행, 조합마다 3회다.
  - 입력은 앱의 묶음 INSERT로 넣었다. 실제 입력 고정의 `INSERT … SELECT`와 통계 갱신 시점이 같은지는 확인하지 않았다.
  - `ANALYZE TABLE` 자체의 시간과 영향은 재지 않았다.

## 실험 14. 입력이 커지면 점수 계산 시간은 어떻게 늘어날까?

실험 10~13은 대부분 한 가지 크기였다. 실제 서비스의 주간 구매 수로 시간을 추정하려면, 크기가 늘 때 시간이 어떤 비율로 느는지 알아야 한다. 통계를 갱신한 상태에서 이번 주 입력만 2.5만·5만·10만 행으로 바꿨다.

도구는 `StatsFreshnessExperimentTest.comparesInputSizesAfterAnalyze`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*StatsFreshnessExperimentTest.comparesInputSizesAfterAnalyze'
```

### 조건

- 실험 13과 같은 DB·분포 규칙·방식, page·chunk 100.
- 측정마다 테이블을 비우고 한 크기만 넣은 뒤 `ANALYZE TABLE`한다. 테이블은 최대 10만 행이다.
- 분포 규칙이 같으므로 크기가 늘 때 달라지는 것이 분포마다 다르다.
  - 상품마다 1행: 상품 수가 2.5만 → 5만 → 10만으로 는다.
  - 1천 상품에 고르게 / 한 상품에 쏠림: 상품 수는 1천 개 그대로이고 상품당 행 수가 는다.
- 크기 × 분포 × 방식마다 3회, 회차마다 먼저 실행하는 방식을 바꿨다. 54번 모두 별도 집계와 점수가 같았다.

### 결과

Step 시간(ms), 3회.

| 분포 | 단위 | 2.5만 행 | 5만 행 | 10만 행 | 읽은 item (2.5만 / 5만 / 10만) |
| --- | --- | --- | --- | --- | --- |
| 상품마다 1행 | 구매 행 | 2,120 · 1,641 · 1,611 | 3,063 · 3,367 · 3,651 | 6,358 · 6,551 · 6,667 | 2.5만 / 5만 / 10만 |
| 상품마다 1행 | 상품 ID | 11,416 · 11,936 · 11,934 | 24,682 · 22,960 · 24,468 | 47,118 · 47,770 · 41,736 | 2.5만 / 5만 / 10만 |
| 1천 상품에 고르게 | 구매 행 | 1,619 · 1,822 · 1,768 | 3,317 · 3,520 · 3,449 | 6,474 · 7,144 · 5,854 | 2.5만 / 5만 / 10만 |
| 1천 상품에 고르게 | 상품 ID | 503 · 488 · 487 | 443 · 566 · 481 | 620 · 521 · 462 | 1천 / 1천 / 1천 |
| 한 상품에 쏠림 | 구매 행 | 1,332 · 1,312 · 1,426 | 2,899 · 2,816 · 2,658 | 5,009 · 5,588 · 4,580 | 2.5만 / 5만 / 10만 |
| 한 상품에 쏠림 | 상품 ID | 427 · 424 · 469 | 591 · 506 · 491 | 453 · 606 · 445 | 1천 / 1천 / 1천 |

검사한 행은 구매 행 방식이 입력 행 수와 같았고(2.5만 / 5만 / 10만), 상품 ID 방식이 약 2배였다(상품 ID 페이지 + 상품별 SUM이 읽은 구매 행).

### 관찰

- **구매 행 방식은 구매 행 수에 비례했다.** 세 분포 모두 행이 2배가 되면 시간도 약 2배였다(10만 행에서 약 4.6~7.1초, 행 하나에 약 46~71µs). 상품 수와는 관계가 거의 없었다.
- **상품 ID 방식은 상품 수에 비례했다.** 상품마다 1행이면 상품 수가 2배일 때 시간도 약 2배였다(상품 하나에 약 0.42~0.49ms). 상품이 1천 개로 고정이면 구매 행이 4배가 돼도 약 0.42~0.62초로 거의 같았다. 검사한 행은 늘었지만 이 크기에서는 시간에 드러나지 않았다.
- 두 방식이 같아지는 지점은 대략 "구매 행 수 × 46~71µs = 상품 수 × 0.42~0.49ms", 즉 **상품 하나에 구매가 약 6~11행보다 많으면 상품 ID 방식이 빠르다**고 읽을 수 있다. 세 분포·세 크기에서 관찰한 값으로 계산한 대략의 비율이며, 그 사이 분포를 직접 재지는 않았다.
- 상품마다 1행 10만 행에서 상품 ID 방식 약 42~48초는 실험 12(42초대)와 같은 범위였다.
- 한계: 로컬 단일 MySQL, 10만 행까지다. 10만 행을 넘는 크기에서도 비례하는지, 메모리에 다 올라가지 않는 크기에서 상품별 SUM의 비용이 어떻게 되는지는 재지 않았다.

## 실험 15. 인덱스를 지정하면 넣은 직후에도 같은 계획일까?

실험 13에서 통계가 거의 빈 테이블에 넣은 직후에는 두 방식 모두 검사한 행이 수백 배로 늘었다. 대책으로 상품 ID 방식(실제 점수 계산 Step)의 상품별 SUM에 상품 인덱스를 지정했다([ADR 0091](../adr/0091-force-product-index-for-product-total.md)). 비교용 구매 행 Job은 다음 페이지 SQL에 PRIMARY를 지정하는 선택지를 켜고 다시 쟀다.

도구는 `StatsFreshnessExperimentTest.comparesAfterIndexHint`.

```bash
./gradlew :apps:commerce-batch:experimentTest --tests '*StatsFreshnessExperimentTest.comparesAfterIndexHint'
```

### 조건

- 실험 13과 같은 DB·분포·크기(이번 주 5만 행), page·chunk 100.
- 통계 상태 2가지: 빈 테이블에 넣은 직후 / 넣은 뒤 `ANALYZE TABLE`. 조합마다 3회, 회차마다 먼저 실행하는 방식을 바꿨다.
- 상품 ID 방식: `SELECT COALESCE(SUM(quantity), 0) FROM ranking_snapshot_input FORCE INDEX (idx_ranking_snapshot_input_product) WHERE snapshot_id = ? AND product_id = ?`
- 구매 행 방식: `FROM ranking_snapshot_input FORCE INDEX (PRIMARY)`
- 36번 모두 별도 집계와 점수가 같았다.

### 결과

Step 시간(ms)과 검사한 행, 각 3회. 대책 전 값은 실험 13의 "빈 테이블에 넣은 직후"다.

| 분포 | 단위 | 대책 전, 빈 테이블 직후 | **대책 후, 빈 테이블 직후** | 대책 후, ANALYZE 뒤 |
| --- | --- | --- | --- | --- |
| 5만 상품 × 1행 | 구매 행 | 9,244~10,027ms, 약 2,450만~2,500만 행 | **3,441~4,085ms, 5만 행** | 3,410~3,640ms, 5만 행 |
| 5만 상품 × 1행 | 상품 ID | 23,056~26,797ms, 100,499행 | 23,793~25,076ms, 100,499행 | 24,273~26,676ms, 100,499행 |
| 1천 상품 × 50행 | 구매 행 | 9,531~9,750ms, 약 2,165만~2,235만 행 | **3,442~3,591ms, 5만 행** | 3,516~3,824ms, 5만 행 |
| 1천 상품 × 50행 | 상품 ID | 8,934~9,189ms, 약 4,825만~5,005만 행 | **499~571ms, 100,009행** | 469~532ms, 100,009행 |
| 한 상품에 4.5만 행 | 구매 행 | 8,870~9,401ms, 약 2,350만~2,500만 행 | **2,877~3,067ms, 5만 행** | 2,849~2,961ms, 5만 행 |
| 한 상품에 4.5만 행 | 상품 ID | 9,177 · 500 · 9,077ms | **540~607ms, 342,509행** | 490~503ms, 105,009행 |

### 관찰

- **인덱스를 지정하자 빈 테이블에 넣은 직후도 `ANALYZE` 뒤와 같은 범위가 됐다.** 1천 상품 × 50행의 상품 ID 방식은 약 9초 → 약 0.5초, 구매 행 방식은 약 9.5초 → 약 3.5초였다.
- 통계를 갱신한 상태에서도 느려지지 않았다.
- 5만 상품 × 1행의 상품 ID 방식은 대책 전후 모두 약 24초였다. 원래 검사한 행이 정상이었고, 시간은 SUM 호출 5만 번이 정한다(실험 14).
- 한 상품에 쏠린 분포의 상품 ID 방식은 빈 테이블 직후 검사한 행이 342,509행으로 `ANALYZE` 뒤(105,009행)보다 많았지만 시간은 0.54~0.61초였다. 실험 13의 같은 조건 2회차(0.5초, 347,509행)와 비슷한 값이다. 어느 SQL에서 늘었는지는 나눠 보지 않았다.
- 한계: 로컬 단일 MySQL, 이번 주 5만 행, 조합마다 3회다. 실제 Job의 구매 행 방식은 없고, 구매 행 쪽 힌트는 비교용 Job에서만 확인했다.
