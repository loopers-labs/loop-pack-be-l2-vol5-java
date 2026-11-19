# 9주차 설계: 주간 구매 랭킹 — 고정한 입력으로 이어 가고, 완성본만 공개한다

이 문서는 `docs/week8/design.md` 를 기준으로 **9주차에 추가된 것만** 적는다. 절 번호는 새로 시작하고 결정 번호는 8주차를 이어 D-176부터 쓴다. 결정 번호는 정한 순서대로라 장 순서와 다르다.

## 1. 범위

**W6 · W7 의 상품 구매 활동을 주간 snapshot 으로 고정하고, commerce-batch 의 job 이 상품별 구매 수량 합으로 랭킹을 계산해 완성본만 공개한다. commerce-api 는 공개된 완성본만 읽는다.**

| 항목 | 계약 (과제) |
|---|---|
| 기간 | UTC 월요일 00:00 부터 다음 월요일 00:00 전까지 |
| 주간 소속 | 주문의 최초 `confirmedAt` |
| 점수 | 상품별 구매 quantity 합 |
| 순서 | 점수 내림차순, 동점이면 productId 오름차순 |

| 구분 | 이번 범위 | 범위 밖 |
|---|---|---|
| 추가 | 주간 랭킹 job(입력 고정 → 점수 계산 → 공개), 주간 랭킹 조회 API | Redis 실시간 랭킹, 운영 스케줄러, 처리량 측정 |
| 유지 | 7주차까지의 구매 활동 · 8주차 쿠폰. 쿠폰은 점수에 섞지 않는다 | |

## 2. 입력 고정

### 주간 입력은 처리 기록의 확정 시각으로 고른다 (D-176)

**`processed_order_confirmation.confirmed_at` 이 `[periodStart, periodStart + 7일)` (UTC) 인 주문을 골라 `product_purchase_activity` 와 `order_id` 로 join 한다.** 확정 시각은 처리 기록에만 있다(6주차 D-78). `order_id` 가 처리 기록에서 유일하므로 주문마다 "최초 확정 시각"은 하나다.

- 입력 행의 단위는 `(orderId, productId)` 다. 한 주문의 여러 품목을 모두 포함한다.
- 시작 경계는 포함, 끝 경계는 제외한다. 경계 시각(일요일 23:59:59Z, 월요일 00:00Z)을 fixture 에 넣어 UTC 저장과 함께 검증한다.

### 원본은 잠금 없는 스냅샷 읽기로 읽고, 앱이 snapshot 입력에 쓴다 (D-177)

**입력 고정 TX 안에서 원본을 일반 SELECT(InnoDB 일관된 읽기)로 읽고, 읽은 행을 앱이 snapshot 입력 테이블에 insert 한다.**

- 일관성은 TX 의 첫 읽기 시점(read view)이 보장한다. 그 뒤 streamer 가 commit 한 활동은 이 TX 에 보이지 않는다.
- 배치가 쓰는 곳은 snapshot 테이블뿐이라 streamer 의 저장과 부딪히지 않는다.
- 격리 수준은 기본값(REPEATABLE READ)을 유지한다. READ COMMITTED 로 낮추면 TX 안의 읽기마다 시점이 달라진다.
- 데이터가 커지면 같은 TX 안에서 `order_id, product_id` 순으로 나눠 읽는다. 같은 TX 라 읽기 시점은 유지된다.

| 검토한 안 | 포기한 이유 |
|---|---|
| `INSERT … SELECT` 한 문장 | REPEATABLE READ 에서 원본을 공유 next-key 잠금으로 읽어(MySQL 문서), 배치 TX 가 끝날 때까지 consumer 의 활동 insert 를 막는다. 일관성에는 잠금이 필요 없다 |
| `INSERT … SELECT` + READ COMMITTED | 잠금은 사라지지만 TX 안의 여러 읽기가 같은 시점이라는 보장이 약해진다 |

포기하는 것: 데이터가 DB → 앱 → DB 로 이동한다. TX 가 길어지면 InnoDB 가 그동안의 undo 기록을 정리하지 못한다.

