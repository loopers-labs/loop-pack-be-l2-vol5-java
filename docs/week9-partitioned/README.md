# 주문 파티션별 주간 구매 랭킹

Kafka 소비자가 주문의 구매 활동을 적재한다. 각 Batch 인스턴스의 스케줄러는 지난주의 입력을 함께 사용할 계획을 찾거나 만든다. 주차·revision당 한 번 고정한 입력을 주문번호 구간으로 나누고, 각 JVM은 자기 구간의 상품을 Spring Batch Local Partitioning으로 병렬 집계한다. 모든 구간의 부분 점수가 고정 입력과 일치하면 새 랭킹을 공개한다.

고객 조회는 `GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10`이다. limit은 기본 10, 범위 1~100이다. 미공개는 `NOT_PUBLISHED`, 구매 0건 완성본은 snapshot ID가 있는 `PUBLISHED`다. 비공개 상품·브랜드를 제외한 뒤 limit을 적용하며, 표시되는 상품은 저장된 순위를 유지한다.

- [실행 시뮬레이터 · 두 JVM의 주문 분할과 B 재시도 후 2 + 3 = 5 확인](weekly-ranking-flow.html) — HTML 파일을 브라우저에서 열어 단계별로 실행한다.
- [ADR · 주간 입력을 한 번 고정하고 구간별 점수를 검산한 뒤 공개하는 이유](adr-0001-weekly-ranking.md)
- [재시작·늦은 구매·덮어쓰기의 MySQL 검증 기록](verification.md)
- [기술 글 · worker가 네 개일 때 한 JVM과 두 JVM의 성능이 달랐던 이유](benchmark.md)

## 입력과 점수

주간 소속은 최초 주문 확정 시각의 UTC 월요일 00:00 이상, 다음 월요일 00:00 미만이다. 월요일 00:01 UTC부터 지난주를 계획한다. 첫 기동은 최근 마감 주부터, 이후에는 자동 계획이 빠진 주를 따라잡는다. 계획 생성 때 가동 중인 서버 수와 서버별 로컬 상품 파티션 수를 저장한다. 주문당 구매 품목 행 수를 고려한 주문 파티션의 주문번호 경계가 같은 주문의 품목을 함께 묶는다. 새 서버는 기본 담당자의 생존 신호가 만료된 READY 주문 파티션을 인수할 수 있다. 다음 revision에서는 현재 가동 수로 주문 파티션 수를 다시 정한다.

입력 계약 3에서는 주차·revision마다 구매 원본을 한 번 읽는다. 계획을 만드는 MySQL 트랜잭션이 `READ COMMITTED`에서 단일 `INSERT … SELECT`로 그 읽기 시점에 커밋된 주간 구매를 복사한다. 복사본으로 주문 경계를 정하고 전체 및 구간별 건수·SHA-256을 계산한다. 계획, 입력, 경계, 지문은 함께 커밋된다. 고정 실패 시 같은 revision을 다시 요청할 수 있다.

공개 API의 `frozenAt`은 공통 복사 직전에 얻어 전체 manifest에 저장한 UTC 시각이다. 실제 포함 여부는 복사 SQL의 읽기 시점에 결정된다. 그 이후에 도착한 구매는 어느 주문 구간이든 다음 명시적 revision에서 반영된다. 늦게 시작한 서버와 재시작한 worker도 같은 복사본을 읽는다. 입력 checksum은 전체 입력을 주문번호·상품번호 순으로 직렬화한다.

| 단계 | 저장 및 재실행 기준 |
|---|---|
| 스케줄러 | MySQL에 주차·revision별 공통 snapshot과 N개의 주문 파티션을 함께 고정한다. 실행권에는 담당자와 세대 번호가 있다. |
| 주문 파티션 Job | snapshot ID와 주문 파티션 번호가 식별 parameter다. 계획에서 받은 구간의 건수·checksum을 검증한 뒤 고정된 입력으로 계산한다. |
| 로컬 worker | 고정된 주문 파티션의 상품을 상품 ID 경계에 따라 로컬 상품 파티션으로 나눈다. 로컬 Step마다 영속 reader 체크포인트가 있고, 부분 점수와 같은 DB 트랜잭션에서 커밋한다. |
| 부분 점수 | 키는 `(snapshot_id, order_partition_id, product_id)`다. 최초 값을 유지하며, 동일 값 재시도는 성공한다. 다른 값 재시도는 별도 트랜잭션의 충돌 원장에 남고 해당 revision의 공개가 보류된다. |
| 전역 공개 | 모든 주문 파티션이 완료된 후 입력과 주문 파티션별 checksum을 확인한다. 상품별 부분합을 합쳐 입력의 상품별 실제 수량과 검산하고 순위·완성 기록·포인터를 함께 커밋한다. 동점은 상품번호 오름차순이다. |

