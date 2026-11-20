# 9주차 · 프로세스 kill 실험 기록 (로컬 전용, 2026-11-20)

목적: 설계 7-2 #21 "프로세스 kill → `JobExecution` STARTED 잔류" 를 실제로 재현하고, 수동 복구 절차 (가)~(라) 가 문장 그대로 동작하는지 확인한다. 코드·설정 변경 없음. 바뀐 것은 로컬 DB 데이터뿐이다.

전제·환경
- jar: `apps/commerce-batch/build/libs/commerce-batch-ecac307.jar` (`./gradlew :apps:commerce-batch:bootJar`). 모든 기동은 `java -jar … --spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=update --job.name=weeklyRanking periodStart=2026-09-07 snapshotId=<id>`.
- DB: `docker/infra-compose.yml` mysql, `loopers`, `application/application`. SQL 은 04-manual-run 의 `mysql` 셸로 실행.
- **시각 표기**: 이 문서의 "셸 시각" 은 `date -u`(UTC). `BATCH_*.START_TIME/END_TIME` 은 JVM 기본 시간대(Asia/Seoul) 벽시계 = UTC+9. 컨테이너 MySQL 은 `@@system_time_zone=UTC` 라 `NOW(6)` 은 UTC. 업무 테이블(`frozen_at`·`published_at`)은 UTC.
- 출발 상태: 2026-08-17 주 JobInstance 1·2 COMPLETED, pointer → `weekly-20260817-r2`, `product_purchase_activity` 5행, STARTED 없음. 이 주는 끝까지 읽기만 했고 종료 시 동일함을 확인했다.
- read.count 디코드: `SHORT_CONTEXT` 를 base64 디코드한 뒤 키 `weeklyProductIdReader.read.count` 뒤의 `xp` 다음 4바이트를 big-endian int 로 읽는다(`/tmp/rc.py`). 기준선 r2 로 10001 이 나오는 것을 먼저 확인했다.

---

## 0. 요약 표 (기술 글용)

| 실험 | kill 지점 | kill 시점 COMMIT_COUNT | read.count (context) | scores 행 수 (kill 직후) | 복구 후 재시작 Step 2 READ_COUNT | 최종 scores | checksum (input / result) | pointer |
|---|---|---|---|---|---|---|---|---|
| 기준선 r2 | 없음 | 5001 (완료) | 10001 | 10000 | — | 10000 | `9628…47c8` / `3573…7795` | r2 |
| 1 r3 Step 2 도중 | commit 2585 (51.7%) | 2585 | **5170** | **5170** (= 2585×2 = read.count) | **4830** (= 10000 − 5170) | 10000 | r2 와 **동일** / **동일** | r3 |
| 2 r4 늦은 행 뒤 새 revision | 없음 | 5001 | 10001 | — | — | 10000 | r3 와 **다름** / **다름** (상품 100001 점수 5→12) | r4 |
| 3 r6 Step 1 도중 | Step 1 시작 7ms 뒤 | 0 | (Step 2 없음) | 0, inputs 0, 회차 행 없음 | Step 1 **재실행** → Step 2 READ_COUNT 10000 | 10000 | r5 와 다름 / 다름 (늦은 행 950001 포함, inputs 10002) | r6 |
| 4 r7 Step 3 도중 | Step 3 시작 6ms 뒤 (TX 안) | 5001 (Step 2 완료) | 10001 | 10000, **ranking 전부 NULL** | Step 3 만 재실행 (334ms) | 10000 | r6 와 **동일** / **동일** | r7 |

JobExecution 번호: r1=3, r2=4, r3=5(kill)→6(재시작), r4=7, r5=8(의도치 않은 정상 완료), r6=9(kill)→10, r7=11(kill)→12.

---

## 1. 준비

### 1-1. seed

셸 06:35 UTC. 2026-09-07 주에 상품 1개 = 주문 1개로 INSERT (재귀 CTE).