### 입력과 manifest 는 한 TX 로 commit 한다 (D-178)

**snapshot 입력 행과 manifest(기간 · revision · 상태 · 고정 시점 · 건수 · checksum)를 같은 TX 에서 저장한다.** 입력 고정은 tasklet 하나, TX 하나다.

- 고정 중 실패하면 전부 rollback 되어 반쪽 snapshot 이 남지 않는다. "고정 중" 상태가 필요 없다.
- 입력 checksum 은 읽은 행으로 바로 계산한다. 직렬화 규칙은 과제를 따른다: `orderId, productId` 순 정렬, `orderId, productId, quantity, confirmedAt`, 필드 사이 TAB · 행 끝 LF, UTC 시각.
- 완료된 입력 고정 step 은 재시작 때 다시 실행하지 않는다.

### 고정 시점은 표시 · 기록용이고, 포함 여부의 기준이 아니다 (D-179)

**manifest 의 "입력 고정 시점"은 첫 읽기 시점의 근사값이다. 어떤 활동이 snapshot 에 들어갔는지는 시각이 아니라 snapshot 입력 행 자체로 판단한다.**

- read view 에 보이는 행은 그 시점까지 **commit** 된 행이다. 활동의 `created_at` 은 insert 시각이라, `created_at` 이 고정 시점보다 앞서도 commit 이 늦은 행은 snapshot 에 없다. 과제가 경고한 `max(id)` 와 같은 구조다.
- 따라서 늦은 구매는 "snapshot 입력에 없는 `(orderId, productId)`"로 찾는다. `created_at > 고정 시점` 으로 찾지 않는다.
- TX 안에서 manifest 를 먼저 조회하면(같은 snapshotId 확인) 그 조회가 첫 읽기가 된다. 고정 시점은 TX 의 첫 읽기 직전에 기록한다.
- 시각은 앱의 `Clock` 으로 적는다. 테스트에서 고정하기 쉽고, DB `NOW(6)` 를 쓰려면 Spring TX 대신 JDBC 로 `START TRANSACTION WITH CONSISTENT SNAPSHOT` 을 직접 다뤄야 한다. 앱 · DB 시계 차이와 read view 생성까지의 수 ms 는 표시용이라 감수한다.

## 3. 공개

### 공개는 manifest 의 상태로 표현한다 (D-180)

**별도 공개 pointer 테이블을 두지 않고, manifest 의 상태가 `PUBLISHED` 인 snapshot 을 그 기간의 공개본으로 본다.** 공개 전환은 한 TX 에서 기존 공개본을 `COMPLETED` 로 내리고 새 snapshot 을 `PUBLISHED` 로 올린다. API 는 MVCC 로 전환 전이나 후 중 하나만 본다.

- **기간마다 공개본은 하나**: 생성 컬럼 `published_period = IF(status = 'PUBLISHED', period_start, NULL)` 에 유일 인덱스를 건다. MySQL 은 조건부 유일 인덱스가 없지만 NULL 끼리는 겹칠 수 있어, `PUBLISHED` 일 때만 유일해진다. 동시 공개 TX 가 둘 다 올리려 하면 하나는 유일 제약으로 실패한다.
- 유일 검사는 행마다 바로 일어나므로, 전환 TX 는 기존 공개본을 먼저 내리고 새 것을 올린다.
- **오래된 revision 차단**: 지금 공개본보다 revision 이 클 때만 공개한다.
- **공개 재실행**: 이미 `PUBLISHED` 인 같은 snapshot 의 공개는 아무것도 바꾸지 않고 성공으로 끝낸다.
- **내려간 공개본은 `COMPLETED` 로 되돌린다.** "공개됐다가 밀려났다"는 상태를 따로 두지 않는다. 덮어쓰기는 revision 조건이 막는다.

| 검토한 안 | 포기한 이유 |
|---|---|
| 기간별 공개 pointer 테이블(`period_start` PK) | 테이블이 하나 늘고, manifest 상태와 pointer 두 곳이 어긋날 수 있다 |
| 유일성을 공개 TX 의 `FOR UPDATE` 잠금 후 판단으로 지킴 | 잠금을 빠뜨리는 경로가 생기면 `PUBLISHED` 가 둘이 된다 |
| 내려간 공개본에 `SUPERSEDED` 상태 | 덮어쓰기는 revision 조건으로 이미 막혀 상태만 늘어난다 |