## 실행 준비

Java 21, MySQL 8.0과 기존 구매 활동 스키마를 사용한다. 새 전용 DB에는 상거래 DDL 013까지 적용한 뒤 `docker/week9/001-batch-metadata-mysql.sql`, `docker/payment-lab/014`~`018`의 랭킹 DDL을 순서대로 한 번 적용한다. C05 스키마가 준비된 DB에는 새 017·018만 적용한다. 원본 C06/C07의 `017-weekly-ranking-requests.sql`과 새 `017-weekly-ranking-plans.sql`의 용도가 다르므로 파일명을 확인한다. 구버전과 새 버전의 비교 실행은 각각 격리된 DB를 사용한다.

`COMMERCE_MYSQL_JDBC_URL`, `COMMERCE_MYSQL_USER`, `COMMERCE_MYSQL_PASSWORD`에 같은 보존 DB의 연결 정보를 제공한다. 각 인스턴스에서 Batch JAR를 다음과 같이 실행한다. Redis 연결은 기존 `ranking-preserve` 프로필 설정을 따른다.

```powershell
.\gradlew.bat :apps:commerce-batch:bootJar
$batchJar = Get-ChildItem 'apps/commerce-batch/build/libs/commerce-batch-*.jar' |
    Where-Object Name -NotLike '*-plain.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$profiles = '--spring.profiles.active=ranking-preserve,ranking-scheduler'
java -jar $batchJar.FullName $profiles --ranking.scheduler.local-grid=2 --ranking.weekly.local-workers=2
```

기본 node ID는 실행마다 생성한다. 이름을 지정할 때는 인스턴스마다 구분되는 `--ranking.scheduler.node-id`를 사용한다. `local-grid`는 계획에 저장할 로컬 상품 파티션 수, `local-workers`는 이 JVM에서 동시에 실행할 스레드 수다. 커서 reader와 청크 트랜잭션은 별도의 연결을 사용할 수 있다. 상주 모드의 Hikari 최대 풀 크기는 `2 × local-workers + 3` 이상으로 설정한다. 예를 들어 worker 2개면 최소 7개다. 이는 streaming reader와 청크 트랜잭션 외에 heartbeat·조율·독립 충돌 기록에 필요한 연결을 확보하기 위한 시작 조건이다.

실행 중 새 서버가 합류해도 기존 revision의 주문 파티션 수는 고정된다. 두 노드 동시 처리 실험에서는 두 프로세스에 `--ranking.scheduler.auto-plan=false`를 주고 `weekly_ranking_members`에서 두 생존 신호를 먼저 확인한 뒤 아래 request 모드로 해당 주 revision 1을 생성한다. 일반 자동 운영에서는 정해진 시각에 이미 등록된 노드가 그 주의 분배에 참여한다.

### 새 revision 요청

상주 인스턴스가 등록된 동일 DB에서 실행한다. 기본 자동 실행은 revision 1을 만든다. 늦은 활동을 반영할 때는 마지막 공개 revision의 다음 번호를 명시한다. 충돌로 종료된 revision은 증거를 보존한 채 원인을 확인하고, 새 입력으로 다시 계산할 때 그 다음 번호를 명시한다. 동일 기간의 열린 계획은 먼저 완료하거나 처리 결과를 확인한다. 예시 주차는 2026-08-17에 시작한다.

```powershell
java -jar $batchJar.FullName $profiles --ranking.scheduler.mode=request --ranking.scheduler.request-period=2026-08-17 --ranking.scheduler.request-revision=2
```