```sql
SET cte_max_recursion_depth = 10000;
INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
WITH RECURSIVE n AS (SELECT 1 i UNION ALL SELECT i+1 FROM n WHERE i < 3000)
SELECT 900000+i, 100000+i, 1+FLOOR(RAND()*5),
       TIMESTAMPADD(SECOND, FLOOR(RAND()*(5*86400)), '2026-09-08 00:00:00'), NOW(6), NOW(6) FROM n;
```

결과: 3000행, confirmed_at `2026-09-08 00:04:24` ~ `2026-09-12 23:59:23`, quantity 1~5.

### 1-2. 기준선 r1 (3,000행) → 15초 미만이라 폐기

| 항목 | 값 |
|---|---|
| Job | COMPLETED, exit 0, 10s947ms |
| Step 2 | READ 3000 / WRITE 3000 / COMMIT 1501 / ROLLBACK 0, 15:35:46.950 → 15:35:57.581 (**10.6초**) |
| 회차 | input_count 3000, expected 3000, input `2694bf13…4064`, result `b77463f3…41d8` |

**지시 기준(15초) 미달** → 3001~10000 을 같은 방식으로 7,000행 추가(합계 10,000행, 상품 100001~110000)하고 r2 를 기준선으로 다시 만들었다.

### 1-3. 기준선 r2 (10,000행)

| 항목 | 값 |
|---|---|
| Job | COMPLETED, exit 0, 49s545ms |
| Step 1 | 238ms |
| Step 2 | READ 10000 / WRITE 10000 / **COMMIT 5001** / ROLLBACK 0, 15:36:27.370 → 15:37:16.343 (**49.0초**, 약 100 commit/초) |
| Step 3 | 247ms |
| 회차 | input_count 10000, expected 10000, input_checksum `962853de4512cd87ef895354a84d890fd23f9560035609989fee81442d2347c8`, result_checksum `3573d749ed88f90f60b1e04a4b31a465e3b54acdf8249255846200cfa5a7f795` |
| context | `weeklyProductIdReader.read.count = 10001` (항목 10000 + 끝 확인 read 1) |
| pointer | 2026-09-07 → revision 2, `published_at` 은 r1 의 06:35:57 유지 |

> COMMIT_COUNT 5001 = chunk 5000 + 마지막 빈 chunk 1. "기준선 commit 수의 절반" 목표는 2500.

---

## 2. 실험 1 — Step 2 도중 `kill -9` (r3)

### 2-1. 기동과 kill

- 셸 06:37:49.19 UTC 기동, PID 59582. 1초 폴링 `SELECT STATUS, COMMIT_COUNT FROM BATCH_STEP_EXECUTION WHERE STEP_NAME='calculateWeeklyScoresStep' ORDER BY STEP_EXECUTION_ID DESC LIMIT 1`.
- 폴링 추이: 06:37:55 `STARTED 61` → … → 06:38:18 `STARTED 2467` → 06:38:19 `STARTED 2583` → **`kill -9` 06:38:19.977 UTC**. `wait` 종료 코드 **137**.
- 로그는 `Executing step: [calculateWeeklyScoresStep]` 이 마지막 줄. 실패를 알리는 줄은 하나도 없다(프로세스가 즉사하므로 당연하지만, "알림" 관점에서 기록).

### 2-2. kill 직후 (셸 06:38:35 UTC 조회)