포기하는 것: 생성 컬럼의 의도가 스키마만 보고는 드러나지 않는다. 한때 공개됐었다는 이력이 manifest 에 남지 않는다. 이전 revision 으로 되돌리는 공개는 revision 조건에 막혀 별도 경로가 필요하다.

### 점수와 순위는 한 테이블에 둔다 (D-181)

**`(snapshot_id, product_id)` 유일 키의 점수 테이블 하나에 계산 순위 컬럼을 둔다.** 점수 step 은 `rank` 없이 절대 점수를 upsert 하고, 공개 step 은 같은 TX 에서 순위를 채운 뒤 상태를 `PUBLISHED` 로 바꾼다. 과제의 stage 와 ranking 은 개념상 구분이며 테이블 구성은 설계에 맡긴다(실습 §2).

- 한 snapshot 의 점수 행은 한 번 들어가면 값이 바뀌지 않는다. commit 전 실패는 rollback 되어 행이 없고, commit 된 chunk 는 재시작 때 다시 읽지 않으며, 다시 쓰더라도 고정 입력의 합이라 같은 값이다.
- 계산 중인 행은 API 에 보이지 않는다. API 는 `PUBLISHED` 인 manifest 와 join 해서만 읽는다(D-180).
- 공개 재실행은 같은 순위를 다시 쓰므로 그대로 멱등하다.
- 점수 행은 공개 뒤에도 지우지 않는다. snapshot 마다 행이 쌓이고 `snapshot_id` 로 구분된다.

| 검토한 안 | 포기한 이유 |
|---|---|
| 점수 stage 와 완성 ranking 테이블 분리 | 점수 행이 이미 불변이라 분리로 얻는 보호가 거의 없다. 테이블이 늘고 같은 점수를 두 번 저장하며, 공개 재실행 때 중복 insert 를 따로 막아야 한다 |

포기하는 것: 계산 중 행과 완성 행이 한 테이블에 있어 "완성 여부"를 테이블만으로 알 수 없고 manifest 상태를 함께 봐야 한다.

## 4. 실행과 revision

### revision 은 실행 진입점이 정해 식별 파라미터로 넘긴다 (D-184)

**사람은 기간만 넘긴다. batch 의 실행 진입점이 그 기간의 최대 revision snapshot 을 보고 revision 을 정해, `periodStart` 와 함께 식별 파라미터로 job 에 넘긴다.**

```
java -jar commerce-batch.jar --job.name=weeklyRanking --spring.batch.job.enabled=false \
  --weekly-ranking.command=run --periodStart=2026-08-17        # 실행 · 재시작
  --weekly-ranking.command=check --periodStart=2026-08-17      # 늦은 구매 확인 (D-191)
  --weekly-ranking.command=abandon --periodStart=2026-08-17 --revision=2   # 포기 (D-185)
```

- Boot 의 기본 job runner 는 revision 없이 실행하므로 `spring.batch.job.enabled=false` 를 함께 준다. 빠뜨리면 진입점이 실패한다.
- 실행이 COMPLETED 가 아니면 예외로 끝나 종료 코드가 0 이 아니다.

```
그 기간의 최대 revision snapshot
 ├─ 없음                       → r1
 ├─ PUBLISHED                  → 최대 + 1 (새 계산)
 ├─ 포기됨                     → 최대 + 1
 └─ 그 외 (FROZEN · COMPLETED) → 같은 revision (재시작)
```