## 중단된 주문 파티션 인수

먼저 `weekly_ranking_plans.state`와 `weekly_ranking_order_partitions.last_error`를 함께 확인한다.

| 관측값 | 운영 동작 |
|---|---|
| 계획 `CONFLICT`, 손상 파티션 `FROZEN_INPUT_INTEGRITY_MISMATCH` | `last_error`의 기대·실제 건수와 SHA-256을 확인하고 구매 활동 원본과 대조한다. 원본을 확인한 뒤 다음 revision을 명시적으로 요청한다. 고객은 그 revision이 검산을 마칠 때까지 이전 공개본을 조회한다. |
| 계획 `OPEN`, 파티션 `RECOVERY_REQUIRED`, 사유 `CHECKPOINT_EVIDENCE_MISMATCH` | 실패한 로컬 Step의 주문번호 경계, 상품 경계, reader 위치와 커밋한 부분 점수를 계획에 대조한다. 유한한 주문 경계는 저장된 `Long` 값이 일치해야 하며, 열린 쪽의 키는 없어야 한다. 인수 명령도 같은 증거를 검사한다. |
| 점검 도중 DB 연결 단절 | DB 연결이 복구되면 다음 스케줄러 점검이 같은 실행을 다시 확인한다. |

1. `weekly_ranking_plans`, `weekly_ranking_order_partitions`, `weekly_ranking_score_conflicts`에서 대상 주·revision·주문 파티션·담당자·세대·실행 ID·고정 여부를 확인한다. 운영 환경에서 이전 JVM의 종료를 독립적으로 확인한다. heartbeat 만료만으로 실행 종료를 판정하지 않는다.
2. 최초 Job 생성에서 MySQL 교착 롤백(1213)이 확정된 경우, 스케줄러는 담당자·세대, 공통 입력의 구간 지문, Batch 실행 및 부분 점수·충돌 기록의 부재를 확인한다. 이 조건에서 최초 시도를 포함해 세 번까지 기동한다. 그 밖의 불확정 상태에서는 `BATCH_JOB_EXECUTION`과 `BATCH_STEP_EXECUTION`에서 마지막 실행과 로컬 Step 상태를 대조한다. 충돌이 기록되면 기존 부분 점수, 시도값, 해당 고정 입력을 보존해 원인을 조사한다. 재실행 가능한 일반 실패는 같은 JobInstance에서 자동 재시도한다.
3. 종료가 확인된 대상에 한해 아래 단건 명령을 실행한다. 예시의 변수는 위 DB 조회에서 확인한 실제 값이다. 코드가 소유권·세대·최신 실행 ID·영속 체크포인트와 저장 점수를 다시 대조한다. 실행 ID 0은 아직 Batch 실행이 생성되지 않은 경우다.

```powershell
java -jar $batchJar.FullName $profiles --ranking.scheduler.mode=recover `
  "--ranking.scheduler.snapshot-id=$snapshotId" "--ranking.scheduler.order-partition-id=$orderPartitionId" `
  "--ranking.scheduler.owner-id=$oldOwner" "--ranking.scheduler.generation=$generation" `
  "--ranking.scheduler.execution-id=$executionId" --ranking.scheduler.termination-confirmed=true `
  "--ranking.scheduler.reason=$terminationEvidence"
```

4. 다음 스케줄러가 같은 주문 파티션을 인수했는지, JobInstance ID가 유지되고 JobExecution만 새로 생겼는지 확인한다. 이전 공개본은 새 revision의 모든 주문 파티션이 완료되어 검산을 통과할 때까지 조회할 수 있다.

```sql
SELECT p.period_start, p.revision, p.state AS plan_state, s.*
FROM weekly_ranking_plans p JOIN weekly_ranking_order_partitions s USING (snapshot_id)
WHERE p.period_start = '2026-08-17' ORDER BY p.revision, s.order_partition_id;

SET @snapshot_id = (SELECT snapshot_id FROM weekly_ranking_plans
                    WHERE period_start = '2026-08-17' AND revision = 1);
SELECT snapshot_id, order_partition_id, product_id, stored_score, attempted_score, generation, recorded_at
FROM weekly_ranking_score_conflicts WHERE snapshot_id = @snapshot_id;
```