| 대상 | 관찰 | 기대 | 판정 |
|---|---|---|---|
| `BATCH_JOB_EXECUTION` 5 | STATUS **STARTED**, EXIT_CODE **UNKNOWN**, END_TIME **NULL**, START 15:37:54.002(KST), LAST_UPDATED 15:37:54.003 | STARTED·NULL | 일치 |
| Step 13 freeze | COMPLETED, COMMIT 1 | | |
| Step 14 calculate | **STARTED / EXECUTING**, READ 5170 / WRITE 5170 / **COMMIT 2585** / ROLLBACK 0, END_TIME NULL, LAST_UPDATED 15:38:19.964 | STARTED | 일치. 폴링 마지막값 2583 과 kill 사이 2 commit 더 진행 |
| context | `read.count = 5170` | commit×2 | **5170 = 2585×2** |
| `weekly_ranking_scores` r3 | **5170행**, max product_id 105170, ranking 0 | commit×2 | 일치 — 진행 중 chunk 흔적 없음 (scores = read.count) |
| `weekly_ranking_inputs` r3 | 10000행 | | Step 1 commit 분 보존 |
| 회차 r3 | **CALCULATING**, frozen_at 06:37:54.061Z, input_checksum = r2 와 동일, result NULL | CALCULATING | 일치 |
| pointer | revision 2 → r2 유지 | r2 | 일치 |

> 쓰기 중인 chunk 는 DB 에 없다: commit 수 × 2 = context 의 read.count = scores 행 수 가 정확히 맞았다. reader checkpoint 와 chunk commit 이 한 TX 라는 D10 의 전제가 kill -9 에서도 성립한다.

### 2-3. 복구 전 같은 파라미터 재시작 (셸 06:38:47 UTC)

- 종료 코드 **1** (Boot 가 `ApplicationRunner` 예외로 종료. `BatchStatus.ordinal()` 경로가 아님 → 04-manual-run 4-1 의 `[미확인]` 에 대한 부분 답: 기동 실패는 1).
- 로그 전문(핵심 2줄):

```
java.lang.IllegalStateException: Failed to execute ApplicationRunner
Caused by: org.springframework.batch.core.repository.JobExecutionAlreadyRunningException: A job execution for this job is already running: JobExecution: id=5, version=1, startTime=2026-11-20T15:37:54.002439, endTime=null, lastUpdated=2026-11-20T15:37:54.003363, status=STARTED, exitStatus=exitCode=UNKNOWN;exitDescription=, job=[JobInstance: id=5, version=0, Job=[weeklyRanking]], jobParameters=[{'periodStart':'{value=2026-09-07, type=class java.lang.String, identifying=true}','snapshotId':'{value=weekly-20260907-r3, type=class java.lang.String, identifying=true}'}]
```

- DB 변화 없음: JobExecution 5 STARTED/NULL/LAST_UPDATED 그대로, Step 14 COMMIT 2585, scores 5170. 새 JobExecution 도 생기지 않았다.

### 2-4. (실험 2 전반) 늦은 행 INSERT — 복구 전 (셸 06:39:09 UTC)

```sql
INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
VALUES (950000, 100001, 7, '2026-09-10 03:00:00.000000', NOW(6), NOW(6));
```

→ 2026-09-07 주 원본 10001행.

### 2-5. 수동 복구 (가)~(라)

**(가) 공개 상태 확인** — pointer: `2026-09-07 / revision 2 / weekly-20260907-r2`. 회차: r1 COMPLETED, r2 COMPLETED, **r3 CALCULATING**. → "아직 공개 안 된 r3 가 계산 중 상태로 멈춤" 으로 판단.

**(나) STARTED 식별**

```sql
SELECT je.JOB_EXECUTION_ID, je.JOB_INSTANCE_ID, je.STATUS, je.START_TIME, je.END_TIME, p.PARAMETER_NAME, p.PARAMETER_VALUE
FROM BATCH_JOB_EXECUTION je JOIN BATCH_JOB_INSTANCE ji ON ji.JOB_INSTANCE_ID=je.JOB_INSTANCE_ID
JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID=je.JOB_EXECUTION_ID
WHERE je.STATUS='STARTED';
```

→ 2행 (JOB_EXECUTION_ID 5, periodStart 2026-09-07 / snapshotId weekly-20260907-r3). "실제 프로세스가 없는" 확인은 `ps -p 59582` (없음) + `pgrep -fl commerce-batch` (없음).

**(다) STEP → JOB 순 FAILED** — 설계 SQL 그대로, `:id` = 5 (셸 06:39:27 UTC)