- job 안에서 revision 을 정하면 식별 파라미터가 기간뿐이라 한 주에 JobInstance 가 하나뿐이 되어 재계산을 못 한다. 그래서 job 밖에서 정한다.
- 끝나지 않은 revision 은 같은 번호로 재시작해 같은 JobInstance 의 마지막 commit 부터 이어 간다. 실패마다 새 번호를 쓰면 `run.id` 를 붙이는 것과 같다.
- 입력 고정이 실패하면 manifest 가 남지 않으므로(D-178) 다시 계산해도 같은 번호가 나온다.
- 최대 revision 하나만 본다. D-180 으로 내려간 공개본도 `COMPLETED` 라 "PUBLISHED 가 아니면 재시작"으로 판단하면 내려간 r1 을 잡는다.
- 같은 기간의 동시 실행은 같은 revision 을 고르므로 같은 JobInstance 가 되고, Spring Batch 가 실행 중인 JobInstance 의 두 번째 실행을 거절한다.

### 막힌 revision 은 사람이 명시적으로 포기한다 (D-185)

**기본은 같은 revision 재시작이다. 고정한 입력 자체가 잘못돼 재시작으로 풀리지 않을 때만 사람이 그 revision 을 포기로 표시하고, 다음 실행은 새 revision 으로 입력을 다시 고정한다.**

- 일시 장애 · 코드 버그는 같은 revision 재시작으로 푼다. 포기는 이 둘이 아닐 때만 쓴다.
- **포기는 manifest 의 `ABANDONED` 상태로만 표시한다.** 포기 명령은 `FROZEN · COMPLETED → ABANDONED` 조건부 update 다. `PUBLISHED` 는 포기할 수 없다.
- 실행 진입점은 최대 revision 이 `ABANDONED` 면 새 번호를 고른다(D-184).
- 포기한 revision 으로 job 을 직접 실행해도 이어지지 않도록, 점수 계산 · 공개 step 은 시작할 때 manifest 가 `ABANDONED` 면 실패한다.
- 공개 전이는 `COMPLETED → PUBLISHED` 만 허용한다. revision 조건(D-180)만으로는 공개본보다 번호가 큰 포기 revision 을 막지 못한다.
- 입력 고정이 실패한 revision 은 manifest 가 없어 포기할 대상이 아니다. 다시 실행하면 입력을 새로 고정한다.

| 검토한 안 | 포기한 이유 |
|---|---|
| 항상 같은 revision 재시작, 포기 경로 없음 | 입력이 잘못되면 DB 를 직접 고치는 것 말고 빠져나갈 길이 없다 |
| 실패하면 항상 새 revision | 새 JobInstance 라 checkpoint 를 버리고, 그 사이 늦은 구매가 입력에 섞인다 |
| Spring Batch 의 `ABANDONED`(`JobOperator.abandon`) | manifest 는 그대로라 실행 진입점이 batch metadata 도 봐야 해서 판단 근거가 두 곳이 된다 |
| manifest 와 Spring Batch 양쪽에 표시 | 두 곳을 함께 바꿔야 하고 한쪽만 바뀐 상태가 생길 수 있다 |

포기하는 것: 상태가 하나 늘고 포기 명령이 필요하다. 재시작 차단을 Spring Batch 가 아니라 step 시작 검사가 맡는다.

### snapshot 은 문자열 snapshotId 로 가리키고, 식별 파라미터는 세 값이다 (D-186)

**snapshot(manifest) 테이블은 snapshot 하나에 한 행이고, 입력 · 점수 테이블은 `snapshot_id` 로 그 행을 가리킨다. job 의 식별 파라미터는 `periodStart` · `revision` · `snapshotId` 셋이다.**

- snapshotId 는 기간과 revision 으로 만든다. 예: `weekly-20260817-r2`. 실행 진입점이 revision 을 정한 뒤 함께 만든다(D-184).
- 세 값이 서로 맞는지 job 시작 때 검증한다. `snapshotId` 가 `periodStart` · `revision` 으로 만든 값과 다르거나 `periodStart` 가 월요일이 아니면 실행하지 않는다. 테스트처럼 진입점을 거치지 않고 job 을 실행하는 경로에서 어긋날 수 있다.
- 입력 테이블은 원본 구매 활동의 복사본이다. 원본은 늦은 구매로 계속 바뀌므로, 같은 행 집합을 시각 · `max(id)` 경계로 다시 고를 수 없다(D-179). 과제도 입력을 snapshot 에 복사하라고 한다.