## 기존 DB에서 전환

C05와 C07의 고정 입력·최종 점수·순위·공개 포인터(DDL 014~016)와 단일 Job `weeklyRanking`의 실행 이력은 보존한다. 이전 바이너리로 진행 중인 Job을 마치고, 이미 공개한 주의 재계산에는 다음 revision을 사용한다. C05(`7cd9f0b`)에서 분기한 새 구현은 원본 브랜치 `volume-9/weekly-ranking`의 C07(`5e27dcb`)과 비교할 수 있다.

주문 구간별 독립 고정 버전(입력 계약 2)은 새 공통 고정 버전(계약 3)의 작업도 가져갈 수 있다. 배포 전에 구버전의 신규 계획 생성을 중단하고 열린 계획을 마친 뒤 구버전 JVM을 모두 종료한다. 계약 3은 기존 014~018 DDL을 사용한다. `weeklyRankingOrderPartition` Job이 `verifyWeeklyRankingOrderPartitionInputs` Step에서 시작하며, 인수 시에는 `snapshotId`와 `orderPartitionId`를 유지하고 `ownerId`와 `generation`을 갱신한다.

<details>
<summary>중간 개발 버전의 weekly_ranking_shards 테이블이 남아 있는 경우</summary>

Job·Step 이름과 reader 상태 키가 달라진 실험 DB에서는 완료된 결과와 Batch 실행 이력을 함께 옮긴다. 기존 실행 ID·체크포인트를 보존하는 절차다.

1. 기존 바이너리에서 진행 중인 계획과 복구 대기를 먼저 해결한다. 계획의 최종 상태와 공개 포인터를 확인한 뒤 기존 스케줄러와 쓰기 프로세스를 멈추고 일관된 DB 백업을 만든다.
2. 별도 MySQL에 백업을 `loopers_pre_order_partition`으로 복원한다. 같은 MySQL의 빈 대상 DB `loopers`에는 같은 시점의 공통 테이블과 데이터를 복원한다. 여기에는 `BATCH_*` 전체, 구매 활동, 고정 입력, 최종 점수, 완성 기록, 순위, 공개 포인터가 포함된다. 대상 복원에서 제외할 중간 버전 조율 테이블은 `weekly_ranking_members`, `weekly_ranking_schedule_cursor`, `weekly_ranking_plans`, `weekly_ranking_shards`, `weekly_ranking_partial_scores`, `weekly_ranking_score_conflicts` 여섯 개다.
3. 대상 DB에 새 017·018을 적용한다. 스케줄러를 정지한 상태에서 MySQL 클라이언트로 [완료 데이터 이관 SQL](../../docker/week9/compat/import-completed-order-partitions.sql)을 실행한다. 명령 예시는 MySQL 클라이언트 안에서 `SOURCE docker/week9/compat/import-completed-order-partitions.sql`이다.
4. 이관 SQL은 미완료 계획·실행과 이미 사용 중인 대상을 거절한다. 완료된 계획·주문 파티션·부분 점수·충돌 증거와 주차 커서를 한 트랜잭션에서 복사한다. 새 JVM이 기동할 때 자신의 생존 신호를 등록한다. 종료한 프로세스의 생존 신호는 옮기지 않는다.
5. 원본·대상의 주차별 공개 snapshot ID와 revision, 상품별 부분 점수, 공개 순위를 대조한다. `BATCH_*`의 기존 Job·Step 이름과 실행 ID는 역사적 기록으로 유지된다. 새 바이너리에서 **다음 revision**을 시작해 새 이름의 JobInstance가 생성되는지 확인한다.

이 절차를 MySQL 8.0.46에서 검증했다. 열린 계획의 이관은 오류 1644로 거절됐고, 완료 후에는 부분 점수 두 행(2·3), 공개 점수 5점·1위, 기존 JobInstance 80과 JobExecution 85가 보존됐다.

</details>

## 전체 검사

```powershell
.\gradlew.bat :apps:commerce-batch:check :apps:commerce-api:check :apps:commerce-streamer:check --continue
```