```
UPDATE BATCH_STEP_EXECUTION ... WHERE JOB_EXECUTION_ID = 5 AND STATUS = 'STARTED';  → Rows matched: 1  Changed: 1
UPDATE BATCH_JOB_EXECUTION  ... WHERE JOB_EXECUTION_ID = 5 AND STATUS = 'STARTED';  → Rows matched: 1  Changed: 1
```

결과: Job 5 FAILED/FAILED, Step 14 FAILED/FAILED, Step 13 (COMPLETED) 는 조건으로 건드리지 않음. **VERSION 은 1 그대로**(갱신하지 않았는데 재시작에 문제 없었음).

> **달랐던 점 ①**: `END_TIME = NOW(6)` 이 **06:39:27(UTC)** 로 들어가 `START_TIME 15:37:54(KST)` 보다 **9시간 앞선 종료 시각**이 됐다. 컨테이너 MySQL 의 `NOW()` 는 UTC 이고 Batch 가 쓰는 시각은 JVM 벽시계(KST)이기 때문. 재시작을 막지는 않는다(Batch 는 STATUS 만 본다). 7-2 #24 의 "9시간 차" 가 복구 SQL 에도 그대로 적용된다는 뜻이며, 절차 문장에 반영이 필요하다(6절).

**(라) 같은 파라미터로 재시작** (셸 06:39:59 → 06:40:28 UTC, exit 0)

| 대상 | 관찰 | 기대 | 판정 |
|---|---|---|---|
| JobExecution | **같은 JobInstance 5**, 새 JobExecution **6** COMPLETED | 같은 instance·새 execution | 일치 |
| Step 1 | JobExecution 6 에 freeze StepExecution **없음** (로그도 `Executing step: [calculateWeeklyScoresStep]` 부터) | 없음 | 일치 |
| Step 2 (15) | **READ 4830** / WRITE 4830 / COMMIT 2416 / ROLLBACK 0, 23.7초 | 10000 − 5170 = 4830 | **일치** |
| context | read.count 10001 | | |
| scores r3 | 10000행, ranking 1..10000 모두 부여 | 10000 | 일치 |
| inputs r3 | **10000** (늦은 행 950000 미포함) | 10000 | 일치 (실험 2 전반) |
| 회차 r3 | COMPLETED, input_checksum **= r2**, result_checksum **= r2** | 기준선과 동일 | **일치** |
| 상품 100001 | r1·r2·r3 모두 score 5 ranking 1 | 불변 | 일치 |
| pointer | revision **3** → r3, published_at 06:35:57(r1) 유지, updated_at 06:40:28 | r3 | 일치 |

---

## 3. 실험 2 후반 — 새 revision r4 (셸 06:40:42 → 06:41:36 UTC, exit 0)

| 대상 | 관찰 | 기대 |
|---|---|---|
| Step 2 | READ 10000 / COMMIT 5001 (상품 수는 그대로 10000) | |
| inputs r4 | **10001** | 10001 |
| 회차 r4 | input_count 10001, expected 10000, input `3e776897…8ee1` (≠ r3), result `a00ea523…e4e0` (≠ r3) | 둘 다 다름 |
| 상품 100001 | r3 score 5 → **r4 score 12** (5+7), ranking 1 유지 | 변화 |
| pointer | revision 4 → r4 | r4 |

"고정 뒤 도착한 구매는 현재 snapshot 에 들어가지 않고(r3 재시작) 새 revision 에만 반영(r4)" — D15 가 kill·복구를 거친 뒤에도 성립.

---

## 4. 실험 3 — Step 1 도중 `kill -9` (best-effort → **성공**, r6)

### 4-1. 타이밍 접근과 실패한 첫 시도 (r5)