| 검토한 안 | 포기한 이유 |
|---|---|
| `(period_start, revision)` 두 컬럼으로 가리킴 | 입력 · 점수 테이블의 키가 길어지고, API 응답에 snapshot 을 한 값으로 내보내기 어렵다 |
| 숫자 id(auto increment)로 가리킴 | job 실행 전에는 값을 알 수 없어 식별 파라미터로 쓸 수 없다 |
| 식별 파라미터 `periodStart` · `revision` 둘 | 정보 중복은 없지만 과제 예시와 다르다 (이번에는 세 값을 고름) |
| 입력을 복사하지 않고 manifest 에 경계 · checksum 만 저장 | 경계로는 같은 집합을 재현할 수 없고, checksum 만으로는 고정이 아니라 변화 감지라 늦은 구매 하나에 재시작이 실패한다 |

포기하는 것: 기간 · revision 이 snapshotId 와 manifest 컬럼에 두 번 저장되고, 세 값의 일치를 검증해야 한다. revision 마다 그 주의 활동이 한 번 더 복사된다.

## 5. 구현 방식

### commerce-batch 는 JPA 엔티티 없이 JDBC 로만 다룬다 (D-182)

**입력 고정 · 점수 계산 · 공개를 모두 SQL(JDBC)로 처리하고, batch 에는 JPA 엔티티를 두지 않는다.**

- 작업이 대량 복사 · 집계 · upsert(`ON DUPLICATE KEY UPDATE`) · 순위 윈도 함수 · 생성 컬럼(D-180)이라, JPA 를 써도 native SQL 로 돌아온다. IDENTITY 키라 JPA 묶음 insert 도 되지 않는다.
- batch 에 엔티티가 없으므로 local · test 의 `ddl-auto: create` 가 batch 에서 테이블을 만들거나 지우지 않는다.
- manifest 상태 전이(`FROZEN → COMPLETED → PUBLISHED`, `PUBLISHED → COMPLETED`, revision 조건, 이미 공개면 무변경)는 한 클래스에 모은 조건부 update 로 지키고, 영향 행 수가 기대와 다르면 거절한다.

| 검토한 안 | 포기한 이유 |
|---|---|
| manifest 만 엔티티, 나머지 JDBC | 같은 TX 에서 JPA 와 JDBC 가 섞여 flush 순서를 신경 써야 하고, batch 엔티티가 테이블을 만들어 스키마 소유 문제가 다시 생긴다 |

포기하는 것: 상태 전이 규칙이 엔티티 메서드가 아니라 SQL 조건으로 표현된다.

### 새 테이블의 스키마는 commerce-api 엔티티가 만든다 (D-183)

**manifest · 입력 · 점수 테이블은 commerce-api 의 JPA 엔티티로 정의한다.** 8주차 D-138(공유 테이블은 api 가 소유) · D-172(다른 앱은 엔티티 없이 JDBC) 를 그대로 따른다.

- api 는 manifest 와 점수를 읽는다. 입력 테이블은 api 가 읽지 않지만 스키마를 한곳에서 정의하려고 엔티티로 둔다.
- 생성 컬럼(D-180)은 엔티티에 `columnDefinition` 과 읽기 전용(`insertable = false, updatable = false`)으로 적고, 유일 인덱스는 `@Table` 에 둔다. Hibernate 가 이 정의로 테이블을 만드는지는 구현 때 확인한다.
- batch 테스트는 api 엔티티가 없으므로 구매 활동 · 처리 기록 · 새 테이블을 테스트용 DDL 스크립트로 만든다.

| 검토한 안 | 포기한 이유 |
|---|---|
| SQL 스크립트 하나로 만들고 batch · api 테스트가 공유 | 다른 주차와 경로가 달라지고, api 가 JPA 로 읽을 때 매핑과 스크립트가 맞는지 따로 확인해야 한다 |

포기하는 것: api 에 쓰지 않는 입력 엔티티가 생긴다. batch 테스트 DDL 이 api 엔티티와 어긋나도 자동으로 잡히지 않는다.

### job 은 입력 고정 → 점수 계산 → 계산 완료 → 공개 네 step 이다 (D-194)