- 로그 실측: `Starting` 로그 → Step 1 `Executing step` 까지 약 4.1초, Step 1 자체는 **133~238ms**. DB 폴링(1초, docker exec 왕복 ~100ms)으로는 잡을 수 없어 **로그 파일 감시**로 바꿨다.
- 첫 시도(r5, 셸 06:42:11): `tail -n0 -F log | grep -m1 -q …; kill -9` 파이프라인. **kill 이 발사되지 않았다** — zsh 가 파이프라인의 `tail` 종료까지 기다려 `grep` 이 끝나도 다음 줄로 넘어가지 않았다. 그 사이 r5 는 **정상 완료**(JobExecution 8 COMPLETED, 50초, inputs 10001, checksum 둘 다 r4 와 동일, pointer → r5). 실험 결과로는 무의미하지만 DB 에 r5 회차가 남아 있으니 기록한다(r4 와 같은 입력의 재계산이라 R6 수렴 사례로는 쓸 수 있음).
- 두 번째(r6): `while ! grep -q "Executing step: \[freezeWeeklyInputStep\]" $LOG; do :; done; kill -9 $PID` 바쁜 루프.

### 4-2. kill (r6, PID 60105)

- 셸 06:44:36.06 기동 → 로그 `15:44:41.343 Executing step: [freezeWeeklyInputStep]` → **kill -9 06:44:41.350 UTC** (Step 1 시작 **약 7ms 뒤**). exit 137. 로그에 `입력 고정 완료` 줄 없음.

| 대상 | 관찰 | 기대 |
|---|---|---|
| JobExecution 9 (instance 8) | STARTED, END_TIME NULL | STARTED |
| Step 23 freeze | **STARTED**, COMMIT 0, END_TIME NULL | |
| inputs r6 | **0행** | 0 |
| 회차 r6 | **행 없음** | 없음 |
| scores r6 | 0 | 0 |

→ 7-1 #1 "Step 1 한 TX → rollback 으로 반쪽 snapshot 없음" 을 kill -9 로 확인. (TX 가 server 쪽에서 끊겨 rollback 된 것. `INSERT…SELECT` 가 끝났는지 여부와 무관하게 회차 행까지 한 TX 라 흔적이 없다.)

### 4-3. 늦은 행 → 복구 → 재시작

- 셸 06:45:11 UTC: `INSERT … VALUES (950001, 100002, 3, '2026-09-11 03:00:00.000000', …)` → 원본 10002행.
- (가) pointer r5 / (나) STARTED = JobExecution 9, snapshotId r6 / (다) 설계 SQL `:id`=9 → Step 1행·Job 1행 Changed (END_TIME 역시 UTC 로 들어감) / (라) 재시작 06:45:11 → 06:46:07, exit 0.

| 대상 | 관찰 | 기대 |
|---|---|---|
| JobExecution 10 | COMPLETED, 같은 instance 8 | |
| Step 24 freeze | **다시 실행** COMPLETED, 163ms | Step 1 재실행 |
| Step 25 calculate | READ 10000 / COMMIT 5001 (처음부터) | |
| inputs r6 | **10002** (늦은 행 950001 포함) | 10002 |
| 회차 r6 | input_count 10002, expected 10000, input `d125a1ca…90f3` (≠ r5), result `afbe761f…5e12` (≠ r5) | 둘 다 다름 |
| 상품 100002 | r5 score 2 ranking 5965 → **r6 score 5 ranking 2** | 변화 |
| pointer | revision 6 → r6 | r6 |

→ 실험 2 와 정반대: **봉인(Step 1 commit) 전에 죽으면 늦은 행이 같은 snapshotId 에 들어가고, 봉인 뒤에 죽으면 들어가지 않는다.** 경계는 Step 1 의 commit 한 점이다.

---

## 5. 실험 4 — Step 3 kill (best-effort, r7)

- 같은 로그 트리거(`Executing step: [openWeeklyRankingStep]`). 셸 06:46:24 기동, 로그 `15:47:22.651 Executing step: [open…]` → **kill -9 06:47:22.657 UTC** (Step 3 시작 **6ms 뒤**). exit 137.
- 결과는 "공개 commit **직후**" 가 아니라 **"공개 TX 도중"** 이었다 (Step 3 가 250~330ms 걸리는데 6ms 만에 죽음):

| 대상 | 관찰 |
|---|---|
| JobExecution 11 (instance 9) | STARTED, NULL |
| Step 27·28 | freeze COMPLETED, calculate COMPLETED (READ 10000 / COMMIT 5001) |
| Step 29 open | **STARTED / EXECUTING**, COMMIT 0 |
| scores r7 | 10000행, **ranking 전부 NULL** |
| 회차 r7 | **CALCULATING**, result_checksum NULL |
| pointer | revision 6 → r6 **유지** |

→ 7-1 #4 (공개 TX 도중 실패 → 전부 rollback) 를 kill -9 로 확인한 셈. **"commit 직후 ~ metadata 저장 전" 창(7-1 #5)은 ms 단위라 이 방식으로는 잡히지 않았다.** 그 사례는 `OpenWeeklyRankingConvergenceTest(openOnlyProbe)` 로 대체 확인된 것으로 둔다.
- 복구 (셸 06:47:40, `:id`=11, Step 1행·Job 1행 Changed) → 재시작 06:47:41 → 06:47:46 exit 0: JobExecution 12 에 **`openWeeklyRankingStep` 하나만** (334ms), scores 10000 ranked 10000, 회차 r7 COMPLETED, **input·result checksum 모두 r6 와 동일**, pointer → r7.

---

## 6. 7-2 수동 절차 평가

### 그대로 된 것
- (나) 의 "`STATUS='STARTED'` + `JOB_INSTANCE_ID` 로 PARAMS 조인" 으로 어느 periodStart·snapshotId 인지 바로 식별됐다 (3회 모두).
- (다) 의 UPDATE 2문장은 `AND STATUS='STARTED'` 덕에 COMPLETED Step 을 건드리지 않았고(Rows matched 1), **`VERSION` 을 올리지 않아도** 재시작이 됐다 (3회 모두).
- (라) "완료된 Step 1 은 다시 돌지 않고 Step 2 는 checkpoint 다음부터" — READ_COUNT 4830 = 10000 − 5170 으로 정확히 확인. Step 1 이 STARTED 였던 경우(실험 3)는 Step 1 부터, Step 3 이 STARTED 였던 경우(실험 4)는 Step 3 만 다시 돌았다. 절차 문장은 Step 2 경우만 적고 있지만 다른 두 경우도 같은 절차로 복구됐다.
- (가) 는 실험 세 번 모두 "pointer 는 이전 revision, 고장 난 회차는 CALCULATING(또는 행 없음)" 이라 "이미 공개됐으면 수렴만" 분기를 탈 일이 없었다.