- **점수 계산(chunk)**: reader 는 고정 입력의 서로 다른 `product_id` 를 오름차순으로 읽는 cursor reader 이고, 위치를 `saveState` 로 step 의 ExecutionContext 에 남긴다. processor 는 같은 snapshot 입력에서 상품의 수량 합을 구하고, writer 는 `(snapshot_id, product_id)` 에 절대 점수를 upsert 한다. 결과와 reader 위치는 같은 chunk TX 로 commit 된다.
- **계산 완료(tasklet)**: 입력의 상품 수와 점수 행 수가 같은지 확인하고 `FROZEN → COMPLETED` 로 바꾼다. 이미 `COMPLETED` 면 그대로 끝낸다. step 이 끝난 뒤의 listener 는 chunk TX 밖이라 상태 전이를 두지 않는다.
- 점수 계산 · 계산 완료 step 은 시작할 때 manifest 가 없거나 `ABANDONED` 면 실패한다(D-185).
- chunk 크기는 설정값이다. 재시작 실험(실습 §6)은 2 를 쓰고, 기본값 100 은 측정한 최적값이 아니다.

| 검토한 안 | 포기한 이유 |
|---|---|
| paging reader(`product_id` 정렬 키) | 고정 입력은 바뀌지 않아 cursor 의 위치 재현 문제가 없고, probe 로 확인한 방식이다. 큰 입력에서 페이지 단위 조회가 필요해지면 바꾼다 |
| 계산 완료 전이를 공개 step 에 합침 | 상태가 "계산은 끝났지만 공개 전"을 표현하지 못하고, 공개 실패 때 계산 완료 여부를 구분할 수 없다 |

### 공개 step 은 순위 → 기존 공개본 내리기 → 새 공개본 올리기를 한 TX 로 한다 (D-195)

1. manifest 가 `PUBLISHED` 면 아무것도 바꾸지 않고 끝낸다. `COMPLETED` 가 아니면 실패한다.
2. 그 기간의 공개본 revision 이 이 revision 보다 크면 실패한다. 늦게 끝난 오래된 실행은 공개본을 되돌리지 않고 FAILED 로 남는다.
3. `row_number() over (order by score desc, product_id asc)` 로 순위를 채운다. 동점도 순위가 다르다(과제 기대값: 상품 7 · 9 가 5점으로 1 · 2위).
4. 기존 공개본을 `revision < 이 revision` 조건으로 `COMPLETED` 로 내리고, 이 snapshot 을 `COMPLETED → PUBLISHED` 로 올린다. 그사이 다른 실행이 더 새 revision 을 공개했다면 내리기는 0행이고 올리기가 `published_period` 유일 제약으로 실패한다.

- 결과 checksum(순위 순서의 `productId, score, rank`)은 테스트에서 정상 실행과 재시작을 비교하는 데만 쓰고 manifest 에 저장하지 않는다.
- 오래된 revision 의 실행을 성공으로 끝내지 않고 실패로 두는 것은, 공개하지 않은 실행이 COMPLETED 로 보이면 공개됐다고 오해할 수 있어서다. D-184 의 진입점은 최대 revision 만 고르므로 이 실패는 job 을 직접 실행한 경우에만 생긴다.

## 6. 조회 API

### 주간 랭킹 조회는 product 기능 아래에 둔다 (D-187)

**`GET /api/v1/rankings/weekly?periodStart=…&limit=…` 와 랭킹 엔티티(snapshot · 입력 · 점수) · 상태 enum 을 commerce-api 의 product 기능 패키지 아래에 둔다.**

- 랭킹은 상품 구매 수량의 순위이고, 응답도 상품 표시 정보와 함께 나간다. 지금 랭킹 관련 기능은 이 주간 랭킹뿐이다.
- 포기하는 것: product 패키지가 상품 자체와 랭킹 계산 결과를 함께 담는다. 랭킹 기능이 늘면 분리를 다시 판단한다.

### 응답은 계산 순위와 현재 상품 정보를 함께 준다 (D-188)

**항목마다 productId · 점수 · 계산 순위와, 조회 시점의 상품 이름 · 가격 · 브랜드를 준다.** 응답 머리에는 상태(`PUBLISHED` / `NOT_PUBLISHED`) · periodStart · periodEnd · snapshotId · 입력 고정 시점을 둔다.

- 이름 · 가격 · 브랜드는 **현재 값**이다. 계산 당시 값이 아니다.
- 순위는 계산 당시 순위를 그대로 준다. 삭제 · 비노출 상품을 빼므로 1 · 3위처럼 빈 순위가 생길 수 있다(과제 계약).
- 브랜드 삭제는 상품 삭제로 이어지므로(`deleteAllOfBrand`) 노출 조건은 기존 조회와 같은 `product.deleted_at is null` 이다.

### 조회는 공개 snapshot 확인 → 순위 행 조회 두 번이다 (D-189)

1. 그 기간의 `PUBLISHED` snapshot 을 찾는다. 없으면 `NOT_PUBLISHED` 와 빈 목록을 준다.
2. 찾은 `snapshot_id` 로 점수 · 상품 · 브랜드를 join 해 삭제 상품을 거르고, 계산 순위 순으로 limit 을 적용한다. 0행이면 `PUBLISHED` 와 snapshotId · 빈 목록을 준다.

- 순위 행만 묻는 쿼리 하나로는 "공개 전", "구매 0건 공개본", "순위 상품이 전부 삭제된 공개본"이 모두 0행이라 구분할 수 없고, snapshotId · 고정 시점을 꺼낼 행도 없다.
- 두 쿼리 사이에 새 revision 이 공개돼도 2 는 1 의 `snapshot_id` 로만 읽고, 그 snapshot 의 점수 행은 바뀌지 않아(D-181) 서로 다른 snapshot 이 섞이지 않는다.
- 삭제 상품 제외와 limit 을 SQL 한 번에 적용해, 걸러진 뒤 limit 이 모자라는 일이 없다.

| 검토한 안 | 포기한 이유 |
|---|---|
| snapshot 에서 출발하는 `LEFT JOIN` 한 쿼리 | 삭제 조건을 `ON` 절에 넣어야 하고, "상품 없음"을 뜻하는 NULL 행을 응답 변환에서 걸러야 한다 |
| 넉넉히 읽은 뒤 기존 "살아 있는 상품 id" 조회로 거르고 자름 | 삭제 상품이 많으면 한 번 읽어서는 limit 을 채우지 못한다 |

### 업무 규칙 위반은 새 오류 코드로 400 을 준다 (D-190)

**`periodStart` 가 월요일이 아니거나 `limit` 이 1 ~ 100 밖이면 `ProductErrorCode` 에 추가한 코드(`INVALID_RANKING_PERIOD`, `INVALID_RANKING_LIMIT`)로 400 을 준다.** 날짜 형식 오류 · 파라미터 누락은 기존 `ApiControllerAdvice` 처리(400, `BAD_REQUEST`)를 그대로 쓴다.

- `limit` 을 생략하면 10 이다(과제 예시). 범위 밖 값은 기본값으로 바꾸지 않고 거절한다.
- 공개본이 없을 때 `snapshotId` · `frozenAt` 은 응답에서 빠진다. 프로젝트 Jackson 설정이 null 필드를 내보내지 않는다.

## 7. 늦은 구매

### 새 revision 은 사람이 다시 실행해 만들고, 늦은 구매는 감지만 한다 (D-191)

**늦은 구매를 반영하려면 사람이 그 기간으로 job 을 다시 실행한다. 최대 revision 이 `PUBLISHED` 라 새 revision 이 된다(D-184). 늦은 구매가 있는지는 결과를 바꾸지 않는 확인 명령으로 센다.**

- 늦은 구매 = 그 기간 원본에 있지만 공개 snapshot 의 입력에 없는 `(orderId, productId)`. 시각으로 판단하지 않는다(D-179).
- 확인 명령은 건수를 로그로 남기기만 한다. 알림은 범위 밖이며, 나중에 이 결과에 붙인다.
- 확인 명령은 별도 job 이 아니라 랭킹 실행 진입점의 옵션으로 둔다. 공개 snapshot 조회를 revision 결정과 같이 쓰고, 실행 기록은 batch metadata 에 남기지 않는다.
- 과제 §7 의 "늦은 구매를 포함한 새 revision 재계산"은 다시 실행하는 것으로 충족한다.