### 문장을 고쳐야 할 것
1. **(다) `END_TIME = NOW(6)` 의 시간대.** Batch 는 JVM 벽시계(KST)로 쓰는데 DB `NOW()` 는 서버 시간대(이 컨테이너는 UTC)다. 결과 `END_TIME < START_TIME`. 동작엔 지장이 없지만 사후 조회에서 "음수 소요 시간" 으로 보인다. 후보: `END_TIME = CONVERT_TZ(NOW(6), @@session.time_zone, 'Asia/Seoul')` 또는 주석으로 "JVM 시간대에 맞춰 넣을 것" 명시. 운영 DB 시간대를 먼저 확인해야 한다(#24 와 연결).
2. **(나) "실제 프로세스가 없는 실행" 판정 방법이 비어 있다.** 이번엔 PID 를 알고 있어 `ps -p` 로 확인했지만 운영에선 PID 를 모른다. `LAST_UPDATED` 가 chunk 마다 갱신된다는 사실(실험 1: 15:38:19.964 = kill 직전 commit)을 쓰면 "LAST_UPDATED 가 N분 이상 멈춘 STARTED" 가 실용적인 기준이다. Step 1·3 처럼 commit 이 없는 Tasklet 은 LAST_UPDATED 가 START_TIME 과 같으므로 Step 길이의 상한(여기선 1초 미만)을 함께 적어야 한다.
3. **(라) 의 서술 범위.** "Step 2 는 checkpoint 다음부터" 만 있는데 Step 1 STARTED(→ Step 1 재실행, **늦은 행이 포함됨**)·Step 3 STARTED(→ Step 3 만 재실행) 두 경우를 한 줄씩 추가하면 운영자가 "재시작 뒤 입력이 달라질 수 있는 경우" 를 미리 안다. 특히 Step 1 재실행은 D15 의 "늦은 구매는 새 revision" 원칙과 겉보기엔 어긋나 보이므로(같은 snapshotId 인데 입력이 늘어남) 명시할 가치가 있다. 실제로는 "봉인이 안 된 상태였다" 가 맞는 설명이다.
4. (사소) 복구 전 재시작 시도의 종료 코드는 **1** 이며 `BatchStatus.ordinal()`(FAILED=5)이 아니다 — 04-manual-run 4-1 `[미확인]` 갱신 재료.

---

## 7. 운영 준비에 대한 관찰 (결정은 보류)

- **kill 은 로그에 아무 흔적도 남기지 않는다.** 실험 1·3·4 모두 마지막 로그가 `Executing step: […]` 이고 ERROR·WARN 이 없다. 로그 기반 알림만으로는 감지 불가 → "알림" 의 원천은 **metadata 테이블 상태**여야 한다: `BATCH_JOB_EXECUTION.STATUS='STARTED' AND LAST_UPDATED < NOW() - interval X` (Step 2 는 chunk 마다 LAST_UPDATED 갱신, 약 100 commit/초).
- 업무 테이블 쪽 신호: 회차 `CALCULATING` 이 오래 머무는 것. 단 Step 1 도중 kill 은 회차 행조차 없어 **업무 테이블만 보면 "아무 일도 없었다"** 로 보인다 — 7-2 #4 의 역할 분리("실행 상태는 BATCH 가 맡음")가 실제로 필요한 이유.
- 종료 코드: kill -9 는 137, 복구 전 재시도는 1. 스케줄러가 종료 코드를 보고 있다면 137 자체가 신호가 된다.
- **STARTED 자기 정리 필요성**: 세 번 모두 수동 UPDATE 2문장으로 복구됐고 재시작 결과가 기준선과 checksum 까지 일치했다. 절차 자체는 가볍다. 자동화하지 않는 근거(죽은 실행 vs 느린 실행 구분 불가)는 그대로 유효하되, 이번 관찰로 "LAST_UPDATED 정체 시간" 이라는 **구분 가능한 신호**가 있음은 확인됐다. 자동 FAILED 전환보다는 "정체 알림 → 사람이 (나)~(라)" 가 관찰에 부합한다.
- 재시작 비용: 실험 1 은 남은 48.3% 만 다시 계산(23.7초 vs 49초). 실험 3 은 전체 재실행. 실험 4 는 0.3초.

---

## 8. 종료 시 DB 상태

- 2026-08-17 주: JobInstance 1·2 COMPLETED, 회차 r1·r2 COMPLETED, pointer → `weekly-20260817-r2` (변화 없음).
- 2026-09-07 주: JobInstance 3~9 (r1~r7), JobExecution 3~12. FAILED 로 수동 전환한 execution 5·9·11. 회차 r1~r7 모두 COMPLETED, pointer → `weekly-20260907-r7` (revision 7). `product_purchase_activity` 2026-09-07 주 10002행(seed 10000 + 늦은 행 950000·950001), 총 10007행.
- `BATCH_JOB_EXECUTION` 에 STARTED 없음.
- 로그: `/tmp/batch-weekly-20260907-{r1,r2,r3,r3-retry1,r3-restart,r4,r5,r6,r6-restart,r7,r7-restart}.log`