| 검토한 안 | 포기한 이유 |
|---|---|
| 감지 없이 다시 실행만 | 늦은 구매가 있는지 알 방법이 없고, 나중에 알림을 만들 때 감지부터 새로 짜야 한다 |
| 감지하면 자동으로 새 revision 생성 | 과제의 "명시적으로" 와 멀어지고, 언제 돌릴지(스케줄)가 범위 밖이다 |

### 늦은 구매가 없어도 다시 실행하면 새 revision 을 공개한다 (D-192)

**입력이 공개본과 같아도 막지 않는다.** 점수 · 순위는 같고, snapshotId 와 입력 고정 시점만 새 값으로 바뀐다.

- 바뀐 고정 시점은 "그 시점까지도 늦은 구매가 없었다"는 사실이라 틀린 정보가 아니다.
- 감지 명령으로 0건을 확인하면 다시 실행할 이유가 없어 이런 revision 은 드물다.

| 검토한 안 | 포기한 이유 |
|---|---|
| 입력 checksum 이 공개본과 같으면 공개하지 않고 끝냄 | 입력을 고정한 뒤에야 알 수 있어 공개 안 된 snapshot 이 남고, `COMPLETED` 로 두면 D-184 가 다음 실행 때 재시작 대상으로 잡는다. 이를 피할 상태 규칙이 더 필요하다 |

포기하는 것: 같은 결과의 revision 이 쌓이고, 응답의 snapshotId 가 내용 변화 없이 바뀔 수 있다.

## 8. 검증

### 연결 테스트는 하나, 시나리오는 최소로 둔다 (D-193)

**commerce-api 테스트 하나에서 실제 흐름을 잇는다: 주문 확정 → Outbox relay → Kafka → streamer(bootJar) 가 구매 활동 저장 → batch(bootJar) 실행 → 랭킹 API.** 시나리오는 정상 흐름(r1 공개)과 늦은 구매(r2 공개) 두 가지다.

- streamer 는 기존 `OrderOutboxDeliveryE2ETest` 처럼 bootJar 프로세스로 띄운다. batch 는 bootJar 를 실행하고 끝날 때까지 기다리는 프로세스로 돌린다(r1 실행 · 늦은 구매 확인 · r2 실행). batch bootJar 는 streamer 와 같은 방식으로 api 테스트 전에 만든다.
- 확인할 것: 같은 이벤트 재발행에도 구매 활동 · 점수가 늘지 않음, 같은 주문의 여러 품목 포함, 확인 명령이 늦은 구매를 셈, r2 공개 전에는 r1 · 공개 후에는 r2.
- 주문 확정 시각은 실제 시계라 연결 테스트에서 주간 경계를 고를 수 없다. 구간 시작 포함 · 끝 제외는 batch 입력 고정 테스트가 UTC 경계 시각으로 확인한다.
- 재시작 · 동시 공개 · 공개 TX rollback · 포기 · 삭제 상품 · 미공개 · 빈 주는 batch 와 api 의 각 테스트가 맡는다.
- 이 테스트가 batch 테스트 DDL 과 api 엔티티의 어긋남(D-183), streamer 가 쓴 `confirmed_at` 과 주간 경계 쿼리의 어긋남을 잡는 곳이다.

| 검토한 안 | 포기한 이유 |
|---|---|
| 앱별로 나눠 검증 | 과제 §7 의 "한 연결 테스트에서" 와 체크리스트를 충족하지 못하고, 앱 사이 이음새를 아무도 확인하지 않는다 |
| 연결 테스트에 모든 시나리오를 넣음 | 프로세스를 여러 번 띄워 느리고, 실패했을 때 원인을 좁히기 어렵다 |

포기하는 것: 테스트 하나가 무겁고 느리다. batch bootJar 빌드가 api 테스트의 선행 작업이 된다.
